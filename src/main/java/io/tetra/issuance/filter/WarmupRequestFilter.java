package io.tetra.issuance.filter;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import io.tetra.issuance.common.WarmupContext;
import io.tetra.issuance.metrics.WarmupRequests;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 기동 웜업 요청({@link WarmupRequests} — 기동 때 만든 값 + 루프백)이면 이 요청 동안 {@link WarmupContext} 를 켠다.
 * 인증 필터보다 먼저 돈다(FilterConfig order 5). 웜업이 아닌 요청은 표시 없이 그대로 넘긴다.
 */
public class WarmupRequestFilter extends OncePerRequestFilter {

	public static final String URL_PATTERN = "/api/issuance/*";

	private final WarmupRequests warmupRequests;

	public WarmupRequestFilter(WarmupRequests warmupRequests) {
		this.warmupRequests = warmupRequests;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!warmupRequests.isWarmup(request)) {
			chain.doFilter(request, response);
			return;
		}
		try (WarmupContext.Scope scope = WarmupContext.enter()) {
			chain.doFilter(request, response);
		}
	}

}
