package io.tetra.issuance.filter;

import java.security.interfaces.RSAPublicKey;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * 기동 웜업 전용 임시 RS256 키 쌍. StartupWarmup 이 개인키로 입장 토큰을 만들고, JwtAuthFilter 는 웜업 요청(X-Tetra-Warmup 값 + 루프백)일 때만
 * 이 공개키로 검증한다.
 * <p>
 * <b>웜업 동안만 열려 있다</b> — StartupWarmup 이 {@link #open()} 으로 만들고, 웜업이 끝나면(readiness 가 UP 되기 전) {@link #close()} 로 버린다.
 * 닫힌 뒤에는 웜업 표시가 있어도 받아 줄 키가 없어 서명 검증이 실패한다 (인증 우회 경로가 남지 않게). 웜업을 끈 Pod 는 아예 만들지 않는다.
 * 이 프로세스 메모리에만 있고 DB·밖으로 나가지 않는다.
 */
@Component
public class WarmupKeys {

	private record Pair(RSAKey key, RSAPublicKey publicKey) {
	}

	private volatile Pair pair;

	/** 새 임시 키를 만든다 (2048비트 생성은 1코어에서 수백 ms) */
	public synchronized RSAKey open() throws JOSEException {
		RSAKey key = new RSAKeyGenerator(2048).generate();
		pair = new Pair(key, key.toRSAPublicKey());
		return key;
	}

	/** 임시 키를 버린다 — 이후 웜업 토큰은 검증 실패 */
	public synchronized void close() {
		pair = null;
	}

	/** 열려 있으면 공개키, 닫혔으면 null */
	public RSAPublicKey publicKeyIfOpen() {
		Pair p = pair;
		return p == null ? null : p.publicKey();
	}

}
