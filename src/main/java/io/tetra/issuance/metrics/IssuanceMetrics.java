package io.tetra.issuance.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 발급 업무 카운터 (MVP 계획 A6). <b>이 Pod가 처리한 수</b>라서 여러 Pod 를 합칠 때는 {@code sum by (event_id)}.
 * 모든 지표에는 공통 태그 tenant_id·event_id 가 붙는다 (application.yml management.metrics.tags).
 * <pre>
 * tetra_sessions_total{result="created|reused|before_open"}          세션 발급 요청 결과
 * tetra_tickets_total{result="issued|already_claimed|session_gone"}  번호표 요청 결과
 * tetra_claims_total{result="success|sold_out", replay="true|false"} claim 결과 (replay = 처음 결과를 다시 돌려준 요청)
 * </pre>
 */
@Component
public class IssuanceMetrics {

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

}
