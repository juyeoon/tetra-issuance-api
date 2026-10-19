package io.tetra.issuance.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 테넌트 스키마 t_{tenant_id}.issuance_history (MVP 물리 ERD). claim 결과가 확정될 때 1회 INSERT 만 한다.
 * result 는 SUCCESS / FAILED_SOLDOUT. 일시 3개(세션 발급·번호표·결과 확정)는 DATETIME(3), KST.
 */
@Entity
@Table(name = "issuance_history")
public class IssuanceHistory {

	public static final String RESULT_SUCCESS = "SUCCESS";
	public static final String RESULT_FAILED_SOLDOUT = "FAILED_SOLDOUT";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "issuance_id")
	private Long issuanceId;

	@Column(name = "tenant_id", length = 12)
	@JdbcTypeCode(SqlTypes.CHAR)
	private String tenantId;

	@Column(name = "event_id")
	@JdbcTypeCode(SqlTypes.INTEGER)
	private Long eventId;

	@Column(name = "user_id", length = 128)
	private String userId;

	@Column(name = "ticket_number")
	@JdbcTypeCode(SqlTypes.INTEGER)
	private Long ticketNumber;

	/** 세션 발급 시각 (JWT 검증 후 세션 생성), KST */
	@Column(name = "session_issued_at")
	private LocalDateTime sessionIssuedAt;

	/** 번호표 발급 시각 (D9), KST */
	@Column(name = "queue_entered_at")
	private LocalDateTime queueEnteredAt;

	/** 재고 차감 결과가 정해진 시각, KST */
	@Column(name = "issued_at")
	private LocalDateTime issuedAt;

	@Column(name = "result", length = 20)
	private String result;

	protected IssuanceHistory() {
	}

	public IssuanceHistory(String tenantId, long eventId, String userId, Long ticketNumber,
			LocalDateTime sessionIssuedAt, LocalDateTime queueEnteredAt, LocalDateTime issuedAt, String result) {
		this.tenantId = tenantId;
		this.eventId = eventId;
		this.userId = userId;
		this.ticketNumber = ticketNumber;
		this.sessionIssuedAt = sessionIssuedAt;
		this.queueEnteredAt = queueEnteredAt;
		this.issuedAt = issuedAt;
		this.result = result;
	}

	public Long getIssuanceId() {
		return issuanceId;
	}

	public String getResult() {
		return result;
	}

}
