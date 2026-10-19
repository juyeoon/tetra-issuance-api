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
 * 테넌트 스키마 t_{tenant_id}.event_auth_key (MVP 물리 ERD) 읽기 전용.
 * 이벤트 승인(WF1) 때 생성되고 종료(WF4) 때 EXPIRED 가 된다. key_version 은 JWT 헤더의 kid.
 * JWT 검증은 status = ACTIVE 이고 지금 < expires_at 인 키로만 한다 (WF4가 실패해도 expires_at 으로 무효가 됨).
 */
@Entity
@Immutable
@Table(name = "event_auth_key")
public class EventAuthKey {

	public static final String ACTIVE = "ACTIVE";

	@Id
	@Column(name = "key_id")
	@JdbcTypeCode(SqlTypes.INTEGER) // INT UNSIGNED
	private Long keyId;

	@Column(name = "event_id")
	@JdbcTypeCode(SqlTypes.INTEGER)
	private Long eventId;

	@Column(name = "tenant_id", length = 12)
	@JdbcTypeCode(SqlTypes.CHAR)
	private String tenantId;

	@Column(name = "public_key", length = 2048)
	private String publicKey;

	@Column(name = "key_version")
	@JdbcTypeCode(SqlTypes.INTEGER)
	private Long keyVersion;

	@Column(name = "status", length = 20)
	private String status;

	@Column(name = "expires_at")
	private LocalDateTime expiresAt;

	protected EventAuthKey() {
	}

	public Long getKeyId() {
		return keyId;
	}

	public Long getEventId() {
		return eventId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getPublicKey() {
		return publicKey;
	}

	public Long getKeyVersion() {
		return keyVersion;
	}

	public String getStatus() {
		return status;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

}
