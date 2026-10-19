package io.tetra.issuance.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.service.CouponCatalog.CouponInfo;

/**
 * 쿠폰 목록 + 잔여 수량 (플랜 4-4). 재고는 이벤트 단위라 모든 쿠폰의 remaining 이 같은 값이다(이벤트 남은 수량).
 * 잔여 수량은 반드시 Redis 에서 읽는다 — MySQL event.stock_count 는 초기값일 뿐 차감되지 않는다.
 * 프런트는 remaining 을 화면에 쓰지 않는다(확정).
 */
@Service
public class CouponService {

	private final CouponCatalog catalog;
	private final CouponStockStore stockStore;

	public CouponService(CouponCatalog catalog, CouponStockStore stockStore) {
		this.catalog = catalog;
		this.stockStore = stockStore;
	}

	public record CouponView(long couponId, String name, String description, long remaining) {
	}

	public List<CouponView> list(long eventId) {
		List<CouponInfo> coupons = catalog.coupons(eventId);
		if (coupons.isEmpty()) {
			return List.of();
		}
		long remaining = stockStore.remaining(eventId);
		List<CouponView> views = new ArrayList<>(coupons.size());
		for (CouponInfo c : coupons) {
			views.add(new CouponView(c.couponId(), c.name(), c.description(), remaining));
		}
		return views;
	}

}
