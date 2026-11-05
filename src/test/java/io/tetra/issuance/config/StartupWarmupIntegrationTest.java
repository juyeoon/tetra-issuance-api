package io.tetra.issuance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.filter.WarmupKeys;
import io.tetra.issuance.metrics.WarmupRequests;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.service.EventMetaCache;
import io.tetra.issuance.support.TestEventKeys;
import io.tetra.issuance.support.TestJwtFactory;

/**
 * 기동 웜업과 readiness 의 인증키 확인 (MVP 계획 A4). 웜업은 실제 HTTP(127.0.0.1)로 입장 → 번호표 → 쿠폰 목록 → claim 을 돌므로
 * 실제 서버를 띄운다 (2026-11-05 웜업 보강).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class StartupWarmupIntegrationTest {

	/** 웜업이 건드리면 안 되는 실제 키 (시드 이벤트 1) */
	static final List<String> REAL_KEYS = List.of("ticket:seq:1", "ticket:total:1", "ticket:retry:1", "entry:count:1",
			"cursor:serving:1", CouponStockStore.stockKey(1));

	static final List<String> HTTP_URIS = List.of("/api/issuance/session", "/api/issuance/events/{eventId}/ticket",
			"/api/issuance/events/{eventId}/coupons", "/api/issuance/events/{eventId}/coupons/claim");

	@LocalServerPort
	int port;

	@Autowired
	StartupWarmup warmup;

	@Autowired
	WarmupKeys warmupKeys;

	@Autowired
	WarmupRequests warmupRequests;

	@Autowired
	EventAuthKeyHealthIndicator authKeyHealth;

	@Autowired
	EventMetaCache eventMetaCache;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	MeterRegistry registry;

	final HttpClient http = HttpClient.newHttpClient();

	@Test
	void 웜업은_실제_HTTP_경로를_돌고_warmup_키만_쓰고_지운다_실제_키_이력_지표는_그대로() {
		redis.opsForValue().set(CouponStockStore.stockKey(1), "7");
		Map<String, String> realBefore = values(REAL_KEYS);
		int sessions = redis.keys("session:*").size();
		int claimDone = redis.keys("claim:done:*").size();
		int jti = redis.keys("jti:*").size();
		Integer history = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issuance_history", Integer.class);
		double business = businessCounters();
		Map<String, Long> requests = httpRequests();
		double tomcat = tomcatRequests();

		warmup.run(null);

		StartupWarmup.Report report = warmup.lastReport();
		assertThat(report.errors()).as("웜업 오류 — %s", report).isZero();
		assertThat(report.flows()).isEqualTo(report.planned());
		assertThat(report.tickets()).isEqualTo(report.flows());
		assertThat(report.coupons()).isEqualTo(report.flows());
		assertThat(report.claims()).isEqualTo(report.flows());
		assertThat(report.sessions()).isPositive();
		assertThat(tomcatRequests() - tomcat).as("HTTP 가 실제 서버에 닿았다").isGreaterThanOrEqualTo(report.flows() * 3);

		assertThat(redis.keys(StartupWarmup.PREFIX + "*")).isEmpty();
		assertThat(values(REAL_KEYS)).isEqualTo(realBefore);
		assertThat(redis.keys("session:*")).hasSize(sessions);
		assertThat(redis.keys("claim:done:*")).hasSize(claimDone);
		assertThat(redis.keys("jti:*")).hasSize(jti);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issuance_history", Integer.class))
			.isEqualTo(history);
		assertThat(businessCounters()).as("업무 지표 tetra_*").isEqualTo(business);
		assertThat(httpRequests()).as("http_server_requests").isEqualTo(requests);
	}

	@Test
	void 웜업이_끝나면_임시_키를_버려_웜업_표시가_있어도_입장_토큰을_받지_않는다() throws Exception {
		TestEventKeys.registerActiveKey(jdbcTemplate, TestJwtFactory.withNewKeyPair().publicKeyPem());
		eventMetaCache.reload();
		TestJwtFactory warmupSigned = TestJwtFactory.withKey(warmupKeys.open());
		String acceptedWhileOpen = warmupSigned.token().userId("warmup-open").sign();
		assertThat(enter(acceptedWhileOpen, true)).as("웜업 중 · 웜업 표시 · 루프백").isEqualTo(302);
		assertThat(enter(warmupSigned.token().userId("plain").sign(), false)).as("웜업 표시 없음").isEqualTo(401);

		warmup.run(null); // 끝나면 close

		assertThat(warmupKeys.publicKeyIfOpen()).isNull();
		assertThat(enter(warmupSigned.token().userId("warmup-closed").sign(), true)).as("웜업 뒤").isEqualTo(401);
	}

	@Test
	void 인증키가_있으면_readiness_인증키_항목은_UP_없으면_DOWN() {
		jdbcTemplate.update("DELETE FROM event_auth_key WHERE event_id = 1");
		eventMetaCache.reload();
		assertThat(authKeyHealth.health().getStatus()).isEqualTo(Status.DOWN);

		TestEventKeys.registerActiveKey(jdbcTemplate, TestJwtFactory.withNewKeyPair().publicKeyPem());
		eventMetaCache.reload();
		assertThat(authKeyHealth.health().getStatus()).isEqualTo(Status.UP);
	}

	/** 127.0.0.1 로 세션 API 호출 — 상태 코드 */
	int enter(String jwt, boolean warmupHeader) throws Exception {
		HttpRequest.Builder request = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/issuance/session?JWT=" + jwt));
		if (warmupHeader) {
			request.header(WarmupRequests.HEADER, warmupRequests.token());
		}
		return http.send(request.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	Map<String, String> values(List<String> keys) {
		return keys.stream().collect(Collectors.toMap(Function.identity(), k -> String.valueOf(redis.opsForValue().get(k))));
	}

	double businessCounters() {
		return Set.of("tetra.sessions", "tetra.tickets", "tetra.claims", "tetra.session.rejects").stream()
			.flatMap(name -> registry.find(name).counters().stream())
			.mapToDouble(Counter::count)
			.sum();
	}

	Map<String, Long> httpRequests() {
		return HTTP_URIS.stream().collect(Collectors.toMap(Function.identity(), uri -> registry.find("http.server.requests")
			.tag("uri", uri).timers().stream().mapToLong(Timer::count).sum()));
	}

	double tomcatRequests() {
		return registry.find("tomcat.global.request").functionTimers().stream().mapToDouble(FunctionTimer::count).sum();
	}

}
