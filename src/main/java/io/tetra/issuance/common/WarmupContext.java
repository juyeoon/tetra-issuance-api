package io.tetra.issuance.common;

/**
 * 지금 스레드가 기동 웜업(StartupWarmup)이 보낸 요청을 처리 중인지 표시한다.
 * <p>
 * 웜업은 실제 요청과 <b>같은 코드</b>(Tomcat → 필터 → 컨트롤러 → 서비스 → Redis Lua → JSON)를 타야 JIT·스레드 풀이 데워진다.
 * 그래서 코드를 따로 두지 않고, 이 표시가 있을 때만 아래가 달라진다.
 * <ul>
 * <li>Redis 키 앞에 {@link #KEY_PREFIX} — 실제 번호표 순번·재고·세션·진입 카운터·jti 키를 건드리지 않는다 (키 이름 메서드가 {@link #keyPrefix()} 를 붙임)</li>
 * <li>업무 지표(tetra_*_total)를 세지 않는다 (IssuanceMetrics)</li>
 * <li>입장 OPEN·이벤트 시작·종료 검사를 넘는다 — Pod 는 시작 전에 뜨므로 그대로면 Redis 앞에서 끝난다 (검사 자체는 실행한다)</li>
 * <li>입장 토큰은 이 프로세스가 만든 웜업 전용 키로 검증한다 (JwtAuthFilter)</li>
 * <li>발급 이력(MySQL)은 쓰지 않는다 (ClaimService)</li>
 * </ul>
 * 표시는 WarmupRequestFilter 가 웜업 요청(WarmupRequests — 기동 때 만든 임의 값 + 루프백)에만 켜고 요청이 끝나면 지운다.
 * 서블릿 요청은 한 스레드에서 끝나므로 ThreadLocal 로 충분하다.
 */
public final class WarmupContext {

	public static final String KEY_PREFIX = "warmup:";

	private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

	private static final Scope SCOPE = ACTIVE::remove;

	private WarmupContext() {
	}

	public static boolean active() {
		return ACTIVE.get() != null;
	}

	/** 웜업 요청이면 {@code "warmup:"}, 아니면 빈 문자열 — Redis 키 이름 앞에 붙인다 */
	public static String keyPrefix() {
		return active() ? KEY_PREFIX : "";
	}

	/** try-with-resources 로 쓴다 — 블록이 끝나면 표시를 지운다 */
	public static Scope enter() {
		ACTIVE.set(Boolean.TRUE);
		return SCOPE;
	}

	public interface Scope extends AutoCloseable {

		@Override
		void close();

	}

}
