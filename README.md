---
title: "Rhaomi 프로젝트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
review_trigger: "프로젝트 구조 또는 핵심 범위 변경 시"
---

# Rhaomi

라오미펫 애견미용실의 실제 영업용 모바일 중심 랜딩페이지 프로젝트다.

- 저장소: `xxh3898/Rhaomi`
- 공개 사이트: 도메인 확정 전
- 운영 관리자: 은총쌤
- 개발·인프라 담당: 조치호
- 배포 대상: Mac mini
- 공개 프론트엔드: Next.js App Router + TypeScript + Static Export
- 관리 API: Java 25 LTS + Spring Boot 4.1.1
- 데이터베이스: PostgreSQL
- 정적 웹 서버·리버스 프록시: Nginx

## 현재 구현 범위

Phase 0 기준 문서와 Issue #1의 Static Export 기반, Issue #3의 Spring Boot 관리자 인증 기반을 유지한다. Phase 1C-1~6의 콘텐츠·매장정보·private media·갤러리 API와 relation, Phase 1C-7의 `/admin/` Static Export 인증 셸·local same-origin Nginx gateway, Phase 1C-8a~8e의 여섯 관리자 UI에 이어 Phase 1C-8f1에서 transactional `contentRevision`과 publishing outbox producer를, Phase 1C-8f2에서 pending/due claim·lease recovery·`publishGeneration`·attempt/result 상태 머신 기반을 추가했다. Phase 1C-8f3은 별도 stateless service credential을 쓰는 internal read-only build snapshot·public-scope media API를 제공한다. Phase 1C-8f4는 API transport와 분리된 strict snapshot transformer, responsive public image 파생본과 원자적 staging 산출물을 제공한다. 실제 polling publisher, build API HTTP client, 30초 debounce orchestration, Next 랜딩·SEO 렌더링과 release switch는 후속 Issue 범위다.

```text
.
├── .github/
│   ├── CODEOWNERS
│   ├── workflows/validate.yml
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/
├── src/
│   ├── app/                 # 공개 홈과 /admin Static Export route
│   ├── build-transformer/   # strict BuildSnapshotV1·responsive derivative·staging library
│   └── features/            # admin auth/transport, dashboard, media·shop·breed·service·gallery UI
├── backend/                 # Spring Boot admin/build API, publication producer/state와 PostgreSQL contract test
├── infra/nginx/dev.conf     # local same-origin gateway와 /api/build 명시적 차단
├── scripts/                 # 정적 산출물·gateway·HEIC·Compose smoke 검증
├── tests/                   # frontend·runtime contract test
├── docs/                    # 제품·아키텍처·운영 기준 문서
├── compose.dev.yaml         # 개발 전용 gateway/frontend/backend/PostgreSQL
├── next.config.ts
├── package.json
├── package-lock.json
├── AGENTS.md
└── README.md
```

## 작업 원칙

1. `docs/01-product/open-items.md`에서 출시 전 미확정 항목을 확인한다.
2. `docs/09-decisions/`의 accepted ADR을 구현 기준으로 사용한다.
3. 실제 구현으로 계약이 바뀌면 코드와 문서를 같은 PR에서 동기화한다.
4. feature PR은 `dev`를 대상으로 하고 검증된 release만 `main`으로 승격한다.

## 로컬 개발

host에 Node.js나 Java를 설치하지 않고 `compose.dev.yaml`의 고정된 runtime image를 사용한다. 먼저 example을 복사하고 placeholder를 local 개발 전용 값으로 교체한다. 이 파일은 host Compose 보간과 backend 주입에만 쓰며 frontend container에는 mount하지 않는다.

```bash
cp .env.example .env.dev.local
```

### 프론트엔드

```bash
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend run --rm frontend npm ci
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile validation run --rm --no-deps contract-check npm run lint
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile validation run --rm --no-deps contract-check npm run typecheck
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile validation run --rm --no-deps contract-check npm test
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile validation run --rm --no-deps contract-check sh -c 'npm run build && npm run validate:export'
```

frontend의 dependency install과 dev runtime은 `src`, package manifest와 Next·TypeScript config만 read-only allowlist로 받는다. repository root, `.env*`, `backend/`, local secret/config와 private runtime data는 frontend filesystem에 없다. repository-wide contract test에 필요한 backend source는 실제 env file을 mount하지 않고 network도 끈 `contract-check`에만 제공한다.

