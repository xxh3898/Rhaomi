---
title: "테스트 전략"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-09-01"
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
- exact top-level·Shop·Breed·Service·Gallery·Notice·Media key allowlist, `schemaVersion=2`, canonical decimal string revision/generation과 `codeImageDigest`·audit·storage/hash·claim field 부재
- 실제 PostgreSQL 18.6·Flyway V1~V9에서 `9007199254740993`과 `9223372036854775807` active generation HTTP 200, JSON string exact 보존과 content revision `0` 경계
- published Breed/Service ordering, Gallery due/future와 relation published 조건, Notice active/future/expired·pinned ordering, Markdown source 보존
- Shop singleton·NAP/time/HTTPS/image-alt validation, Service final state와 direct-invalid content 전체 422
- Shop/Gallery relation의 active media distinct union·id ordering과 canonical master size·SHA 검증, invalid/missing/corrupt file 전체 snapshot 실패
- public-scope media success header, unlinked·draft/archived/future Gallery-only·archived·missing media 404, corrupt bytes 503, malformed UUID 400
- snapshot transaction barrier 동안 concurrent content commit 뒤 기존 response의 pre-mutation 일관성과 다음 request의 새 revision/content
- snapshot/media success·failure 전후 content revision, outbox, generation, lease, attempt와 content/media state 불변

### publisher control loop

- normal backend의 publisher bean/thread 부재와 exact mode argument 전용 non-web root, controller·web server 부재
- bounded owner/poll/lease/renew/shutdown/absolute lock path validation, 30초 debounce의 non-configurable contract
- claim 없음의 idle wait·busy-spin 부재와 generation 없는 stale no-op executor 0회
- first `claimedAt` 기준 exact 30초 fixed window, `T0 + 30s` 포함·직후 제외
- burst generation 1/2/3의 lower→highest coalesce와 generation 3 단일 executor, recovered lower generation의 highest 유지
- debounce·async executor lease heartbeat, pre-lock·executor 중 lease 상실과 state completion false의 success 금지
- typed executor 네 결과와 safe exception의 fixed state mapping, lock unavailable의 transient 처리
- idle/debounce/executor shutdown과 shutdown 뒤 새 claim 부재, actual executor body 종료 뒤 lock handle release
- PostgreSQL 18.6 pending burst monotonic generation·coalesce·highest success, stale no-generation, due retry와 expired lease recovery의 same generation
- shared filesystem을 쓰는 두 publisher contender의 executor maximum concurrency 1과 loser retry-wait
- interrupt를 무시하고 release latch까지 살아 있는 actual async executor로 lease-loss·shutdown timeout 동안 두 번째 file lock 획득 불가, physical 종료 뒤에만 재획득 가능
- lease를 잃은 executor의 늦은 success/no-public-change 결과가 completeSuccess·completeNoop으로 기록되지 않음
- actual advisory lock file 내용 0 byte, fixed I/O failure와 path detail 비노출

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
- explicit validation-only `publisher-validation` profile에서 Java 25·Node 24·Sharp actual full publication E2E, public release temp filesystem과 normal service 집합 불변
- int64 E2E가 현재 task label의 사전 생성 Node/Gradle cache volume만 사용하며 불일치·부재 시 unlabeled volume을 암묵 생성하지 않음

### production decoder-only image

`sh scripts/validate-production-image.sh`를 Linux native architecture에서 실행한다.

- exact Temurin Java 25 JRE·Node `24.20.0` digest, libheif `v1.23.1` tag/commit/archive SHA와 libde265 `1.0.16-r0`
- upstream `plugin_option`·root `option` inventory exact match, CMake cache의 libde265 built-in 단독 활성과 모든 codec plugin `OFF`
- x265 package·`libx265` ELF link·codec plugin file 0, encoder/example/CLI/compiler/Git/CMake/source/header/cache/test source 부재
- executable backend JAR와 production-only Node graph의 tracked publisher Static Export 성공, runtime npm package manager 부재
- tmpfs PostgreSQL과 private media에서 actual HEIC·generic HEIF→JPEG 201, orientation·sRGB·metadata strip
- HEIC sequence `422 MEDIA_INVALID_IMAGE`, AVIF `415 MEDIA_TYPE_UNSUPPORTED`
- exact image ID·Git HEAD를 metadata로 가진 CycloneDX SBOM, tracked core component source/version/license, x265 0
- exact pinned Grype 실행·database metadata와 severity summary, scan 실패·금지 component 발견 시 validation 실패
- Mac mini Docker Linux arm64 native run과 Hosted Backend Linux amd64 run의 동일 entrypoint

