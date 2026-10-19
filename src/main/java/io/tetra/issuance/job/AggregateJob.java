package io.tetra.issuance.job;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.redis.EntryStore;

/**
 * 종료 집계 Job (TETRA_MODE=aggregate, MVP 계획 A3 · MVP 물리 ERD event_result_summary).
 * 이벤트 종료 워크플로우가 KVS 를 닫은 직후, 이벤트 Redis 를 지우기 전에 실행한다.
 * <ul>
 * <li>session_count = entry:count:{eventId} (신규 세션 수, 재입장 제외)</li>
 * <li>ticket_count = ticket:total:{eventId} (번호표를 처음 받은 수, 재발급 제외)</li>
 * <li>peak_concurrent·peak_at = entry:min:{eventId}:* 로 계산한 최대 동시 접속 (B13, {@link PeakConcurrency})</li>
 * <li>metrics_finalized_at = 지금</li>
 * </ul>
 * ON DUPLICATE KEY UPDATE 라 다시 실행해도 안전하다. 성공·품절 수·CSV 열은 발급 이력 저장 API(Portal·Console 쪽) 몫이라 건드리지 않는다.
 */
@Component
public class AggregateJob {

	private static final Logger log = LoggerFactory.getLogger(AggregateJob.class);

	private static final String UPSERT = """
			INSERT INTO event_result_summary
			  (event_id, tenant_id, session_count, ticket_count, peak_concurrent, peak_at, metrics_finalized_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE
			  session_count = VALUES(session_count), ticket_count = VALUES(ticket_count),
			  peak_concurrent = VALUES(peak_concurrent), peak_at = VALUES(peak_at),
			  metrics_finalized_at = VALUES(metrics_finalized_at)""";

	private final StringRedisTemplate redis;

	private final JdbcTemplate jdbcTemplate;

	private final TetraProperties.Scope scope;

	private final Duration sessionTtl;

	private final Clock clock;

	public AggregateJob(StringRedisTemplate redis, JdbcTemplate jdbcTemplate, TetraProperties properties, Clock clock) {
		this.redis = redis;
		this.jdbcTemplate = jdbcTemplate;
		this.scope = properties.scope();
		this.sessionTtl = properties.session().ttl();
		this.clock = clock;
	}

	public record Summary(long sessionCount, long ticketCount, PeakConcurrency.Peak peak) {
	}

	public Summary run() {
		long eventId = scope.eventId();
		long sessions = counter("entry:count:" + eventId);
		long tickets = counter("ticket:total:" + eventId);
		PeakConcurrency.Peak peak = PeakConcurrency.of(entriesPerMinute(eventId), sessionTtl);
		jdbcTemplate.update(UPSERT, eventId, scope.tenantId(), sessions, tickets, peak.concurrent(), peak.peakAt(),
				LocalDateTime.now(clock).withNano(0));
		log.info("Aggregate done: event={} sessions={} tickets={} peak={} at {}", eventId, sessions, tickets,
				peak.concurrent(), peak.peakAt());
		return new Summary(sessions, tickets, peak);
	}

	private long counter(String key) {
		String v = redis.opsForValue().get(key);
		return v == null ? 0 : Long.parseLong(v);
	}

	/** entry:min:{eventId}:{yyyyMMddHHmm} 전부 → 분별 신규 세션 수 */
	private Map<LocalDateTime, Long> entriesPerMinute(long eventId) {
		String prefix = "entry:min:" + eventId + ":";
		Map<LocalDateTime, Long> result = new HashMap<>();
		try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(1000).build())) {
			while (keys.hasNext()) {
				String key = keys.next();
				try {
					LocalDateTime minute = LocalDateTime.parse(key.substring(prefix.length()), EntryStore.MINUTE);
					result.merge(minute, counter(key), Long::sum);
				}
				catch (DateTimeParseException e) {
					log.warn("Ignored malformed entry minute key {}", key);
				}
			}
		}
		return result;
	}

}
