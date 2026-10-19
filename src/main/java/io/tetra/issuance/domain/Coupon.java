package io.tetra.issuance.domain;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 테넌트 스키마 t_{tenant_id}.coupon (MVP 물리 ERD) 읽기 전용. 이벤트당 최대 10종, coupon_id 가 노출 순서.
 * 재고는 쿠폰별이 아니라 이벤트 단위(event.stock_count) — claim 1건이 이벤트의 쿠폰을 모두 받는다.
 */
@Entity
@Immutable
@Table(name = "coupon")
public class Coupon {

	@Id
	@Column(name = "coupon_id")
	@JdbcTypeCode(SqlTypes.INTEGER) // INT UNSIGNED
	private Long couponId;

	@Column(name = "event_id")
	@JdbcTypeCode(SqlTypes.INTEGER)
	private Long eventId;

	@Column(name = "tenant_id", length = 12)
	@JdbcTypeCode(SqlTypes.CHAR)
	private String tenantId;

	@Column(name = "name", length = 100)
	private String name;

	@Column(name = "description", length = 500)
	private String description;

	protected Coupon() {
	}

	public Long getCouponId() {
		return couponId;
	}

	public Long getEventId() {
		return eventId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

}
