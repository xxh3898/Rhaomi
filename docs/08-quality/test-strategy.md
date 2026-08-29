---
title: "테스트 전략"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "기술·기능 범위 변경 시"
---

# 테스트 전략

## 목표

- 정적 사이트 생성 계약 보호
- 관리자 session·CSRF·인가 경계 보호
- PostgreSQL/Flyway/JPA schema 일치
- 콘텐츠 오류의 공개 유출 방지
- 모바일 문의·검색 metadata 보호
- 배포 실패 시 기존 사이트 보호

## 현재 Phase 1C-7 자동 검증

### Frontend

- lint
- TypeScript typecheck
- Node contract test와 Vitest/jsdom component·API client test
- Next Static Export
- `out/index.html`, `out/admin/index.html`, admin robots metadata와 runtime server artifact 부재 검증
- 공개 홈의 admin link/API 비노출
- Middleware·Route Handler·Server Action·request API 부재
- admin API의 relative URL, same-origin credential, GET no-store와 JSON shape 검증
- initial me 200/401/5xx/network/malformed 상태 분리
- login 전·후 CSRF, fixed error mapping, password 제거와 UTF-8 72-byte client 안내
- logout 204/401/403, mutation non-retry와 authenticated API 401 session-expired 처리
- visible label/autocomplete, Enter, pending 중복 방지, live alert, password focus, retry, identity와 disabled 영역
- browser storage·URL·log credential/token 비저장 정적 검사

### Backend unit

- test runtime `Runtime.version().feature() == 25`
- bootstrap 기본 비활성
- bootstrap credential 불완전 시 fail closed
- production profile bootstrap 거부
- email 정규화·password hash 생성
- bootstrap UTF-8 password 72-byte 허용·73-byte encoder 전 거부
- 인증 service·repository 장애의 generic 503과 내부 detail 비노출

### Backend PostgreSQL integration

- Spring context와 실제 PostgreSQL 연결
- Flyway V1 `admin_users` 생성
- JPA `ddl-auto=validate`
- plaintext password 비저장과 BCrypt match
- missing/bad/inactive/good login과 동일한 credential 401
- login UTF-8 password 72-byte 성공·73-byte validation 거부
- `ProviderManager` 인증 완료 principal과 session `SecurityContext`의 password hash 부재
- anonymous `/me`와 보호 endpoint 거부
- login 후 `/me`
- logout CSRF deny/allow와 session 무효화
- login CSRF deny/allow
- HttpOnly·SameSite session cookie와 fixation 후 id 변경
- response의 password/hash 비노출
- health 외 미설계 API·Actuator·non-API path deny

### 견종·서비스 domain/API/DB

- Flyway V1에서 V2 upgrade와 clean database V1·V2 순차 적용
- `breeds`, `services` table·명명된 status/slug/sort/actor FK constraint
- duplicate slug와 게시 서비스 필수값의 PostgreSQL 최종 차단
- JPA `ddl-auto=validate`
- anonymous와 CSRF 없는 create/update 거부
- create의 draft·sort 기본값·AdminPrincipal actor 기록
- 전체 상태 목록의 `sort_order ASC, name ASC, id ASC` 정렬과 단건 조회
- immutable slug, full update, archive row 보존과 draft/published 복구
- 다른 관리자의 update에서 created_by 보존과 updated_by 변경
- invalid/uppercase/공백 slug, duplicate slug, 없는 id의 400/409/404 계약
- unknown/id/audit/actor field mass assignment 거부
- 서비스 publish 필수값과 게시 중 필수값 제거 거부, 실패 시 row rollback
- hard `DELETE`와 `PATCH` endpoint 부재
- actor 없는 application service 호출 거부와 generic DB 5xx detail 비노출

### 공지 domain/API/DB

- Flyway V1·V2에서 V3 upgrade와 clean database V1·V2·V3 순차 적용
- `notices` table, `TIMESTAMP(6) WITH TIME ZONE` 시간 field와 명명된 status/title/slug/published/window/actor FK constraint
- DB default draft·pinned false와 duplicate slug·tab/newline-only title·published body·게시 필수값·모든 상태의 기간 무결성 최종 차단
- anonymous·public read와 CSRF 없는 create/update 거부
- create의 항상 draft, pinned 누락·null false, 문자열 정규화와 AdminPrincipal actor 기록
- `pinned DESC, published_at DESC NULLS LAST, updated_at DESC, id ASC`의 모든 tie-breaker 정렬
- 단건 조회, immutable slug, full update, archive row 보존과 draft/published 복구
- 두 번째 관리자 update의 created audit 불변과 updated_by 변경
- invalid slug, duplicate, malformed timestamp·UUID, 길이 경계, unknown/id/audit field의 400/409 계약과 exception detail 비노출
- 게시 필수값과 draft/published/archived 기간 오류의 422 코드 분리
- partial `PUT`, 게시·기간 validation 실패 뒤 row·audit 전체 불변
- 미래 publishedAt 허용, 100ns collapse 422, 정확히 1µs 차이 성공과 create/update/재조회 microsecond round-trip
- hard `DELETE`, `PATCH`, public endpoint와 scheduler 부재

