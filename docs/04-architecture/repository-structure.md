---
title: "저장소 구조"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-30"
review_trigger: "module·배포 구조 변경 시"
---

# 저장소 구조

기존 Next.js source를 이동하지 않고 repository root에 `backend/`를 추가한다.

## Phase 1C-8f3 현재 구조

```text
Rhaomi/
├── .github/
│   └── workflows/
│       └── validate.yml
├── src/
│   ├── app/
│   │   ├── page.tsx               # 공개 Static Export 홈
│   │   └── admin/                 # Static Export auth shell·DOM test
│   ├── features/admin-auth/       # relative API client·shape/error test
│   └── test/                      # Vitest DOM setup
├── backend/
│   ├── gradle/wrapper/            # Gradle 9.7.1 Wrapper
│   ├── src/main/java/kr/co/rhaomi/backend/
│   │   ├── admin/                 # admin_users domain
│   │   ├── auth/                  # login/me/logout/csrf API
│   │   ├── build/                 # stateless read-only snapshot·public-scope media API
│   │   ├── breed/                 # 견종 관리 domain/API
│   │   ├── content/               # 상태·audit·공통 오류 계약
│   │   ├── config/                # security와 bootstrap
│   │   ├── gallery/               # 갤러리 CRUD·관계·게시 검증 domain/API
│   │   ├── notice/                # 공지 관리 domain/API와 게시·기간 검증
│   │   ├── media/                 # private upload·HEIC 정규화·storage domain/API
│   │   ├── publication/           # revision/outbox producer와 claim·lease·generation state service
│   │   ├── service/               # 미용 서비스 관리 domain/API
│   │   └── shop/                  # 매장정보 singleton·media relation domain/API와 검증
│   ├── src/main/resources/
│   │   ├── db/migration/          # Flyway V1~V9, V8 producer·V9 claim/generation state
│   │   └── application.yml
│   ├── src/test/                  # PostgreSQL auth·콘텐츠·build snapshot/media 계약
│   └── Dockerfile.dev             # exact Java 25 + libheif runtime
├── infra/nginx/dev.conf           # local same-origin proxy와 /api/build 명시적 404
├── scripts/
│   ├── generate-synthetic-media-fixtures.mjs
│   ├── validate-backend-auth.mjs
│   ├── validate-backend-media.mjs
│   ├── validate-gateway.mjs
│   ├── validate-backend-compose.sh
│   ├── validate-frontend-credential-isolation.mjs
│   └── validate-export.mjs
├── tests/                         # frontend·runtime contract
├── docs/
├── compose.dev.yaml
├── next.config.ts
├── package.json
├── package-lock.json
├── .env.example
└── README.md
```

- `compose.dev.yaml`은 `dev-rhaomi` project와 개발 전용 network/volume만 사용한다. frontend는 repository root나 `.env*`를 mount하지 않고 runtime source/config allowlist만 받으며, network-disabled `contract-check`도 actual local env file 없이 tracked 검증 source만 받는다.
- `backend/build`, `.gradle`, `.next`, `out`, `node_modules`는 생성 파일이므로 Git에 포함하지 않는다.
- Directus runtime, schema snapshot, permission artifact와 provisioning script는 현재 구조에 없다.
- 관리자 collection controller는 견종·서비스·공지·갤러리의 `GET`, `POST`, `PUT`을 제공한다. 매장정보 singleton은 `GET`, `PUT`, private media는 list/detail/content `GET`, upload `POST`, status `PUT`만 제공하며 모든 domain에서 `PATCH`·`DELETE`를 제공하지 않는다.
- `/admin/`은 인증 상태·로그인·로그아웃과 매장정보·미디어·견종·서비스·갤러리·공지 관리 component를 same-page Static Export shell에서 제공한다.
- `src/features/admin-auth`는 relative `/api/admin/**`, same-origin credential, GET no-store, response shape와 고정 오류 mapping을 한 경계에서 처리한다.
- `infra/nginx/dev.conf`는 local 개발 전용이며 production Nginx·TLS 설정이 아니다.
- `backend/.../publication`은 domain transaction 밖에서 호출할 수 없는 `MANDATORY` producer recorder와 deterministic JDBC state service를 둔다. state service는 due claim, source/boundary 최소 stale 판정, generation·lease·retry·terminal/coalesce primitive만 제공하며 HTTP controller, scheduler, background executor나 범용 queue framework를 제공하지 않는다.
- `backend/.../build`는 별도 stateless principal과 GET allowlist, active generation gate, read-only `REPEATABLE READ` snapshot, exact DTO와 current public relation media 조회만 제공한다. admin session을 재사용하거나 publication/content state를 변경하지 않는다.

