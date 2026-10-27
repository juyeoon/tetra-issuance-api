package io.tetra.issuance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.service.EventMetaCache;
import io.tetra.issuance.support.TestEventKeys;
import io.tetra.issuance.support.TestJwtFactory;

/**
 * 입장 OPEN(MVP 계획 B4·C12): 시작 − 60분 − 60초(서버 여유)보다 이르면 세션 없이 대기방으로 302.
 * 외부 확인 health(B5). 시드 이벤트의 시작 시각을 바꿨다가 끝나면 되돌린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EntryOpenIntegrationTest {

	static final String SEED_START = "2026-09-29 10:00:00";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EventMetaCache eventMetaCache;

	final TestJwtFactory tenant = TestJwtFactory.withNewKeyPair();

	@BeforeEach
	void setUp() {
		TestEventKeys.registerActiveKey(jdbcTemplate, tenant.publicKeyPem());
	}

	@AfterEach
	void 시작_시각을_시드_값으로() {
		jdbcTemplate.update("UPDATE event SET start_at = ? WHERE event_id = 1", SEED_START);
		eventMetaCache.reload();
	}

	/** 이벤트 시작을 지금 + secondsFromNow 초로 */
	void startEventIn(long secondsFromNow) {
		LocalDateTime start = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusSeconds(secondsFromNow).withNano(0);
		jdbcTemplate.update("UPDATE event SET start_at = ? WHERE event_id = 1", start);
		eventMetaCache.reload();
	}

	long entryCount() {
		String v = redis.opsForValue().get("entry:count:1");
		return v == null ? 0 : Long.parseLong(v);
	}

	MockHttpServletResponse enter(String jti) throws Exception {
		return mockMvc.perform(get("/api/issuance/session")
				.param("JWT", tenant.token().userId("open-" + UUID.randomUUID()).jti(jti).sign()))
				.andReturn().getResponse();
	}

	@Test
	void OPEN_전이면_세션_없이_대기방으로_302이고_jti와_진입_카운트를_남기지_않는다() throws Exception {
		// startEventIn(2 * 3600); // OPEN = 지금 + 1시간 — TEST-LEAD 원래 값
		startEventIn(3600 + 300); // OPEN = 지금 + 1시간 — TEST-LEAD 시험용 임시 (entry-open-before 5분)
		long before = entryCount();
		String jti = "open-" + UUID.randomUUID();

		MockHttpServletResponse res = enter(jti);
		assertThat(res.getStatus()).isEqualTo(302);
		assertThat(res.getHeader("Location")).isEqualTo("/");
		assertThat(res.getHeader("Set-Cookie")).isNull();
		assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(redis.hasKey("jti:" + jti)).isFalse();
		assertThat(entryCount()).isEqualTo(before);
	}

	@Test
	void OPEN_60초_전_여유_안이면_세션을_발급한다() throws Exception {
		// startEventIn(3600 + 30); // 시작 − 60분 − 60초 = 지금 − 30초 — TEST-LEAD 원래 값
		startEventIn(300 + 30); // 시작 − 5분 − 60초 = 지금 − 30초 — TEST-LEAD 시험용 임시
		MockHttpServletResponse res = enter("open-" + UUID.randomUUID());
		assertThat(res.getStatus()).isEqualTo(302);
		assertThat(res.getHeader("Set-Cookie")).startsWith("TETRA_SID=");
	}

	@Test
	void 여유보다_이르면_발급하지_않는다() throws Exception {
		// startEventIn(3600 + 90); // 시작 − 60분 − 60초 = 지금 + 30초 — TEST-LEAD 원래 값
		startEventIn(300 + 90); // 시작 − 5분 − 60초 = 지금 + 30초 — TEST-LEAD 시험용 임시
		assertThat(enter("open-" + UUID.randomUUID()).getHeader("Set-Cookie")).isNull();
	}

	@Test
	void 시작된_이벤트는_세션을_발급한다() throws Exception {
		eventMetaCache.reload(); // 시드: 이미 시작
		assertThat(enter("open-" + UUID.randomUUID()).getHeader("Set-Cookie")).startsWith("TETRA_SID=");
	}

	// --- 외부 확인 (B5) ---------------------------------------------------------

	@Test
	void health는_인증_없이_이_Pod의_eventId를_돌려준다() throws Exception {
		mockMvc.perform(get("/api/issuance/health"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.status").value("UP"))
				.andExpect(jsonPath("$.data.eventId").value(1));
	}

	@Test
	void info_API는_없다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/1/info")).andExpect(status().is4xxClientError());
	}

}
