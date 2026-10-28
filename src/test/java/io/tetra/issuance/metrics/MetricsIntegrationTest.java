package io.tetra.issuance.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.config.StartupWarmup;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.config.TetraProperties.WarmupMode;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.service.CouponCatalog;
import io.tetra.issuance.service.EventMetaCache;
import io.tetra.issuance.support.TestEventKeys;
import io.tetra.issuance.support.TestJwtFactory;
import jakarta.servlet.http.Cookie;

/**
 * 업무 지표와 공통 태그 (MVP 계획 A6). /actuator/prometheus 는 관리 포트(8081, B6)에만 있어서
 * 실제 서버를 띄우고 관리 포트를 무작위로 받는다. 업무 API 는 그대로 MockMvc.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@AutoConfigureMockMvc
class MetricsIntegrationTest {

	@LocalManagementPort
	int managementPort;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	TetraProperties properties;

	@Autowired
	Clock clock;

	@Autowired
	MeterRegistry registry;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EventMetaCache eventMetaCache;

	@Autowired
	CouponCatalog catalog;

	@Autowired
	StartupWarmup warmup;

	@Autowired
	WarmupRequests warmupRequests;

	static final String HEALTH_URI = "/api/issuance/health";

	static final String CURSOR_URI = "/api/issuance/events/{eventId}/queue/cursor";

	final TestJwtFactory tenant = TestJwtFactory.withNewKeyPair();

	@BeforeEach
	void setUp() {
		TestEventKeys.registerActiveKey(jdbcTemplate, tenant.publicKeyPem());
		eventMetaCache.reload();
		catalog.warmup(WarmupMode.FORCE);
	}

	double count(String name, String... tags) {
		Counter c = registry.find(name).tags(tags).counter();
		return c == null ? 0 : c.count();
	}

	double gauge(String name) {
		Gauge g = registry.find(name).gauge();
		assertThat(g).as(name).isNotNull();
		return g.value();
	}

	Map<String, Double> rejects() {
		return registry.find("tetra.session.rejects").counters().stream()
				.collect(Collectors.toMap(c -> c.getId().getTag("reason"), Counter::count));
	}

	/** 컨텍스트 게이지와 따로, 캐시 없이 지금 값을 읽는다 */
	double sessionsActiveNow() {
		var fresh = new SimpleMeterRegistry();
		new EventStateGauges(fresh, redis, properties, clock);
		return fresh.get("tetra.event.sessions.active").gauge().value();
	}

	/** http.server.requests 요청 수 — 같은 uri 의 status·outcome 조합을 합친다 */
	long requests(String uri) {
		return registry.find("http.server.requests").tag("uri", uri).timers().stream().mapToLong(Timer::count).sum();
	}

	/** Tomcat 커넥터가 받은 요청 수 (tomcat.global.request) — 관찰을 꺼도 실제 HTTP 는 여기 잡힌다 */
	double tomcatRequests() {
		return registry.find("tomcat.global.request").functionTimers().stream().mapToDouble(FunctionTimer::count).sum();
	}

	static RequestPostProcessor remoteAddr(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	String enter(String userId) throws Exception {
		String setCookie = mockMvc.perform(get("/api/issuance/session").param("JWT", tenant.token().userId(userId).sign()))
				.andReturn().getResponse().getHeader("Set-Cookie");
		return setCookie.substring("TETRA_SID=".length(), setCookie.indexOf(';'));
	}

	@Test
	void 세션_번호표_claim_결과를_센다() throws Exception {
		double created = count("tetra.sessions", "result", "created");
		double reused = count("tetra.sessions", "result", "reused");
		double issued = count("tetra.tickets", "result", "issued");
		double success = count("tetra.claims", "result", "success", "replay", "false");
		double replay = count("tetra.claims", "result", "success", "replay", "true");

		String userId = "metrics-" + UUID.randomUUID();
		String sid = enter(userId);
		enter(userId); // 같은 사용자 재입장 → 재사용
		Cookie cookie = new Cookie("TETRA_SID", sid);
		mockMvc.perform(post("/api/issuance/events/1/ticket").cookie(cookie));
		mockMvc.perform(post("/api/issuance/events/1/coupons/claim").cookie(cookie));
		mockMvc.perform(post("/api/issuance/events/1/coupons/claim").cookie(cookie)); // 재요청

		assertThat(count("tetra.sessions", "result", "created")).isEqualTo(created + 1);
		assertThat(count("tetra.sessions", "result", "reused")).isEqualTo(reused + 1);
		assertThat(count("tetra.tickets", "result", "issued")).isEqualTo(issued + 1);
		assertThat(count("tetra.claims", "result", "success", "replay", "false")).isEqualTo(success + 1);
		assertThat(count("tetra.claims", "result", "success", "replay", "true")).isEqualTo(replay + 1);
	}

	@Test
	void 이벤트_상태_게이지는_Redis_값을_읽는다() {
		redis.opsForValue().set(CouponStockStore.stockKey(1), "4");
		redis.opsForValue().set("cursor:serving:1", "900");
		redis.opsForValue().set("ticket:seq:1", "1234");

		assertThat(gauge("tetra.event.stock.remaining")).isEqualTo(4);
		assertThat(gauge("tetra.event.cursor")).isEqualTo(900);
		assertThat(gauge("tetra.event.ticket.last")).isEqualTo(1234);
	}

	@Test
	void 유효_세션_게이지는_세션_발급이_남긴_분_키를_센다() throws Exception {
		double before = sessionsActiveNow();
		String userId = "metrics-" + UUID.randomUUID();
		enter(userId);
		enter(userId); // 재입장(세션 재사용)은 세지 않는다
		assertThat(sessionsActiveNow()).isEqualTo(before + 1);
	}

	@Test
	void jti를_다시_쓰면_reused만_오르고_세션_쿠키_실패는_세지_않는다() throws Exception {
		Map<String, Double> before = rejects();
		String jwt = tenant.token().userId("metrics-" + UUID.randomUUID()).sign();
		mockMvc.perform(get("/api/issuance/session").param("JWT", jwt)).andExpect(status().isFound());
		mockMvc.perform(get("/api/issuance/session").param("JWT", jwt)).andExpect(status().isUnauthorized());
		// 세션 쿠키 실패 (SessionAuthFilter) — 입장 토큰 거절이 아니다
		mockMvc.perform(post("/api/issuance/events/1/ticket")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/issuance/events/1/coupons/claim").cookie(new Cookie("TETRA_SID", "bogus")))
				.andExpect(status().isUnauthorized());

		Map<String, Double> after = rejects();
		after.forEach((reason, n) -> assertThat(n).as(reason).isEqualTo(before.get(reason) + (reason.equals("reused") ? 1 : 0)));
	}

	@Test
	void 새_지표가_actuator_prometheus에_나온다() throws Exception {
		HttpResponse<String> res = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).containsPattern("(?m)^tetra_event_sessions_active\\{event_id=\"1\",tenant_id=\"poctenant001\"} ");
		for (String reason : IssuanceMetrics.REJECT_REASONS) {
			assertThat(res.body()).containsPattern(
					"(?m)^tetra_session_rejects_total\\{event_id=\"1\",reason=\"" + reason + "\",tenant_id=\"poctenant001\"} ");
		}
		// 결과별 카운터도 첫 요청 전부터 나와 있어야 한다. 값은 같은 컨텍스트의 다른 테스트가 올렸을 수 있어 보지 않는다
		for (String series : List.of(
				"tetra_sessions_total{event_id=\"1\",result=\"created\",tenant_id=\"poctenant001\"}",
				"tetra_sessions_total{event_id=\"1\",result=\"reused\",tenant_id=\"poctenant001\"}",
				"tetra_sessions_total{event_id=\"1\",result=\"before_open\",tenant_id=\"poctenant001\"}",
				"tetra_tickets_total{event_id=\"1\",result=\"issued\",tenant_id=\"poctenant001\"}",
				"tetra_tickets_total{event_id=\"1\",result=\"already_claimed\",tenant_id=\"poctenant001\"}",
				"tetra_tickets_total{event_id=\"1\",result=\"session_gone\",tenant_id=\"poctenant001\"}",
				"tetra_claims_total{event_id=\"1\",replay=\"false\",result=\"success\",tenant_id=\"poctenant001\"}",
				"tetra_claims_total{event_id=\"1\",replay=\"false\",result=\"sold_out\",tenant_id=\"poctenant001\"}",
				"tetra_claims_total{event_id=\"1\",replay=\"true\",result=\"success\",tenant_id=\"poctenant001\"}",
				"tetra_claims_total{event_id=\"1\",replay=\"true\",result=\"sold_out\",tenant_id=\"poctenant001\"}")) {
			assertThat(res.body()).containsPattern("(?m)^" + Pattern.quote(series) + " ");
		}
	}

	@Test
	void 모든_지표에_테넌트와_이벤트_태그가_붙는다() {
		assertThat(registry.find("tetra.claims").counters()).isNotEmpty().allSatisfy(c -> {
			assertThat(c.getId().getTag("tenant_id")).isEqualTo("poctenant001");
			assertThat(c.getId().getTag("event_id")).isEqualTo("1");
		});
		assertThat(registry.find("tetra.event.cursor").gauge().getId().getTag("event_id")).isEqualTo("1");
	}

	/**
	 * 응답 시간 고정 버킷 (OPS-05 지연 그래프 p95). 300·400·500ms 를 촘촘히 둔다 — 주의 기준선(초안 500ms, 부하 시험 뒤 확정)이
	 * 어느 값이 되어도 경계에서 정확히 계산되게. 버킷 경계만 바뀌고
	 * _count 시리즈의 태그(KEDA·홈 화면 쿼리)는 그대로여야 한다.
	 */
	@Test
	void 요청_지연은_고정_버킷으로_기록되고_태그는_그대로다() throws Exception {
		Cookie cookie = new Cookie("TETRA_SID", enter("metrics-" + UUID.randomUUID()));
		mockMvc.perform(post("/api/issuance/events/1/ticket").cookie(cookie));
		mockMvc.perform(post("/api/issuance/events/1/coupons/claim").cookie(cookie));

		for (String uri : new String[] { "/api/issuance/session", "/api/issuance/events/{eventId}/ticket",
				"/api/issuance/events/{eventId}/coupons/claim" }) {
			Timer timer = registry.find("http.server.requests").tag("uri", uri).timer();
			assertThat(timer).as(uri).isNotNull();
			assertThat(Arrays.stream(timer.takeSnapshot().histogramCounts()).mapToDouble(b -> b.bucket(TimeUnit.SECONDS)))
				.as(uri)
				.containsExactly(0.025, 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.8, 1.0, 2.0, 5.0);
			assertThat(timer.getId().getTags()).extracting(Tag::getKey)
				.containsExactlyInAnyOrder("error", "event_id", "exception", "method", "outcome", "status", "tenant_id",
						"uri");
		}
	}

	// --- 기동 웜업 요청은 http.server.requests 에서 뺀다 (KEDA·Console·Portal RPS) ---------------------

	@Test
	void 웜업_표시와_루프백이면_요청_지표에_남지_않는다() throws Exception {
		long health = requests(HEALTH_URI);
		long cursor = requests(CURSOR_URI);
		mockMvc.perform(get(HEALTH_URI).header(WarmupRequests.HEADER, warmupRequests.token()))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/issuance/events/1/queue/cursor").header(WarmupRequests.HEADER, warmupRequests.token()))
				.andExpect(status().isOk());
		assertThat(requests(HEALTH_URI)).isEqualTo(health);
		assertThat(requests(CURSOR_URI)).isEqualTo(cursor);
	}

	@Test
	void 웜업_표시라도_외부_주소면_요청_지표에_남는다() throws Exception {
		long before = requests(HEALTH_URI);
		mockMvc.perform(get(HEALTH_URI).header(WarmupRequests.HEADER, warmupRequests.token()).with(remoteAddr("203.0.113.7")))
				.andExpect(status().isOk());
		assertThat(requests(HEALTH_URI)).isEqualTo(before + 1);
	}

	@Test
	void 표시가_없는_요청은_요청_지표에_남는다() throws Exception {
		long health = requests(HEALTH_URI);
		long cursor = requests(CURSOR_URI);
		mockMvc.perform(get(HEALTH_URI)).andExpect(status().isOk());
		mockMvc.perform(get("/api/issuance/events/1/queue/cursor")).andExpect(status().isOk());
		assertThat(requests(HEALTH_URI)).isEqualTo(health + 1);
		assertThat(requests(CURSOR_URI)).isEqualTo(cursor + 1);
	}

	/** 실제 서버 경로 — 웜업이 127.0.0.1 로 보낸 HTTP 가 Tomcat 까지 가지만 요청 지표에는 남지 않는다 */
	@Test
	void 기동_웜업의_HTTP_요청은_서버에_닿고_요청_지표에는_남지_않는다() {
		long health = requests(HEALTH_URI);
		long cursor = requests(CURSOR_URI);
		double tomcat = tomcatRequests();

		warmup.run(null);

		assertThat(tomcatRequests()).as("웜업 HTTP 가 서버에 닿았다").isGreaterThan(tomcat);
		assertThat(requests(HEALTH_URI)).isEqualTo(health);
		assertThat(requests(CURSOR_URI)).isEqualTo(cursor);
	}

}
