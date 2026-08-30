---
title: "테스트 전략"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
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

## 현재 Phase 1C-8f3 자동 검증

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
- login POST와 post-login fresh CSRF 단계 분리, fresh CSRF pending 전 password·form email 제거, pre-login token 폐기
- post-login CSRF 실패의 unavailable 분리와 `/me`+fresh CSRF recovery, 기존 session의 mutation-ready 전 fresh CSRF 준비
- login 400/401/403/503 fixed error mapping, password 제거와 UTF-8 72-byte client 안내
- logout 204/401/403, mutation non-retry와 authenticated API 401 session-expired 처리
- visible label/autocomplete, Enter, pending 중복 방지, live alert, password focus, retry와 identity
- browser storage·URL·log credential/token 비저장 정적 검사
- 매장정보·갤러리·미디어·견종·서비스·공지가 모두 enabled인 dashboard navigation과 same-page 관리 홈 복귀
- media list loading/ready/empty/error/refreshing, server ordering 유지와 active/archived client filter
- private image Blob GET의 JPEG/PNG 검증, bounded lazy fetch와 object URL refresh/unmount revoke
- 단일 multipart `file`, 20 MiB client 차단, pending double-submit 차단과 success file state clear
- 400/413/415/422/503/403/network의 frontend-owned upload 문구와 raw detail 비노출
- active/archive status-only PUT, item pending isolation, 403 non-retry·no optimistic success와 401 login 복귀
- shop `SHOP_SETTINGS_NOT_FOUND`, `BUSINESS_HOURS_INVALID`, `SHOP_MEDIA_RELATION_INVALID` allowlist와 기존 media mapping 회귀
- shop response mutable 26개+audit 4개 strict shape, malformed/extra field 거부와 full request mutable key 전체·audit key 부재
- shop GET loading→200 populate, 404 미초기화 empty form, generic error+retry와 session expiry
- required/nullable/time/weekday/parking/url control, nullable blank→null과 backend canonical response form 교체
- 최초·후속 full PUT, pending double submit·field 변경 방지, 400/422/403/5xx fixed message와 raw detail 비노출
- Hero·미용사·OG relation 직후 slot별 inline picker 배치, slot 전환 시 active-only single instance
- `미디어 선택` Enter open, picker 첫 control focus 진입, close·selection 후 해당 slot trigger focus 복귀
- 없음/clear, same media Hero·미용사·OG 재사용과 OG alt field 부재
- Hero·미용사 image-alt pair와 300 code-point, archived/missing 현재 relation 가시화·새 선택 금지·clear/active 교체
- picker private Blob bounded load·object URL revoke, media list retry와 preview/session expiry
- shop ready 상태 background GET·auto-save 부재와 save snapshot/canonical response의 stale overwrite 방지
- Breed/Service exact relative list·detail·POST·PUT path와 도메인별 strict list/item response validator, malformed·unexpected internal field 거부
- `CONTENT_NOT_FOUND`, `SLUG_CONFLICT`, `PUBLISH_VALIDATION_FAILED` allowlist와 기존 media/shop error mapping 회귀
- 견종·서비스 loading/ready/empty/error/refreshing, locale comparator 없이 GET list server array order 보존
- create/update canonical item response 적용 뒤 post-mutation GET의 server ordering 반영, refresh failure의 저장 성공 분리·explicit recovery
- 항상 draft인 생성, blank description·priceText·sortOrder의 null 변환, immutable slug와 update full representation
- draft/published/archived 표시, archive를 delete로 표현하지 않는 복구 flow와 서비스 published description·priceText UX 보조
- create/edit 첫 input focus와 취소 즉시 trigger 복귀, 성공 뒤 post-mutation GET pending 중 focus ref 보존 및 resolve/reject로 `ready` 복귀한 enabled trigger/item action에만 focus 복귀, pending ref의 중복 POST/PUT 방지와 pre-mutation stale GET·post-mutation generation 경쟁 차단
- 401 session callback, 403/network/5xx mutation non-retry와 frontend-owned error 문구·raw detail 비노출
- 320px CSS, 44px control, keyboard native control, aria status/alert/pressed와 archived text state
- Gallery exact relative list/detail/POST/PUT path, strict response key·UUID·status·nullable text·Instant·audit 검증과 internal/relation object field 거부
- Gallery loading/ready/empty/error/refreshing, locale comparator 없는 backend `featured, sortOrder, publishedAt, id` 배열 순서 보존
- always-draft POST와 nullable/default 변환, status·관계·text·featured·sortOrder·timestamp 전체 PUT, unchanged microsecond Instant 보존과 canonical response 적용
- create/update 성공 뒤 post-mutation GET ordering, 저장 성공과 후속 GET 실패 분리·explicit refresh, pre-mutation stale GET·pending 중복 mutation 차단
- Gallery 목록과 breed/service/media catalog 독립 load/error/retry, 관계 오류 중 기존 card 보존과 mutation gate
- draft/archived의 모든 상태 existing relation·active/archived media 선택, published의 published breed/service·active media·altText·publishedAt 안내와 backend 최종 authority
- cover=before/after 허용, before=after 상태 무관 차단, archived/missing relation의 상태 text와 clear/replace
- cover/before/after relation 인접 all-existing single picker, Enter open·첫 control focus·close/selection trigger 복귀와 private Blob object URL revoke
- `GALLERY_ITEM_NOT_FOUND`, `GALLERY_RELATION_INVALID`, `GALLERY_PUBLISH_INVALID` fixed mapping, 401 callback과 403/network/5xx non-retry·raw detail 비노출
- Gallery create/edit 취소 즉시 focus, 성공 뒤 canonical GET resolve/reject로 ready가 된 enabled trigger focus, archive/restore와 delete action 부재
- Notice exact relative list/detail/POST/PUT path, 13개 exact response key·UUID·status·slug·normalized nullable text·microsecond Instant·audit 검증과 internal field 거부
- Notice loading/ready/empty/error/refreshing과 backend `pinned, publishedAt, updatedAt, id` 배열 순서 보존, always-draft POST와 immutable slug를 제외한 full PUT
- source-only Markdown, pinned, published body·publishedAt과 상태 공통 expiry window, 미래·지난 유효 window와 정확히 1µs 경계 검증
- Gallery·Notice 공통 local datetime helper의 invalid/null 처리와 unchanged backend microsecond Instant 보존
- Notice create/update response 선적용과 post-mutation canonical GET, 저장 성공·refresh 실패 분리·explicit recovery, stale GET·중복 mutation 차단
- `NOTICE_WINDOW_INVALID` 포함 400/404/409/422 frontend-owned 문구, raw detail 비노출, 401 callback과 403/network/5xx non-retry
- Notice create/edit 첫 제목 focus, 취소 즉시 복귀, canonical GET resolve/reject 뒤 ready/enabled trigger focus와 archive/restore·delete action 부재

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
- `POST`, `PATCH`, `DELETE`, id 기반 관리자 endpoint·anonymous public endpoint 부재와 generic DB 5xx detail 비노출

