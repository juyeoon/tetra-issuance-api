package io.tetra.issuance.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tetra.issuance.common.ErrorCode;

/** 세션 거절 사유 — 에러 코드가 8개 reason 중 하나로만 묶이는지. 결과별 카운터는 생성 때 0으로 등록되는지. */
class IssuanceMetricsTest {

	/** 지표 이름의 카운터별 태그 조합 — 키 순서로 "replay=false,result=success" 처럼 */
	static List<String> tagSets(SimpleMeterRegistry registry, String name) {
		return registry.find(name).counters().stream()
				.map(c -> c.getId().getTags().stream().map(t -> t.getKey() + "=" + t.getValue())
						.collect(Collectors.joining(",")))
				.toList();
	}

	/** 결과 라벨을 첫 사용 때가 아니라 생성 때 모두 만들어야 increase 가 Pod 마다 첫 증가를 놓치지 않는다 */
	@Test
	void 결과별_카운터는_생성_때_모두_0으로_등록된다() {
		var registry = new SimpleMeterRegistry();
		new IssuanceMetrics(registry);
		assertThat(tagSets(registry, "tetra.sessions"))
				.containsExactlyInAnyOrder("result=created", "result=reused", "result=before_open");
		assertThat(tagSets(registry, "tetra.tickets"))
				.containsExactlyInAnyOrder("result=issued", "result=already_claimed", "result=session_gone");
		assertThat(tagSets(registry, "tetra.claims")).containsExactlyInAnyOrder("replay=false,result=success",
				"replay=false,result=sold_out", "replay=true,result=success", "replay=true,result=sold_out");
		for (String name : List.of("tetra.sessions", "tetra.tickets", "tetra.claims")) {
			assertThat(registry.find(name).counters()).as(name).allSatisfy(c -> assertThat(c.count()).isZero());
		}
	}

	@Test
	void 입장_토큰_에러_코드는_사유별로_묶인다() {
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.JWT_INVALID_SIGNATURE)).isEqualTo("invalid_signature");
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.JWT_EXPIRED)).isEqualTo("expired");
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.JWT_MALFORMED)).isEqualTo("malformed");
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.USER_ID_TOO_LONG)).isEqualTo("malformed");
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.INVALID_TENANT_ID)).isEqualTo("malformed");
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.JWT_MISSING)).isEqualTo("missing");
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.TENANT_MISMATCH)).isEqualTo("tenant_mismatch");
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.EVENT_NOT_FOUND)).isEqualTo("event_not_found");
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.JWT_REUSED)).isEqualTo("reused");
	}

	@Test
	void 그_밖의_코드는_other() {
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.INTERNAL_ERROR)).isEqualTo("other");
		assertThat(IssuanceMetrics.rejectReason(ErrorCode.SESSION_NOT_FOUND)).isEqualTo("other");
	}

	@Test
	void 어떤_코드든_등록된_reason_으로만_센다() {
		var registry = new SimpleMeterRegistry();
		var metrics = new IssuanceMetrics(registry);
		EnumSet.allOf(ErrorCode.class).forEach(metrics::sessionRejected);
		assertThat(registry.find("tetra.session.rejects").counters()).hasSize(IssuanceMetrics.REJECT_REASONS.size());
		assertThat(registry.get("tetra.session.rejects").tag("reason", "other").counter().count())
				.isEqualTo(Arrays.stream(ErrorCode.values()).filter(c -> IssuanceMetrics.rejectReason(c).equals("other")).count());
	}

}
