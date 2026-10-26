package io.tetra.issuance.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.redis.EntryStore;

/** 유효 세션 게이지 — 컨테이너 없이 가짜 Redis(Map) + 바꿀 수 있는 Clock. 창은 결과 탭 최대 동시 접속과 같은 (m − 2h, m]. */
class EventStateGaugesTest {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");
	/** 현재 분 m = 10:30 (초가 있어도 분으로 자른다) */
	static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 10, 30, 45);
	static final LocalDateTime M = NOW.withSecond(0);

	final Map<String, String> store = new HashMap<>();
	final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	final MutableClock clock = new MutableClock(NOW.atZone(KST).toInstant(), KST);
	ValueOperations<String, String> ops;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.multiGet(anyCollection()))
				.thenAnswer(inv -> ((Collection<String>) inv.getArgument(0)).stream().map(store::get).toList());

		TetraProperties properties = mock(TetraProperties.class);
		TetraProperties.Session session = mock(TetraProperties.Session.class);
		when(properties.scope()).thenReturn(new TetraProperties.Scope("poctenant001", 1L));
		when(properties.session()).thenReturn(session);
		when(session.ttl()).thenReturn(Duration.ofHours(2));
		new EventStateGauges(registry, redis, properties, clock);
	}

	double sessionsActive() {
		return registry.get("tetra.event.sessions.active").gauge().value();
	}

	void entries(LocalDateTime minute, long count) {
		store.put(EntryStore.entryMinuteKey(1L, minute), Long.toString(count));
	}

	@Test
	void 현재_분부터_119분_전까지_더하고_정확히_120분_전은_뺀다() {
		entries(M, 5); // 현재 분 포함
		entries(M.minusMinutes(60), 3);
		entries(M.minusMinutes(119), 7); // 창의 첫 분
		entries(M.minusMinutes(120), 100); // 정확히 2시간 전 — 이미 만료
		entries(M.plusMinutes(1), 1000); // 다음 분(다른 Pod 시계가 앞선 경우) — 아직 창 밖
		store.put(EntryStore.entryMinuteKey(2L, M), "50"); // 다른 이벤트

		assertThat(sessionsActive()).isEqualTo(15);
	}

	@Test
	void 분_키가_없으면_0() {
		assertThat(sessionsActive()).isZero();
	}

	@Test
	void Redis를_못_읽으면_NaN() {
		when(ops.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
		assertThat(sessionsActive()).isNaN();
	}

	@Test
	void 값이_숫자가_아니면_NaN() {
		store.put(EntryStore.entryMinuteKey(1L, M), "x");
		assertThat(sessionsActive()).isNaN();
	}

	@Test
	@SuppressWarnings("unchecked")
	void 키_120개를_MGET_한_번으로_읽고_5초_동안은_다시_읽지_않는다() {
		entries(M, 5);
		assertThat(sessionsActive()).isEqualTo(5);

		ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
		verify(ops).multiGet(keys.capture());
		assertThat(keys.getValue()).hasSize(120)
				.contains(EntryStore.entryMinuteKey(1L, M), EntryStore.entryMinuteKey(1L, M.minusMinutes(119)))
				.doesNotContain(EntryStore.entryMinuteKey(1L, M.minusMinutes(120)));

		entries(M, 6);
		clock.advance(Duration.ofSeconds(4));
		assertThat(sessionsActive()).isEqualTo(5); // 캐시
		verify(ops, times(1)).multiGet(anyCollection());

		clock.advance(Duration.ofSeconds(1));
		assertThat(sessionsActive()).isEqualTo(6);
		verify(ops, times(2)).multiGet(anyCollection());
	}

	@Test
	void 분이_바뀌면_창도_한_분_밀린다() {
		entries(M.minusMinutes(119), 7);
		clock.advance(Duration.ofSeconds(15)); // 10:31:00 — 08:31 은 이제 정확히 120분 전
		assertThat(sessionsActive()).isZero();
	}

	@Test
	void 공통_태그_외_라벨은_없다() {
		assertThat(registry.get("tetra.event.sessions.active").gauge().getId().getTags()).isEmpty();
		assertThat(List.of("tetra.event.stock.remaining", "tetra.event.cursor", "tetra.event.ticket.last"))
				.allSatisfy(name -> assertThat(registry.find(name).gauge()).isNotNull());
	}

	static final class MutableClock extends Clock {

		private Instant now;

		private final ZoneId zone;

		MutableClock(Instant now, ZoneId zone) {
			this.now = now;
			this.zone = zone;
		}

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(now, zone);
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
