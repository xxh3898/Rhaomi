---
title: "저장소 구조"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "module·배포 구조 변경 시"
---

# 저장소 구조

기존 Next.js source를 이동하지 않고 repository root에 `backend/`를 추가한다.

## Phase 1C-4 현재 구조

```text
Rhaomi/
├── .github/
│   └── workflows/
│       └── validate.yml
├── src/
│   └── app/                       # Next.js Static Export
├── backend/
│   ├── gradle/wrapper/            # Gradle 9.7.1 Wrapper
│   ├── src/main/java/kr/co/rhaomi/backend/
│   │   ├── admin/                 # admin_users domain
│   │   ├── auth/                  # login/me/logout/csrf API
│   │   ├── breed/                 # 견종 관리 domain/API
│   │   ├── content/               # 상태·audit·공통 오류 계약
│   │   ├── config/                # security와 bootstrap
│   │   ├── notice/                # 공지 관리 domain/API와 게시·기간 검증
│   │   ├── media/                 # private upload·HEIC 정규화·storage domain/API
│   │   ├── service/               # 미용 서비스 관리 domain/API
│   │   └── shop/                  # 매장정보 singleton domain/API와 입력 검증
│   ├── src/main/resources/
│   │   ├── db/migration/          # Flyway V1~V5, V5 media_assets
│   │   └── application.yml
│   ├── src/test/                  # PostgreSQL auth·콘텐츠·media API/DB/fixture contract
│   └── Dockerfile.dev             # exact Java 25 + libheif runtime
├── scripts/
│   ├── generate-synthetic-media-fixtures.mjs
│   ├── validate-backend-auth.mjs
│   ├── validate-backend-media.mjs
│   ├── validate-backend-compose.sh
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

- `compose.dev.yaml`은 `dev-rhaomi` project와 개발 전용 network/volume만 사용한다.
- `backend/build`, `.gradle`, `.next`, `out`, `node_modules`는 생성 파일이므로 Git에 포함하지 않는다.
- Directus runtime, schema snapshot, permission artifact와 provisioning script는 현재 구조에 없다.
- 관리자 collection controller는 견종·서비스·공지의 `GET`, `POST`, `PUT`을 제공한다. 매장정보 singleton은 `GET`, `PUT`, private media는 list/detail/content `GET`, upload `POST`, status `PUT`만 제공하며 모든 domain에서 `PATCH`·`DELETE`를 제공하지 않는다.

## 전체 제품 목표 구조 — planned

```text
Rhaomi/
├── src/
│   ├── app/
│   │   ├── page.tsx
│   │   ├── admin/                 # 후속 static 관리자 UI
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
│       ├── java/                  # 후속 콘텐츠·build API
│       └── resources/db/migration/
├── public/
│   ├── brand/
│   └── generated/
├── infra/
│   ├── compose/
│   ├── nginx/
│   ├── deploy-hook/
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
- password hash, session id와 credential을 log에 남기지 않는다.

### `backend/src/main/resources/db/migration`

- PostgreSQL schema source of truth
- 적용된 migration 수정 금지, 변경은 새 version migration
- JPA `ddl-auto`로 schema 생성 금지
- destructive migration은 별도 data/backup/rollback 승인 필요

### `src/generated` — 후속

- build API 동기화가 만든 고정 입력
- 수동 수정 금지
- schema version 포함
- 일부 실패에서 과거 데이터와 혼합 금지

### `public/generated` — 후속

- 공개용 image 파생본
- 원본 upload 금지
- 내용 hash 기반 파일명
- metadata 제거 후 export에 포함

### `infra` — 후속

- 운영 Docker Compose, Nginx, deploy hook과 backup job
- local 개발 Compose와 운영 credential·volume을 공유하지 않음

### `scripts`

- local과 CI가 공유하는 검증 진입점
- GitHub Actions에 긴 shell을 중복 작성하지 않음
- 실제 credential 출력 금지
