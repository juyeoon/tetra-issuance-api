package io.tetra.issuance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.service.EventMetaCache;
import io.tetra.issuance.support.TestEventKeys;
import io.tetra.issuance.support.TestJwtFactory;

/** 기동 웜업과 readiness 의 인증키 확인 (MVP 계획 A4). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StartupWarmupIntegrationTest {

	@Autowired
	StartupWarmup warmup;

	@Autowired
	EventAuthKeyHealthIndicator authKeyHealth;

	@Autowired
	EventMetaCache eventMetaCache;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void 웜업은_warmup_키만_쓰고_끝나면_지운다_실제_번호와_재고는_그대로() {
		String seqBefore = redis.opsForValue().get("ticket:seq:1");
		redis.opsForValue().set(CouponStockStore.stockKey(1), "7");

		warmup.run(null);

		Set<String> left = redis.keys(StartupWarmup.PREFIX + "*");
		assertThat(left).isEmpty();
		assertThat(redis.opsForValue().get("ticket:seq:1")).isEqualTo(seqBefore);
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1))).isEqualTo("7");
	}

	@Test
	void 인증키가_있으면_readiness_인증키_항목은_UP_없으면_DOWN() {
		jdbcTemplate.update("DELETE FROM event_auth_key WHERE event_id = 1");
		eventMetaCache.reload();
		assertThat(authKeyHealth.health().getStatus()).isEqualTo(Status.DOWN);

		TestEventKeys.registerActiveKey(jdbcTemplate, TestJwtFactory.withNewKeyPair().publicKeyPem());
		eventMetaCache.reload();
		assertThat(authKeyHealth.health().getStatus()).isEqualTo(Status.UP);
	}

}
