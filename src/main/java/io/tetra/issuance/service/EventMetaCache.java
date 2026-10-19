package io.tetra.issuance.service;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import io.tetra.issuance.common.PemPublicKeys;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.domain.Event;
import io.tetra.issuance.domain.EventAuthKey;
import io.tetra.issuance.repository.EventAuthKeyRepository;
import io.tetra.issuance.repository.EventRepository;

/**
 * 이 Pod가 맡는 이벤트 하나(tetra.scope)의 메타(공개키·테넌트·이름·시작/종료 시각·배너·복귀 주소·재고)를
 * 기동 시 한 번 읽어 메모리에 둔다. 요청마다 MySQL 을 읽지 않기 위함 (플랜 4-1).
 * <ul>
 * <li>MVP는 이벤트마다 API Deployment 하나다(MVP 계획 A2). 다른 eventId 요청은 캐시에 없으므로 404 EVENT_NOT_FOUND</li>
 * <li>이벤트가 없거나, 테넌트가 scope 와 다르거나, 승인(APPROVED)되지 않았으면 <b>기동 실패</b> —
 * 잘못된 설정으로 트래픽을 받지 않게</li>
 * <li>공개키는 event_auth_key 에서 status = ACTIVE 이고 아직 만료되지 않은(지금 &lt; expires_at) 키 전부를 key_version 별로 둔다.
 * JWT 검증 때 헤더의 kid(= key_version)로 고른다 (MVP 계획 B12). 키가 없으면 기동은 하되 입장 토큰을 모두 거절한다</li>
 * </ul>
 * 기동 후 바뀐 값은 {@link #reload()} 로 다시 읽는다.
 */
@Component
public class EventMetaCache implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(EventMetaCache.class);

	private final EventRepository eventRepository;

	private final EventAuthKeyRepository authKeyRepository;

	private final TetraProperties.Scope scope;

	private final Clock clock;

	private volatile Optional<EventMeta> current = Optional.empty();

	public EventMetaCache(EventRepository eventRepository, EventAuthKeyRepository authKeyRepository,
			TetraProperties properties, Clock clock) {
		this.eventRepository = eventRepository;
		this.authKeyRepository = authKeyRepository;
		this.scope = properties.scope();
		this.clock = clock;
	}

	/** 웹 서버가 요청을 받기 전에 채워 둔다. 조건이 맞지 않으면 예외로 기동을 멈춘다. */
	@Override
	public void afterPropertiesSet() {
		reload();
	}

	public void reload() {
		long eventId = scope.eventId();
		Event e = eventRepository.findById(eventId)
				.orElseThrow(() -> new IllegalStateException("Scope event " + eventId + " not found in schema t_" + scope.tenantId()));
		if (!scope.tenantId().equals(e.getTenantId())) {
			throw new IllegalStateException("Scope event " + eventId + " belongs to tenant " + e.getTenantId()
					+ ", not " + scope.tenantId());
		}
		if (!e.isApproved()) {
			throw new IllegalStateException("Scope event " + eventId + " is not approved (application_status = "
					+ e.getApplicationStatus() + ")");
		}
		Map<Long, RSAPublicKey> keys = activeKeys(eventId, LocalDateTime.now(clock));
		current = Optional.of(new EventMeta(e.getEventId(), e.getTenantId(), e.getName(), e.getStartAt(), e.getEndAt(),
				nullToEmpty(e.getBannerImagePath()), nullToEmpty(e.getEndpointUrl()), e.getStockCount(), keys));
		if (keys.isEmpty()) {
			log.warn("Event {} has no active auth key — entry tokens will be rejected until a key is registered", eventId);
		}
		log.info("Event meta loaded: tenant={} event={} stock={} key versions={}", e.getTenantId(), eventId,
				e.getStockCount(), keys.keySet());
	}

	/** 이 Pod가 맡은 이벤트면 그 메타, 아니면 empty */
	public Optional<EventMeta> find(long eventId) {
		return current.filter(m -> m.eventId() == eventId);
	}

	/** 올라와 있는 이벤트 (재고 워밍업용 — 이 Pod의 이벤트 하나) */
	public Collection<EventMeta> all() {
		return current.map(List::of).orElse(List.of());
	}

	/** key_version → 키. ACTIVE·미만료이고 PEM 을 읽을 수 있는 것만 */
	private Map<Long, RSAPublicKey> activeKeys(long eventId, LocalDateTime now) {
		Map<Long, RSAPublicKey> result = new HashMap<>();
		for (EventAuthKey k : authKeyRepository.findAllByEventIdAndStatusOrderByKeyVersionAsc(eventId, EventAuthKey.ACTIVE)) {
			if (!now.isBefore(k.getExpiresAt())) {
				continue;
			}
			RSAPublicKey key = toPublicKey(k);
			if (key != null) {
				result.put(k.getKeyVersion(), key);
			}
		}
		return result;
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	private static RSAPublicKey toPublicKey(EventAuthKey key) {
		try {
			return PemPublicKeys.parseRsa(key.getPublicKey());
		}
		catch (IllegalArgumentException ex) {
			log.error("Event {} auth key version {} is invalid — tokens with this kid will be rejected", key.getEventId(),
					key.getKeyVersion(), ex);
			return null;
		}
	}

}