scanner finding을 blanket ignore하지 않는다. 새 severity cutoff를 이 구현에서 만들지 않으며 actual HIGH/CRITICAL은 fixed dependency로 해소하거나 pinned architecture와 충돌하면 decision gate로 되돌린다. generated SBOM·scan은 test artifact이고 source에 커밋하지 않는다.

## 현재 Phase 1D 운영 아키텍처 contract freeze 검증

Issue #47은 docs-only이므로 production runtime을 실행하지 않고 다음 계약을 검증한다.

- ADR-010~014 frontmatter `approved`, 본문 `Accepted`와 decision log 승인 상태 일치
- Markdown 상대 link와 문서 작성 규칙
- readiness status vocabulary 정의·matrix 사용값 일치와 local/CI synthetic evidence의 production `PASS` 승격 0
- roadmap의 `CONTRACT COMPLETE / PROVISIONING NOT COMPLETE`, open-items·matrix·release checklist blocker 일치
- Cloudflare Tunnel → host edge Nginx → loopback project Nginx와 `/api/admin/**` public route
- `/api/build/**`, `/internal/**`, `/actuator/**` public deny와 backend/PostgreSQL direct exposure 금지
- macOS host canonical `/private/var/lib/rhaomi/{app,public,data/media,state,logs}` 경로 일치와 `/data/postgres` bind source 부재
- Mac public/media/state source와 Linux web/publisher/backend target의 mount mapping·RO/RW 경계 일치
- `/srv/rhaomi`가 container target으로만 쓰이고 Mac host authority·`synthetic.conf`·custom File Sharing prerequisite가 아님
- PostgreSQL production project-scoped named volume, 일반 Compose `down` 보존과 `down -v`·prune·direct delete 금지 일치
- main merge와 manual production apply 분리, exact SHA·digest와 one-shot Flyway
- same-transaction immediate·scheduled event, `contentRevision`·`publishGeneration`, single publisher, overdue recovery·30초 debounce·retry·atomic switch
- 초기 Mac mini local-only application-consistent backup, 03:30 KST, daily 7 / weekly 4 / monthly 6과 isolated `pg_restore`·media restore
- single-host disaster accepted risk, external SSD·iCloud `NOT_CONFIGURED / DEFERRED`와 offsite `PASS` 오표기 0
- 사용자 소유 기존 도메인 전략과 exact FQDN provisioning input 분리, authenticator private key·RP-side credential record·recovery-code secret을 분리한 WebAuthn/passkey 계약, 실제 매장 운영자 콘텐츠·사진 approval authority
- HomeOps 단일 authority, exact 임계값, stateless single restart allowlist·lock·30분 cooldown·금지 범위
- libheif `v1.23.1` exact commit, libde265 decoder-only와 x265 absence·SBOM·amd64/arm64 gate
- application source, Flyway, Dockerfile, Compose, Nginx, workflow와 script diff 0
- corrective exact-head Hosted CI Frontend·Backend·Compose Smoke 3/3 성공

문서 contract PASS 자체는 production runtime acceptance 증거가 아니다. D-IMP-1 decoder-only image는 별도의 final-image build·actual HEIC·SBOM·scan·amd64/arm64 gate로 검증하며, 그 성공도 production Compose, publisher provisioning, backup, HomeOps 또는 실제 Mac·iPhone production acceptance를 대신하지 않는다.

## 현재 D-IMP-2 production Compose·Nginx 자동 검증

