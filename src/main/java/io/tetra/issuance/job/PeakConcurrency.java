package io.tetra.issuance.job;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 최대 동시 접속 계산 (MVP 계획 B13, A안 — 세션 기준).
 * <p>
 * 세션은 만든 뒤 TTL 동안 살아 있고 중간에 끝나지 않는다. 그래서 분 m 의 동시 접속 = (m − TTL, m] 사이 분에 만든 세션 수의 합.
 * 새 세션이 생기는 분에서만 값이 커지므로, 세션이 생긴 분마다 창의 합을 구해 최댓값을 고른다.
 */
public final class PeakConcurrency {

	private PeakConcurrency() {
	}

	/** @param peakAt 최댓값이 처음 나온 분 (세션이 없으면 null) */
	public record Peak(long concurrent, LocalDateTime peakAt) {
	}

	/** @param perMinute 분(초·나노 0) → 그 분에 만든 신규 세션 수 */
	public static Peak of(Map<LocalDateTime, Long> perMinute, Duration sessionTtl) {
		NavigableMap<LocalDateTime, Long> sorted = new TreeMap<>(perMinute);
		long best = 0;
		LocalDateTime bestAt = null;
		long window = 0;
		LocalDateTime tail = sorted.isEmpty() ? null : sorted.firstKey();
		for (Map.Entry<LocalDateTime, Long> e : sorted.entrySet()) {
			window += e.getValue();
			// 창 (m − TTL, m] 밖으로 나간 분을 뺀다
			LocalDateTime windowStart = e.getKey().minus(sessionTtl);
			while (!tail.isAfter(windowStart)) {
				window -= sorted.get(tail);
				tail = sorted.higherKey(tail);
			}
			if (window > best) {
				best = window;
				bestAt = e.getKey();
			}
		}
		return new Peak(best, bestAt);
	}

}
