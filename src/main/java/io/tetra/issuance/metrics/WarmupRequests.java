package io.tetra.issuance.metrics;

import java.util.UUID;

import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 기동 웜업(StartupWarmup)이 자기 자신에게 보내는 HTTP 요청을 http_server_requests 지표에서 뺀다 (MVP 계획 A4·A6).
 * KEDA 트리거·Console·Portal 이 모두 이 지표로 RPS 를 보는데, 웜업 요청이 섞이면 입장 전·스케일아웃 때 RPS 가 부풀려진다.
 * <p>
 * 웜업 요청에는 {@link #HEADER} 로 이 프로세스가 기동 때 만든 임의 값을 싣고, 값이 맞고 원격 주소가 루프백일 때만 웜업으로 본다.
 * 루프백 확인만으로는 부족하다 — K8s 에서는 Boot 가 Tomcat RemoteIpValve 를 켜서 원격 주소를 X-Forwarded-For 로 바꾸므로
 * 클러스터 안 호출자가 127.0.0.1 을 흉내 낼 수 있다. 값은 루프백 요청 헤더에만 실리고 밖으로 나가지 않는다.
 * <p>
 * 관찰(Observation) 자체를 끄므로 카운트·히스토그램·active 모두 남지 않는다. 요청 처리(Tomcat·필터·컨트롤러)는 그대로 돈다.
 */
@Component
public class WarmupRequests implements ObservationPredicate {

	public static final String HEADER = "X-Tetra-Warmup";

	private final String token = UUID.randomUUID().toString();

	/** 웜업 요청에 실을 헤더 값 — StartupWarmup 만 쓴다 */
	public String token() {
		return token;
	}

	@Override
	public boolean test(String name, Observation.Context context) {
		return !(context instanceof ServerRequestObservationContext server && isWarmup(server.getCarrier()));
	}

	boolean isWarmup(HttpServletRequest request) {
		return token.equals(request.getHeader(HEADER)) && isLoopback(request.getRemoteAddr());
	}

	/** Tomcat 이 주는 원격 주소는 IP 문자열이다 (enableLookups 꺼짐) — 이름 조회 없이 문자열로 본다 */
	static boolean isLoopback(String address) {
		return address != null
				&& (address.startsWith("127.") || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1"));
	}

}
