package io.tetra.issuance.job;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import io.tetra.issuance.config.TetraProperties;

/**
 * Job 모드(stock-load · aggregate)에서 기동이 끝나면 해당 Job 을 한 번 실행한다. server 모드에서는 아무것도 하지 않는다.
 * 실패하면 예외가 그대로 올라가 프로세스가 종료 코드 1로 끝난다 (IssuanceServiceApplication.main).
 */
@Component
public class JobRunner implements ApplicationRunner {

	private final TetraProperties.Mode mode;

	private final StockLoadJob stockLoadJob;

	private final AggregateJob aggregateJob;

	public JobRunner(TetraProperties properties, StockLoadJob stockLoadJob, AggregateJob aggregateJob) {
		this.mode = properties.mode();
		this.stockLoadJob = stockLoadJob;
		this.aggregateJob = aggregateJob;
	}

	@Override
	public void run(ApplicationArguments args) {
		switch (mode) {
			case SERVER -> {
			}
			case STOCK_LOAD -> stockLoadJob.run();
			case AGGREGATE -> aggregateJob.run();
		}
	}

}
