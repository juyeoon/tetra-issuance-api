package io.tetra.issuance.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.tetra.issuance.domain.EventAuthKey;

public interface EventAuthKeyRepository extends JpaRepository<EventAuthKey, Long> {

	/** 이벤트의 상태별 키 (버전 순) */
	List<EventAuthKey> findAllByEventIdAndStatusOrderByKeyVersionAsc(Long eventId, String status);

}