- source contract test가 service inventory, external same-image backend/publisher, pinned web/PostgreSQL image, `build:`·`latest` 부재를 확인
- canonical base `/private/var/lib/rhaomi` source와 overlay-only temp source가 분리되고 host `/srv/rhaomi`, source checkout·Docker socket mount가 없음을 확인
- rendered JSON에서 web-only `127.0.0.1` port와 전용 `loopback-edge`, 세 service internal network adjacency, bind target/mode, project-scoped PostgreSQL volume name과 cleanup label 확인
- profile opt-in non-web migration task로 PostgreSQL 18.6·Flyway V1~V9를 적용하고 Flyway-disabled schema task로 재검증한 뒤 normal backend/publisher의 Flyway·bootstrap 비활성 확인
- actual container inspect의 `PortBindings`, `NetworkSettings`, `Mounts`와 web public RO, backend media RW, publisher public/state/lock RW·media RO probe
- static home/admin·immutable asset, anonymous admin upstream, `Secure` session cookie, build/internal/actuator/manifest/dot/unknown 404와 internal valid Bearer authentication 확인
- external형 Host로 `/admin`을 요청해 `308`, exact `Location: /admin/`, internal `http://`·`:8080`·validation loopback port 부재를 확인하고 runtime Nginx config의 fixed `X-Forwarded-Proto: https`·`X-Forwarded-Port: 443`을 확인
- `/admin/`, `/_next/static/`, `/generated/media/` 아래 실제 dot-prefixed fixture가 존재해도 404인지 확인하고 synthetic query-bearing Referer marker가 actual web access log에 남지 않는지 확인
- synthetic sentinel을 기록하고 일반 Compose `down` 뒤 같은 task volume identity로 다시 `up`해 row·Flyway history 불변 확인
- pre-existing volume/image 보존, task container/network 0과 task volume retained evidence 확인; volume/image delete·prune 없음
- Mac mini Docker Desktop Linux arm64와 Hosted Backend Linux amd64가 same entrypoint를 exact HEAD production image로 실행

이 검증은 actual `/private/var/lib/rhaomi` ownership·permission, production named volume·Secret·FQDN·Cloudflare·private GHCR/deploy·production migration 증거가 아니다. 해당 항목은 production readiness에서 계속 미완료다.

## 현재 D-IMP-3 production release·migration gate 자동 검증

- production workflow의 `workflow_dispatch`-only trigger와 모든 release job의 `refs/heads/main`·requested exact SHA gate, PR/push/schedule/workflow-run trigger 0
- validation `contents: read`, publish job만 `packages: write`, deploy job만 `environment: production`과 environment secret을 사용하는 권한 분리
- canonical production Dockerfile, amd64+arm64, exact SHA tag·existing-tag overwrite 거부, returned manifest digest, OCI source/revision, SBOM·provenance·scan evidence와 `latest` 0
- pinned Tailscale action/binary checksum, strict SSH host authority, fixed remote executable과 세 scalar argv; heredoc·free-form shell·credential argv 0
- Java one-shot mode의 exact/unknown/duplicate/publisher-mode mutual exclusion, `WebApplicationType.NONE`, controller·admin bootstrap·publisher loop 0
- actual PostgreSQL 18.6 clean schema의 migration V1~V9·JPA validate 성공, Flyway-disabled schema validate 성공, empty schema 실패
- production Compose default four-service 불변, `production-task` profile의 same-image `migration`·`schema-validate`, data network only·port/mount 0·restart 0
- fixed Mac entrypoint의 lower-case SHA·fixed GHCR digest·SBOM strict input, fixed root/config/Docker credential, owner/mode, release-bound backup gate, atomic mkdir lock·own-token cleanup
- exact digest pull·RepoDigest·OCI revision을 writer stop 전 검증하고 backend/publisher `exited` 후에만 migration→schema validation 실행
- task fake Docker의 command ordering, public-static-during-maintenance Compose regression, concurrent lock 거부, migration/schema failure 후 writer auto-resume 0, runtime backend/publisher image ID 일치
- wrong registry·malformed digest·duplicate option·OCI revision mismatch의 mutation 전 fail, synthetic DB/build marker의 output/evidence 노출 0, destructive volume/image command 0
- Mac mini native Linux arm64 D-IMP-1 image·D-IMP-2 Compose·D-IMP-3 task validator와 Hosted Linux amd64 동일 source/entrypoint 실행

이 검증은 synthetic/task-scoped source acceptance다. actual GHCR push, GitHub Environment·reviewer·secret 설정, Tailscale production 연결, Mac `/private/var/lib/rhaomi` installation, production volume/data/backup gate·migration·deploy를 수행하지 않는다.

## 현재 Phase 1C-8f4 transformer 자동 검증

