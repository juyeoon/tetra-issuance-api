#!/usr/bin/env bash
# infra-k8s envs/dev/versions.yaml 의 issuanceApiImageTag 를 새 이미지 태그로 바꾸는 MR 을 만들고 병합을 예약한다 (인프라 인수인계 CD 3-1).
#   0) issuance-api main HEAD 가 이 파이프라인 커밋이 아니면 건너뜀 (옛 파이프라인이 최신 MR 을 닫지 않게)
#   1) infra-k8s main 의 versions.yaml 읽기 → 태그가 같으면 성공 종료
#   2) 브랜치 bot/issuance-api-<태그> → 파일 커밋 → MR (squash, remove_source_branch) — 이미 있으면 재사용
#   3) 다른 bot/issuance-api-* MR 닫기 + 소스 브랜치 삭제
#   4) MR 파이프라인이 있으면 자동 병합 예약, 없으면 즉시 병합
# 토큰·응답 본문은 출력하지 않는다. 실패하면 HTTP 상태와 GitLab 오류 메시지만.
#
# 사용: CI 에서  bash scripts/update-infra-tag.sh
#       로컬 치환 확인  bash scripts/update-infra-tag.sh --replace-only <versions.yaml> <태그>   (결과는 표준출력)
set -euo pipefail

TAG_KEY=issuanceApiImageTag
BOT_BRANCH_PREFIX=bot/issuance-api-

# ── versions.yaml 치환 ───────────────────────────────────────────────

current_tag() {   # <파일> → 현재 태그 값 (따옴표 제외)
  sed -nE "s/^[[:space:]]*${TAG_KEY}:[[:space:]]*[\"']?([^\"'[:space:]#]*).*/\1/p" "$1"
}

replace_tag() {   # <원본> <결과> <태그> — 해당 줄의 값만 바꾸고 들여쓰기·따옴표·뒤 주석 유지
  local in=$1 out=$2 tag=$3 count changed
  count=$(grep -cE "^[[:space:]]*${TAG_KEY}:" "$in" || true)
  if [[ "$count" != 1 ]]; then
    echo "${TAG_KEY} 줄이 ${count}개 — 정확히 1개여야 함" >&2
    return 1
  fi
  sed -E "s/^([[:space:]]*${TAG_KEY}:[[:space:]]*)([\"']?)[^\"'[:space:]#]*([\"']?)/\1\2${tag}\3/" "$in" > "$out"

  # 바뀐 줄이 정확히 1줄이고 그 값이 새 태그인지 확인
  changed=$(awk 'NR==FNR { a[FNR]=$0; n=FNR; next } { if ($0 != a[FNR]) c++; m=FNR } END { if (m != n) print "x"; else print c+0 }' "$in" "$out")
  if [[ "$changed" != 1 ]]; then
    echo "치환 결과가 예상과 다름 (바뀐 줄: ${changed}) — 1줄이어야 함" >&2
    return 1
  fi
  if [[ "$(current_tag "$out")" != "$tag" ]]; then
    echo "치환 후 ${TAG_KEY} 값이 ${tag} 가 아님" >&2
    return 1
  fi
}

check_tag_format() {
  [[ "$1" =~ ^[0-9a-f]{7,40}$ ]] || { echo "태그 형식이 이상함: '$1' (커밋 SHA 16진수여야 함)" >&2; exit 1; }
}

