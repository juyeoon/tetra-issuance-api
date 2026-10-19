package io.tetra.issuance.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 기동 웜업 (MVP 계획 A4 · 운영 flow 2-2 "Application Warmup"). server 모드에서만, 트래픽을 받기 전에 한 번 실행한다.
 * <p>
 * Spring 은 ApplicationRunner 가 모두 끝난 뒤에 readiness 를 ACCEPTING_TRAFFIC 으로 바꾸므로, 여기서 시간을 쓰는 만큼
 * ALB 대상 등록이 늦어진다 — 그래서 상한(tetra.warmup.max-duration)을 둔다.
 * <ol>
 * <li>DB 커넥션 풀을 minimumIdle(= 풀 크기)까지 채움</li>
 * <li>Redis 연결, Lua 스크립트 SCRIPT LOAD (첫 EVALSHA 가 NOSCRIPT 로 한 번 더 왕복하지 않게)</li>
 * <li>핫 경로 반복: RS256 서명 검증(JIT), 번호표·claim Lua 를 {@code warmup:} 접두사 키로 실행, 자기 자신에게 HTTP
 * (health·커서) — 실제 번호·재고·세션 키는 건드리지 않는다</li>
 * <li>{@code warmup:} 키 삭제</li>
 * </ol>
 * 웜업이 실패해도 기동은 계속한다(로그만) — 웜업은 성능을 위한 것이고 정확성과 무관하다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartupWarmup implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);

	static final String PREFIX = "warmup:";

	private final TetraProperties properties;

	private final DataSource dataSource;

	private final StringRedisTemplate redis;

	private final Map<String, RedisScript<?>> scripts;

	private final Environment environment;

	public StartupWarmup(TetraProperties properties, DataSource dataSource, StringRedisTemplate redis,
			Map<String, RedisScript<?>> scripts, Environment environment) {
		this.properties = properties;
		this.dataSource = dataSource;
		this.redis = redis;
		this.scripts = scripts;
		this.environment = environment;
	}

	@Override
	public void run(ApplicationArguments args) {
		int iterations = properties.warmup().iterations();
		if (properties.mode() != TetraProperties.Mode.SERVER || iterations == 0) {
			return;
		}
		long started = System.nanoTime();
		long deadline = started + properties.warmup().maxDuration().toNanos();
		try {
			fillConnectionPool(deadline);
			loadScripts();
			int done = hotPath(iterations, deadline);
			log.info("Warmup done in {} ms: {} of {} iterations, {} scripts loaded",
					Duration.ofNanos(System.nanoTime() - started).toMillis(), done, iterations, scripts.size());
		}
		catch (Exception e) {
			log.warn("Warmup failed — continuing startup without it", e);
		}
		finally {
			deleteWarmupKeys();
		}
	}

	/** minimumIdle 까지 커넥션이 열릴 때까지 기다린다 (Hikari 는 백그라운드로 채움) */
	private void fillConnectionPool(long deadline) throws Exception {
		try (Connection c = dataSource.getConnection()) {
			c.isValid(2);
		}
		if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
			int target = hikari.getMinimumIdle();
			while (hikari.getHikariPoolMXBean().getTotalConnections() < target && System.nanoTime() < deadline) {
				Thread.sleep(50);
			}
		}
	}

	private void loadScripts() {
		redis.execute((RedisCallback<Object>) connection -> {
			connection.ping();
			for (RedisScript<?> script : scripts.values()) {
				connection.scriptingCommands().scriptLoad(script.getScriptAsString().getBytes(StandardCharsets.UTF_8));
			}
			return null;
		});
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private int hotPath(int iterations, long deadline) throws Exception {
		RSAKey key = new RSAKeyGenerator(2048).generate();
		RSASSASigner signer = new RSASSASigner(key);
		RSASSAVerifier verifier = new RSASSAVerifier(key.toRSAPublicKey());
		RedisScript ticket = scripts.get("ticketIssueScript");
		RedisScript claim = scripts.get("couponClaimScript");
		String run = PREFIX + UUID.randomUUID() + ":";
		redis.opsForHash().put(run + "session", "user_id", "warmup");
		redis.opsForValue().set(run + "stock", Integer.toString(iterations));
		HttpClient http = localServer() == null ? null : HttpClient.newHttpClient();

		int i = 0;
		for (; i < iterations && System.nanoTime() < deadline; i++) {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("1").build(),
					new JWTClaimsSet.Builder().claim("user_id", "warmup-" + i).jwtID(UUID.randomUUID().toString()).build());
			jwt.sign(signer);
			SignedJWT.parse(jwt.serialize()).verify(verifier);
			if (ticket != null) {
				redis.execute(ticket, List.of(run + "session", run + "seq", run + "total", run + "retry", run + "done:" + i),
						Long.toString(System.currentTimeMillis()));
			}
			if (claim != null) {
				redis.execute(claim, List.of(run + "done:" + i, run + "stock"));
			}
			if (http != null && i % 10 == 0) {
				get(http, "/api/issuance/health");
				get(http, "/api/issuance/events/" + properties.scope().eventId() + "/queue/cursor");
			}
		}
		return i;
	}

	private String localServer() {
		String port = environment.getProperty("local.server.port");
		return port == null ? null : "http://127.0.0.1:" + port;
	}

	private void get(HttpClient http, String path) throws Exception {
		http.send(HttpRequest.newBuilder(URI.create(localServer() + path)).timeout(Duration.ofSeconds(2)).GET().build(),
				HttpResponse.BodyHandlers.discarding());
	}

	private void deleteWarmupKeys() {
		try {
			List<String> keys = new ArrayList<>();
			try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").count(1000).build())) {
				cursor.forEachRemaining(keys::add);
			}
			if (!keys.isEmpty()) {
				redis.delete(keys);
			}
		}
		catch (Exception e) {
			log.warn("Warmup key cleanup failed", e);
		}
	}

}