- strict snapshot exact key·schema·semantic·published/time/relation/media manifest와 typed safe error
- Breed nullable description과 Service required description의 canonical/nonblank 재검증, backend에 없는 transformer 전용 길이 제한 부재
- Admin API published Breed description과 Build API snapshot의 U+00A0·U+2007·U+202F exact 보존, 동일 snapshot transformer acceptance
- Breed·Service·Notice의 Java `String.strip()`/UTF-16 length, Shop/Gallery의 Unicode-space strip, Shop code-point와 Gallery final UTF-16 length 차이, U+FEFF 보존 회귀
- distinct media fetch-once와 source canonical order 보존, derivative manifest deterministic ordering
- JPEG·PNG signature/decode·size/pixel/single-image/APNG 제한과 corrupt/type mismatch
- EXIF/GPS/XMP·orientation을 넣은 synthetic source의 rotate·sRGB·metadata strip
- Gallery card·large·Hero AVIF·WebP·JPEG width, no upscale, output decode·format·metadata 재검증
- output byte SHA-256 filename, duplicate byte dedupe와 byte-for-byte repeated-run determinism
- temp sibling failure cleanup, existing successful target preservation과 filesystem output failure
- CLI success와 path·UUID·decoder detail 없는 fixed failure output
- Linux amd64 Hosted CI와 Linux arm64 Mac Compose의 actual Sharp transform
- default backend/postgres와 frontend profile service 집합에 publisher 자동 추가 부재, normal backend HTTP 회귀
- explicit publisher mode의 Java 25 non-web startup과 controller/port 부재

## 현재 Phase 1C-8f6 Build API adapter·staging orchestration 자동 검증

- absolute root http/https URL, exact lowercase-hex credential와 positive Java long의 request 전 fail-closed validation
- exact Bearer·generation query, no redirect/cookie, snapshot/media body까지 bounded timeout
- raw JSON V2 strict parse, future/unknown/V1 schema·field·numeric revision/generation과 generation mismatch safe failure, canonical int64 malformed/overflow 경계, 10,001-char·Java Unicode whitespace 회귀
- 401/403, 409, 422, 429/5xx/timeout, malformed JSON·unexpected snapshot MIME의 terminal/transient/generation category
- manifest membership과 UUID별 concurrent duplicate fetch 1회, exact MIME·Content-Length·body length
- media 404/503와 corrupt actual byte의 transport/deterministic transformer category 분리
- synthetic loopback HTTP JPEG/PNG → existing transformer → atomic staging의 canonical string revision/generation·generatedAt·decode/hash/metadata 검증
- raw HTTP `9007199254740993` → Node parser → V2 `content.json`·`media-manifest.json`과 machine CLI의 exact string 보존
- CLI environment-only credential, fixed argv, safe one-line JSON과 exit `0/20/21/22`, secret/URL/path/UUID/stack leak 0
- Linux amd64 Hosted와 Linux arm64 Mac Compose Node 24/Sharp 실행
- 8f6 staging-only CLI 실행 시 publication DB state, Flyway V1~V9, default Compose와 public/admin route 불변; 8f7 actual executor full gate는 아래 별도 suite에서 검증

## 현재 Phase 1C-8f7 정적 render·release 단위·통합 자동 검증

- generated V2 exact loader, 홈 Notice별 title·optional summary·full `time[datetime]`·detail link, 공지 상세·sitemap·robots Static Export와 backend runtime request 부재
- URL/phone/source Markdown의 raw HTML escaping, link protocol allowlist, remote image alt-only와 exported dangerous URL 검사
- slug/canonical·JSON-LD·admin noindex·alt·responsive `<picture>` 및 generated media hash/missing/orphan 검증
- symlink·special file·private URL/path·credential marker와 broken internal link fail-closed
- private manifest의 canonical string revision/generation, valid leap-day와 1~6 fractional second를 허용하고 overflow date/time을 거부하는 strict `generatedAt`, code identity·site tree digest 일치
- release root의 one-direct-child package confinement, exact-parent current/previous와 sibling/absolute/out-of-root 거부, validation 뒤와 switch 직전 `BigInt` stale guard, equal/lower no-op, immutable collision과 `Long.MAX_VALUE`
- candidate loopback home/notice/media/404 pre-switch smoke, previous/current atomic symlink 뒤 동일 post-switch smoke, existing/first release rollback, 실패 candidate cleanup과 smoke 성공 뒤에만 실행되는 retention 보호, post-switch housekeeping 실패의 `DEFERRED` success 분리
- actual Java process executor의 fixed argv/allowlist environment, safe machine result, root·descendant physical termination과 lock lifetime
- PostgreSQL 18.6·Flyway V1~V9 active generation의 Build API→Sharp→Next→release→DB state success, higher/previous, lower no-op, transient same-generation retry, terminal current 유지

다음은 여전히 후속 운영 통합 범위다.

- future Gallery publish·archive stale event의 production acceptance
- 승인된 manual rebuild/retry의 새 generation과 실제 production code image/digest

## 현재 Phase 1C-8f8 local publication acceptance