### private media domain/API/DB

- 기존 Flyway V1→V4 database의 V5 upgrade와 clean V1→V2→V3→V4→V5 migration
- `media_assets` 전체 column, `TIMESTAMP(6) WITH TIME ZONE`, 명명된 status/type/extension/조합/size/dimension/pixel/hash/storage-key/actor constraint와 JPA validate
- 두 번째 row·동일 SHA-256 허용, storage key unique와 DB 우회 invalid 값 차단
- anonymous list/detail/content/upload/update, CSRF 없는 upload/update와 public admin-media route 거부
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
- `PATCH`, `DELETE`, publish action과 anonymous public endpoint 부재, domain별 고정 404/422/generic 5xx 계약

### publication revision·outbox producer

- 기존 V1→V7 database의 V8 upgrade와 clean V1→V8 migration, JPA validate
- `content_revision_state` singleton·nonnegative guard와 초기 revision 0
- `publishing_outbox` exact typed column, `TIMESTAMP(6) WITH TIME ZONE`, kind/source/revision/boundary/source-kind named CHECK와 required index
- transaction 필수 recorder와 PostgreSQL row increment 기반 동시 allocator의 unique·gapless revision
- rollback된 transaction의 revision/event 부재와 다음 성공 mutation revision 재사용
- Breed·Service draft-only와 published 진입·수정·이탈, Shop 모든 PUT, Notice·Gallery 상태 matrix, Media upload·archive·restore 분류
- Notice create/update의 changed publishedAt·expiresAt과 published Gallery 진입/reschedule scheduled event, 동일 mutation event의 같은 revision과 old row 보존
- Notice·Gallery·Shop·Media validation 실패의 revision/event/content/file 불변
- outbox insert 강제 실패의 content·revision rollback과 다음 mutation 복구
- media persistence 뒤 revision allocation 실패의 DB row·final/temp file orphan 부재
- 새 HTTP/build endpoint·credential, API response field, Compose/workflow/dependency 변경 부재

