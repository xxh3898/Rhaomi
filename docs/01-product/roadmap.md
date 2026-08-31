---
title: "로드맵"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-31"
review_trigger: "릴리스 범위 변경 시"
---

# 로드맵

## Phase 0 — 기준 문서

- 제품·범위·아키텍처 결정
- ADR 작성
- GitHub 템플릿
- 출시 전 미확정 항목 분리

## Phase 1 — 실행 기반과 관리 backend

### Phase 1A — 공개 frontend 부트스트랩 완료

- Next.js App Router + TypeScript + Static Export
- 개발 전용 Docker Compose와 PostgreSQL
- local 환경변수 예시와 persistent volume
- lint, typecheck, contract test, static build/export gate

### Phase 1B — Spring Boot 관리자 인증 기반

- Directus 실행 경로 제거
- Java 25 LTS + Spring Boot + PostgreSQL
- Flyway V1 `admin_users`
- Spring Security 서버 세션과 CSRF
- login, me, logout, 최소 health
- local/test bootstrap과 실제 PostgreSQL contract test
- Frontend, Backend, Compose Smoke Hosted CI

Phase 1B는 콘텐츠 CRUD나 관리자 화면의 완료를 의미하지 않는다.

### Phase 1C-1 — 견종·서비스 관리 API

- Flyway V2 `breeds`, `services`
- `draft | published | archived` 상태와 복구 가능한 archive 정책
- 관리자 session·CSRF 기반 create/read/full update API
- immutable slug, DTO field allowlist, actor/audit 추적
- 실제 PostgreSQL API·DB 계약 테스트

Phase 1C-1은 갤러리·이미지·공지·매장정보, 관리자 화면이나 공개 build API의 완료를 의미하지 않는다.

### Phase 1C-2 — 공지 관리 API

- Flyway V3 `notices`
- 관리자 session·CSRF 기반 create/read/full update API
- immutable slug와 request field allowlist
- 게시 본문·게시 시각과 게시·만료 기간의 application/PostgreSQL 이중 검증
- pinned·게시 시각·수정 시각·id 기반 deterministic 목록
- actor/audit 불변성과 실제 PostgreSQL API·DB 계약 테스트

Phase 1C-2는 공개 공지 UI·정적 route, build API, Markdown 렌더링·sanitize, scheduler나 관리자 화면의 완료를 의미하지 않는다.

### Phase 1C-3 — 매장정보 singleton 관리 API

- Flyway V4 `shop_settings`와 DB-level one-row guard
- 관리자 session·CSRF 기반 `GET`·idempotent full `PUT`
- 핵심 NAP·영업시간·휴무 요일·주차·소개·예약 안내·외부 링크 field allowlist
- `HH:mm`/`TIME(0)` 영업시간, 전화번호, HTTPS URL validation
- server-owned actor/audit와 실제 PostgreSQL API·DB 계약 테스트

Phase 1C-3은 실제 운영값 입력, Hero·프로필·OG 이미지 relation, 갤러리, 관리자 화면, 공개 build API나 정적 배포의 완료를 의미하지 않는다.

### Phase 1C-4 — private media upload 기반

- Flyway V5 `media_assets`와 backend 전용 private filesystem master
- 실제 byte signature·decoder·용량·dimension·pixel 검증
- JPEG/PNG 검증 원본 보존, HEIC/HEIF orientation·sRGB·metadata-free JPEG 정규화
- 관리자 session·CSRF 기반 upload/list/detail/content/archive·restore API
- DB/filesystem rollback cleanup과 restart persistence
- Linux amd64 Hosted CI와 Mac mini/Linux arm64 실제 HEIC fixture 검증

Phase 1C-4는 갤러리 relation, Hero·프로필·OG FK, public/build media API, responsive 파생본, 관리자 UI, 실제 iPhone Safari 검증이나 운영 storage 배포의 완료를 의미하지 않는다.

### Phase 1C-5 — 갤러리 관리자 CRUD와 media relation

- Flyway V6 `gallery_items`와 breed·service·cover/before/after media FK
- 관리자 session·CSRF 기반 `GET`·항상 draft인 `POST`·full `PUT`
- published 필수값과 breed/service `published`, media `active` 관계 상태 검증
- archive·restore, actor/audit 불변성, microsecond performed/published timestamp
- actual PostgreSQL migration·DB·HTTP 관계 계약 테스트