## 전체 제품 목표 구조 — planned

```text
Rhaomi/
├── src/
│   ├── app/
│   │   ├── page.tsx
│   │   ├── admin/                 # 현재 auth shell, 후속 콘텐츠 UI
│   │   └── notice/[slug]/
│   ├── components/
│   ├── generated/
│   │   ├── content.json
│   │   └── media-manifest.json
│   ├── lib/
│   │   ├── content/
│   │   ├── media/
│   │   ├── seo/
│   │   └── validation/
│   └── types/
├── backend/
│   └── src/main/
│       ├── java/                  # 현재 admin/build API와 후속 publisher orchestration
│       └── resources/db/migration/
├── public/
│   ├── brand/
│   └── generated/
├── infra/
│   ├── compose/
│   ├── nginx/
│   ├── publisher/
│   └── backup/
├── scripts/
├── tests/
└── docs/
```

planned 경로는 관련 Issue가 구현할 때만 추가한다.

## 디렉터리 계약

### `backend/src/main/java`

- controller는 entity를 직접 response로 반환하지 않는다.
- 관리자 request DTO는 명시적 field allowlist를 사용한다.
- 인증·인가·domain·persistence 경계를 package로 구분하되 불필요한 layer를 만들지 않는다.
- publication recorder는 최종 domain persistence와 같은 transaction에서 한 mutation당 한 번만 호출한다.
- publication state service는 전달받은 `now`·lease를 microsecond로 정규화하고 owner·generation·active lease를 확인한다. 실제 poll/debounce/build orchestration이나 full public eligibility를 이 package에 복제하지 않는다.
- build package는 64자 lowercase hex service token을 timing-safe 비교하고 session을 만들지 않는다. snapshot/media response는 exact allowlist만 사용하며 raw entity·storage path·hash·audit·claim 내부 상태를 노출하지 않는다.
- password hash, session id와 credential을 log에 남기지 않는다.

### `backend/src/main/resources/db/migration`

- PostgreSQL schema source of truth
- 적용된 migration 수정 금지, 변경은 새 version migration
- JPA `ddl-auto`로 schema 생성 금지
- destructive migration은 별도 data/backup/rollback 승인 필요

### `src/generated` — 후속

- 후속 publisher가 build API response와 승인된 code image를 결합해 만드는 고정 입력
- 수동 수정 금지
- schema version 포함
- 일부 실패에서 과거 데이터와 혼합 금지

### `public/generated` — 후속

- 공개용 image 파생본
- 원본 upload 금지
- 내용 hash 기반 파일명
- metadata 제거 후 export에 포함

### `infra`

- `infra/nginx/dev.conf`는 local same-origin gateway만 정의
- 운영 Docker Compose, production Nginx, single publisher와 backup job은 후속
- local 개발 Compose와 운영 credential·volume을 공유하지 않음
- future production Compose는 repository 밖 Mac host `/private/var/lib/rhaomi`의 public/media/state bind source와 production project-scoped PostgreSQL named volume을 명시적으로 구분
- Linux container `/srv/rhaomi/public` target을 Mac host `/srv/rhaomi` source로 해석하거나 `synthetic.conf`·custom File Sharing 전제로 사용하지 않음
- production data, named volume과 `/Volumes/<provisioned-volume>/...` backup repository를 Git worktree·`infra/` 아래에 생성하지 않음

### `scripts`

- local과 CI가 공유하는 검증 진입점
- GitHub Actions에 긴 shell을 중복 작성하지 않음
- 실제 credential 출력 금지
- frontend filesystem token 검증은 raw token 대신 SHA-256 digest를 비교하고 match된 literal을 출력하지 않음