### publication claim·generation state

- 기존 V1→V8 database의 V9 upgrade, 기존 V8 event의 default `PENDING` 호환과 clean V1→V9 migration·JPA validate
- `publish_generation_state` singleton·nonnegative initial 0과 transactional row increment rollback non-consumption
- outbox state/result allowlist, state별 generation·attempt·owner·lease·retry·completion shape, unique generation·coalesce self-FK와 required index
- immediate pending·due scheduled `(availableAt, id)` ordering과 `FOR UPDATE SKIP LOCKED` single claim·locked-row skip·concurrent double-claim 방지
- fresh generation·첫 attempt atomicity, future scheduled 미claim과 forced rollback 뒤 PENDING/counter 불변
- Notice published/expiry와 Gallery published boundary exact claim, reschedule·boundary 제거·draft/archive·missing source의 generation 없는 stale no-op
- claim layer가 relation·media·file/full eligibility를 복제하지 않고 current source status·expected boundary만 확인하는 계약
- owner 형식, active lease renewal·wrong owner 거부, expired lease same-generation recovery·concurrent single winner와 attempt 4 exhaustion
- transient failure 1분·5분·15분 schedule, due 이전 미claim, same-generation retry와 fourth failure exhaustion
- success/no-op/terminal failure reclaim 부재와 owner·generation·active lease completion guard
- 같은 content revision의 distinct fresh generation과 lower→higher active coalesce, higher→lower·missing/terminal/wrong-owner target 거부
- typed internal status model과 controller·scheduler·background executor·service credential·환경 변수 부재

### internal build security·snapshot·media

- valid 64자 lowercase hex Bearer와 missing·wrong·malformed·duplicate header, disabled non-production token의 fixed 401/503 계약
- 별도 stateless build principal·SecurityFilterChain, session 비생성, GET CSRF 불필요와 관리자 session/build token 교차 권한 부재
- snapshot·media 두 GET allowlist, POST·PUT·PATCH·DELETE·unknown route deny와 raw token/header 비노출
- production profile의 missing·malformed token startup failure와 valid token·Secure cookie 성공
- active PROCESSING generation+live lease 성공, pending·retry-wait·terminal·expired·unknown·nonpositive generation 거부
- read-only PostgreSQL REPEATABLE READ transaction과 단일 server-owned microsecond generatedAt, current revision과 target event revision 분리
- exact top-level·Shop·Breed·Service·Gallery·Notice·Media key allowlist, `schemaVersion=1`, `codeImageDigest`·audit·storage/hash·claim field 부재
- published Breed/Service ordering, Gallery due/future와 relation published 조건, Notice active/future/expired·pinned ordering, Markdown source 보존
- Shop singleton·NAP/time/HTTPS/image-alt validation, Service final state와 direct-invalid content 전체 422
- Shop/Gallery relation의 active media distinct union·id ordering과 canonical master size·SHA 검증, invalid/missing/corrupt file 전체 snapshot 실패
- public-scope media success header, unlinked·draft/archived/future Gallery-only·archived·missing media 404, corrupt bytes 503, malformed UUID 400
- snapshot transaction barrier 동안 concurrent content commit 뒤 기존 response의 pre-mutation 일관성과 다음 request의 새 revision/content
- snapshot/media success·failure 전후 content revision, outbox, generation, lease, attempt와 content/media state 불변

