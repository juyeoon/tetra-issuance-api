package io.tetra.issuance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.domain.Event;
import io.tetra.issuance.domain.EventAuthKey;
import io.tetra.issuance.repository.EventAuthKeyRepository;
import io.tetra.issuance.repository.EventRepository;
import io.tetra.issuance.support.TestJwtFactory;

/** 단일 이벤트 모드(A2): 이 Pod의 scope 이벤트 하나만 올리고, 조건이 틀리면 기동을 멈춘다. */
class EventMetaCacheTest {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");
	static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 9, 0);

	final EventRepository eventRepository = mock(EventRepository.class);
	final EventAuthKeyRepository keyRepository = mock(EventAuthKeyRepository.class);
	final TetraProperties properties = mock(TetraProperties.class);
	final TestJwtFactory tenant = TestJwtFactory.withNewKeyPair();

	EventMetaCache cache(String tenantId, long eventId) {
		when(properties.scope()).thenReturn(new TetraProperties.Scope(tenantId, eventId));
		return new EventMetaCache(eventRepository, keyRepository, properties, Clock.fixed(NOW.atZone(KST).toInstant(), KST));
	}

	Event event(long id, String tenantId, String status) {
		Event e = mock(Event.class);
		when(e.getEventId()).thenReturn(id);
		when(e.getTenantId()).thenReturn(tenantId);
		when(e.getStartAt()).thenReturn(NOW.minusDays(1));
		when(e.getEndAt()).thenReturn(NOW.plusDays(1));
		when(e.getStockCount()).thenReturn(10L);
		when(e.getApplicationStatus()).thenReturn(status);
		when(e.isApproved()).thenReturn(Event.APPROVED.equals(status));
		when(eventRepository.findById(id)).thenReturn(Optional.of(e));
		return e;
	}

	EventAuthKey key(long eventId, long version, LocalDateTime expiresAt) {
		EventAuthKey k = mock(EventAuthKey.class);
		when(k.getEventId()).thenReturn(eventId);
		when(k.getKeyVersion()).thenReturn(version);
		when(k.getPublicKey()).thenReturn(tenant.publicKeyPem());
		when(k.getExpiresAt()).thenReturn(expiresAt);
		return k;
	}

	@Test
	void scope_이벤트만_올리고_다른_eventId는_없다() {
		event(1, "poctenant001", "APPROVED");
		List<EventAuthKey> keys = List.of(key(1, 1, NOW.plusDays(2)));
		when(keyRepository.findAllByEventIdAndStatusOrderByKeyVersionAsc(1L, EventAuthKey.ACTIVE)).thenReturn(keys);
		EventMetaCache cache = cache("poctenant001", 1);
		cache.afterPropertiesSet();

		assertThat(cache.find(1L)).isPresent();
		assertThat(cache.find(2L)).isEmpty();
		assertThat(cache.all()).hasSize(1);
		assertThat(cache.find(1L).get().stockCount()).isEqualTo(10);
		assertThat(cache.find(1L).get().publicKey(1)).isPresent();
	}

	@Test
	void 만료된_키는_올리지_않는다() {
		event(1, "poctenant001", "APPROVED");
		List<EventAuthKey> keys = List.of(key(1, 1, NOW), key(1, 2, NOW.plusHours(1)));
		when(keyRepository.findAllByEventIdAndStatusOrderByKeyVersionAsc(1L, EventAuthKey.ACTIVE)).thenReturn(keys);
		EventMetaCache cache = cache("poctenant001", 1);
		cache.afterPropertiesSet();

		EventMeta meta = cache.find(1L).orElseThrow();
		assertThat(meta.publicKey(1)).isEmpty(); // expires_at = 지금 → 만료
		assertThat(meta.publicKey(2)).isPresent();
	}

	@Test
	void 키가_없어도_기동은_하지만_입장_키는_없다() {
		event(1, "poctenant001", "APPROVED");
		when(keyRepository.findAllByEventIdAndStatusOrderByKeyVersionAsc(1L, EventAuthKey.ACTIVE)).thenReturn(List.of());
		EventMetaCache cache = cache("poctenant001", 1);
		cache.afterPropertiesSet();

		assertThat(cache.find(1L).orElseThrow().hasPublicKey()).isFalse();
	}

	@Test
	void scope_이벤트가_없으면_기동_실패() {
		when(eventRepository.findById(1L)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> cache("poctenant001", 1).afterPropertiesSet())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("not found");
	}

	@Test
	void 이벤트의_테넌트가_scope와_다르면_기동_실패() {
		event(1, "othertenant1", "APPROVED");
		assertThatThrownBy(() -> cache("poctenant001", 1).afterPropertiesSet())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("othertenant1");
	}

	@Test
	void 승인되지_않은_이벤트면_기동_실패() {
		event(1, "poctenant001", "APPLIED");
		assertThatThrownBy(() -> cache("poctenant001", 1).afterPropertiesSet())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("not approved");
	}

}
