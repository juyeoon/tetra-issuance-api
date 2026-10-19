package io.tetra.issuance.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트 DB의 시드 이벤트(1)에 테스트 공개키를 인증키로 등록한다 (MVP 물리 ERD — event_auth_key).
 * 기존 키를 지우고 key_version 1, ACTIVE, 만료 = 호스팅 종료로 넣는다. 등록 뒤 EventMetaCache.reload() 를 불러야 앱이 읽는다.
 */
public final class TestEventKeys {

	private TestEventKeys() {
	}

	public static void registerActiveKey(JdbcTemplate jdbcTemplate, String publicKeyPem) {
		jdbcTemplate.update("DELETE FROM event_auth_key WHERE event_id = 1");
		jdbcTemplate.update("INSERT INTO event_auth_key (event_id, tenant_id, public_key, key_version, status, issued_at, expires_at) "
				+ "SELECT event_id, tenant_id, ?, 1, 'ACTIVE', '2026-09-21 10:00:00', hosting_end_at FROM event WHERE event_id = 1",
				publicKeyPem);
	}

}