H2 전용 통과는 DB contract 증거로 인정하지 않는다. Hosted CI Backend job은 실제 PostgreSQL service를 사용한다.
Gradle test는 `RHAOMI_TEST_DATABASE_ALLOWED=true`가 명시되지 않으면 application context를 시작하기 전에 중단한다. fixture 정리는 지정된 test email에만 한정한다.

### Compose Smoke

- exact service/image와 config validation
- exact Nginx tag/digest와 `127.0.0.1:3000` gateway-only browser bind
- frontend host port 부재와 gateway/PostgreSQL network 비공유
- frontend repository-root bind와 `.env.dev.local` 부재, source/config read-only allowlist mount
- frontend·gateway environment의 `RHAOMI_BUILD_SERVICE_TOKEN` 부재와 frontend `/workspace`의 backend token literal digest match 0건
- backend environment token digest 일치, raw token 비출력, backend loopback valid Bearer의 인증 단계 통과와 gateway same Bearer 404
- network-disabled `contract-check`의 actual env file 부재와 repository-wide frontend contract 검증
- exact Temurin 25 image의 Java 25 `bootRun` 확인
- gateway/frontend/backend/PostgreSQL health
- backend loopback bind와 PostgreSQL host port 부재
- explicit local/test bootstrap
- gateway `/admin/` HTML과 `/api/**` JSON routing·no-CORS·server version 비노출
- valid build Bearer를 포함해 gateway `/api/build/**` 선행 404, backend loopback/integration에서만 build 인증 허용
- backend 중단 중 API non-200과 frontend HTML fallback 부재
- gateway를 통한 실제 HTTP CSRF login/me/post-login fresh CSRF/logout
- 20 MiB multipart source가 gateway 413으로 차단되지 않음
- backend/PostgreSQL restart 후 Flyway·account 지속성
- 합성 HEIC upload→canonical JPEG signature·dimension·metadata strip
- backend/PostgreSQL restart 후 같은 media id·byte size·SHA-256 유지
- private media named volume의 backend-only mount와 PostgreSQL volume 분리
- Directus service 부재
- 종료 시 named volume 보존

## 현재 Phase 1D 운영 아키텍처 문서 검증

Issue #19는 docs/ADR-only이므로 production runtime을 실행하지 않고 다음 계약을 검증한다.

- ADR-010~014 frontmatter `approved`, 본문 `Accepted`와 decision log 승인 상태 일치
- Markdown 상대 link와 문서 작성 규칙
- Cloudflare Tunnel → host edge Nginx → loopback project Nginx와 `/api/admin/**` public route
- `/api/build/**`, `/internal/**`, `/actuator/**` public deny와 backend/PostgreSQL direct exposure 금지
- macOS host canonical `/private/var/lib/rhaomi/{app,public,data/media,state,logs}` 경로 일치와 `/data/postgres` bind source 부재
- Mac public/media/state source와 Linux web/publisher/backend target의 mount mapping·RO/RW 경계 일치
- `/srv/rhaomi`가 container target으로만 쓰이고 Mac host authority·`synthetic.conf`·custom File Sharing prerequisite가 아님
- PostgreSQL production project-scoped named volume, 일반 Compose `down` 보존과 `down -v`·prune·direct delete 금지 일치
- main merge와 manual production apply 분리, exact SHA·digest와 one-shot Flyway
- same-transaction immediate·scheduled event, `contentRevision`·`publishGeneration`, single publisher, overdue recovery·30초 debounce·retry·atomic switch
- external SSD·iCloud 별도 encrypted restic repository, local integrity와 remote sync evidence 분리, 03:30 KST, daily 7 / weekly 4 / monthly 6
- weekly structural check, monthly full data read, quarterly isolated restore, 최초 fresh retrieval remote evidence, local/offsite RPO 24h·RTO 8h 분리
- HomeOps 단일 authority, exact 임계값, stateless single restart allowlist·lock·30분 cooldown·금지 범위
- libheif `v1.23.1` exact commit, libde265 decoder-only와 x265 absence·SBOM·amd64/arm64 gate
- application source, Flyway, Dockerfile, Compose, Nginx, workflow와 script diff 0
- corrective exact-head Hosted CI Frontend·Backend·Compose Smoke 3/3 성공

문서 PASS는 production Compose, publisher, backup, HomeOps 또는 decoder-only image의 runtime acceptance 증거가 아니다.