Phase 1C-5는 public/build gallery API, responsive 파생본, 공개 갤러리 UI, 관리자 UI, 실제 사진 seed나 운영 migration의 완료를 의미하지 않는다. 관계 대상의 후속 상태 변경은 갤러리에 cascade하지 않고 후속 public snapshot이 eligibility를 다시 검증한다.

### Phase 1C-6 — 매장정보 Hero·프로필·OG media relation

- Flyway V7 `shop_settings` nullable Hero·프로필·OG media FK
- Hero·프로필 image/alt pair의 application·PostgreSQL 검증
- non-null relation의 `media_assets` 존재·`active` 상태 검증
- scalar UUID response, actor/audit 불변성과 실제 PostgreSQL migration·DB·HTTP 계약 테스트
- 관계 대상 후속 archive의 non-cascade와 후속 public build 재검증 계약

Phase 1C-6은 실제 이미지·운영값 입력, crop·responsive derivative, public/build API, Hero·프로필·SEO 렌더링, 관리자 UI나 운영 migration의 완료를 의미하지 않는다.

### Phase 1C-7 — 관리자 웹 인증 셸과 local same-origin gateway

- `/admin/` Static Export route와 `noindex, nofollow, noarchive`
- session 확인·로그인·장애 재시도·로그아웃과 disabled 관리 영역 dashboard
- relative same-origin admin API client와 login 후 fresh CSRF 획득
- credential·CSRF·session 정보의 browser 비영속
- exact Nginx image/digest local gateway와 frontend/backend/PostgreSQL network 분리
- gateway를 통한 auth·20 MiB request·HEIC·restart persistence Compose smoke

Phase 1C-7은 실제 콘텐츠 CRUD 화면, 운영 Nginx/TLS·2FA, 공개 build API나 실제 iPhone Safari 검증의 완료를 의미하지 않는다.

### Phase 1C-8a — 관리자 미디어 UI

- 기존 session·in-memory CSRF를 재사용하는 공통 JSON·multipart·binary admin transport
- `/admin/` dashboard에서 미디어만 enabled인 same-page navigation
- private media 목록·active/archived filter와 authenticated lazy Blob preview
- 단일 파일 upload, 20 MiB client 안내와 backend-authoritative format validation
- 복구 가능한 archive/restore와 401 session expiry·403 mutation non-retry
- 320px·keyboard·focus·44px·aria·reduced-motion component/contract 검증

Phase 1C-8a는 다른 콘텐츠 CRUD UI, media picker, public/build media API, 실제 iPhone Safari HEIC upload나 운영 배포의 완료를 의미하지 않는다.

### Phase 1C-8b — 관리자 매장정보 편집과 미디어 선택 UI

- `/admin/` dashboard에서 매장정보·미디어가 enabled인 same-page navigation
- `shop_settings` loading·미초기화·조회와 mutable field 26개의 full PUT 생성·수정 form
- backend canonical response 적용, 401 session expiry·403 mutation non-retry와 frontend-owned 오류 문구
- Hero·미용사·OG가 공유하는 active-only single private media picker
- archived/missing 기존 relation 가시화, clear/replace와 Hero·미용사 image-alt pair 보조
- private Blob lazy preview 재사용과 320px·keyboard·focus·44px·aria·reduced-motion component/contract 검증

Phase 1C-8b는 실제 운영값·사진 seed, 공개 Hero·소개·OG 렌더링, build API/publisher, 나머지 콘텐츠 CRUD UI, 실제 iPhone Safari·VoiceOver나 운영 배포의 완료를 의미하지 않는다.

### Phase 1C-8c — 관리자 견종·서비스 콘텐츠 UI

- `/admin/` dashboard에서 매장정보·미디어·견종·서비스가 enabled인 same-page navigation
- 견종·서비스 loading·empty·error·refresh 목록, backend list response의 `sortOrder ASC, name ASC, id ASC` 순서 보존과 mutation 뒤 canonical GET
- 항상 draft인 명시적 생성, immutable slug와 status/name/nullable text/sortOrder full PUT 편집
- 서비스 published 전환의 description·priceText UX 보조와 backend 최종 validation authority 유지
- strict response allowlist, 404/409/422 frontend-owned 오류, 401 session expiry·403 mutation non-retry
- mutation 중복·stale refresh 경쟁 차단, post-mutation GET 실패 분리·explicit recovery, canonical refresh ready 뒤 enabled trigger focus 복귀와 320px·keyboard·44px·aria·reduced-motion 검증

