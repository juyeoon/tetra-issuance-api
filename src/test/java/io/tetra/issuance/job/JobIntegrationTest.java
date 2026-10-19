package io.tetra.issuance.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.redis.CouponStockStore;

/** Job 모드(A3) — 실제 MySQL·Redis 로 재고 적재와 종료 집계를 실행한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobIntegrationTest {

	@Autowired
	StockLoadJob stockLoadJob;

	@Autowired
	AggregateJob aggregateJob;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void 집계_키와_결과를_비운다() {
		Set<String> minuteKeys = redis.keys("entry:min:1:*");
		if (minuteKeys != null && !minuteKeys.isEmpty()) {
			redis.delete(minuteKeys);
		}
		redis.delete(List.of("entry:count:1", "ticket:total:1"));
		jdbcTemplate.update("DELETE FROM event_result_summary");
	}

	// --- 재고 적재 (stock-load) --------------------------------------------------

	@Test
	void 재고_적재는_비어_있는_Redis에_event_stock_count를_올린다() {
		redis.delete(CouponStockStore.stockKey(1));
		stockLoadJob.run();
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1))).isEqualTo("10");
	}

	@Test
	void 재고_적재를_다시_돌려도_차감분은_유지된다_if_absent() {
		redis.opsForValue().set(CouponStockStore.stockKey(1), "3");
		stockLoadJob.run();
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1))).isEqualTo("3");
	}

	// --- 종료 집계 (aggregate) ---------------------------------------------------

	@Test
	void 종료_집계는_Redis_카운터를_event_result_summary에_기록한다() {
		redis.opsForValue().set("entry:count:1", "180");
		redis.opsForValue().set("ticket:total:1", "150");
		redis.opsForValue().set("entry:min:1:202611010900", "100");
		redis.opsForValue().set("entry:min:1:202611010930", "50");
		redis.opsForValue().set("entry:min:1:202611011130", "30"); // 09:00 세션 100명은 11:00에 만료 → 50 + 30 = 80

		AggregateJob.Summary summary = aggregateJob.run();
		assertThat(summary.peak().concurrent()).isEqualTo(150);

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM event_result_summary WHERE event_id = 1");
		assertThat(row.get("tenant_id")).isEqualTo("poctenant001");
		assertThat(((Number) row.get("session_count")).longValue()).isEqualTo(180);
		assertThat(((Number) row.get("ticket_count")).longValue()).isEqualTo(150);
		assertThat(((Number) row.get("peak_concurrent")).longValue()).isEqualTo(150);
		assertThat(row.get("peak_at")).isEqualTo(LocalDateTime.of(2026, 11, 1, 9, 30));
		assertThat(row.get("metrics_finalized_at")).isNotNull();
		assertThat(row.get("success_count")).isNull(); // 발급 이력 저장 API 몫 — 건드리지 않는다
	}

	@Test
	void 종료_집계를_다시_실행하면_같은_행을_갱신한다() {
		redis.opsForValue().set("entry:count:1", "10");
		aggregateJob.run();
		redis.opsForValue().set("entry:count:1", "12");
		aggregateJob.run();

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_result_summary", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT session_count FROM event_result_summary WHERE event_id = 1",
				Integer.class)).isEqualTo(12);
	}

	@Test
	void 접속이_없던_이벤트도_0으로_기록한다() {
		aggregateJob.run();
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM event_result_summary WHERE event_id = 1");
		assertThat(((Number) row.get("session_count")).longValue()).isZero();
		assertThat(((Number) row.get("peak_concurrent")).longValue()).isZero();
		assertThat(row.get("peak_at")).isNull();
	}

}
