package io.tetra.issuance.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 테넌트 스키마 t_{tenant_id}.event (MVP 물리 ERD) 중 발급 API가 읽는 컬럼만 매핑한 읽기 전용 엔티티.
 * 이벤트 신청·승인·상태 변경은 Portal·Console·워크플로우 영역이라 이 서비스는 쓰지 않는다.
 * 공개키는 event_auth_key, 재고(stock_count)는 이벤트 단위 카운터 1개다.
 */
@Entity
@Immutable
@Table(name = "event")
public class Event {

	public static final String APPROVED = "APPROVED";

	@Id
	@Column(name = "event_id")
	@JdbcTypeCode(SqlTypes.INTEGER) // INT UNSIGNED
	private Long eventId;

	@Column(name = "tenant_id", length = 12)
	@JdbcTypeCode(SqlTypes.CHAR) // CHAR(12) ascii_bin
	private String tenantId;

	@Column(name = "name", length = 100)
	private String name;

	@Column(name = "start_at")
	private LocalDateTime startAt;

	@Column(name = "end_at")
	private LocalDateTime endAt;

	/** 신청 성공 이후에는 NOT NULL(chk_event_banner) */
	@Column(name = "banner_image_path", length = 1024)
	private String bannerImagePath;

	@Column(name = "endpoint_url", length = 2048)
	private String endpointUrl;

	/** 일괄 발급 재고(신청 시점 값, 고정). 실시간 차감은 Redis 에서만 한다 */
	@Column(name = "stock_count")
	@JdbcTypeCode(SqlTypes.INTEGER) // INT UNSIGNED
	private Long stockCount;

	@Column(name = "application_status", length = 20)
	private String applicationStatus;

	protected Event() {
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

	public LocalDateTime getStartAt() {
		return startAt;
	}

	public LocalDateTime getEndAt() {
		return endAt;
	}

	public String getBannerImagePath() {
		return bannerImagePath;
	}

	public String getEndpointUrl() {
		return endpointUrl;
	}

	public Long getStockCount() {
		return stockCount;
	}

	public String getApplicationStatus() {
		return applicationStatus;
	}

	public boolean isApproved() {
		return APPROVED.equals(applicationStatus);
	}

}