Phase 1C-8c는 실제 견종·서비스 seed, 공개 서비스·필터 렌더링, 갤러리·공지 UI, build API/publisher, 실제 iPhone Safari·VoiceOver나 운영 배포의 완료를 의미하지 않는다.

### Phase 1C-8d — 관리자 갤러리 콘텐츠·관계 편집 UI

- `/admin/` dashboard에서 매장정보·갤러리·미디어·견종·서비스가 enabled이고 공지만 disabled인 same-page navigation
- 모든 상태 갤러리 목록의 backend `featured DESC, sortOrder ASC, publishedAt DESC NULLS LAST, id ASC` 순서 보존과 mutation 뒤 canonical GET
- 항상 draft인 생성과 status·관계·text·featured·sortOrder·performedAt·publishedAt 전체 PUT 편집
- breed·service·media catalog 독립 로드·복구와 draft/archived의 존재 관계, published의 published/active 관계 UX 보조
- active·archived private media를 구분해 선택하는 slot 인접 single picker, cover 재사용·before/after 상호 배제와 Blob preview lifecycle
- strict response/error allowlist, stale GET·중복 mutation 차단, post-mutation GET 실패 분리·explicit recovery와 canonical refresh 뒤 focus 복귀

Phase 1C-8d는 실제 갤러리 seed, 공개 responsive image·갤러리 렌더링, 공지 UI, build API/publisher, 실제 iPhone Safari·VoiceOver나 운영 배포의 완료를 의미하지 않는다.

### Phase 1C-8e — 관리자 공지 콘텐츠·게시기간 편집 UI

- `/admin/` dashboard의 여섯 관리 영역 enabled same-page navigation
- 모든 상태·미래·만료 공지 목록의 backend `pinned DESC, publishedAt DESC NULLS LAST, updatedAt DESC, id ASC` 순서 보존
- status 없는 draft 생성과 immutable slug를 제외한 status·title·summary·bodyMarkdown·pinned·publishedAt·expiresAt full PUT 편집
- source-only Markdown textarea, published body·publishedAt과 상태 공통 publish/expiry window UX 보조, backend 최종 validation authority 유지
- Gallery와 공유하는 local datetime 경계, unchanged backend microsecond Instant 보존과 정확한 1µs window 비교
- strict response/error allowlist, stale GET·중복 mutation 차단, post-mutation GET 실패 분리·explicit recovery와 canonical refresh 뒤 focus 복귀

Phase 1C-8e는 실제 공지 seed, 공개 공지 route·Markdown sanitize/rendering, scheduler·polling·자동 상태 전환, build API/publisher, 실제 iPhone Safari·VoiceOver나 운영 배포의 완료를 의미하지 않는다.

### Phase 1C-8f1 — 콘텐츠 revision·publishing outbox producer 기반

- Flyway V8 `content_revision_state` singleton과 typed `publishing_outbox`
- 지원 콘텐츠 mutation 성공 1회당 transactional row 기반 `contentRevision` 정확히 1회 증가
- Shop·Breed·Service·Notice·Gallery·Media의 공개 영향 immediate trigger 분류
- Notice `publishedAt`·`expiresAt`과 published Gallery `publishedAt`의 durable scheduled event
- validation·DB·outbox failure의 content/revision/event rollback과 media final file orphan cleanup
- PostgreSQL 동시 allocator·rollback·V1~V7→V8·clean V1→V8 계약 테스트

Phase 1C-8f1은 producer side foundation만 구현한다. outbox claim/lease, `publishGeneration`, build API·service credential, snapshot transformer, static publisher와 공개 반영은 포함하지 않는다.

### Phase 1C-8f2 — internal claim·publishGeneration state machine

- Flyway V9 transactional `publish_generation_state` singleton과 outbox state/result invariant
- `FOR UPDATE SKIP LOCKED` 기반 pending/due single claim, generation·첫 attempt atomicity와 rollback non-consumption
- current Notice·Gallery status/boundary가 달라진 scheduled event의 generation 없는 stale no-op
- active owner·lease renewal, expired lease와 1분·5분·15분 transient failure의 same-generation recovery/retry, 총 attempt 4회 제한
- typed success/no-op/failure와 lower→higher generation coalesce primitive

