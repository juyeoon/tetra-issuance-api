-- 서빙 커서 조회 + 락 기반 피기백 증가 (플랜 4-3). 별도 스케줄러 없이 폴링 요청이 증가를 겸한다.
-- 커서는 번호표 끝 + 여유를 넘지 않는다 — 한산할 때 커서가 번호표보다 크게 앞서 가 있으면
-- 그 뒤 몰린 사용자가 대기 없이 한꺼번에 입장한다 (클라이언트는 gap = 번호 − 커서 ≤ 0 이면 바로 입장).
-- KEYS[1] advance-lock:{eventId}    윈도우 락 (윈도우당 1건만 SET NX 성공)
-- KEYS[2] cursor:serving:{eventId}  서빙 커서 (이벤트당 전역 값 1개)
-- KEYS[3] ticket:seq:{eventId}      번호표 끝 (ticket-issue.lua 가 채번)
-- ARGV[1] 윈도우 밀리초 (tetra.queue.cursor-window, 기본 3000)
-- ARGV[2] 윈도우당 증가량 (tetra.queue.cursor-step, 기본 300)
-- ARGV[3] 증가 여부 "1"/"0" — 이벤트 시작 전(D10)·기동 웜업 요청이면 "0" (락도 잡지 않고 읽기만)
-- ARGV[4] 번호표 끝 위 여유 (tetra.queue.cursor-slack, 기본 300)
-- 반환: 현재 커서 (아직 없으면 0)

local cursor = tonumber(redis.call('GET', KEYS[2]) or '0')

if ARGV[3] == '1' and redis.call('SET', KEYS[1], '1', 'NX', 'PX', ARGV[1]) then
  local cap = tonumber(redis.call('GET', KEYS[3]) or '0') + tonumber(ARGV[4])
  local nxt = math.min(cursor + tonumber(ARGV[2]), cap)
  -- 상한이 지금 커서보다 낮아도 줄이지 않는다 — 클라이언트는 받은 커서의 최댓값만 쓴다
  if nxt > cursor then
    redis.call('SET', KEYS[2], nxt)
    cursor = nxt
  end
end

return cursor