### 매장정보 singleton·media relation domain/API/DB

- 기존 Flyway V1→V6 database의 V7 upgrade·기존 row null 보존과 clean V1→V7 순차 적용
- `shop_settings` 전체 column, `TIME(0) WITHOUT TIME ZONE`, `TIMESTAMP(6) WITH TIME ZONE`와 명명된 singleton/nonblank/hours/weekday/actor/media FK/image-alt pair constraint
- application을 우회한 두 번째 TRUE row, FALSE guard, whitespace-only 필수 text, 같거나 역전된 영업시간, 잘못된 요일·actor FK의 DB 차단
- anonymous GET/PUT, CSRF 없는 PUT, public endpoint 거부와 미초기화 `404 SHOP_SETTINGS_NOT_FOUND`
- 최초 PUT `201`, 후속·반복 PUT `200`, 항상 row count 1과 full response/read round-trip
- Unicode whitespace 정규화, 선택형 blank→null, 모든 text 길이 경계, phone 허용 문자·길이·최소 숫자 검증
- 정확한 `HH:mm`, nullable 영문 weekday, absolute HTTPS URL과 host/userinfo/control/2048자 경계
- id/singletonKey/audit/actor/unknown field mass assignment, partial PUT, malformed time/weekday의 `400 INVALID_REQUEST`
- opening >= closing의 `422 BUSINESS_HOURS_INVALID`와 실패 뒤 row·created/updated audit 전체 불변
- 두 번째 관리자 update의 created audit 보존, updated actor 변경과 microsecond audit round-trip
- active Hero·프로필·OG 최초 설정·교체·전체 제거, 같은 media의 세 역할 재사용과 scalar UUID/alt round-trip
- Hero·프로필 Unicode alt trim·blank→null·300 code-point boundary와 image-alt pair DB/application 이중 검증
- role별 missing·archived·malformed UUID, 301 code-point, storage metadata·system field 거부와 `422 SHOP_MEDIA_RELATION_INVALID` 계약
- relation 실패 뒤 row·created/updated audit 불변, 후속 media archive의 non-cascade, archived relation 유지 거부와 clear·active 교체 성공
- 세 media FK `ON DELETE RESTRICT`, 참조 asset hard delete 차단과 response의 storage key/path/hash/entity 비노출
- `POST`, `PATCH`, `DELETE`, id 기반·public/build endpoint 부재와 generic DB 5xx detail 비노출

### private media domain/API/DB

- 기존 Flyway V1→V4 database의 V5 upgrade와 clean V1→V2→V3→V4→V5 migration
- `media_assets` 전체 column, `TIMESTAMP(6) WITH TIME ZONE`, 명명된 status/type/extension/조합/size/dimension/pixel/hash/storage-key/actor constraint와 JPA validate
- 두 번째 row·동일 SHA-256 허용, storage key unique와 DB 우회 invalid 값 차단
- anonymous list/detail/content/upload/update, CSRF 없는 upload/update, public/build route 거부
- JPEG·PNG passthrough byte round-trip, generic/빈 MIME, filename 없음·extension 없음과 traversal filename 무해성
- 실제 합성 HEIC·HEIF를 orientation 적용·sRGB·metadata-free JPEG로 정규화하고 display dimension·pixel 방향·stored hash/byte를 확인
- HEIC still `heic | heix | heim | heis`의 major·compatible brand 인식, HEIC sequence `hevc | hevx | hevm | hevs`와 `msf1`의 422, AVIF `avif | avis`의 415를 detector·실제 API에서 직접 확인
- major `mif1`·compatible `heic`인 valid synthetic HEIC를 `image/heic`·`.heic`로 업로드해 201, orientation·sRGB·metadata strip과 JPEG 정규화를 확인
- duplicate upload의 별도 row/master, deterministic `created_at DESC, id ASC`, archived content 조회와 restore
- missing/empty/unknown multipart, source/output limit, width/height/pixel limit, GIF/WebP/AVIF/SVG/APNG/multi-image/sequence와 corrupt source 거부
- MIME·extension 위장 415, malformed UUID/JSON/status injection 400과 고정 ApiError code
- validation·normalization 실패의 temp/final/DB orphan 부재, 실제 FK persistence failure의 DB rollback·final cleanup
- missing/corrupt master의 generic 500과 private path·constraint detail 비노출
- codec reader·native link 누락 fail-fast, root 생성·쓰기 실패 fail-fast
- 개인 사진이 아닌 합성 기하 도형, Display P3 ICC, 합성 EXIF/GPS/XMP fixture와 생성 근거

### 갤러리 domain/API/DB

