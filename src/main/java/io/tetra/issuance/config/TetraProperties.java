package io.tetra.issuance.config;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * application.yml 의 {@code tetra.*} 설정 (값은 values/values-{환경}.yml).
 * 값이 잘못되면 앱이 기동 단계에서 실패한다 — 잘못된 설정으로 트래픽을 받는 일을 막기 위함.
 */
@Validated
@ConfigurationProperties("tetra")
public record TetraProperties(
		@NotNull Mode mode,
		@Valid @NotNull Scope scope,
		@NotNull ZoneId timezone,
		@Valid @NotNull Session session,
		@Valid @NotNull Queue queue,
		@Valid @NotNull Jwt jwt,
		@Valid @NotNull Stock stock,
		@Valid @NotNull Warmup warmup) {

	/**
	 * 이 Pod가 맡는 테넌트·이벤트 하나 (MVP 계획 A2 — 이벤트마다 API Deployment 하나).
	 * 배포에서는 환경변수 VALUES_TENANT_ID · VALUES_EVENT_ID 로 받는다. DB 스키마는 t_{tenantId}.
	 */
	public record Scope(
			/* DB CHECK chk_tenant_id_format 과 같은 규칙 — 스키마 이름에 그대로 들어가므로 형식을 엄격히 본다 */
			@NotNull
			@Pattern(regexp = "[a-z0-9]{12}", message = "소문자·숫자 12자여야 합니다")
			String tenantId,
			@NotNull @Positive Long eventId) {
	}

	public record Session(
			/*
			 * 302 Location. "/" 로 시작하는 상대 경로만 허용.
			 * - 절대 주소 금지: 오리진이 받는 Host 가 오리진 주소라, 절대 주소/Host 기반 주소는 사용자를 오리진으로 보냄
			 * - "//host", "/\host" 금지: 브라우저가 다른 사이트 주소로 해석함 (오픈 리다이렉트)
			 * - 줄바꿈 금지: 응답 헤더 주입 방지
			 */
			@NotBlank
			@Pattern(regexp = "/(?![/\\\\])[^\\r\\n\\\\]*",
					message = "'/'로 시작하는 상대 경로여야 합니다 ('//', '\\', 절대 주소 불가)")
			String redirectUrl,
			@NotBlank String cookieName,
			@NotNull Duration ttl,
			boolean cookieSecure,
			/* 입장(세션 발급) OPEN = 이벤트 시작 − 이 값 (MVP 계획 B4·C12, 모든 플랜 60분 고정 — event.json entryOpenAt 과 같은 규칙) */
			@NotNull Duration entryOpenBefore,
			/* 서버 허용 오차: OPEN 보다 이만큼 일찍부터 발급 — 브라우저 시계가 빨라 재입장이 반복되지 않게 */
			@NotNull Duration entryOpenGrace) {

		@AssertTrue(message = "entry-open-before · entry-open-grace 는 0초 이상이어야 합니다")
		public boolean isEntryOpenValid() {
			return entryOpenBefore == null || entryOpenGrace == null
					|| (!entryOpenBefore.isNegative() && !entryOpenGrace.isNegative());
		}

		@AssertTrue(message = "ttl 은 0보다 커야 합니다")
		public boolean isTtlPositive() {
			return ttl == null || ttl.isPositive();
		}

	}

	public record Queue(
			@Positive int cursorStep,
			@NotNull Duration cursorWindow,
			@NotNull Duration cursorCacheSMaxage) {

		@AssertTrue(message = "cursor-window 는 1초 이상이어야 합니다")
		public boolean isCursorWindowValid() {
			return cursorWindow == null || cursorWindow.toSeconds() >= 1;
		}

		@AssertTrue(message = "cursor-cache-s-maxage 는 0초 이상이어야 합니다")
		public boolean isCursorCacheSMaxageValid() {
			return cursorCacheSMaxage == null || !cursorCacheSMaxage.isNegative();
		}

	}

	public record Jwt(
			@Positive int userIdMaxLength,
			/** 테넌트 서버와의 시계 오차 허용치 (만료 판정에 더함) */
			@NotNull Duration clockSkew,
			/** exp 가 지금부터 이보다 멀면 거절 */
			@NotNull Duration maxTtl,
			/**
			 * 테스트 JWT 서명용 개인키 파일 경로 (실행 위치 기준). 로컬 전용이라 다른 환경에서는 비워 둘 수 있음.
			 * Path 로 받으면 Spring 이 "../" 상대 경로를 리소스 경로로 바꾸다 실패하므로 문자열로 받는다.
			 */
			String testPrivateKeyPath) {

		@AssertTrue(message = "clock-skew 는 0 이상 1분 이하여야 합니다")
		public boolean isClockSkewValid() {
			return clockSkew == null || (!clockSkew.isNegative() && clockSkew.compareTo(Duration.ofMinutes(1)) <= 0);
		}

		@AssertTrue(message = "max-ttl 은 0보다 커야 합니다")
		public boolean isMaxTtlPositive() {
			return maxTtl == null || maxTtl.isPositive();
		}

	}

	public record Stock(@NotNull WarmupMode warmupMode) {
	}

	/** 기동 웜업 (MVP 계획 A4). iterations 0 이면 웜업을 하지 않는다. */
	public record Warmup(@PositiveOrZero int iterations, @NotNull Duration maxDuration) {
	}

	/**
	 * 실행 모드 (MVP 계획 A3). 같은 이미지를 환경변수 TETRA_MODE 로 골라 쓴다.
	 * SERVER 외에는 웹 서버 없이 한 번 실행하고 종료 코드(성공 0 / 실패 1)로 끝난다 — K8s Job 용.
	 */
	public enum Mode {
		/** 발급 API 웹 서버 (기본) */
		SERVER,
		/** 재고 적재: event.stock_count → Redis stock:{eventId} (T-90분, event 차트 sync wave 2) */
		STOCK_LOAD,
		/** 종료 집계: Redis 카운터 → event_result_summary (종료 시 KVS 닫기 직후, Redis 삭제 전) */
		AGGREGATE;

		/** 환경변수 TETRA_MODE (없으면 SERVER). stock-load 처럼 소문자·하이픈도 받는다 */
		public static Mode fromEnvironment() {
			String v = System.getenv("TETRA_MODE");
			return v == null || v.isBlank() ? SERVER : valueOf(v.trim().toUpperCase().replace('-', '_'));
		}
	}

	/** 재고 워밍업 방식 (설정값 if-absent / force). */
	public enum WarmupMode {
		/** Redis 에 재고 키가 없을 때만 MySQL stock_count 로 채움 (재기동해도 차감분 유지) */
		IF_ABSENT,
		/** 항상 덮어씀 (로컬에서 재고를 처음 상태로 되돌릴 때만) */
		FORCE
	}

}
