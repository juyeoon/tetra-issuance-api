package io.tetra.issuance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/** tetra.* 바인딩과 기동 시 검증. 컨테이너 없이 설정 부분만 띄운다. */
class TetraPropertiesTest {

	/** values-local.yml 과 같은 정상 값 */
	private static final String[] VALID = {
			"tetra.mode=server",
			"tetra.scope.tenant-id=poctenant001",
			"tetra.scope.event-id=1",
			"tetra.timezone=Asia/Seoul",
			"tetra.session.redirect-url=/",
			"tetra.session.cookie-name=TETRA_SID",
			"tetra.session.ttl=2h",
			"tetra.session.cookie-secure=true",
			// "tetra.session.entry-open-before=60m", // TEST-LEAD 원래 값
			"tetra.session.entry-open-before=15m", // TEST-LEAD 시험용 임시
			"tetra.session.entry-open-grace=60s",
			"tetra.queue.cursor-step=300",
			"tetra.queue.cursor-window=3s",
			"tetra.queue.cursor-slack=300",
			"tetra.queue.cursor-cache-s-maxage=1s",
			"tetra.jwt.user-id-max-length=128",
			"tetra.jwt.clock-skew=5s",
			"tetra.jwt.max-ttl=5m",
			"tetra.jwt.test-private-key-path=../local-keys/test-jwt-private.pem",
			"tetra.stock.warmup-mode=if-absent",
			"tetra.warmup.iterations=100",
			"tetra.warmup.max-duration=25s" };

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
			.withUserConfiguration(AppConfig.class)
			.withPropertyValues(VALID);

	@Test
	void 정상_값은_타입에_맞게_바인딩된다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			TetraProperties p = context.getBean(TetraProperties.class);
			assertThat(p.timezone()).isEqualTo(ZoneId.of("Asia/Seoul"));
			assertThat(p.session().redirectUrl()).isEqualTo("/");
			assertThat(p.session().ttl()).isEqualTo(Duration.ofHours(2));
			assertThat(p.session().cookieSecure()).isTrue();
			assertThat(p.queue().cursorStep()).isEqualTo(300);
			assertThat(p.queue().cursorWindow()).isEqualTo(Duration.ofSeconds(3));
			assertThat(p.queue().cursorSlack()).isEqualTo(300);
			assertThat(p.queue().cursorCacheSMaxage()).isEqualTo(Duration.ofSeconds(1));
			assertThat(p.jwt().userIdMaxLength()).isEqualTo(128);
			assertThat(p.stock().warmupMode()).isEqualTo(TetraProperties.WarmupMode.IF_ABSENT);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "server", "stock-load", "aggregate" })
	void 실행_모드는_소문자_하이픈으로_받는다(String mode) {
		runner.withPropertyValues("tetra.mode=" + mode).run(context -> assertThat(context.getBean(TetraProperties.class).mode())
				.isEqualTo(TetraProperties.Mode.valueOf(mode.toUpperCase().replace('-', '_'))));
	}

	@Test
	void 모르는_실행_모드면_기동_실패() {
		runner.withPropertyValues("tetra.mode=batch").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void scope는_테넌트와_이벤트_하나를_받는다() {
		runner.run(context -> {
			TetraProperties.Scope scope = context.getBean(TetraProperties.class).scope();
			assertThat(scope.tenantId()).isEqualTo("poctenant001");
			assertThat(scope.eventId()).isEqualTo(1L);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "POCTENANT001", "poctenant01", "poc-tenant-01", "poctenant0012" })
	void tenant_id_형식이_틀리면_기동_실패(String tenantId) {
		runner.withPropertyValues("tetra.scope.tenant-id=" + tenantId)
				.run(context -> assertThat(context).hasFailed()
						.getFailure().rootCause().hasMessageContaining("tenantId"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1" })
	void event_id가_양수가_아니면_기동_실패(String eventId) {
		runner.withPropertyValues("tetra.scope.event-id=" + eventId)
				.run(context -> assertThat(context).hasFailed()
						.getFailure().rootCause().hasMessageContaining("eventId"));
	}

	@Test
	void scope가_없으면_기동_실패() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
				.withUserConfiguration(AppConfig.class)
				.withPropertyValues(Arrays.stream(VALID).filter(v -> !v.startsWith("tetra.scope.")).toArray(String[]::new))
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void Clock은_설정한_타임존을_쓴다() {
		runner.run(context -> assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Asia/Seoul")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/", "/waiting", "/events/1/waiting?from=session", "/?event={eventId}" })
	void 상대_경로_redirect_url은_허용된다(String url) {
		runner.withPropertyValues("tetra.session.redirect-url=" + url)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://www.naver.com", "https://origin.gamza-dev.shop/", "//evil.com", "/\\evil.com",
			"waiting", "/ok\\path" })
	void 상대_경로가_아닌_redirect_url이면_기동_실패(String url) {
		runner.withPropertyValues("tetra.session.redirect-url=" + url)
				.run(context -> assertThat(context).hasFailed()
						.getFailure().rootCause().hasMessageContaining("redirectUrl"));
	}

	@Test
	void 커서_증가량이_0이면_기동_실패() {
		runner.withPropertyValues("tetra.queue.cursor-step=0")
				.run(context -> assertThat(context).hasFailed()
						.getFailure().rootCause().hasMessageContaining("cursorStep"));
	}

	@Test
	void 커서_윈도우가_1초_미만이면_기동_실패() {
		runner.withPropertyValues("tetra.queue.cursor-window=500ms")
				.run(context -> assertThat(context).hasFailed()
						.getFailure().rootCause().hasMessageContaining("cursorWindow"));
	}

	@Test
	void 커서_여유는_0까지_허용하고_음수면_기동_실패() {
		runner.withPropertyValues("tetra.queue.cursor-slack=0").run(context -> assertThat(context).hasNotFailed());
		runner.withPropertyValues("tetra.queue.cursor-slack=-1")
				.run(context -> assertThat(context).hasFailed()
						.getFailure().rootCause().hasMessageContaining("cursorSlack"));
	}

	@Test
	void 세션_TTL이_0이면_기동_실패() {
		runner.withPropertyValues("tetra.session.ttl=0s")
				.run(context -> assertThat(context).hasFailed()
						.getFailure().rootCause().hasMessageContaining("ttl"));
	}

	@Test
	void 잘못된_워밍업_모드면_기동_실패() {
		runner.withPropertyValues("tetra.stock.warmup-mode=always")
				.run(context -> assertThat(context).hasFailed());
	}

}
