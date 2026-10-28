package io.tetra.issuance.redis;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 서빙 커서 Redis 키.
 * <pre>
 * cursor:serving:{eventId}   서빙 커서
 * advance-lock:{eventId}     증가 윈도우 락 (TTL = cursor-window)
 * </pre>
 * 커서 상한의 기준인 번호표 끝은 {@link TicketStore#seqKey} 를 읽는다.
 */
@Component
public class CursorStore {

	private final StringRedisTemplate redis;
	private final RedisScript<Long> cursorAdvanceScript;

	public CursorStore(StringRedisTemplate redis, @Qualifier("cursorAdvanceScript") RedisScript<Long> cursorAdvanceScript) {
		this.redis = redis;
		this.cursorAdvanceScript = cursorAdvanceScript;
	}

	public static String cursorKey(long eventId) {
		return "cursor:serving:" + eventId;
	}

	public static String lockKey(long eventId) {
		return "advance-lock:" + eventId;
	}

	/**
	 * 윈도우의 첫 요청이면 커서를 step 만큼 올리고(advance 일 때만), 현재 커서를 돌려준다.
	 * 올려도 번호표 끝(ticket:seq) + slack 을 넘지 않고, 커서는 줄지 않는다.
	 */
	public long advanceAndGet(long eventId, boolean advance, Duration window, int step, int slack) {
		Long cursor = redis.execute(cursorAdvanceScript,
				List.of(lockKey(eventId), cursorKey(eventId), TicketStore.seqKey(eventId)),
				Long.toString(window.toMillis()), Integer.toString(step), advance ? "1" : "0", Integer.toString(slack));
		return cursor == null ? 0 : cursor;
	}

}
