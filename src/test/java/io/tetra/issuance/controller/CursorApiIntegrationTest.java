package io.tetra.issuance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.metrics.WarmupRequests;
import io.tetra.issuance.redis.CursorStore;
import io.tetra.issuance.redis.TicketStore;

/**
 * 4-3 커서 API — 실제 Redis·Lua. 시드 이벤트 1은 이미 시작된 상태라 증가가 일어난다.
 * 커서는 번호표 끝(ticket:seq) + slack 을 넘지 않으므로, 증가 폭을 보는 테스트는 번호표 끝을 먼저 넣는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CursorApiIntegrationTest {

	static final String URL = "/api/issuance/events/1/queue/cursor";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	CursorStore cursorStore;

	@Autowired
	WarmupRequests warmupRequests;

	@BeforeEach
	void resetCursor() {
		redis.delete(List.of(CursorStore.cursorKey(1), CursorStore.lockKey(1), TicketStore.seqKey(1)));
	}

	long fetchCursor() throws Exception {
		return fetchCursor(get(URL));
	}

	long fetchCursor(MockHttpServletRequestBuilder request) throws Exception {
		String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.data.cursor")).longValue();
	}

	/** 번호표 끝(ticket:seq) — 커서 상한의 기준 */
	void ticketLast(long last) {
		redis.opsForValue().set(TicketStore.seqKey(1), Long.toString(last));
	}

	/** 3초 경과(락 만료)와 같은 상태 */
	void nextWindow() {
		redis.delete(CursorStore.lockKey(1));
	}

	@Test
	void 쿠키_없이_200이고_CDN용_캐시_헤더를_준다() throws Exception {
		mockMvc.perform(get(URL))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "public, max-age=0, s-maxage=1"))
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.cursor").value(300)); // 윈도우 첫 요청이 +300
	}

	@Test
	void 같은_윈도우_안에서는_다시_오르지_않는다() throws Exception {
		assertThat(fetchCursor()).isEqualTo(300);
		assertThat(fetchCursor()).isEqualTo(300);
		assertThat(fetchCursor()).isEqualTo(300);
	}

	@Test
	void 락_TTL은_윈도우_3초() throws Exception {
		fetchCursor();
		assertThat(redis.getExpire(CursorStore.lockKey(1), TimeUnit.MILLISECONDS)).isBetween(2000L, 3000L);
	}

	@Test
	void 윈도우가_지나면_다시_300_오른다() throws Exception {
		ticketLast(1000); // 상한(1000 + 300)에 닿지 않게
		assertThat(fetchCursor()).isEqualTo(300);
		redis.delete(CursorStore.lockKey(1)); // 3초 경과(락 만료)와 같은 상태
		assertThat(fetchCursor()).isEqualTo(600);
		redis.delete(CursorStore.lockKey(1));
		assertThat(fetchCursor()).isEqualTo(900);
	}

	@Test
	void Lua를_동시에_1000번_불러도_한_윈도우에_정확히_1번만_오른다() throws Exception {
		// HTTP 처리 시간 때문에 3초 윈도우가 지나지 않도록 Lua 를 직접, 윈도우 60초로 호출 (원자성 확인용)
		ticketLast(1000);
		ExecutorService pool = Executors.newFixedThreadPool(64);
		try {
			List<Callable<Long>> calls = new ArrayList<>();
			for (int i = 0; i < 1000; i++) {
				calls.add(() -> cursorStore.advanceAndGet(1L, true, Duration.ofSeconds(60), 300, 300));
			}
			List<Long> results = new ArrayList<>();
			for (Future<Long> f : pool.invokeAll(calls)) {
				results.add(f.get());
			}
			assertThat(results).hasSize(1000).containsOnly(300L);
			assertThat(redis.opsForValue().get(CursorStore.cursorKey(1))).isEqualTo("300");
		}
		finally {
			pool.shutdown();
		}
	}

	@Test
	void HTTP_동시_1000건_동안_커서는_3초마다_최대_1번만_오른다() throws Exception {
		ticketLast(1_000_000); // 상한에 닿지 않게 — 몇 창이 지나도 300 단위로만 오른다
		ExecutorService pool = Executors.newFixedThreadPool(64);
		try {
			List<Callable<Long>> calls = new ArrayList<>();
			for (int i = 0; i < 1000; i++) {
				calls.add(this::fetchCursor);
			}
			long startedAt = System.nanoTime();
			List<Long> results = new ArrayList<>();
			for (Future<Long> f : pool.invokeAll(calls)) {
				results.add(f.get());
			}
			long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

			long finalCursor = Long.parseLong(redis.opsForValue().get(CursorStore.cursorKey(1)));
			long increments = finalCursor / 300;
			assertThat(finalCursor % 300).isZero();
			assertThat(results).allMatch(c -> c % 300 == 0 && c >= 300 && c <= finalCursor);
			// 지난 윈도우 수(+시작 윈도우 1개)보다 많이 오르면 안 된다
			assertThat(increments).isBetween(1L, elapsedMs / 3000 + 1);
		}
		finally {
			pool.shutdown();
		}
	}

	// --- 상한: 커서 ≤ 번호표 끝 + slack(기본 300) — 한산할 때 커서가 앞서 가지 않게 ----------------------

	@Test
	void 번호표_끝이_21이면_창을_여러_번_넘겨도_321에서_멈춘다() throws Exception {
		ticketLast(21);
		assertThat(fetchCursor()).isEqualTo(300);
		for (int i = 0; i < 5; i++) {
			nextWindow();
			assertThat(fetchCursor()).isEqualTo(321);
		}
		assertThat(redis.opsForValue().get(CursorStore.cursorKey(1))).isEqualTo("321");
	}

	@Test
	void 상한이_커서보다_낮아도_커서는_줄지_않는다() throws Exception {
		redis.opsForValue().set(CursorStore.cursorKey(1), "6300"); // 상한 전에 앞서 간 커서
		ticketLast(21);
		assertThat(fetchCursor()).isEqualTo(6300);
		nextWindow();
		assertThat(fetchCursor()).isEqualTo(6300);

		redis.delete(TicketStore.seqKey(1)); // 번호표 끝이 사라져도(상한 300)
		nextWindow();
		assertThat(fetchCursor()).isEqualTo(6300);
	}

	@Test
	void 여유가_0이면_커서는_번호표_끝을_넘지_않는다() {
		Duration window = Duration.ofSeconds(3);
		assertThat(cursorStore.advanceAndGet(1L, true, window, 300, 0)).isZero(); // 번호표가 없으면 오르지 않는다
		ticketLast(21);
		for (int i = 0; i < 3; i++) {
			nextWindow();
			assertThat(cursorStore.advanceAndGet(1L, true, window, 300, 0)).isEqualTo(21);
		}
	}

	@Test
	void 번호표가_늘면_다시_step씩_오른다() throws Exception {
		ticketLast(21);
		assertThat(fetchCursor()).isEqualTo(300);
		nextWindow();
		assertThat(fetchCursor()).isEqualTo(321);

		ticketLast(1000); // 사용자가 몰림 — 상한 1300
		nextWindow();
		assertThat(fetchCursor()).isEqualTo(621);
		nextWindow();
		assertThat(fetchCursor()).isEqualTo(921);
		nextWindow();
		assertThat(fetchCursor()).isEqualTo(1221);
		nextWindow();
		assertThat(fetchCursor()).isEqualTo(1300);
	}

	// --- 기동 웜업 요청은 커서를 읽기만 한다 (WarmupRequests: 웜업 값 + 루프백) ---------------------------

	@Test
	void 웜업_요청은_커서를_올리지_않고_락도_잡지_않는다() throws Exception {
		ticketLast(1000);
		assertThat(fetchCursor()).isEqualTo(300);
		nextWindow();

		for (int i = 0; i < 3; i++) {
			assertThat(fetchCursor(get(URL).header(WarmupRequests.HEADER, warmupRequests.token()))).isEqualTo(300);
		}
		assertThat(redis.hasKey(CursorStore.lockKey(1))).isFalse();
		assertThat(fetchCursor()).isEqualTo(600); // 웜업이 창을 쓰지 않아 다음 요청이 올린다
	}

	@Test
	void 웜업_값이_있어도_루프백이_아니면_커서를_올린다() throws Exception {
		ticketLast(1000);
		assertThat(fetchCursor(get(URL).header(WarmupRequests.HEADER, warmupRequests.token()).with(request -> {
			request.setRemoteAddr("203.0.113.7");
			return request;
		}))).isEqualTo(300);
	}

	@Test
	void 없는_이벤트는_404이고_캐시되지_않는다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/999/queue/cursor"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value("EVENT_NOT_FOUND"));
		assertThat(redis.hasKey(CursorStore.cursorKey(999))).isFalse();
	}

	@Test
	void eventId가_숫자가_아니면_400이고_캐시되지_않는다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/abc/queue/cursor"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"));
	}

}
