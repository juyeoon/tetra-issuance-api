package io.tetra.issuance.service;

import java.security.interfaces.RSAPublicKey;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 요청 처리에 필요한 이벤트 정보 (메모리 캐시용). 시각은 DB 저장 그대로 KST.
 *
 * @param bannerUrl event.banner_image_path — 빈 문자열일 수 있음
 * @param returnUrl event.endpoint_url(테넌트 복귀 주소) — 빈 문자열일 수 있음
 * @param stockCount 일괄 발급 재고(event.stock_count, 신청 시점 값) — Redis 재고 워밍업의 초기값
 * @param publicKeys JWT 검증 공개키 — key_version(JWT 헤더 kid) → 키. event_auth_key 의 ACTIVE·미만료 키만. 비었으면 이 이벤트로는 입장 불가
 */
public record EventMeta(long eventId, String tenantId, String name, LocalDateTime startAt, LocalDateTime endAt,
		String bannerUrl, String returnUrl, long stockCount, Map<Long, RSAPublicKey> publicKeys) {

	public EventMeta {
		publicKeys = Map.copyOf(publicKeys);
	}

	/** 테스트 등에서 화면용 정보가 필요 없을 때. 키는 key_version 1 로 둔다(null 이면 키 없음) */
	public EventMeta(long eventId, String tenantId, LocalDateTime startAt, LocalDateTime endAt, RSAPublicKey publicKey) {
		this(eventId, tenantId, "", startAt, endAt, "", "", 0, publicKey == null ? Map.of() : Map.of(1L, publicKey));
	}

	public boolean hasPublicKey() {
		return !publicKeys.isEmpty();
	}

	/** JWT 헤더 kid(= key_version)에 해당하는 키 */
	public Optional<RSAPublicKey> publicKey(long keyVersion) {
		return Optional.ofNullable(publicKeys.get(keyVersion));
	}

	/** start_at 정각부터 시작 */
	public boolean hasStarted(LocalDateTime now) {
		return !now.isBefore(startAt);
	}

	/** end_at 정각부터 종료 (B1) */
	public boolean hasEnded(LocalDateTime now) {
		return !now.isBefore(endAt);
	}

}
