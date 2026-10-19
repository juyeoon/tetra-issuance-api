package io.tetra.issuance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.tetra.issuance.config.TetraProperties;

@SpringBootApplication
public class IssuanceServiceApplication {

	/**
	 * TETRA_MODE(기본 server)에 따라 웹 서버로 뜨거나, Job 모드(stock-load · aggregate)면 웹 서버 없이 한 번 실행하고 끝낸다.
	 * Job 의 종료 코드: 성공 0, 실패 1 — K8s Job · Step Functions 가 이것으로 성공 여부를 판단한다.
	 */
	public static void main(String[] args) {
		if (TetraProperties.Mode.fromEnvironment() == TetraProperties.Mode.SERVER) {
			SpringApplication.run(IssuanceServiceApplication.class, args);
			return;
		}
		int exitCode;
		try {
			exitCode = SpringApplication.exit(new SpringApplicationBuilder(IssuanceServiceApplication.class)
					.web(WebApplicationType.NONE)
					.run(args));
		}
		catch (RuntimeException e) {
			// 기동 실패·Job 실패 모두 여기로 온다 (Spring 이 원인을 이미 로그로 남김)
			exitCode = 1;
		}
		System.exit(exitCode);
	}

}