if [[ "${1:-}" == "--replace-only" ]]; then
  [[ $# -eq 3 ]] || { echo "사용: $0 --replace-only <versions.yaml> <태그>" >&2; exit 1; }
  check_tag_format "$3"
  tmp_out=$(mktemp)
  trap 'rm -f "$tmp_out"' EXIT
  replace_tag "$2" "$tmp_out" "$3"
  cat "$tmp_out"
  exit 0
fi

# ── GitLab API ───────────────────────────────────────────────────────

: "${CI_BOT_TOKEN:?CI_BOT_TOKEN 없음 (Protected 변수 — 보호 브랜치에서만 주입됨)}"
: "${CI_API_V4_URL:?}" "${CI_PROJECT_ID:?}" "${CI_COMMIT_SHA:?}" "${CI_DEFAULT_BRANCH:?}" "${IMAGE_TAG:?}"

INFRA_PROJECT=${INFRA_PROJECT:-tetra/infra/infra-k8s}
INFRA_BRANCH=${INFRA_BRANCH:-main}
VERSIONS_FILE=${VERSIONS_FILE:-envs/dev/versions.yaml}

API=$CI_API_V4_URL
TAG=$IMAGE_TAG
check_tag_format "$TAG"
BRANCH=${BOT_BRANCH_PREFIX}${TAG}

enc() { jq -rn --arg s "$1" '$s | @uri'; }
P=$(enc "$INFRA_PROJECT")
F=$(enc "$VERSIONS_FILE")
B=$(enc "$BRANCH")

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
RESP=$WORK/resp

STATUS=
api() {   # <메서드> <경로> [JSON 파일] → STATUS, 본문은 $RESP
  local args=(-sS -o "$RESP" -w '%{http_code}' -X "$1" --connect-timeout 10 --max-time 60
              -H "PRIVATE-TOKEN: $CI_BOT_TOKEN")
  [[ -n "${3:-}" ]] && args+=(-H 'Content-Type: application/json' --data-binary "@$3")
  STATUS=$(curl "${args[@]}" "$API$2") || STATUS=000
}

err_msg() {
  jq -r '(.message // .error // "") | if type == "string" then . else tojson end' "$RESP" 2>/dev/null || true
}

die_http() {
  echo "HTTP $STATUS $1: $(err_msg)" >&2
  exit 1
}

echo "API $API · 대상 $INFRA_PROJECT@$INFRA_BRANCH:$VERSIONS_FILE · 새 태그 $TAG"

# 0) issuance-api main HEAD 확인 — CI_BOT_TOKEN 은 infra-k8s 전용이라 여기서는 잡 토큰을 쓴다
head_sha=
head_status=$(curl -sS -o "$RESP" -w '%{http_code}' --connect-timeout 10 --max-time 30 \
  -H "JOB-TOKEN: $CI_JOB_TOKEN" \
  "$API/projects/$CI_PROJECT_ID/repository/branches/$(enc "$CI_DEFAULT_BRANCH")") || head_status=000
if [[ "$head_status" == 200 ]]; then
  head_sha=$(jq -r '.commit.id // empty' "$RESP")
  echo "main HEAD 조회: API"
else
  # 잡 토큰으로 브랜치 API 가 허용되지 않는 경우 — 잡 토큰으로 clone 하는 주소에 ls-remote
  head_sha=$(git ls-remote "$CI_REPOSITORY_URL" "refs/heads/$CI_DEFAULT_BRANCH" 2>/dev/null | cut -f1) || true
  echo "main HEAD 조회: git ls-remote (API HTTP $head_status)"
fi
[[ -n "$head_sha" ]] || { echo "issuance-api $CI_DEFAULT_BRANCH HEAD 를 확인하지 못함" >&2; exit 1; }
if [[ "$head_sha" != "$CI_COMMIT_SHA" ]]; then
  echo "더 새 커밋이 있음 (${head_sha:0:8}) — 건너뜀"
  exit 0
fi

# 1) 현재 versions.yaml
api GET "/projects/$P/repository/files/$F/raw?ref=$(enc "$INFRA_BRANCH")"
[[ "$STATUS" == 200 ]] || die_http "versions.yaml 읽기"
cp "$RESP" "$WORK/base.yaml"
cur=$(current_tag "$WORK/base.yaml")
if [[ "$cur" == "$TAG" ]]; then
  echo "이미 $TAG — 할 일 없음"
  exit 0
fi
echo "현재 태그 ${cur:-(없음)} → $TAG"
replace_tag "$WORK/base.yaml" "$WORK/new.yaml" "$TAG"

# 2) 브랜치 — 이미 있으면 재사용
jq -n --arg b "$BRANCH" --arg r "$INFRA_BRANCH" '{branch: $b, ref: $r}' > "$WORK/branch.json"
api POST "/projects/$P/repository/branches" "$WORK/branch.json"
if [[ "$STATUS" == 201 ]]; then
  echo "브랜치 생성 $BRANCH"
elif [[ "$STATUS" == 400 && "$(err_msg)" == *"already exists"* ]]; then
  echo "브랜치 재사용 $BRANCH"
else
  die_http "브랜치 생성"
fi

# 커밋 — 브랜치 파일이 이미 같은 내용이면 건너뜀
api GET "/projects/$P/repository/files/$F/raw?ref=$B"
[[ "$STATUS" == 200 ]] || die_http "브랜치 versions.yaml 읽기"
if cmp -s "$RESP" "$WORK/new.yaml"; then
  echo "브랜치에 이미 커밋됨 — 커밋 건너뜀"
else
  jq -n --arg b "$BRANCH" --rawfile c "$WORK/new.yaml" --arg m "ci(dev): issuance-api $TAG" \
    '{branch: $b, content: $c, commit_message: $m}' > "$WORK/commit.json"
  api PUT "/projects/$P/repository/files/$F" "$WORK/commit.json"
  [[ "$STATUS" == 200 ]] || die_http "versions.yaml 커밋"
  echo "커밋 ci(dev): issuance-api $TAG"
fi

# MR — 열린 MR 이 있으면 재사용
api GET "/projects/$P/merge_requests?state=opened&source_branch=$B"
[[ "$STATUS" == 200 ]] || die_http "MR 조회"
iid=$(jq -r '.[0].iid // empty' "$RESP")
if [[ -n "$iid" ]]; then
  echo "MR 재사용 !$iid"
else
  jq -n --arg s "$BRANCH" --arg t "$INFRA_BRANCH" --arg title "ci(dev): issuance-api $TAG" \
    --arg d "issuance-api 이미지 태그 반영 (봇). 커밋 ${CI_PROJECT_URL:-}/-/commit/$CI_COMMIT_SHA · 파이프라인 ${CI_PIPELINE_URL:-}" \
    '{source_branch: $s, target_branch: $t, title: $title, description: $d, squash: true, remove_source_branch: true}' > "$WORK/mr.json"
  api POST "/projects/$P/merge_requests" "$WORK/mr.json"
  [[ "$STATUS" == 201 ]] || die_http "MR 생성"
  iid=$(jq -r '.iid' "$RESP")
  echo "MR 생성 !$iid"
fi

# 3) 다른 봇 MR 닫기 — 0) 검사를 통과한(= main 최신 커밋) job 만 여기에 온다
api GET "/projects/$P/merge_requests?state=opened&per_page=100"
[[ "$STATUS" == 200 ]] || die_http "열린 MR 목록"
jq -r --arg p "$BOT_BRANCH_PREFIX" --arg b "$BRANCH" \
  '.[] | select(.source_branch | startswith($p)) | select(.source_branch != $b) | "\(.iid) \(.source_branch)"' \
  "$RESP" > "$WORK/stale"
echo '{"state_event":"close"}' > "$WORK/close.json"
while read -r old_iid old_branch; do
  [[ -z "$old_iid" ]] && continue
  api PUT "/projects/$P/merge_requests/$old_iid" "$WORK/close.json"
  [[ "$STATUS" == 200 ]] || die_http "옛 MR !$old_iid 닫기"
  api DELETE "/projects/$P/repository/branches/$(enc "$old_branch")"
  [[ "$STATUS" == 204 || "$STATUS" == 404 ]] || die_http "옛 브랜치 $old_branch 삭제"
  echo "옛 봇 MR 닫음 !$old_iid ($old_branch)"
done < "$WORK/stale"

# 4) 병합 — 병합 가능 여부 계산이 끝나고 MR 파이프라인이 붙을 때까지 기다린다 (최대 약 60초)
pipeline=
for i in $(seq 1 30); do
  api GET "/projects/$P/merge_requests/$iid"
  [[ "$STATUS" == 200 ]] || die_http "MR !$iid 조회"
  state=$(jq -r '.state' "$RESP")
  if [[ "$state" == merged ]]; then
    echo "MR !$iid 이미 병합됨"
    exit 0
  fi
  if [[ "$(jq -r '.merge_when_pipeline_succeeds // false' "$RESP")" == true ]]; then
    echo "MR !$iid 자동 병합 이미 예약됨"
    exit 0
  fi
  merge_status=$(jq -r '.detailed_merge_status // .merge_status // ""' "$RESP")
  pipeline=$(jq -r '.head_pipeline.id // empty' "$RESP")
  case "$merge_status" in
    checking|unchecked|preparing|approvals_syncing) ;;
    # 파이프라인이 늦게 붙을 수 있으니 없으면 최소 약 20초는 기다린다
    *) [[ -n "$pipeline" || "$i" -ge 10 ]] && break ;;
  esac
  sleep 2
done

if [[ -n "$pipeline" ]]; then
  api PUT "/projects/$P/merge_requests/$iid/merge?merge_when_pipeline_succeeds=true&squash=true"
  if [[ "$STATUS" == 200 ]]; then
    echo "병합 경로: 파이프라인 #$pipeline 성공 시 자동 병합 예약 (merge_when_pipeline_succeeds) — MR !$iid"
  else
    echo "merge_when_pipeline_succeeds 거부 (HTTP $STATUS: $(err_msg)) — auto_merge 로 재시도"
    api PUT "/projects/$P/merge_requests/$iid/merge?auto_merge=true&squash=true"
    [[ "$STATUS" == 200 ]] || die_http "MR !$iid 자동 병합 예약"
    echo "병합 경로: 파이프라인 #$pipeline 성공 시 자동 병합 예약 (auto_merge) — MR !$iid"
  fi
else
  api PUT "/projects/$P/merge_requests/$iid/merge?squash=true"
  [[ "$STATUS" == 200 ]] || die_http "MR !$iid 병합"
  echo "병합 경로: MR 파이프라인 없음 — 즉시 병합 (squash) — MR !$iid"
fi
