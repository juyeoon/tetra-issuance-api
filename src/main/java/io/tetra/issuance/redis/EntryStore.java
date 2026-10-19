package io.tetra.issuance.redis;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 입장 단계의 Redis 키 (플랜 4-1).
 * <pre>
 * jti:{jti}               "1", TTL = 토큰 만료까지  — 입장 토큰 1회 사용 보장
 * entry:count:{eventId}   신규 세션 수(진입 카운터) — 재진입(세션 재사용)은 세지 않음
 * entry:min:{eventId}:{yyyyMMddHHmm}   그 분(KST)에 만든 신규 세션 수 — 종료 집계의 최대 동시 접속(peak_concurrent) 계산용
 * </pre>
 * 최대 동시 접속(MVP 계획 B13, A안): 세션은 만든 뒤 TTL(2시간) 동안 살아 있고 중간에 끝나지 않으므로,
 * 시각 t 의 동시 접속 = t 까지 직전 TTL 안에 만든 세션 수. 분 카운터를 TTL 길이의 창으로 더한 최댓값이 peak 다.
 * 분 키에는 TTL 을 두지 않는다 — 이벤트 종료 때 이벤트 전용 Redis 째 정리된다.
 */
@Component
public class EntryStore {

	private final StringRedisTemplate redis;

	public EntryStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * SET jti:{jti} 1 NX EX ttl. 처음 쓰는 토큰이면 true, 이미 쓴 토큰이면 false (재전송 공격).
	 * exp 가 지나지 않은 짧은 시간 안의 재전송은 exp 검사만으로 못 막기 때문.
	 */
	public boolean markTokenUsed(String jti, Duration ttl) {
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("jti:" + jti, "1", ttl));
	}

	public static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

	public static String entryMinuteKey(long eventId, ZonedDateTime at) {
		return "entry:min:" + eventId + ":" + MINUTE.format(at);
	}

	/** 신규 세션 1건 — 전체 진입 카운터와 그 분의 카운터를 한 번에(파이프라인) 올린다. at 은 KST 시각 */
	public void incrementEntryCount(long eventId, ZonedDateTime at) {
		redis.executePipelined(new SessionCallback<Object>() {
			@Override
			@SuppressWarnings("unchecked")
			public Object execute(RedisOperations operations) {
				RedisOperations<String, String> ops = operations;
				ops.opsForValue().increment("entry:count:" + eventId);
				ops.opsForValue().increment(entryMinuteKey(eventId, at));
				return null;
			}
		});
	}

}
