#!/usr/bin/env bash
# 로컬 테스트용 JWT(RS256) 키페어를 만들고, 공개키로 db/init/09_local_test_key.sql 을 생성한다.
# - 개인키: 테스트 JWT 서명용 (local/keys/, git 제외)
# - 공개키: t_poctenant001.event_auth_key 에 key_version 1(ACTIVE)로 INSERT 되어 세션 API의 JWT 검증에 사용
# 사용: 저장소 루트에서  bash local/scripts/gen-test-keys.sh   (이미 키가 있으면 덮어쓰지 않음, --force 로 재생성)
set -euo pipefail

LOCAL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KEY_DIR="$LOCAL_DIR/keys"
PRIVATE_KEY="$KEY_DIR/test-jwt-private.pem"
PUBLIC_KEY="$KEY_DIR/test-jwt-public.pem"
SQL_FILE="$LOCAL_DIR/db/init/09_local_test_key.sql"

if [[ -f "$PRIVATE_KEY" && "${1:-}" != "--force" ]]; then
  echo "이미 키가 있습니다: $PRIVATE_KEY (재생성하려면 --force)"
else
  mkdir -p "$KEY_DIR"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_KEY"
  openssl pkey -in "$PRIVATE_KEY" -pubout -out "$PUBLIC_KEY"
  echo "키페어 생성: $KEY_DIR"
fi

PUBLIC_PEM="$(cat "$PUBLIC_KEY")"
cat > "$SQL_FILE" <<SQL
-- 로컬 전용: scripts/gen-test-keys.sh 가 생성한 파일 (직접 수정하지 말 것)
-- 테스트 공개키를 이벤트 1의 인증키(key_version 1 = JWT kid, 만료 = 호스팅 종료)로 넣는다.
USE t_poctenant001;
INSERT INTO event_auth_key (event_id, tenant_id, public_key, key_version, status, issued_at, expires_at)
SELECT event_id, tenant_id, '${PUBLIC_PEM}', 1, 'ACTIVE', '2026-09-21 10:00:00', hosting_end_at FROM event WHERE event_id = 1;
SQL
echo "SQL 생성: $SQL_FILE"
