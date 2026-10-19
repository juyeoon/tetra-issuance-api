# issuance-api

Tetra 발급 API (MVP). PoC `issuance-service`(커밋 `6685715`)에서 시작했다.
계획·결정: `MVP/API/PLAN&DATA/MVP_issuance_plan.md`

- Java 21 · Spring Boot 4.1 · Gradle (Kotlin DSL)
- 설정: `application.yml`은 구조만, 값은 `values/values-${TETRA_ENV:local}.yml`
- 지금은 로컬 git으로만 관리한다. GitLab(`app/issuance-api`)·CI는 준비되면 옮긴다.

## 로컬 실행 (저장소 루트에서)

```bash
bash local/scripts/gen-test-keys.sh                      # 최초 1회: 테스트 키 + DB 공개키 SQL
docker compose -f local/docker-compose.yml up -d --wait  # MySQL · Valkey
./gradlew bootRun                                        # 앱 (또는 --profile app 으로 컨테이너)
bash local/scripts/issue-test-jwt.sh                     # 입장 URL 발급
```

테스트: `./gradlew test` (Testcontainers — Docker 필요)

## 폴더

| 위치 | 내용 |
|---|---|
| `src/` | 앱 코드 |
| `values/` | 환경별 설정값 (`local` · `docker` · `dev`) |
| `local/` | 로컬 개발 전용 — `docker-compose.yml`, `db/init`(스키마·시드), `scripts/`, `keys/`(git 제외) |

PoC 로컬 환경(`PoC/docker-compose.yml`)과 포트(3306·6379·8080)가 같으므로 둘을 동시에 띄우지 않는다. 컨테이너 이름은 `issuance-*`로 달라 겹치지 않는다.
