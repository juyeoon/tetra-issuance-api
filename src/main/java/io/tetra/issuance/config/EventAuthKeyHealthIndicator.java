package io.tetra.issuance.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.tetra.issuance.service.EventMeta;
import io.tetra.issuance.service.EventMetaCache;

/**
 * readiness 그룹의 "eventAuthKey" (MVP 계획 A4). 이 Pod가 맡은 이벤트에 쓸 수 있는 인증키(ACTIVE·미만료)가 없으면 DOWN.
 * 키가 없으면 입장 토큰을 모두 거절하므로 트래픽을 받지 않게 한다.
 * 기동 때 정해지는 값이라 DB·Redis 처럼 순간적으로 흔들리지 않는다(그래서 readiness 에 넣어도 모든 Pod 가 한꺼번에 빠지지 않는다).
 */
@Component
public class EventAuthKeyHealthIndicator implements HealthIndicator {

	private final EventMetaCache events;

	private final long eventId;

	public EventAuthKeyHealthIndicator(EventMetaCache events, TetraProperties properties) {
		this.events = events;
		this.eventId = properties.scope().eventId();
	}

	@Override
	public Health health() {
		return events.find(eventId)
				.filter(EventMeta::hasPublicKey)
				.map(e -> Health.up().withDetail("eventId", eventId).build())
				.orElseGet(() -> Health.down().withDetail("eventId", eventId).withDetail("reason", "no active auth key").build());
	}

}
