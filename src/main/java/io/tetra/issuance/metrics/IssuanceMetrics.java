package io.tetra.issuance.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.tetra.issuance.common.ErrorCode;

/**
 * 발급 업무 카운터 (MVP 계획 A6). <b>이 Pod가 처리한 수</b>라서 여러 Pod 를 합칠 때는 {@code sum by (event_id)}.
 * 모든 지표에는 공통 태그 tenant_id·event_id 가 붙는다 (application.yml management.metrics.tags).
 * <pre>
 * tetra_sessions_total{result="created|reused|before_open"}          세션 발급 요청 결과
 * tetra_tickets_total{result="issued|already_claimed|session_gone"}  번호표 요청 결과
 * tetra_claims_total{result="success|sold_out", replay="true|false"} claim 결과 (replay = 처음 결과를 다시 돌려준 요청)
 * tetra_session_rejects_total{reason="..."}                          세션 발급 거절 — 입장 토큰 검증 실패(JwtAuthFilter)·jti 재사용
 * </pre>
 * 거절 사유는 tetra_sessions_total 의 라벨이 아니라 별도 이름이다 (같은 이름에 태그 키 구성이 다르면 Micrometer 등록 오류).
 * 사유는 아래 8개로만 묶고, 토큰·사용자 ID·kid 같은 값은 라벨에 넣지 않는다. 세션 쿠키 실패(SessionAuthFilter)는 세지 않는다.
 */
@Component
public class IssuanceMetrics {

	/** reason 라벨 값 — 앱 시작 때 모두 0으로 등록 (increase 가 첫 실패부터 잡히게) */
	static final List<String> REJECT_REASONS = List.of("invalid_signature", "expired", "malformed", "missing",
			"tenant_mismatch", "event_not_found", "reused", "other");

	private final Counter sessionCreated;

	private final Counter sessionReused;

	private final Counter sessionBeforeOpen;

	private final Counter ticketIssued;

	private final Counter ticketAlreadyClaimed;

	private final Counter ticketSessionGone;

	private final Counter claimSuccess;

	private final Counter claimSoldOut;

	private final Counter claimReplaySuccess;

	private final Counter claimReplaySoldOut;

	private final Map<String, Counter> sessionRejects = new HashMap<>();

	public IssuanceMetrics(MeterRegistry registry) {
		sessionCreated = counter(registry, "tetra.sessions", "세션 발급 요청 결과", "result", "created");
		sessionReused = counter(registry, "tetra.sessions", "세션 발급 요청 결과", "result", "reused");
		sessionBeforeOpen = counter(registry, "tetra.sessions", "세션 발급 요청 결과", "result", "before_open");
		ticketIssued = counter(registry, "tetra.tickets", "번호표 요청 결과", "result", "issued");
		ticketAlreadyClaimed = counter(registry, "tetra.tickets", "번호표 요청 결과", "result", "already_claimed");
		ticketSessionGone = counter(registry, "tetra.tickets", "번호표 요청 결과", "result", "session_gone");
		claimSuccess = counter(registry, "tetra.claims", "claim 결과", "result", "success", "replay", "false");
		claimSoldOut = counter(registry, "tetra.claims", "claim 결과", "result", "sold_out", "replay", "false");
		claimReplaySuccess = counter(registry, "tetra.claims", "claim 결과", "result", "success", "replay", "true");
		claimReplaySoldOut = counter(registry, "tetra.claims", "claim 결과", "result", "sold_out", "replay", "true");
		for (String reason : REJECT_REASONS) {
			sessionRejects.put(reason, counter(registry, "tetra.session.rejects", "세션 발급 거절 사유", "reason", reason));
		}
	}

	private static Counter counter(MeterRegistry registry, String name, String description, String... tags) {
		return Counter.builder(name).description(description).tags(tags).register(registry);
	}

	public void sessionCreated() {
		sessionCreated.increment();
	}

	public void sessionReused() {
		sessionReused.increment();
	}

	public void sessionBeforeOpen() {
		sessionBeforeOpen.increment();
	}

	public void ticketIssued() {
		ticketIssued.increment();
	}

	public void ticketAlreadyClaimed() {
		ticketAlreadyClaimed.increment();
	}

	public void ticketSessionGone() {
		ticketSessionGone.increment();
	}

	public void claim(boolean success, boolean replay) {
		(success ? (replay ? claimReplaySuccess : claimSuccess) : (replay ? claimReplaySoldOut : claimSoldOut)).increment();
	}

	public void sessionRejected(ErrorCode code) {
		sessionRejects.get(rejectReason(code)).increment();
	}

	/** 에러 코드 → reason. user_id 길이·tenant_id 형식은 토큰 내용 형식 오류라 malformed */
	static String rejectReason(ErrorCode code) {
		return switch (code) {
			case JWT_INVALID_SIGNATURE -> "invalid_signature";
			case JWT_EXPIRED -> "expired";
			case JWT_MALFORMED, USER_ID_TOO_LONG, INVALID_TENANT_ID -> "malformed";
			case JWT_MISSING -> "missing";
			case TENANT_MISMATCH -> "tenant_mismatch";
			case EVENT_NOT_FOUND -> "event_not_found";
			case JWT_REUSED -> "reused";
			default -> "other";
		};
	}

}
