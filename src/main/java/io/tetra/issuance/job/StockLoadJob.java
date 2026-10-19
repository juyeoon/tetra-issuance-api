package io.tetra.issuance.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.service.CouponCatalog;

/**
 * 재고 적재 Job (TETRA_MODE=stock-load, MVP 계획 A3). T-90분 준비 때 event 차트가 API보다 먼저 실행한다(sync wave 2).
 * MySQL event.stock_count → Redis stock:{eventId}. 방식은 values stock.warmup-mode(기본 if-absent — 다시 돌려도 차감분 유지).
 */
@Component
public class StockLoadJob {

	private static final Logger log = LoggerFactory.getLogger(StockLoadJob.class);

	private final CouponCatalog catalog;

	private final TetraProperties.WarmupMode warmupMode;

	public StockLoadJob(CouponCatalog catalog, TetraProperties properties) {
		this.catalog = catalog;
		this.warmupMode = properties.stock().warmupMode();
	}

	public void run() {
		catalog.warmup(warmupMode);
		log.info("Stock load done ({})", warmupMode);
	}

}