브라우저 진입점은 gateway의 `http://127.0.0.1:3000` 하나다. frontend dev server는 host port를 열지 않는다. gateway는 일반 `/api/**`를 backend로 전달하지만 `/api/build/**`는 먼저 404로 차단한다.

```bash
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend up -d --wait frontend gateway
```

`/`, `/admin/`, `/api/admin/auth/**`는 모두 위 same-origin으로 확인한다. backend의 `127.0.0.1:8080`은 local 진단·build API 통합 검증용 loopback 경계다.

### Spring Boot와 PostgreSQL

PostgreSQL은 host port를 공개하지 않으며 backend만 `127.0.0.1:8080`에 bind한다. 기본 설정에서는 관리자 계정을 만들지 않는다.

```bash
docker compose --env-file .env.dev.local -f compose.dev.yaml config
docker compose --env-file .env.dev.local -f compose.dev.yaml up -d --wait postgres backend
docker compose --env-file .env.dev.local -f compose.dev.yaml ps
docker compose --env-file .env.dev.local -f compose.dev.yaml down
```

마지막 `down`은 container와 network만 종료하고 개발 named volume은 보존한다. 운영 Compose, 운영 data, 운영 credential은 이 개발 구성을 사용하지 않는다.

backend test는 실제 PostgreSQL을 사용한다.

```bash
docker compose --env-file .env.dev.local -f compose.dev.yaml up -d --wait postgres
RHAOMI_TEST_DATABASE_ALLOWED=true \
docker compose --env-file .env.dev.local -f compose.dev.yaml run --rm --no-deps backend ./gradlew test --no-daemon
docker compose --env-file .env.dev.local -f compose.dev.yaml down
```

현재 collection 관리 API는 `/api/admin/breeds`, `/api/admin/services`, `/api/admin/notices`, `/api/admin/gallery-items`에 `GET`, `POST`, `PUT`만 제공한다. 생성은 항상 `draft`이며 수정 요청은 전체 mutable representation을 보낸다. 공지는 게시·만료 시각을 microsecond로 정규화한 뒤 게시 본문·유효 기간을 application과 PostgreSQL에서 이중 검증한다. 갤러리는 slug 없이 scalar relation id만 반환하고 게시 필수값·관계 존재성·관계 대상 상태를 mutation 전에 검증한다.

Flyway V8은 `content_revision_state` singleton과 typed `publishing_outbox` producer를 만든다. 지원 콘텐츠 mutation 성공 1회마다 같은 PostgreSQL transaction에서 `contentRevision`을 정확히 한 번 증가시키고, 공개 영향 변경은 `CONTENT_CHANGED`, 새로 설정·변경된 공지 게시·만료 경계와 게시 상태 갤러리의 게시 경계는 같은 revision의 durable scheduled event로 기록한다. validation·DB·outbox 실패는 콘텐츠·revision·event를 함께 rollback하며 media final file도 기존 transaction cleanup으로 제거한다. 이 producer는 새 HTTP endpoint나 credential을 추가하지 않는다.

Flyway V9은 transactional `publish_generation_state` singleton과 outbox의 `PENDING | PROCESSING | RETRY_WAIT | SUCCEEDED | NOOP | FAILED | COALESCED` 상태를 추가한다. internal Java service가 `FOR UPDATE SKIP LOCKED`로 due row를 하나만 claim하고 generation 할당·첫 attempt를 같은 transaction으로 기록한다. 만료 lease와 1분·5분·15분 transient retry는 같은 generation으로 최대 네 번째 attempt까지 복구하며, scheduled stale event는 current Notice/Gallery의 published 상태와 expected boundary만 확인한 뒤 generation 없이 `NOOP` 처리한다. 이 상태 기반 자체는 HTTP endpoint, scheduler, polling loop나 credential을 추가하지 않는다.

internal build API는 정확히 `GET /api/build/snapshot`과 `GET /api/build/media/{id}/content`만 허용한다. 64자 lowercase hex Bearer token은 관리자 session·CSRF와 분리되고 request를 session에 저장하지 않는다. local Compose에서도 token은 backend environment에만 전달하며 frontend·gateway에는 environment key나 credential file을 제공하지 않는다. snapshot은 active `PROCESSING` generation과 live lease를 확인한 뒤 하나의 read-only `REPEATABLE READ` transaction에서 server-owned microsecond `generatedAt`, current `contentRevision`, published/time/relation/media/file 조건과 exact DTO allowlist를 검증한다. media content는 현재 Shop 또는 공개 가능한 Gallery relation에 속한 active canonical master만 size·SHA 검증 후 `private, no-store`로 반환한다. build API는 revision, outbox, generation, lease, attempt나 콘텐츠를 변경하지 않으며 dev/public gateway는 이 namespace를 backend로 전달하지 않는다.

