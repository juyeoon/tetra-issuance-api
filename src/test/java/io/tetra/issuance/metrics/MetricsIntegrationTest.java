package io.tetra.issuance.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.config.TetraProperties.WarmupMode;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.service.CouponCatalog;
import io.tetra.issuance.service.EventMetaCache;
import io.tetra.issuance.support.TestEventKeys;
import io.tetra.issuance.support.TestJwtFactory;
import jakarta.servlet.http.Cookie;

/** 업무 지표와 공통 태그 (MVP 계획 A6). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MetricsIntegrationTest {

	@Autowired
	MockMvc mockMvc;

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

}
