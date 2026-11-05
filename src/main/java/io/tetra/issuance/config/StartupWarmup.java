package io.tetra.issuance.config;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zaxxer.hikari.HikariDataSource;

import io.lettuce.core.resource.ClientResources;
import io.tetra.issuance.common.WarmupContext;
import io.tetra.issuance.filter.WarmupKeys;
import io.tetra.issuance.metrics.WarmupRequests;
import io.tetra.issuance.redis.CouponStockStore;

/**
 * 기동 웜업 (MVP 계획 A4 · 운영 flow 2-2 "Application Warmup"). server 모드에서만, 트래픽을 받기 전에 한 번 실행한다.
 * <p>
 * Spring 은 ApplicationRunner 가 모두 끝난 뒤에 readiness 를 ACCEPTING_TRAFFIC 으로 바꾸므로, 여기서 시간을 쓰는 만큼
 * ALB 대상 등록이 늦어진다 — 그래서 상한(tetra.warmup.max-duration)을 둔다.
 * <ol>
 * <li>DB 커넥션 풀을 minimumIdle(= 풀 크기)까지 채움</li>
 * <li>Redis 연결, Lua 스크립트 SCRIPT LOAD (첫 EVALSHA 가 NOSCRIPT 로 한 번 더 왕복하지 않게)</li>
 * <li><b>실제 HTTP 경로</b>를 자기 자신(127.0.0.1)에게 동시에 보낸다 (2026-11-05, 11-04 부하 시험 — 번호표 p99 초과).
 * 웜업 사용자 하나가 입장(세션 API, 임시 키 RS256 검증) 뒤 번호표 → 쿠폰 목록 → claim 을 {@value #FLOWS_PER_SESSION}번 돌고(그때마다
 * claim 결과 키를 지워 다시 받을 수 있게), {@value #SIDE_EVERY}번에 한 번 커서도 부른다. tetra.warmup.concurrency 개가 동시에 돌아
 * Tomcat 스레드·Redis 연결이 폭주 전에 미리 늘고 경합 경로까지 JIT 된다.
 * 요청에는 {@link WarmupRequests} 표시를 달아 서버가 {@link WarmupContext} 로 처리한다 — 같은 코드를 타되 Redis 키는 {@code warmup:}
 * 쪽, 업무 지표·http_server_requests·발급 이력은 남지 않고, 입장 OPEN·시작 시각 검사를 넘는다.
 * 커서 API 는 이 Pod 이벤트의 실제 커서 키를 읽기만 한다 (웜업 표시가 있으면 증가 없음)</li>
 * <li>임시 키를 버리고({@link WarmupKeys#close()} — 이후 웜업 토큰은 서명 실패) {@code warmup:} 키 삭제. 둘 다 readiness 가 UP 되기 전</li>
 * </ol>
 * 결과는 한 줄 로그로 남긴다 — 걸린 시간·상한에 걸렸는지·경로별 횟수·흐름 시간 변화(10% 구간별 평균, JIT 가 끝났는지 보는 값).
 * 웜업이 실패해도 기동은 계속한다(로그만) — 웜업은 성능을 위한 것이고 정확성과 무관하다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartupWarmup implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);

	static final String PREFIX = WarmupContext.KEY_PREFIX;

	/** 웜업 사용자 하나가 번호표 + claim 을 몇 번 도는지 — 입장 토큰 RS256 서명(클라이언트 쪽, 1코어에서 1ms 넘음)을 줄인다 */
	static final int FLOWS_PER_SESSION = 10;

	/** 커서·쿠폰 목록은 흐름 몇 번에 한 번 */
	static final int SIDE_EVERY = 10;

	private static final int SEGMENTS = 10;

	/** 처음에 도는 수와, 흐름의 몇 %가 끝나면 concurrency 까지 늘리는지 */
	static final int RAMP_WORKERS = 4;

	static final int RAMP_SHARE_PERCENT = 5;

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	private final TetraProperties properties;

	private final DataSource dataSource;

	private final StringRedisTemplate redis;

	private final Map<String, RedisScript<?>> scripts;

	private final Environment environment;

	private final WarmupRequests warmupRequests;

	private final WarmupKeys warmupKeys;

	private final ObjectProvider<ClientResources> lettuceResources;

	private volatile Report lastReport;

	public StartupWarmup(TetraProperties properties, DataSource dataSource, StringRedisTemplate redis,
			Map<String, RedisScript<?>> scripts, Environment environment, WarmupRequests warmupRequests,
			WarmupKeys warmupKeys, ObjectProvider<ClientResources> lettuceResources) {
		this.properties = properties;
		this.dataSource = dataSource;
		this.redis = redis;
		this.scripts = scripts;
		this.environment = environment;
		this.warmupRequests = warmupRequests;
		this.warmupKeys = warmupKeys;
		this.lettuceResources = lettuceResources;
	}

	@Override
	public void run(ApplicationArguments args) {
		TetraProperties.Warmup config = properties.warmup();
		if (properties.mode() != TetraProperties.Mode.SERVER || config.iterations() == 0) {
			return;
		}
		logRuntime();
		long started = System.nanoTime();
		long deadline = started + config.maxDuration().toNanos();
		try {
			fillConnectionPool(deadline);
			loadScripts();
			String server = localServer();
			if (server == null) {
				log.info("Warmup: no local web server — HTTP warmup skipped");
				return;
			}
			Report report = new HttpWarmup(server, config.iterations(), config.concurrency(), deadline).run();
			lastReport = report;
			log.info("Warmup done in {} ms (limit {} ms, hit limit: {}): {}",
					Duration.ofNanos(System.nanoTime() - started).toMillis(), config.maxDuration().toMillis(),
					report.hitLimit() ? "yes" : "no", report);
		}
		catch (Exception e) {
			log.warn("Warmup failed — continuing startup without it", e);
		}
		finally {
			warmupKeys.close(); // readiness 전에 임시 키를 버린다 — 이후 웜업 토큰은 받지 않음
			deleteWarmupKeys();
		}
	}

	/** 마지막 HTTP 웜업 결과 (테스트용), 없으면 null */
	Report lastReport() {
		return lastReport;
	}

	/**
	 * 이 컨테이너에서 JVM 이 잡은 값 (11-04 부하 시험 요청) — CPU 1코어면 GC(Serial)·JIT 컴파일러 스레드·Lettuce IO 스레드가 작게 잡힌다.
	 */
	private void logRuntime() {
		Runtime rt = Runtime.getRuntime();
		String gc = ManagementFactory.getGarbageCollectorMXBeans().stream()
				.map(GarbageCollectorMXBean::getName)
				.collect(Collectors.joining(", "));
		ClientResources lettuce = lettuceResources.getIfAvailable();
		log.info("Runtime: availableProcessors={}, gc=[{}], CICompilerCount={}, maxHeap={}MB, "
				+ "tomcat threads max={} minSpare={} acceptCount={}, lettuce io={} computation={}",
				rt.availableProcessors(), gc, vmOption("CICompilerCount"), rt.maxMemory() / (1024 * 1024),
				environment.getProperty("server.tomcat.threads.max", "200"),
				environment.getProperty("server.tomcat.threads.min-spare", "10"),
				tomcatAcceptCount(),
				lettuce == null ? "?" : lettuce.ioThreadPoolSize(),
				lettuce == null ? "?" : lettuce.computationThreadPoolSize());
	}

	/**
	 * 요청 포트(8080) Tomcat 커넥터가 실제로 쓰는 연결 대기열 — 설정값이 아니라 커넥터 값(JMX, server.tomcat.mbeanregistry 켜짐)을 읽어
	 * 적용됐는지 확인한다. 읽지 못하면 설정값 뒤에 "(config)"
	 */
	private String tomcatAcceptCount() {
		String port = environment.getProperty("local.server.port");
		String why;
		try {
			var server = ManagementFactory.getPlatformMBeanServer();
			var names = server.queryNames(new javax.management.ObjectName("*:type=ProtocolHandler,*"), null);
			// 이름의 port 는 설정값이라 무작위 포트(0)면 "auto-1" 이 된다 — 실제로 연 포트(localPort)로 고른다
			for (var name : names) {
				if (port != null && port.equals(String.valueOf(server.getAttribute(name, "localPort")))) {
					return String.valueOf(server.getAttribute(name, "acceptCount"));
				}
			}
			why = "port " + port + " not in " + names;
		}
		catch (Exception e) {
			why = e.toString();
		}
		return environment.getProperty("server.tomcat.accept-count", "100") + " (config: " + why + ")";
	}

	private static String vmOption(String name) {
		try {
			return ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
				.getVMOption(name)
				.getValue();
		}
		catch (RuntimeException e) {
			return "?";
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

	private String localServer() {
		String port = environment.getProperty("local.server.port");
		return port == null ? null : "http://127.0.0.1:" + port;
	}

	private void deleteWarmupKeys() {
		try {
			List<String> keys = new ArrayList<>();
			try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").count(1000).build())) {
				while (cursor.hasNext()) {
					keys.add(cursor.next());
					if (keys.size() == 1000) {
						redis.delete(keys);
						keys.clear();
					}
				}
			}
			if (!keys.isEmpty()) {
				redis.delete(keys);
			}
		}
		catch (Exception e) {
			log.warn("Warmup key cleanup failed", e);
		}
	}

	/** 웜업 결과 (로그 한 줄) */
	/**
	 * 웜업 결과 (로그 한 줄). flowsPerSecondBySegment = 흐름 10% 구간별 처리 속도(흐름/초) — 올라가다 평평해지면 충분히 데운 것.
	 * jitMillis = 웜업 동안 JIT 컴파일 시간, jitLast10Millis = 마지막 10% 동안 — 0 에 가까우면 C2 컴파일까지 끝났다.
	 */
	record Report(int flows, int planned, int concurrency, boolean hitLimit, long sessions, long tickets, long claims,
			long cursors, long coupons, long errors, String firstError, List<Long> flowsPerSecondBySegment, long jitMillis,
			long jitLast10Millis) {

		@Override
		public String toString() {
			return "flows " + flows + "/" + planned + " (concurrency " + concurrency + "), requests session=" + sessions
					+ " ticket=" + tickets + " coupons=" + coupons + " claim=" + claims + " cursor=" + cursors
					+ ", errors " + errors + (firstError == null ? "" : " (first: " + firstError + ")")
					+ ", flows/s by 10% step " + flowsPerSecondBySegment + ", JIT compile " + jitMillis
					+ " ms (last 10%: " + jitLast10Millis + " ms)";
		}

	}

	/** 자기 자신에게 실제 HTTP 로 입장 → 번호표 → claim 을 동시에 보낸다 */
	private final class HttpWarmup {

		private final String server;

		private final int planned;

		private final int concurrency;

		private final long deadline;

		private final long eventId = properties.scope().eventId();

		private final String cookieName = properties.session().cookieName();

		private final RSASSASigner signer;

		private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(REQUEST_TIMEOUT)
			.build();

		private final AtomicInteger next = new AtomicInteger();

		private final AtomicInteger done = new AtomicInteger();

		private final AtomicBoolean stop = new AtomicBoolean();

		private final AtomicReference<String> firstError = new AtomicReference<>();

		private final LongAdder sessions = new LongAdder();

		private final LongAdder tickets = new LongAdder();

		private final LongAdder claims = new LongAdder();

		private final LongAdder cursors = new LongAdder();

		private final LongAdder coupons = new LongAdder();

		private final LongAdder errors = new LongAdder();

		private final LongAdder[] segmentCounts = new LongAdder[SEGMENTS];

		private final AtomicLong[] segmentFirst = new AtomicLong[SEGMENTS];

		private final AtomicLong[] segmentLast = new AtomicLong[SEGMENTS];

		/** JIT 컴파일 누적 시간(ms) — 시작 · 흐름 90% 시점. 마지막 10% 동안 컴파일이 거의 없으면 C2 까지 끝난 것 */
		private final CompilationMXBean compiler = ManagementFactory.getCompilationMXBean();

		private final long compileAtStart = compileMillis();

		private final AtomicLong compileAt90 = new AtomicLong(-1);

		HttpWarmup(String server, int planned, int concurrency, long deadline) throws JOSEException {
			this.signer = new RSASSASigner(warmupKeys.open());
			this.server = server;
			this.planned = planned;
			this.concurrency = concurrency;
			this.deadline = deadline;
			for (int i = 0; i < SEGMENTS; i++) {
				segmentCounts[i] = new LongAdder();
				segmentFirst[i] = new AtomicLong(Long.MAX_VALUE);
				segmentLast[i] = new AtomicLong(Long.MIN_VALUE);
			}
		}

		private long compileMillis() {
			return compiler != null && compiler.isCompilationTimeMonitoringSupported() ? compiler.getTotalCompilationTime()
					: -1;
		}

		Report run() {
			// 재고 절반은 성공, 나머지는 품절 — claim 의 두 갈래를 다 데운다 (warmup:stock:{eventId})
			redis.opsForValue().set(PREFIX + CouponStockStore.stockKey(eventId), Integer.toString(planned / 2));
			try (http; ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
				for (int w = 0; w < concurrency; w++) {
					int worker = w;
					workers.submit(() -> work(worker));
				}
			}
			List<Long> bySegment = new ArrayList<>(SEGMENTS);
			for (int i = 0; i < SEGMENTS; i++) {
				long count = segmentCounts[i].sum();
				long span = segmentLast[i].get() - segmentFirst[i].get();
				bySegment.add(count < 2 || span <= 0 ? 0L : Math.round(count * 1e9 / span));
			}
			long compileEnd = compileMillis();
			long at90 = compileAt90.get();
			return new Report(done.get(), planned, concurrency, System.nanoTime() >= deadline && done.get() < planned,
					sessions.sum(), tickets.sum(), claims.sum(), cursors.sum(), coupons.sum(), errors.sum(),
					firstError.get(), bySegment, compileAtStart < 0 ? -1 : compileEnd - compileAtStart,
					at90 < 0 ? -1 : compileEnd - at90);
		}

		private void work(int worker) {
			// 처음에는 RAMP_WORKERS 개만 돈다 — JIT 가 차가운 1코어 Pod 에 처음부터 수백 개를 넣으면 응답이 시간 초과로 끝난다.
			// 흐름의 RAMP_SHARE 만큼 끝나면 나머지가 합류해 concurrency 까지 늘어난다
			if (worker >= RAMP_WORKERS) {
				long rampFlows = (long) planned * RAMP_SHARE_PERCENT / 100;
				while (done.get() < rampFlows && !stop.get() && System.nanoTime() < deadline
						&& next.get() < planned) {
					try {
						Thread.sleep(20);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
			String user = null;
			String sid = null;
			int flows = 0;
			int n;
			while (!stop.get() && System.nanoTime() < deadline && (n = next.getAndIncrement()) < planned) {
				try {
					if (sid == null || flows == FLOWS_PER_SESSION) {
						user = "warmup-" + worker + "-" + n;
						sid = enter(user);
						flows = 0;
						if (sid == null) {
							continue;
						}
					}
					long t0 = System.nanoTime();
					boolean ok = post("/events/" + eventId + "/ticket", sid, tickets)
							&& get("/events/" + eventId + "/coupons", sid, coupons)
							&& post("/events/" + eventId + "/coupons/claim", sid, claims);
					if (ok) {
						long now = System.nanoTime();
						int index = (int) ((long) n * SEGMENTS / planned);
						segmentCounts[index].increment();
						segmentFirst[index].accumulateAndGet(t0, Math::min);
						segmentLast[index].accumulateAndGet(now, Math::max);
						if (done.incrementAndGet() == (long) planned * 9 / 10) {
							compileAt90.set(compileMillis());
						}
					}
					// 같은 웜업 사용자가 다음 흐름에서 다시 번호표·claim 을 받을 수 있게 (밖에서 지우므로 warmup: 을 직접 붙임)
					redis.delete(PREFIX + CouponStockStore.claimDoneKey(eventId, user));
					if (n % SIDE_EVERY == 0) {
						get("/events/" + eventId + "/queue/cursor", null, cursors);
					}
					flows++;
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				catch (Exception e) {
					error("exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
				}
			}
		}

		/** 세션 API — 302 + Set-Cookie 에서 세션 ID 를 꺼낸다 */
		private String enter(String user) throws Exception {
			Instant now = Instant.now();
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("1").build(),
					new JWTClaimsSet.Builder().claim("user_id", user)
						.claim("tenant_id", properties.scope().tenantId())
						.claim("event_id", eventId)
						.issueTime(Date.from(now))
						.expirationTime(Date.from(now.plusSeconds(60)))
						.jwtID(UUID.randomUUID().toString())
						.build());
			jwt.sign(signer);
			HttpResponse<Void> response = http.send(request("/session?JWT=" + jwt.serialize(), null).GET().build(),
					HttpResponse.BodyHandlers.discarding());
			String prefix = cookieName + "=";
			String sid = response.headers()
				.allValues("set-cookie")
				.stream()
				.filter(c -> c.startsWith(prefix))
				.map(c -> c.substring(prefix.length(), c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
				.findFirst()
				.orElse(null);
			if (response.statusCode() != 302 || sid == null) {
				error("session " + response.statusCode());
				return null;
			}
			sessions.increment();
			return sid;
		}

		private boolean post(String path, String sid, LongAdder counter) throws Exception {
			return send(request(path, sid).POST(HttpRequest.BodyPublishers.noBody()).build(), path, counter);
		}

		private boolean get(String path, String sid, LongAdder counter) throws Exception {
			return send(request(path, sid).GET().build(), path, counter);
		}

		private boolean send(HttpRequest request, String path, LongAdder counter) throws Exception {
			int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
			if (status != 200) {
				error(path.substring(path.lastIndexOf('/') + 1) + " " + status);
				return false;
			}
			counter.increment();
			return true;
		}

		private HttpRequest.Builder request(String path, String sid) {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server + "/api/issuance" + path))
				.header(WarmupRequests.HEADER, warmupRequests.token())
				.timeout(REQUEST_TIMEOUT);
			if (sid != null) {
				builder.header("Cookie", cookieName + "=" + sid);
			}
			return builder;
		}

		/** 실패가 성공보다 많아지면(설정 문제 등) 남은 반복을 멈춘다 — 상한까지 헛돌지 않게 */
		private void error(String what) {
			errors.increment();
			firstError.compareAndSet(null, what);
			if (errors.sum() >= 50 && errors.sum() > done.get()) {
				stop.set(true);
			}
		}

	}

}
