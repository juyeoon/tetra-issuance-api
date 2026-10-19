package io.tetra.issuance.redis;

import java.time.Instant;

/**
 * Redis 세션(session:{sid}) 내용.
 *
 * @param sessionIssuedAt 세션 발급 시각 (발급 이력의 session_issued_at)
 * @param ticketNumber   번호표 발급 전이면 null
 * @param queueEnteredAt 번호표 발급 시각(D9). 발급 전이면 null
 */
public record IssuanceSession(String sessionId, String tenantId, long eventId, String userId, Instant sessionIssuedAt,
		Long ticketNumber,
		Instant queueEnteredAt) {

	public boolean hasTicket() {
		return ticketNumber != null && queueEnteredAt != null;
	}

}
