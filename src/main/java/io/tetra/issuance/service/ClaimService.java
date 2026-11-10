package io.tetra.issuance.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.common.WarmupContext;
import io.tetra.issuance.domain.IssuanceHistory;
import io.tetra.issuance.metrics.IssuanceMetrics;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.redis.IssuanceSession;
import io.tetra.issuance.repository.IssuanceHistoryRepository;
import io.tetra.issuance.service.CouponCatalog.CouponInfo;

/**
 * 쿠폰 발급 (플랜 4-5). 입장 자격(번호 순서) 재검증은 PoC 범위에서 생략 — 재고 Lua 가 초과 발급을 막는다.
 * <ol>
 * <li>이벤트 종료(end_at) 후면 409 EVENT_ENDED — 재고·claim:done·이력 모두 그대로 (B1)</li>
 * <li>번호표 없는 세션이면 409 TICKET_REQUIRED (queue_entered_at 이 없어 이력을 남길 수 없음, M0-3)</li>
 * <li>Lua: 이벤트 재고 카운터를 1 차감하면 이벤트의 쿠폰을 모두 발급(일괄 발급, MVP 물리 ERD), 0이면 품절. 결과를 claim:done 에 저장. 이미 처리된 사용자면 차감 없이
 * 저장된 처음 결과를 그대로 돌려준다 — 응답을 못 받고 다시 요청해도 같은 결과 (인프라 문서 B3)</li>
 * <li>issuance_history 1행 INSERT (성공·품절 모두, served_at NULL). 다시 요청한 경우에도 시도해, 처음 INSERT 가
 * 실패했으면 이때 채워진다 (UNIQUE 라 중복 행은 생기지 않음)</li>
 * </ol>
 * Redis 차감 후 INSERT 가 실패해도 발급 결과는 그대로 응답하고 에러 로그만 남긴다 (PoC 방침, ADR-0002 트레이드오프).
 */
@Service
public class ClaimService {

	private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

	private final EventMetaCache events;
	private final CouponCatalog catalog;
	private final CouponStockStore stockStore;
	private final IssuanceHistoryRepository historyRepository;
	private final Clock clock;

	private final IssuanceMetrics metrics;

	public ClaimService(EventMetaCache events, CouponCatalog catalog, CouponStockStore stockStore,
			IssuanceHistoryRepository historyRepository, Clock clock, IssuanceMetrics metrics) {
		this.metrics = metrics;
		this.events = events;
		this.catalog = catalog;
		this.stockStore = stockStore;
		this.historyRepository = historyRepository;
		this.clock = clock;
	}

	public enum Result {
		SUCCESS, SOLD_OUT
	}

	public record IssuedCoupon(long couponId, String name, String description) {
	}

	public record ClaimResult(Result result, List<IssuedCoupon> coupons) {
	}

	public ClaimResult claim(IssuanceSession session) {
		EventMeta event = events.find(session.eventId())
				.orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
		boolean warmup = WarmupContext.active();
		if (event.hasEnded(LocalDateTime.now(clock)) && !warmup) {
			throw new BusinessException(ErrorCode.EVENT_ENDED);
		}
		if (!session.hasTicket()) {
			throw new BusinessException(ErrorCode.TICKET_REQUIRED);
		}
		List<CouponInfo> coupons = catalog.coupons(session.eventId());
		CouponStockStore.ClaimOutcome outcome = stockStore.claim(session.eventId(), session.userId());
		Instant decidedAt = clock.instant();
		metrics.claim(outcome.success(), outcome.replay());

		Result result = outcome.success() ? Result.SUCCESS : Result.SOLD_OUT;
		if (!warmup) { // 기동 웜업은 발급 이력(실제 데이터)을 쓰지 않는다
			recordHistory(session, result, decidedAt);
		}

		List<IssuedCoupon> issued = outcome.success()
				? coupons.stream().map(c -> new IssuedCoupon(c.couponId(), c.name(), c.description())).toList()
				: List.of();
		return new ClaimResult(result, issued);
	}

	/** issuedAt = 이번 요청에서 결과를 받은 시각 (다시 요청한 경우 처음 INSERT가 실패했을 때만 이 값으로 채워짐) */
	private void recordHistory(IssuanceSession session, Result result, Instant issuedAt) {
		var history = new IssuanceHistory(session.tenantId(), session.eventId(), session.userId(),
				session.ticketNumber(), toLocal(session.sessionIssuedAt()), toLocal(session.queueEnteredAt()),
				toLocal(issuedAt),
				result == Result.SUCCESS ? IssuanceHistory.RESULT_SUCCESS : IssuanceHistory.RESULT_FAILED_SOLDOUT);
		try {
			historyRepository.save(history);
		}
		catch (DataIntegrityViolationException e) {
			// (event_id, user_id) UNIQUE — 이미 기록됨. 평소엔 claim:done 이 먼저 막으므로 재시도·키 유실 때만 발생
			log.info("Issuance history already recorded: event={} user={}", session.eventId(), session.userId());
		}
		// 커넥션을 못 얻으면 트랜잭션 시작에서 CannotCreateTransactionException(TransactionException 계열) — DataAccessException 아님
		catch (DataAccessException | TransactionException e) {
			log.error("Issuance history INSERT failed (Redis already decided): event={} user={} result={}",
					session.eventId(), session.userId(), result, e);
		}
	}

	private LocalDateTime toLocal(Instant instant) {
		return LocalDateTime.ofInstant(instant, clock.getZone());
	}

}
