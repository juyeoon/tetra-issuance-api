package io.tetra.issuance.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.job.PeakConcurrency;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.redis.CursorStore;
import io.tetra.issuance.redis.EntryStore;
import io.tetra.issuance.redis.TicketStore;

/**
 * 이벤트 상태 게이지 (MVP 계획 A6) — 이 Pod의 이벤트 Redis 값을 수집 시점에 읽는다.
 * <b>이벤트 전체 값</b>이라 모든 Pod 가 같은 값을 보고한다 → 여러 Pod 를 볼 때는 {@code max by (event_id)} (sum 하면 Pod 수만큼 부풀려짐).
 * <pre>
 * tetra_event_stock_remaining   남은 재고 (stock:{eventId})
 * tetra_event_cursor            서빙 커서 (cursor:serving:{eventId}) — 이 번호까지 입장
 * tetra_event_ticket_last       마지막 번호표 (ticket:seq:{eventId}) — 대기 인원 ≈ ticket_last − cursor
 * tetra_event_sessions_active   유효 세션 수 — 현재 분 m 의 창 (m − 세션 TTL, m] 의 entry:min:{eventId}:* 합.
 *                               결과 탭 최대 동시 접속(종료 집계 {@link PeakConcurrency})과 같은 정의
 * </pre>
 * 수집 한 번에 Redis GET 3번 + MGET 1번(이벤트 전용 Redis, 수집 간격 5~30초). Redis 를 못 읽으면 NaN.
 */
@Component
public class EventStateGauges {

	private static final Logger log = LoggerFactory.getLogger(EventStateGauges.class);

	/** 유효 세션 수를 다시 읽지 않는 시간 — 수집이 겹쳐도(Prometheus 여러 대 등) MGET 은 이 간격에 한 번 */
	static final Duration SESSIONS_ACTIVE_CACHE = Duration.ofSeconds(5);

	private final StringRedisTemplate redis;

	private final Clock clock;

	private final long eventId;

	private final Duration sessionTtl;

	private volatile Cached sessionsActive;

	private record Cached(double value, long expiresAtMillis) {
	}

	public EventStateGauges(MeterRegistry registry, StringRedisTemplate redis, TetraProperties properties, Clock clock) {
		this.redis = redis;
		this.clock = clock;
		this.eventId = properties.scope().eventId();
		this.sessionTtl = properties.session().ttl();
		gauge(registry, "tetra.event.stock.remaining", "남은 재고", CouponStockStore.stockKey(eventId));
		gauge(registry, "tetra.event.cursor", "서빙 커서", CursorStore.cursorKey(eventId));
		gauge(registry, "tetra.event.ticket.last", "마지막 번호표", TicketStore.seqKey(eventId));
		Gauge.builder("tetra.event.sessions.active", this, EventStateGauges::sessionsActive)
				.description("유효 세션 수 (최근 세션 TTL 안에 만든 신규 세션)")
				.register(registry);
	}

	private void gauge(MeterRegistry registry, String name, String description, String key) {
		Gauge.builder(name, this, self -> self.read(key)).description(description).register(registry);
	}

	private double read(String key) {
		try {
			String v = redis.opsForValue().get(key);
			return v == null ? 0 : Double.parseDouble(v);
		}
		catch (RuntimeException e) {
			log.debug("Gauge read failed for {}", key, e);
			return Double.NaN;
		}
	}

	double sessionsActive() {
		long now = clock.millis();
		Cached cached = sessionsActive;
		if (cached != null && now < cached.expiresAtMillis()) {
			return cached.value();
		}
		double value = readSessionsActive();
		sessionsActive = new Cached(value, now + SESSIONS_ACTIVE_CACHE.toMillis());
		return value;
	}

	/** 분 키는 세션 발급(SessionService)과 같은 Clock 시간대(KST) 로 만든다 */
	private double readSessionsActive() {
		LocalDateTime minute = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
		List<String> keys = PeakConcurrency.windowMinutes(minute, sessionTtl).stream()
				.map(m -> EntryStore.entryMinuteKey(eventId, m))
				.toList();
		try {
			List<String> values = redis.opsForValue().multiGet(keys);
			if (values == null) {
				return Double.NaN;
			}
			long sum = 0;
			for (String v : values) {
				if (v != null) {
					sum += Long.parseLong(v);
				}
			}
			return sum;
		}
		catch (RuntimeException e) {
			log.debug("Gauge read failed for entry minutes of event {}", eventId, e);
			return Double.NaN;
		}
	}

}
