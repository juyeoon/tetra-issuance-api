package io.tetra.issuance.config;

import java.time.Clock;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.tetra.issuance.common.ErrorResponseWriter;
import io.tetra.issuance.filter.JwtAuthFilter;
import io.tetra.issuance.filter.SessionAuthFilter;
import io.tetra.issuance.filter.WarmupKeys;
import io.tetra.issuance.filter.WarmupRequestFilter;
import io.tetra.issuance.metrics.IssuanceMetrics;
import io.tetra.issuance.metrics.WarmupRequests;
import io.tetra.issuance.redis.SessionStore;
import io.tetra.issuance.service.EventMetaCache;

/** 인증 필터를 정해진 경로에만 등록한다 (ADR-0002 결정 2). */
@Configuration(proxyBeanMethods = false)
public class FilterConfig {

	/** 기동 웜업 요청 표시 — 인증 필터보다 먼저 (WarmupContext) */
	@Bean
	FilterRegistrationBean<WarmupRequestFilter> warmupRequestFilter(WarmupRequests warmupRequests) {
		var registration = new FilterRegistrationBean<>(new WarmupRequestFilter(warmupRequests));
		registration.addUrlPatterns(WarmupRequestFilter.URL_PATTERN);
		registration.setName("warmupRequestFilter");
		registration.setOrder(5);
		return registration;
	}

	@Bean
	FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(EventMetaCache events, TetraProperties properties, Clock clock,
			ErrorResponseWriter errorWriter, IssuanceMetrics metrics, WarmupKeys warmupKeys) {
		var registration = new FilterRegistrationBean<>(
				new JwtAuthFilter(events, properties.jwt(), clock, errorWriter, metrics, warmupKeys));
		registration.addUrlPatterns(JwtAuthFilter.PATH);
		registration.setName("jwtAuthFilter");
		registration.setOrder(10);
		return registration;
	}

	@Bean
	FilterRegistrationBean<SessionAuthFilter> sessionAuthFilter(SessionStore sessionStore, TetraProperties properties,
			ErrorResponseWriter errorWriter) {
		var registration = new FilterRegistrationBean<>(
				new SessionAuthFilter(sessionStore, properties.session().cookieName(), errorWriter));
		registration.addUrlPatterns(SessionAuthFilter.URL_PATTERN);
		registration.setName("sessionAuthFilter");
		registration.setOrder(20);
		return registration;
	}

}
