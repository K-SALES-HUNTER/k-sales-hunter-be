# k-sales-hunter-be

K-Sales Hunter 백엔드. FE 에 `/api/v1/*` REST 를 제공하고, LLM 이 필요한 작업은 AI 서비스의
`/internal/ai/*` 로 프록시한다.

```
[React FE]  --REST /api/v1/*-->  [Spring BE]  --REST /internal/ai/*-->  [Python AI (FastAPI+LangGraph)]
                                      └──── PostgreSQL(pgvector) public 스키마 ────┘
```

지금은 골격만 있다. 각 담당자가 자기 라우트를 이 위에 얹는다.

## Quick Start

JDK 21 필요. Gradle 은 wrapper 를 쓰므로 따로 설치하지 않는다.

```bash
# 1. DB 를 띄운다. AI 레포의 docker-compose 를 쓴다 (Spring 과 AI 가 같은 인스턴스를 공유한다)
cd ../k-sales-hunter-ai && docker compose up -d && cd -

# 2. 실행
./gradlew bootRun

# 3. 확인
curl http://localhost:8080/api/v1/actuator/health
```

`./gradlew build` 는 컨텍스트 로딩 테스트를 포함하므로 DB 가 떠 있어야 통과한다.

## 설정

전부 `src/main/resources/application.yml` 이고, 환경변수로 덮어쓴다.

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sales_hunter` | DB 주소 |
| `DB_USER` / `DB_PASSWORD` | `postgres` / `postgres` | DB 계정 |
| `SERVER_PORT` | `8080` | 포트 |
| `AI_BASE_URL` | `http://localhost:8000` | AI 서비스 주소 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | FE 개발 서버 |

비밀키는 커밋하지 않는다. 로컬 값은 환경변수나 `application-local.yml`(gitignore) 로.

## 지켜야 할 것

1. **`public` 스키마만 쓴다.** `ai` 스키마는 AI 서비스 소유다. 반대 방향은 DB 권한으로 이미 막혀 있다
   (AI 레포 `scripts/init_db.sql`).
2. **스키마는 Flyway 가 소유한다.** 테이블 변경은 `src/main/resources/db/migration/V{n}__{설명}.sql` 로.
   `ddl-auto` 는 `validate` 이므로 엔티티만 고치면 기동이 깨진다.
3. **FE 타입이 계약이다.** `k-sales-hunter-fe/src/types/*.ts` 모양대로 응답한다. 필드 이름을 바꾸지 않는다.
   스펙 변경은 `k-sales-hunter-ai/docs/API_SPEC_FE기준.md` PR 을 먼저 올린다.
4. **AI 호출은 `aiRestClient` 빈을 주입받는다.** `RestClient` 를 라우트에서 새로 만들지 않는다
   (`config/AiClientConfig.java`).
5. **에러 응답은 Spring 기본 ProblemDetail(RFC 9457)** 을 쓴다. 별도 에러 래퍼를 만들지 않는다.

## 라우트 담당

| 사람 | 담당 |
|---|---|
| 차은호 | 레포 골격, auth, products·uploads, analysis 프록시와 보고서 매핑 |
| 권수현 | dashboard, sales-info·quote·categories, sales-ops |
| 이동건 | settings·stores, 코파일럿 프록시 |
| 강근우 | detail-page, images, image-jobs |

상세는 `k-sales-hunter-ai/docs/ONBOARDING.md` §5.

## 브랜치

`feat/<파트>-<기능>` → `develop` PR → 1인 리뷰 → 머지. `main` 은 데모 태그용.
커밋 컨벤션은 3개 레포 공통(Feat/Fix/Design/Docs/Refactor/Chore).