- 기존 Flyway V1→V5 database의 V6 upgrade와 clean V1→V2→V3→V4→V5→V6 migration, JPA validate
- `gallery_items` exact column, `TIMESTAMP(6) WITH TIME ZONE`, 명명된 status/nonblank/sort/published/before-after/FK/actor constraint와 deterministic index
- application 우회 invalid status·음수 sort·whitespace-only optional text·published 필수값·before=after·잘못된 relation/actor FK 차단
- 참조 breed·service·cover/before/after media와 actor hard delete의 `ON DELETE RESTRICT`
- anonymous, CSRF 없는 create/update, public/build route 거부와 minimal draft 201·Location·기본값·server audit
- Unicode trim·blank→null·문자열 길이·malformed UUID/JSON/timestamp·unknown/system field·partial full PUT의 400 계약
- 모든 relation 존재성, draft/archived의 non-public target 유지와 published의 breed/service `published`·media `active` 검증
- published 필수 breed/service/cover/altText/publishedAt, cover=before/after 허용, before=after 422
- relation·게시 validation 실패 뒤 row와 created/updated audit 전체 불변, 두 번째 관리자 updated audit 전환
- archive→draft/published 복구와 relation target 후속 상태 변경의 non-cascade
- `featured DESC, sort_order ASC, published_at DESC NULLS LAST, id ASC`의 모든 tie-breaker와 단건 round-trip
- performedAt·publishedAt nanosecond 입력의 microsecond 절삭·DB/response/재조회 round-trip과 future publishedAt 허용
- response의 scalar relation id만 노출하고 media storage key/path/hash와 relation 객체를 embed하지 않음
- `PATCH`, `DELETE`, publish action, public/build endpoint 부재와 domain별 고정 404/422/generic 5xx 계약

H2 전용 통과는 DB contract 증거로 인정하지 않는다. Hosted CI Backend job은 실제 PostgreSQL service를 사용한다.
Gradle test는 `RHAOMI_TEST_DATABASE_ALLOWED=true`가 명시되지 않으면 application context를 시작하기 전에 중단한다. fixture 정리는 지정된 test email에만 한정한다.

### Compose Smoke

- exact service/image와 config validation
- exact Nginx tag/digest와 `127.0.0.1:3000` gateway-only browser bind
- frontend host port 부재와 gateway/PostgreSQL network 비공유
- exact Temurin 25 image의 Java 25 `bootRun` 확인
- gateway/frontend/backend/PostgreSQL health
- backend loopback bind와 PostgreSQL host port 부재
- explicit local/test bootstrap
- gateway `/admin/` HTML과 `/api/**` JSON routing·no-CORS·server version 비노출
- backend 중단 중 API non-200과 frontend HTML fallback 부재
- gateway를 통한 실제 HTTP CSRF login/me/post-login fresh CSRF/logout
- 20 MiB multipart source가 gateway 413으로 차단되지 않음
- backend/PostgreSQL restart 후 Flyway·account 지속성
- 합성 HEIC upload→canonical JPEG signature·dimension·metadata strip
- backend/PostgreSQL restart 후 같은 media id·byte size·SHA-256 유지
- private media named volume의 backend-only mount와 PostgreSQL volume 분리
- Directus service 부재
- 종료 시 named volume 보존

## 후속 build·콘텐츠 단위·통합 테스트

- URL, phone link, 공지 build-time 게시 도래·expiry filter
- slug/canonical, JSON-LD, alt validation
- published 관계와 file scope
- 매장정보 Hero·프로필·OG relation의 active status·private master·파생 file 재검증
- build API read-only와 모든 mutation deny
- snapshot schema와 image manifest
- content fixture → transformer → static snapshot

## 후속 콘텐츠 UI/E2E

- 홈 → gallery filter → 상세 → 문의
- 홈 → 공지 → 상세 → 홈
- map/phone/external CTA
- mobile sticky CTA, 404, keyboard only
- `/admin` 콘텐츠 form/list/upload, validation, archive·restore
- axe, heading/landmark/focus/dialog/contrast/reflow/reduced motion
- 실제 iPhone Safari image upload와 session cookie 동작

## 후속 배포 테스트

- content publish/archive
- build event auth, debounce, lock
- failed build does not switch
- atomic switch와 rollback
- stale build ordering
- backend/PostgreSQL 중단 중 공개 site 유지

## test data

- test-only 관리자 email/password
- active/inactive admin
- 공개/초안/보관 콘텐츠와 만료 공지, active/archived media relation
- 긴 title·견종명·매장 text, 선택형 URL 빈 값과 HTTPS/비HTTPS URL
- 합성 portrait/landscape/손상/HEIC·HEIF·metadata image

실사용 email, 실제 운영 password, token과 운영 DB/API는 test에 사용하지 않는다.

## CI gate

PR:

- Frontend
- Backend PostgreSQL/auth/media contract를 exact Java 25·libheif image에서 실행
- Compose Smoke의 same-origin auth·HEIC 정규화·media volume restart persistence
- diff·secret·문서 link 검사

Release는 실제 build snapshot, image pipeline, E2E, SEO, Nginx preview, actual device와 rollback evidence를 추가한다.
