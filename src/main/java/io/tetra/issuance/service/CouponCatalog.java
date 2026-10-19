package io.tetra.issuance.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.config.TetraProperties.WarmupMode;
import io.tetra.issuance.domain.Coupon;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.repository.CouponRepository;

/**
 * 쿠폰 정보(이름·설명)를 기동 시 메모리에 두고, 같은 때 Redis 재고를 워밍업한다 (플랜 4-4).
 * 재고는 이벤트 단위 카운터 1개(event.stock_count → Redis stock:{eventId}, MVP 물리 ERD).
 * <p>
 * 기동 = Issuance Service 프로세스 시작 (배포 환경은 Pod 가 뜰 때마다). 웹 서버가 요청을 받기 전에 실행된다.
 * 기본 if-absent 라 여러 Pod 가 각자 실행해도 처음 1번만 채워지고, 재시작·스케일아웃 때 차감분이 유지된다.
 * force 는 로컬 초기화 전용. Redis 데이터 유실 시 위험은 Next Plan N3.
 */
@Component
public class CouponCatalog implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(CouponCatalog.class);

	private final CouponRepository couponRepository;

	private final EventMetaCache events;
	private final CouponStockStore stockStore;
	private final WarmupMode warmupMode;

	private final TetraProperties.Mode mode;
	private volatile Map<Long, List<CouponInfo>> byEvent = Map.of();

	public CouponCatalog(CouponRepository couponRepository, EventMetaCache events, CouponStockStore stockStore,
			TetraProperties properties) {
		this.couponRepository = couponRepository;
		this.events = events;
		this.stockStore = stockStore;
		this.warmupMode = properties.stock().warmupMode();
		this.mode = properties.mode();
	}

	/** 화면에 보여줄 쿠폰 정보 */
	public record CouponInfo(long couponId, String name, String description) {
	}

	@Override
	public void afterPropertiesSet() {
		load();
		// 기동 시 워밍업은 웹 서버일 때만 (안전망). 재고 적재 Job 은 StockLoadJob 이 직접 부르고,
		// 종료 집계 Job 에서는 재고를 다시 채우면 안 된다
		if (mode == TetraProperties.Mode.SERVER) {
			warmup(warmupMode);
		}
	}

	public void load() {
		Map<Long, List<CouponInfo>> loaded = new LinkedHashMap<>();
		// 이 Pod가 맡은 이벤트(tetra.scope)의 쿠폰만
		for (Coupon c : events.all().stream()
				.flatMap(e -> couponRepository.findAllByEventIdOrderByCouponIdAsc(e.eventId()).stream()).toList()) {
			loaded.computeIfAbsent(c.getEventId(), k -> new ArrayList<>())
					.add(new CouponInfo(c.getCouponId(), c.getName(), c.getDescription()));
		}
		loaded.replaceAll((k, v) -> List.copyOf(v));
		byEvent = Map.copyOf(loaded);
	}

	/** MySQL event.stock_count → Redis stock:{eventId} (승인된 이벤트마다 1개) */
	public void warmup(WarmupMode mode) {
		int written = 0;
		int total = 0;
		for (EventMeta event : events.all()) {
			total++;
			if (stockStore.putStock(event.eventId(), event.stockCount(), mode == WarmupMode.IF_ABSENT)) {
				written++;
			}
		}
		log.info("Coupon stock warmup ({}): {} of {} keys written, {} kept", mode, written, total, total - written);
	}

	/** 이벤트의 쿠폰 목록 (couponId 순, 없으면 빈 목록) */
	public List<CouponInfo> coupons(long eventId) {
		return byEvent.getOrDefault(eventId, List.of());
	}

}
