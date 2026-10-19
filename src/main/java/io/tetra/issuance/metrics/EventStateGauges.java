package io.tetra.issuance.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.redis.CursorStore;
import io.tetra.issuance.redis.TicketStore;

/**
 * 이벤트 상태 게이지 (MVP 계획 A6) — 이 Pod의 이벤트 Redis 값을 수집 시점에 읽는다.
 * <b>이벤트 전체 값</b>이라 모든 Pod 가 같은 값을 보고한다 → 여러 Pod 를 볼 때는 {@code max by (event_id)} (sum 하면 Pod 수만큼 부풀려짐).
 * <pre>
 * tetra_event_stock_remaining   남은 재고 (stock:{eventId})
 * tetra_event_cursor            서빙 커서 (cursor:serving:{eventId}) — 이 번호까지 입장
 * tetra_event_ticket_last       마지막 번호표 (ticket:seq:{eventId}) — 대기 인원 ≈ ticket_last − cursor
 * </pre>
 * 수집 한 번에 Redis GET 3번(이벤트 전용 Redis, 수집 간격 5~30초). Redis 를 못 읽으면 NaN.
 */
@Component
public class EventStateGauges {

	private static final Logger log = LoggerFactory.getLogger(EventStateGauges.class);

	private final StringRedisTemplate redis;

	public EventStateGauges(MeterRegistry registry, StringRedisTemplate redis, TetraProperties properties) {
		this.redis = redis;
		long eventId = properties.scope().eventId();
		gauge(registry, "tetra.event.stock.remaining", "남은 재고", CouponStockStore.stockKey(eventId));
		gauge(registry, "tetra.event.cursor", "서빙 커서", CursorStore.cursorKey(eventId));
		gauge(registry, "tetra.event.ticket.last", "마지막 번호표", TicketStore.seqKey(eventId));
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

}