Phase 1C-8f2는 HTTP 없는 DB/state-machine foundation만 구현한다. 실제 polling loop, 30초 debounce/coalesce orchestration, build API·service credential, snapshot transformer, static build와 release switch는 포함하지 않는다. claim의 scheduled stale 검사는 source status·expected boundary에 한정하고 relation·media·file을 포함한 전체 공개 eligibility는 후속 build API/transformer가 다시 검증한다.

### Phase 1C-8f3 — internal read-only build API·service credential

- 관리자 session과 분리된 64자 lowercase hex Bearer credential, stateless 전용 SecurityFilterChain과 internal read-only namespace
- active `PROCESSING` generation·live lease gate와 `REPEATABLE READ` 기반 current `contentRevision`·`publishGeneration`·server-owned microsecond `generatedAt` snapshot
- exact public DTO allowlist, published/time/relation/media/file 이중 검증과 read-only 불변식
- Shop·공개 가능 Gallery relation의 distinct media manifest와 검증된 canonical master content 조회
- 모든 build mutation·unknown route와 dev/public gateway 접근 거부

Phase 1C-8f3은 build 입력 조회 경계까지만 구현한다. publisher polling loop, 30초 debounce, image derivative, Markdown/HTML transform, Next build, filesystem lock, release manifest와 atomic switch는 포함하지 않는다.

### Phase 1C-8f4 — build snapshot transformer·responsive image derivative

- Build API transport와 분리된 strict `BuildSnapshotV2` schema·semantic·relation·time/media manifest 재검증과 int64 canonical decimal string 보존
- distinct `MediaContentProvider` fetch-once와 JPEG·PNG signature/decode/size/pixel/single-image fail-closed 검증
- Gallery card·large와 Hero의 no-upscale AVIF·WebP·JPEG 파생본, Shop 미용사·OG의 임시 JPEG fallback
- output-byte SHA-256 filename, 결정적 `content.json`·`media-manifest.json`과 `public/generated/media` staging 산출물
- temp sibling 완성 뒤 새 target rename, failure cleanup과 기존 성공 target 보존
- Linux amd64 Hosted CI·Linux arm64 Mac Compose의 exact Node 24·Sharp 실행

Phase 1C-8f4는 독립 transformer library·filesystem CLI와 staging contract까지만 구현한다. build API HTTP client, polling publisher, 30초 debounce, Markdown/HTML·SEO·Next render, global lock, release manifest·atomic switch는 포함하지 않는다.

### Phase 1C-8f5 — publisher control loop·debounce·coalesce·lock

- exact opt-in dedicated non-web publisher process와 normal backend lifecycle 분리
- existing state service 기반 pending/due·retry·expired lease 반복 claim
- 첫 accepted generation 기준 `T0 + 30s` 포함 fixed debounce와 highest-generation coalesce
- debounce·executor 중 lease heartbeat, lost ownership의 completion 거부
- container-side configurable `FileChannel.tryLock` global lock, physical executor termination acknowledgment와 typed build executor/result port
- idle/debounce/executor shutdown, interrupt를 무시하는 actual async executor의 lock lifetime, PostgreSQL burst·stale·same-generation retry/recovery와 shared-lock contender 검증

Phase 1C-8f5는 control plane만 구현한다. production executor는 release를 만들지 않는 fail-closed placeholder다. Build API HTTP client, transformer·Next 실행, manifest와 `current/previous` switch는 포함하지 않는다.

### Phase 1C-8f6 — Build API adapter·transformer orchestration

- backend-only service credential을 request 전 검증하는 no-redirect·bounded internal Build API HTTP client
- raw snapshot strict parse, manifest-scoped in-flight/result memoized media provider와 기존 transformer port 연결
- safe terminal/transient/generation failure와 machine-oriented CLI, isolated atomic staging 검증

Phase 1C-8f6은 data-plane staging preparation까지만 구현한다. staging 성공은 public publication success나 outbox 완료가 아니며 production placeholder executor를 교체하지 않는다. Next/Markdown/SEO render, release manifest·stale guard·`current/previous` switch와 production secret/image provisioning은 포함하지 않는다.

### Phase 1C-8f7 — Next render·release manifest·atomic switch

- generated V2 기반 홈·정적 공지 상세, safe Markdown, responsive media와 SEO·Next Static Export
- HTML/link/canonical/sitemap/robots/media hash·비밀값의 fail-closed release validation
- strict release manifest, `BigInt` generation stale guard, immutable install과 `previous/current` atomic switch
- candidate 검증·post-switch loopback serving smoke, rollback과 current/previous 보호 retention
- fixed argv Java→Node executor, child/descendant physical termination과 DB completion 연결
- 실제 PostgreSQL 18.6·합성 private media·Sharp·Next·filesystem switch의 full pipeline 검증

