package io.tetra.issuance.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.tetra.issuance.domain.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	/** 이벤트의 쿠폰 (노출 순서 = coupon_id) */
	List<Coupon> findAllByEventIdOrderByCouponIdAsc(Long eventId);

}