build snapshot transformer는 `BuildSnapshotV1`의 exact key·schema·semantic·관계·게시 시각·media manifest를 다시 검증하고 `MediaContentProvider`로 distinct canonical media를 한 번씩만 읽는다. JPEG·PNG signature·decode·30 MiB·12,000px·60MP·단일 image 조건을 재검증한 뒤 orientation·sRGB·metadata 제거와 no-upscale responsive AVIF·WebP·JPEG 파생본을 만든다. 파생 byte SHA-256 파일명, 결정적 manifest 순서와 `src/generated/{content.json,media-manifest.json}`·`public/generated/media`를 임시 sibling에서 완성한 뒤 새 staging target으로 rename한다. 실패 시 partial temp를 제거하고 기존 성공 target은 교체하지 않는다. CLI filesystem adapter와 library port만 제공하며 build API HTTP client, publisher polling·release/current 전환, Next 렌더링은 포함하지 않는다.

매장정보는 상태나 공개 id가 없는 단일 현재값이다. `/api/admin/shop-settings`의 `GET`과 전체 `PUT`만 제공하며 최초 PUT은 `201`, 이후 PUT은 `200`이다. PostgreSQL UNIQUE/CHECK가 row를 하나로 제한하고 Hero·프로필 image/alt pair와 세 media FK를 방어한다. API는 핵심 NAP·영업시간·전화번호·HTTPS 외부 링크, nullable Hero·프로필·OG scalar media id, Hero·프로필 대체텍스트와 server-owned audit를 검증한다. non-null media는 존재하고 `active`여야 하며 관계가 나중에 archived돼도 자동 제거하지 않는다. 모든 state-changing 요청에는 관리자 session과 CSRF token이 필요하며 `PATCH`와 영구 `DELETE` endpoint는 제공하지 않는다. 실제 운영값과 실사진은 seed하지 않는다.

미디어는 `/api/admin/media`의 목록·단건·private content 조회, multipart upload와 status `PUT`을 제공한다. 20 MiB source, 30 MiB stored, 12,000px, 60MP 제한을 실제 byte signature와 decoder로 검증하며 client MIME·확장자·파일명을 신뢰하지 않는다. server-owned UUID storage key와 SHA-256 무결성 metadata를 사용하고 `active | archived` row와 master file을 유지한다. original filename·storage key·filesystem path·SHA-256은 관리자 또는 build DTO에 노출하지 않는다. anonymous/public media endpoint와 physical delete는 없으며 build service만 current public relation scope의 검증된 canonical master를 읽을 수 있다.

gateway routing, credential filesystem 격리, health, local/test bootstrap, CSRF login/me/logout, 20 MiB request 경계와 재기동 후 persistent volume을 한 번에 검증하려면 다음처럼 명시적 test credential을 process 환경으로 전달한다. script는 smoke 전용 build token을 memory에서 생성해 backend에만 전달하고 raw 값을 출력하지 않는다. 실제 운영 email/password/token을 사용하지 않는다.

```bash
RHAOMI_BOOTSTRAP_ADMIN_ENABLED=true \
RHAOMI_BOOTSTRAP_ADMIN_EMAIL=admin.smoke@example.com \
RHAOMI_BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-local-test-password' \
sh scripts/validate-backend-compose.sh .env.dev.local
```

## 현재 핵심 결론