## 현재 Phase 1C-8f4 transformer 자동 검증

- strict snapshot exact key·schema·semantic·published/time/relation/media manifest와 typed safe error
- Breed nullable description과 Service required description의 canonical/nonblank 재검증, backend에 없는 transformer 전용 길이 제한 부재
- distinct media fetch-once와 source canonical order 보존, derivative manifest deterministic ordering
- JPEG·PNG signature/decode·size/pixel/single-image/APNG 제한과 corrupt/type mismatch
- EXIF/GPS/XMP·orientation을 넣은 synthetic source의 rotate·sRGB·metadata strip
- Gallery card·large·Hero AVIF·WebP·JPEG width, no upscale, output decode·format·metadata 재검증
- output byte SHA-256 filename, duplicate byte dedupe와 byte-for-byte repeated-run determinism
- temp sibling failure cleanup, existing successful target preservation과 filesystem output failure
- CLI success와 path·UUID·decoder detail 없는 fixed failure output
- Linux amd64 Hosted CI와 Linux arm64 Mac Compose의 actual Sharp transform

## 후속 publisher·정적 render 단위·통합 테스트

- Build API URL/phone/source Markdown을 transformer가 링크·HTML로 안전하게 변환
- slug/canonical, JSON-LD, alt validation과 content fixture → transformer → static snapshot
- build API HTTP client의 authenticated snapshot/media acquisition과 구현된 transformer port 연결
- additional admin mutation 없는 future Notice publish·expiry와 Gallery publish, publisher restart 뒤 overdue 처리
- Notice·Gallery claim 뒤 변경까지 포함한 Build API 재검증 결과가 release까지 stale 공개를 막음
- 30초 debounce·global lock·highest generation coalescing과 최종 snapshot 정확성
- 낮거나 같은 generation의 old build switch 거부와 승인된 manual rebuild/retry의 새 generation
- persisted snapshot/release manifest의 contentRevision·publishGeneration·generatedAt·codeImageDigest 일치

## 후속 콘텐츠 UI/E2E

- 홈 → gallery filter → 상세 → 문의
- 홈 → 공지 → 상세 → 홈
- map/phone/external CTA
- mobile sticky CTA, 404, keyboard only
- `/admin` 공지 form/list
- axe, heading/landmark/focus/dialog/contrast/reflow/reduced motion
- 실제 iPhone Safari HEIC photo-library upload와 session cookie·object URL 동작

## 후속 배포 테스트

- content publish/archive
- exact main SHA·image digest·manual environment approval와 고정 deploy entrypoint
- write maintenance·one-shot Flyway·schema validate와 expand/contract compatibility
- immediate/due publishing event·overdue recovery·service auth·debounce·lock
- failed build does not switch
- atomic switch와 rollback
- 실제 Mac canonical directory 생성·ownership·permission과 public/media/state bind mount smoke
- PostgreSQL named volume의 container restart·일반 Compose `down`·`up` persistence, destructive volume command 부재
- `publishGeneration` stale build ordering과 manifest의 `contentRevision`·`publishGeneration`·`generatedAt`
- backend/PostgreSQL 중단 중 공개 site 유지
- `/api/build/**`, `/internal/**`, `/actuator/**` public deny
- successful release 5개와 current/previous retention, failed artifact 7일
- 외장 SSD·iCloud backup set, local repository snapshot/check와 Apple remote sync evidence 분리, retention·prune post-check
- second trusted device 또는 local cache를 배제한 clean path의 fresh retrieval·restic check·대표 restore
- isolated restore의 manifest·DB·media·static build와 local/offsite RPO·RTO
- raw PGDATA volume을 required restic input으로 사용하지 않고 `pg_dump -Fc`를 새 isolated named volume에 `pg_restore`
- 외장 SSD `/Volumes/<provisioned-volume>/...` exact path·volume identity와 encrypted repository 검증
- HomeOps synthetic/internal/container/host/DB/publisher/backup threshold·alert
- stateless web/backend single restart와 deploy/backup lock·30분 cooldown·audit
- decoder-only image x265 absence와 Linux amd64·Mac mini Linux arm64 actual HEIC

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