Phase 1C-8f7은 격리된 synthetic/local·CI release foundation을 구현한다. actual Mac `/private/var/lib/rhaomi` provisioning, production Compose/Nginx/secret/image, 실제 domain·살롱 콘텐츠 공개와 production mutation은 포함하지 않는다.

### Phase 1C-8f8 — sample content·local end-to-end acceptance

- production seed가 아닌 synthetic Shop·Breed·Service·Gallery·Notice·JPEG/PNG/HEIC sample을 local bootstrap·same-origin Admin HTTP로 생성
- draft-only revision, public update, archive, Notice future publish·expiry, publisher downtime overdue recovery, stale reschedule와 fixed 30초 close-boundary coalesce를 실제 release까지 검증
- Build Snapshot V2→responsive transformer→Next Static Export→immutable release→`previous/current` switch와 manifest/revision/generation 일치 검증
- backend·PostgreSQL 중단 뒤 read-only local Nginx에서 홈·공지·media·robots·sitemap·runtime 독립, public deny·SEO·접근성·hash media 검증
- tmpfs DB·task temp root·internal network·single-command cleanup으로 기존 개발 data/volume과 production path·secret을 격리

Phase 1C-8f8은 local/CI synthetic acceptance gate다. 실제 Mac `/private/var/lib/rhaomi` provisioning, production Compose/Nginx/Cloudflare·secret/domain, 실제 살롱 콘텐츠·사진 공개와 iPhone Safari/VoiceOver는 후속 운영·물리 기기 gate다.

### Phase 1D — Production 운영 아키텍처 계약

- ADR-010 Cloudflare Tunnel·계층형 Nginx topology, macOS `/private/var/lib/rhaomi` host root, PostgreSQL project-scoped named volume과 수동 digest code release
- ADR-011 immediate·scheduled transactional event, 두 revision·single publisher·atomic switch
- ADR-012 외장 SSD·iCloud encrypted restic backup, remote-sync evidence와 isolated restore
- ADR-013 HomeOps 단일 관제·제한된 stateless restart
- ADR-014 pinned source HEIC decoder-only production runtime

Phase 1D는 문서·계약 확정만 의미한다. 실제 Mac directory ownership·bind smoke, PostgreSQL restart·일반 Compose `down` persistence·isolated `pg_restore`, production Compose/Nginx, GitHub Environment, publisher와 production build credential 주입, backup repository, HomeOps 설정과 production image는 후속 Issue에서 구현·검증한다.

## Phase 2 — 공개 랜딩 MVP

- Hero
- 갤러리·견종 필터
- 은총쌤 소개
- 서비스
- 예약 전 안내
- 공지
- 위치·영업시간
- 고정 CTA
- 반응형·접근성

## Phase 3 — 정적 콘텐츠 배포

- content snapshot
- 이미지 최적화 파생본
- 정적 route 생성
- backend 콘텐츠 transaction과 같은 PostgreSQL transaction의 immediate event와 Notice 게시·만료·Gallery 게시 boundary event
- `contentRevision`·`publishGeneration`, overdue recovery와 30초 debounce를 처리하는 단일 internal publisher
- internal read-only build API와 build transformer 이중 검증
- `publishGeneration` 기준 원자적 배포·rollback

## Phase 4 — SEO·출시 품질

- metadata, canonical, Open Graph
- LocalBusiness JSON-LD
- robots, sitemap
- Google Search Console
- 네이버 서치어드바이저
- 성능·접근성·실기기 검증
- 외장 SSD·iCloud encrypted restic backup, local/offsite RPO 분리·fresh retrieval·isolated restore
- HomeOps 단일 관제·alert·bounded stateless restart
- decoder-only HEIC production image와 SBOM·amd64/arm64 검증
- 관리자 2FA와 TLS/session cookie production gate

## Phase 5 — 운영 검증 후 선택

- 견종별 독립 SEO 페이지
- 공지 RSS
- Before/After 비교
- 관리자 UX 간소화
- 외부 객체 storage
- 개인정보 최소형 분석
- 임시휴무 배너
- 다중 이미지 갤러리

## 제외 유지

예약·결제·고객 계정·문의 폼은 별도 사업 요구가 확인되기 전 로드맵에 넣지 않는다.
