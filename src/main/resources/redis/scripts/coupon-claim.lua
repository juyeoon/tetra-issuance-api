-- 쿠폰 일괄 발급 (플랜 4-5, MVP 물리 ERD). 원자적으로 처리해 초과 발급·중복 발급을 막는다.
-- 재고는 이벤트 단위 카운터 1개다. 1을 차감하면 그 이벤트의 쿠폰을 모두 받고, 0이면 품절.
-- KEYS[1]  claim:done:{eventId}:{userId}  claim 처리 결과 (성공·품절 모두). 값 = '1' 성공 / '0' 품절
-- KEYS[2]  stock:{eventId}                이벤트 재고 (워밍업·재고 적재 때 event.stock_count 로 채움)
-- 반환: { 처음 처리 0 | 이전 결과 1, 성공 1 | 품절 0 }
-- 이미 처리된 사용자가 다시 claim 하면 재고는 건드리지 않고 저장된 처음 결과를 그대로 돌려준다
-- (응답을 못 받은 사용자가 다시 요청해도 같은 결과 — 인프라 문서 B3)

local done = redis.call('GET', KEYS[1])
if done then
  return { 1, tonumber(done) }
end

local success = 0
if tonumber(redis.call('GET', KEYS[2]) or '0') > 0 then
  redis.call('DECR', KEYS[2])
  success = 1
end
redis.call('SET', KEYS[1], success)
return { 0, success }
