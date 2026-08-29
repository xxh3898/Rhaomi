---
title: "로드맵"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
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

### Phase 1C-8 이후 — 나머지 콘텐츠 기능

- `/admin` 콘텐츠 관리 UI
- build-time read-only API와 credential 분리
- 샘플 콘텐츠

### Phase 1D — Production 운영 아키텍처 계약

- ADR-010 Cloudflare Tunnel·계층형 Nginx topology와 수동 digest code release
- ADR-011 immediate·scheduled transactional event, 두 revision·single publisher·atomic switch
- ADR-012 외장 SSD·iCloud encrypted restic backup, remote-sync evidence와 isolated restore
- ADR-013 HomeOps 단일 관제·제한된 stateless restart
- ADR-014 pinned source HEIC decoder-only production runtime

Phase 1D는 문서·계약 확정만 의미한다. production Compose/Nginx, GitHub Environment, publisher/outbox/build API, backup repository, HomeOps 설정과 production image는 후속 Issue에서 구현·검증한다.

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
- backend 콘텐츠 transaction과 같은 PostgreSQL transaction의 immediate·future notice boundary publishing event
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