- 고객 페이지는 정적 HTML로 배포하며 SSR을 사용하지 않는다.
- 공개 사이트는 런타임에 Spring Boot나 PostgreSQL에 의존하지 않는다.
- 관리자 인증은 HttpOnly session cookie와 CSRF 보호를 사용하는 Spring Security 기반이다.
- `/admin/`은 noindex인 Static Export client shell이며 backend session이 최종 보안 경계다. login POST와 identity 검증이 끝나면 form credential과 pre-login CSRF를 먼저 제거하고, 별도 fresh CSRF 준비가 성공한 뒤에만 authenticated mutation-ready 상태가 된다. 준비 실패 재시도는 `/me`로 기존 session을 확인한 뒤 fresh CSRF만 다시 획득한다.
- local browser 요청은 Nginx gateway의 same-origin `/api/**`를 사용한다. frontend는 host port를 열지 않고 gateway는 PostgreSQL network에 참여하지 않는다.
- 견종·서비스 기준정보는 관리자 session·CSRF가 적용된 API로 생성·조회·수정·보관할 수 있다.
- 공지는 같은 인증 경계에서 생성·조회·수정·보관하며 게시 필수값과 게시·만료 기간을 검증한다.
- 매장정보 singleton은 같은 인증 경계에서 조회·전체 갱신하며 DB와 application이 한 행·필수값·영업시간·HTTPS URL·Hero/프로필 image-alt pair·active media relation을 검증한다.
- private media master는 같은 인증 경계에서 업로드·조회·archive하며 HEIC/HEIF는 backend에서 canonical JPEG로 정규화한다.
- `/admin/`은 미디어 목록·authenticated Blob preview·단일 upload·active/archive filter·archive/restore, 매장정보 조회·미초기화·full PUT, 견종·서비스·갤러리 목록·생성·전체 수정·게시·보관·복구 UI를 제공한다.
- Hero·미용사·OG는 active private media를 한 개씩 선택·해제할 수 있고, archived/missing 기존 relation은 숨기지 않고 clear/replace가 필요한 상태로 표시한다.
- 갤러리는 실제 견종·대표 서비스·private media를 FK로 참조하고 같은 인증 경계와 `/admin/` UI에서 생성·조회·전체 수정·게시·보관·복구한다. draft·archived 편집에서는 존재하는 보관 media도 선택할 수 있지만 published 전환은 게시된 견종·서비스와 active media를 요구한다.
- 공지는 `/admin/`에서 항상 draft로 생성하고 immutable slug, source-only Markdown, 고정 여부, 미래 게시·만료 시각과 `draft | published | archived`를 full PUT으로 관리한다. 목록은 backend 배열 순서를 보존하고 변경하지 않은 microsecond Instant를 그대로 유지한다.
- 관계 대상의 상태 변경은 갤러리나 매장정보에 cascade하지 않으며 build snapshot이 published/relation/file 조건과 선택된 매장 이미지를 다시 검증한다.
- 지원 콘텐츠 mutation은 transactional row counter로 `contentRevision`을 한 번만 전진시키고, 공개 영향 변경과 Notice·Gallery 시간 경계는 V8 typed outbox에 같은 transaction으로 기록한다.
- V9 internal state service는 pending/due claim, transactional `publishGeneration`, active lease·owner guard, same-generation recovery/retry, typed terminal result와 lower→higher coalesce primitive를 제공한다.
- 별도 stateless build credential과 active generation 기반 read-only snapshot·public-scope media content API를 제공하며 관리자 session이나 browser 경로와 공유하지 않는다. local frontend는 token environment뿐 아니라 `.env.dev.local`과 backend filesystem도 mount하지 않는다.
- strict build snapshot transformer와 responsive public image derivative·staging 산출물은 구현됐지만 실제 publisher polling loop·build API HTTP client·30초 debounce/coalesce orchestration·Next static build·release switch는 아직 구현되지 않았다.
- 공개 콘텐츠 변경은 정적 사이트 재빌드·검증·원자적 교체를 유발한다.
- 고객용 예약 시스템, 결제, 회원가입, 문의 폼은 만들지 않는다.
- 전화, 인스타그램, 네이버톡톡 등 외부 문의 채널로 연결한다.
- 검색 노출은 기술 선택만으로 보장되지 않는다. 정적 HTML, 로컬 SEO, NAP 일치, 콘텐츠 품질, 검색엔진 등록과 운영이 함께 필요하다.

## 주요 문서

- [문서 인덱스](docs/README.md)
- [제품 개요](docs/01-product/product-brief.md)
- [기능 범위](docs/01-product/scope.md)
- [시스템 구조](docs/04-architecture/system-context.md)
- [도메인 데이터 모델](docs/04-architecture/cms-data-model.md)
- [정적 퍼블리싱 파이프라인](docs/04-architecture/static-publishing-pipeline.md)
- [SEO 전략](docs/05-seo/seo-strategy.md)
- [배포 및 롤백](docs/07-operations/deployment.md)
- [출시 체크리스트](docs/08-quality/release-checklist.md)