- test/local bootstrap과 same-origin internal Nginx의 CSRF→login→fresh CSRF→me, actual multipart JPEG/PNG/HEIC upload와 Admin create/full PUT
- synthetic Shop 26-field·nullable CTA·Hero/Groomer/OG relation, published/draft/archived Breed·Service·Gallery·Notice dataset
- first release의 exact Shop/service/gallery/notice/location, future/draft/archived 비노출, V2 content와 private release manifest revision/generation/generatedAt 일치
- draft Gallery full PUT의 revision-only·event/generation/current 불변, published Service price full PUT의 higher generation/current·old previous·exact HTML 반영
- published Gallery archive 후 card/alt binding 제거와 unrelated public content 유지
- `Asia/Seoul` offset input·UTC Instant 일치, future Notice publish·expiry의 mutation 없는 release, publisher gap 후 overdue recovery
- rescheduled old boundary의 generation 없는 `STALE_TRIGGER`, close publish/expiry의 fixed `T0 + 30s` highest-generation coalesce·final snapshot
- generated AVIF/WebP/JPEG profile, no-upscale width, output SHA-256 filename/byte, alt, HEIC→JPEG master regression과 private UUID/path/backend marker 비노출
- backend·PostgreSQL·Admin gateway 중단 후 read-only `current` Nginx의 home/notice/media/robots/sitemap 200, unknown/manifest/admin/build/internal/actuator 404
- static HTML의 `lang=ko`, main, heading, image alt, link href, Notice `time[datetime]`, safe Markdown와 SEO contract
- `.env.example`, tmpfs DB, marker temp root, task-only internal network·required cleanup label을 사용하고 일반 `down` 후 task container/network 0; Docker volume/image delete 없음
- `scripts/validate-local-publication-acceptance.sh`를 로컬 Mac mini Linux arm64와 기존 Hosted Compose Smoke Linux amd64에서 동일하게 실행

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
- actual current main SHA·published image digest·provisioned manual Environment approval과 Mac에 설치된 fixed deploy entrypoint
- actual backup eligibility·write maintenance·one-shot Flyway·schema validate와 expand/contract compatibility
- immediate/due publishing event·overdue recovery·service auth·debounce·lock
- failed build does not switch
- atomic switch와 rollback
- 실제 Mac canonical directory 생성·ownership·permission과 public/media/state bind mount smoke
- PostgreSQL named volume의 container restart·일반 Compose `down`·`up` persistence, destructive volume command 부재
- `publishGeneration` stale build ordering과 manifest의 `contentRevision`·`publishGeneration`·`generatedAt`
- backend/PostgreSQL 중단 중 공개 site 유지
- `/api/build/**`, `/internal/**`, `/actuator/**` public deny
- successful release 5개와 current/previous retention, failed artifact 7일
- protected source와 분리된 Mac mini local backup set, manifest/check와 retention·prune post-check
- isolated restore의 manifest·DB·media·static build와 local RPO·RTO, single-host loss accepted risk evidence
- raw PGDATA volume을 required restic input으로 사용하지 않고 `pg_dump -Fc`를 새 isolated named volume에 `pg_restore`
- password 위 WebAuthn/passkey의 authenticator private key server 비수집·RP-side credential ID/public key/필요 metadata, registration revoke/remove, recovery-code secret 무효화·rotation과 password-only production 차단
- 사용자 소유 기존 도메인의 exact FQDN·canonical/OG/sitemap/robots/public HTTPS smoke
- 실제 매장 운영자의 NAP·정책·문구·링크·사진·게시 권한 승인
- HomeOps synthetic/internal/container/host/DB/publisher/backup threshold·alert
- stateless web/backend single restart와 deploy/backup lock·30분 cooldown·audit
- decoder-only image x265 absence와 Linux amd64·Mac mini Linux arm64 actual HEIC

외장 SSD·iCloud 3-2-1, recovery key와 fresh retrieval은 초기 production 이후 future hardening test다. 도입 전에는 미실행 상태를 실패나 성공으로 오기록하지 않는다.

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
- Backend PostgreSQL/auth/media contract를 exact Java 25 development image에서 실행하고 canonical production decoder-only image build·actual HEIC·SBOM·scan을 같은 job에서 추가 검증
- Compose Smoke의 same-origin auth·HEIC 정규화·media volume restart persistence
- diff·secret·문서 link 검사

Release는 실제 build snapshot, image pipeline, E2E, SEO, Nginx preview, actual device와 rollback evidence를 추가한다.
