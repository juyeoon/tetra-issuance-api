package io.tetra.issuance.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.common.WarmupContext;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.filter.VerifiedEntryToken;
import io.tetra.issuance.metrics.IssuanceMetrics;
import io.tetra.issuance.redis.EntryStore;
import io.tetra.issuance.redis.SessionStore;

/**
 * 세션 발급 (플랜 4-1 처리 순서 2~3). 서명·만료·테넌트 검증은 JwtAuthFilter 가 끝냈다.
 * MySQL 은 읽지 않는다 (Redis + 이벤트 메타 캐시만).
 * <p>
 * 입장 OPEN(MVP 계획 B4·C12): 이벤트 시작 − entry-open-before(60분) − entry-open-grace(60초)보다 이르면 세션을 만들지 않는다.
 * jti·진입 카운터도 남기지 않으며, 컨트롤러가 쿠키 없이 대기방으로 302 한다 — 페이지 이동이라 에러를 주면 JSON 화면이 보이기 때문.
 * 대기 화면은 event.json 의 entryOpenAt 이 지나면 테넌트 페이지로 가서 새 JWT 로 다시 들어온다.
 */
@Service
public class SessionService {

	private final EntryStore entryStore;
	private final SessionStore sessionStore;

	private final EventMetaCache events;
	private final TetraProperties properties;
	private final Clock clock;

	private final IssuanceMetrics metrics;

	public SessionService(EntryStore entryStore, SessionStore sessionStore, EventMetaCache events,
			TetraProperties properties, Clock clock, IssuanceMetrics metrics) {
		this.metrics = metrics;
		this.entryStore = entryStore;
		this.sessionStore = sessionStore;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	/** @return 세션 ID (기존 세션 재사용이면 그 ID). 입장 OPEN 전이면 empty — 세션을 만들지 않는다 */
	public Optional<String> enter(VerifiedEntryToken token) {
		EventMeta event = events.find(token.eventId()).orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
		// 기동 웜업(WarmupContext)은 입장 OPEN 전에 돌므로 검사만 하고 넘는다 — 키는 warmup: 쪽에 쓰인다
		if (!isEntryOpen(event, LocalDateTime.now(clock)) && !WarmupContext.active()) {
			metrics.sessionBeforeOpen();
			return Optional.empty();
		}

		// 2. jti 1회 사용: 토큰이 유효한 동안(시계 오차 허용분 포함)만 기록해 두면 된다
		Duration ttl = Duration.between(clock.instant(), token.expiresAt()).plus(properties.jwt().clockSkew());
		if (!entryStore.markTokenUsed(token.jti(), atLeastOneSecond(ttl))) {
			metrics.sessionRejected(ErrorCode.JWT_REUSED);
			throw new BusinessException(ErrorCode.JWT_REUSED);
		}

		// 3. 같은 이벤트·사용자의 세션이 살아 있으면 재사용 (진입 카운터 증가 없음)
		var existing = sessionStore.findSessionIdByUser(token.eventId(), token.userId())
				.filter(sid -> sessionStore.find(sid).isPresent());
		if (existing.isPresent()) {
			metrics.sessionReused();
			return existing;
		}
		String sid = sessionStore.create(token.tenantId(), token.eventId(), token.userId(), clock.instant(),
				properties.session().ttl());
		entryStore.incrementEntryCount(token.eventId(), clock.instant().atZone(clock.getZone()));
		metrics.sessionCreated();
		return Optional.of(sid);
	}

	/** 지금 ≥ 시작 − entry-open-before − entry-open-grace (KST) */
	boolean isEntryOpen(EventMeta event, LocalDateTime now) {
		TetraProperties.Session config = properties.session();
		return !now.isBefore(event.startAt().minus(config.entryOpenBefore()).minus(config.entryOpenGrace()));
	}

	private static Duration atLeastOneSecond(Duration d) {
		return d.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : d;
	}

}
