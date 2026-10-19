package io.tetra.issuance.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 최대 동시 접속(B13 A안): 세션은 TTL 동안 살아 있으므로 분 m 의 동시 접속 = (m − TTL, m] 에 만든 세션 수. */
class PeakConcurrencyTest {

	static final Duration TTL = Duration.ofHours(2);
	static final LocalDateTime T = LocalDateTime.of(2026, 11, 1, 9, 0);

	@Test
	void 세션이_없으면_0이고_시각은_없다() {
		assertThat(PeakConcurrency.of(Map.of(), TTL)).isEqualTo(new PeakConcurrency.Peak(0, null));
	}

	@Test
	void TTL_안에_만든_세션은_모두_동시_접속이다() {
		var peak = PeakConcurrency.of(Map.of(T, 100L, T.plusMinutes(30), 50L, T.plusMinutes(119), 10L), TTL);
		assertThat(peak).isEqualTo(new PeakConcurrency.Peak(160, T.plusMinutes(119)));
	}

	@Test
	void 정확히_TTL이_지난_세션은_빠진다() {
		// T 에 만든 세션은 T+120분에 만료 → T+120분 시점에는 그 분에 만든 30명만
		var peak = PeakConcurrency.of(Map.of(T, 100L, T.plusMinutes(120), 30L), TTL);
		assertThat(peak).isEqualTo(new PeakConcurrency.Peak(100, T));
	}

	@Test
	void 최댓값이_처음_나온_분을_기록한다() {
		// T+10: 80 (최대) → T+130: T 의 50이 빠지고 30+... 비교
		var peak = PeakConcurrency.of(Map.of(T, 50L, T.plusMinutes(10), 30L, T.plusMinutes(130), 40L), TTL);
		// T+10 → 80, T+130 → (T+10, T+130] = 30 + 40 = 70
		assertThat(peak).isEqualTo(new PeakConcurrency.Peak(80, T.plusMinutes(10)));
	}

}
