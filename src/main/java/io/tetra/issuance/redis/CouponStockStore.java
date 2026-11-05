package io.tetra.issuance.redis;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import io.tetra.issuance.common.WarmupContext;

/**
 * 재고·claim Redis 키. 재고는 이벤트 단위 카운터 1개다(MVP 물리 ERD — event.stock_count).
 * <pre>
 * stock:{eventId}                  이벤트 재고 (워밍업·재고 적재 때 event.stock_count 로 채움, claim 때만 DECR)
 * claim:done:{eventId}:{userId}    claim 처리 결과 '1' 성공 / '0' 품절 (TTL 없음 — 이벤트 종료 때 Redis 째 정리). 번호표 발급도 이 키를 보고 막는다
 * </pre>
 */
@Component
public class CouponStockStore {

	private final StringRedisTemplate redis;

	private final RedisScript<List<Long>> couponClaimScript;

	public CouponStockStore(StringRedisTemplate redis,
			@Qualifier("couponClaimScript") RedisScript<List<Long>> couponClaimScript) {
		this.redis = redis;
		this.couponClaimScript = couponClaimScript;
	}

	/** 기동 웜업 요청이면 앞에 warmup: (WarmupContext) — 아래 키 이름 메서드도 같다 */
	public static String stockKey(long eventId) {
		return WarmupContext.keyPrefix() + "stock:" + eventId;
	}

	public static String claimDoneKey(long eventId, String userId) {
		return WarmupContext.keyPrefix() + "claim:done:" + eventId + ":" + userId;
	}

	/** 재고 워밍업. ifAbsent=true 면 키가 없을 때만 채운다(재시작해도 차감분 유지). @return 실제로 값을 넣었는지 */
	public boolean putStock(long eventId, long stock, boolean ifAbsent) {
		String key = stockKey(eventId);
		if (ifAbsent) {
			return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, Long.toString(stock)));
		}
		redis.opsForValue().set(key, Long.toString(stock));
		return true;
	}

	/** 남은 재고 (키가 없으면 0, 음수는 0으로). */
	public long remaining(long eventId) {
		String v = redis.opsForValue().get(stockKey(eventId));
		return v == null ? 0L : Math.max(0L, Long.parseLong(v));
	}

	/** claim 결과. replay = 이미 처리된 사용자라 저장된 처음 결과를 돌려준 것 */
	public record ClaimOutcome(boolean replay, boolean success) {
	}

	/**
	 * 원자적 claim. 처음이면 재고를 1 차감하고 결과를 claim:done 에 저장, 이미 처리된 사용자면 저장된 결과를 그대로 돌려준다.
	 */
	public ClaimOutcome claim(long eventId, String userId) {
		List<Long> reply = redis.execute(couponClaimScript, List.of(claimDoneKey(eventId, userId), stockKey(eventId)));
		if (reply == null || reply.size() != 2) {
			throw new IllegalStateException("coupon-claim.lua returned unexpected result: " + reply);
		}
		return new ClaimOutcome(reply.get(0) == 1L, reply.get(1) == 1L);
	}

}
