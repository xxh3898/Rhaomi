---
title: "API·빌드 계약"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-30"
review_trigger: "관리 API·build 입력 변경 시"
---

# API·빌드 계약

## 공개 사이트 runtime 계약

공개 고객 브라우저는 Spring Boot API나 PostgreSQL을 호출하지 않는다. 공개 release는 Static Export 결과만 제공한다.

## 현재 관리자 인증 API

| method | path | anonymous | CSRF | 응답 |
|---|---|---:|---:|---|
| `GET` | `/api/admin/auth/csrf` | 허용 | N/A | header name, parameter name, token |
| `POST` | `/api/admin/auth/login` | 허용 | 필수 | id, email, role |
| `GET` | `/api/admin/auth/me` | 거부 | N/A | id, email, role |
| `POST` | `/api/admin/auth/logout` | 거부 | 필수 | `204 No Content` |

- 인증은 server session에 저장한다.
- login 실패는 잘못된 password, 없는 email과 inactive account를 같은 401 계약으로 처리한다.
- 인증 service 또는 repository 장애는 내부 원인을 노출하지 않는 503 `AUTH_SERVICE_UNAVAILABLE`로 처리한다.
- login password는 UTF-8 최대 72 byte이며 초과 입력은 credential 비교 전에 400 `INVALID_REQUEST`로 거부한다.
- request/response와 인증 완료 principal·저장된 `SecurityContext`에 `password_hash`를 포함하지 않는다.
- `/api/admin/**`는 위 anonymous 예외 외 인증이 기본이다.
- 아직 설계하지 않은 `/api/**`는 deny한다.
- 세 anonymous endpoint 외 non-API path와 미허용 Actuator path를 포함한 모든 request는 deny한다.

## 현재 콘텐츠·매장정보 관리 API

모든 endpoint는 관리자 session 인증이 필요하다. `POST`와 `PUT`은 유효한 CSRF token이 있어야 한다.

| method | path | 용도 | 성공 응답 |
|---|---|---|---|
| `GET` | `/api/admin/breeds` | 전체 상태 견종 목록 | `200 OK` |
| `GET` | `/api/admin/breeds/{id}` | 견종 단건 | `200 OK` |
| `POST` | `/api/admin/breeds` | `draft` 견종 생성 | `201 Created` |
| `PUT` | `/api/admin/breeds/{id}` | 견종 전체 mutable field 수정·상태 전환 | `200 OK` |
| `GET` | `/api/admin/services` | 전체 상태 서비스 목록 | `200 OK` |
| `GET` | `/api/admin/services/{id}` | 서비스 단건 | `200 OK` |
| `POST` | `/api/admin/services` | `draft` 서비스 생성 | `201 Created` |
| `PUT` | `/api/admin/services/{id}` | 서비스 전체 mutable field 수정·상태 전환 | `200 OK` |
| `GET` | `/api/admin/notices` | 모든 상태·시각의 공지 목록 | `200 OK` |
| `GET` | `/api/admin/notices/{id}` | 공지 단건 | `200 OK` |
| `POST` | `/api/admin/notices` | `draft` 공지 생성 | `201 Created` |
| `PUT` | `/api/admin/notices/{id}` | 공지 전체 mutable field 수정·상태 전환 | `200 OK` |
| `GET` | `/api/admin/gallery-items` | 모든 상태 갤러리 목록 | `200 OK` |
| `GET` | `/api/admin/gallery-items/{id}` | 갤러리 단건 | `200 OK` |
| `POST` | `/api/admin/gallery-items` | `draft` 갤러리 생성 | `201 Created` |
| `PUT` | `/api/admin/gallery-items/{id}` | 갤러리 전체 mutable field 수정·상태 전환 | `200 OK` |
| `GET` | `/api/admin/shop-settings` | 현재 매장정보 singleton 조회 | `200 OK` |
| `PUT` | `/api/admin/shop-settings` | 전체 매장정보 최초 생성 또는 갱신 | 최초 `201`, 이후 `200` |

- 견종·서비스 생성 request allowlist는 name, slug, description, 선택형 priceText, sortOrder만 허용하며 status는 받지 않는다.
- 견종·서비스 수정 request allowlist는 status, name, description, 선택형 priceText, sortOrder만 허용하며 slug는 받지 않는다.
- 모든 관리자 request DTO는 id, createdAt, updatedAt, createdBy, updatedBy와 unknown/system field를 거부한다.
- slug는 `^[a-z0-9]+(?:-[a-z0-9]+)*$` 형식이며 unique이고 생성 후 변경할 수 없다.
- 견종·서비스 목록은 `sort_order ASC, name ASC, id ASC`로 정렬한다.
- 공지 목록은 `pinned DESC, published_at DESC NULLS LAST, updated_at DESC, id ASC`로 정렬하며 모든 상태와 미래·만료 공지를 포함한다.
- `PATCH`, `DELETE`, anonymous/public read endpoint는 제공하지 않는다.
- application service는 actor를 `AdminPrincipal.id`에서만 받고 생성 시 created_by·updated_by, 수정 시 updated_by를 기록한다.

공지 생성 allowlist는 title, slug, summary, bodyMarkdown, pinned, publishedAt, expiresAt이고 status를 받지 않는다. pinned 누락·null은 false다. 수정 allowlist는 status, title, summary, bodyMarkdown, pinned, publishedAt, expiresAt이며 slug를 받지 않는다. title·slug·summary·body의 최대 길이는 각각 200·160·300·50,000자다. 시간은 ISO-8601 offset/UTC로 받고 malformed timestamp를 거부한다. 게시·만료 시각은 create/update 모두 microsecond 정밀도로 절삭한 뒤 최종 값으로 기간을 검증하고 response에 반환한다. 정규화 후 같은 시각은 `422 NOTICE_WINDOW_INVALID`, 정확히 1µs 차이는 성공이다.

오류 응답은 request·unknown field·slug·길이·timestamp·malformed UUID 오류 `400 INVALID_REQUEST`, 없는 id `404 CONTENT_NOT_FOUND`, 중복 slug `409 SLUG_CONFLICT`, 게시 필수값 부족 `422 PUBLISH_VALIDATION_FAILED`, 공지 기간 위반 `422 NOTICE_WINDOW_INVALID`를 사용한다. malformed UUID를 포함한 request 오류와 DB 장애의 내부 exception detail은 response에 포함하지 않는다. anonymous는 `401`, CSRF 실패는 `403`이며 DB 장애는 generic `5xx`를 사용한다.

서비스가 `published`가 되려면 name·slug·description·priceText가 모두 유효해야 한다. 검증 실패는 기존 row를 부분 변경하지 않는다. `archived` row는 삭제하지 않고 유효한 전체 값으로 `draft`나 `published`로 복구할 수 있다.

현재 `/admin/` 견종·서비스 client는 위 계약을 다음처럼 소비한다.

- 두 도메인의 API adapter와 response validator를 명시적으로 분리하고 UUID·status·slug·sortOrder·Instant·audit UUID와 exact response key를 runtime에서 검증한다. storage/path/internal 추가 field와 malformed response는 성공으로 처리하지 않는다.
- 생성 form의 빈 sortOrder와 선택 text는 각각 `null`로 보내 backend default·정규화 authority를 유지한다. 수정 form은 slug를 전송하지 않고 status, name, nullable description, 서비스의 nullable priceText와 필수 sortOrder를 한 번의 full `PUT`으로 보낸다.
- list response의 배열 순서는 PostgreSQL `sort_order ASC, name ASC, id ASC` 결과를 전달한 backend가 authority이며 client는 locale comparator로 다시 정렬하지 않는다.
- create/update 성공 response는 해당 item의 canonical state로 먼저 적용하고, 즉시 GET list를 수행해 전체 canonical ordering을 다시 획득한다. 후속 GET 실패는 저장 실패로 바꾸지 않고 목록 순서 refresh 경고와 explicit refresh를 제공하며 mutation을 자동 재전송하지 않는다.
- mutation 시작 전에 이전 GET sequence를 무효화하고 post-mutation GET을 현재 generation authority로 삼아 stale GET이 최신 목록을 덮지 않게 한다.
- `CONTENT_NOT_FOUND`, `SLUG_CONFLICT`, `PUBLISH_VALIDATION_FAILED`만 allowlisted frontend 문구로 표시하고 backend raw message를 출력하지 않는다. 401은 session expiry로 위임하고 403·network·5xx mutation을 자동 재전송하지 않는다.
- archive는 reversible status로만 표현하며 `PATCH`, `DELETE`, public/build route를 client에 추가하지 않는다.

공지가 `published`가 되려면 title·immutable slug·bodyMarkdown·publishedAt이 유효해야 한다. expiresAt이 있으면 모든 상태에서 publishedAt이 존재하고 expiresAt이 그보다 늦어야 한다. 미래 publishedAt은 허용하고 만료만으로 status를 자동 변경하지 않는다. 검증 실패는 mutable field와 audit를 모두 보존한다.

현재 `/admin/` 공지 client는 response의 id, status, title, slug, summary, bodyMarkdown, pinned, publishedAt, expiresAt, createdAt, updatedAt, createdBy, updatedBy exact key를 검증한다. 목록 GET 배열을 재정렬하지 않고 create/update response를 해당 item canonical state로 먼저 적용한 뒤 GET list로 전체 ordering을 다시 받는다. create는 status를, update는 slug·id·actor·audit를 전송하지 않는다. `NOTICE_WINDOW_INVALID`를 별도 allowlisted 문구로 표시하되 backend raw detail을 사용하지 않으며 401은 session expiry, 403·network·5xx mutation은 자동 재전송하지 않는다. Gallery와 공유하는 local datetime helper가 unchanged backend microsecond Instant를 full PUT에 보존한다.

### 갤러리 collection

- create allowlist는 dogName, breedId, primaryServiceId, coverImageId, beforeImageId, afterImageId, summary, altText, featured, sortOrder, performedAt, publishedAt이고 status를 받지 않는다. featured 누락·null은 false, sortOrder 누락·null은 100이다.
- full PUT은 위 mutable field와 status를 모두 명시한다. nullable field도 key 생략을 허용하지 않으며 id·actor·audit·unknown/system field는 `400 INVALID_REQUEST`다.
- 문자열은 Unicode 양끝 whitespace를 제거하고 비면 null로 저장한다. dogName·summary·altText 최대 길이는 100·1000·300이고 sortOrder는 0 이상이다.
- performedAt·publishedAt은 ISO-8601 offset/UTC를 받고 application에서 microsecond로 절삭한 값으로 검증·저장·응답한다. 미래 값은 허용한다.
- 목록은 `featured DESC, sort_order ASC, published_at DESC NULLS LAST, id ASC`이며 모든 상태를 포함한다.
- draft·archived 최종 상태는 null이 아닌 breed·service·media row의 존재만 요구한다. published는 breed·primary service·cover·nonblank altText·publishedAt이 필수이고 breed/service `published`, cover와 선택한 before/after media `active`를 요구한다.
- cover는 before 또는 after와 같을 수 있지만 before와 after가 같으면 상태와 무관하게 `422 GALLERY_PUBLISH_INVALID`다. 관계 대상이 없거나 published 관계 상태가 유효하지 않으면 `422 GALLERY_RELATION_INVALID`다.
- response는 relation UUID만 반환하고 target 객체, storage key/path/hash와 raw media metadata를 embed하지 않는다.
- 없는 id는 `404 GALLERY_ITEM_NOT_FOUND`다. `PATCH`, `DELETE`, publish action, public `/api/gallery-items/**`, `/api/build/gallery-items/**`는 제공하지 않는다.
- 관계 대상의 후속 상태 변경은 gallery status·relation을 cascade하지 않는다. 후속 public snapshot이 publishedAt 도래, relation status와 master/파생 file 유효성을 다시 확인한다.
- 모든 validation·relation 실패는 mutation 전에 종료해 기존 row와 created/updated audit 전체를 보존한다. DB/repository 장애는 내부 constraint·path·SQL detail 없는 generic `5xx`다.

현재 `/admin/` 갤러리 client는 같은 계약을 다음처럼 소비한다.

- strict adapter는 response exact key, UUID·status·boolean·0 이상 sortOrder·nullable text·ISO Instant·audit를 검증하고 relation 객체나 storage metadata가 섞인 응답을 거부한다.
- 초기·명시적 목록 GET은 backend 배열을 재정렬하지 않고 그대로 사용한다. create/update 성공 response를 해당 item의 canonical state로 먼저 적용한 뒤 post-mutation GET으로 전체 server ordering을 다시 획득한다.
- POST는 status를 보내지 않고 빈 선택값과 sortOrder를 `null`로 보내 backend draft·default authority를 유지한다. PUT은 status와 nullable key를 포함한 mutable field 전체를 정확히 한 번 전송하며 id·actor·audit를 포함하지 않는다.
- breed·service·media catalog는 Gallery 목록과 독립적으로 로드한다. draft·archived 편집에서는 존재하는 모든 상태의 관계를 선택할 수 있고 published 목표에서는 게시된 breed/service와 active media만 유효하다고 안내하되 backend를 최종 authority로 유지한다.
- cover/before/after picker는 관계 control 바로 아래에 한 개만 열고 active·archived private media를 상태와 함께 표시한다. authenticated Blob preview의 object URL을 교체·unmount에서 revoke한다.
- performedAt·publishedAt은 local datetime input으로 편집한다. 변경하지 않은 canonical microsecond Instant는 full PUT에서 보존하고 변경값과 성공 response는 backend 정규화 결과를 canonical state로 사용한다.
- mutation 전 이전 GET sequence를 무효화하고 post-mutation GET을 현재 generation authority로 삼는다. 후속 GET 실패는 저장 실패로 바꾸지 않으며 warning·explicit refresh를 제공하고 mutation을 자동 재전송하지 않는다.
- `GALLERY_ITEM_NOT_FOUND`, `GALLERY_RELATION_INVALID`, `GALLERY_PUBLISH_INVALID`만 frontend-owned 문구로 표시하고 raw backend detail을 노출하지 않는다. 401은 session expiry로 위임하고 403·network·5xx는 자동 retry하지 않는다.

### 매장정보 singleton

- `shop_settings`에는 status와 공개 id가 없으며 URL에도 DB id를 사용하지 않는다.
- `GET`은 아직 row가 없으면 `404 SHOP_SETTINGS_NOT_FOUND`다.
- `PUT`은 shopName, regionLabel, businessType, phone, address, openingTime, closingTime, closedWeekday, parkingAvailable, parkingNote, heroTitle, heroDescription, groomerName, groomerIntro, reservationNotice, heroImageId, heroImageAltText, groomerImageId, groomerImageAltText, ogImageId와 여섯 URL만 받는 full representation이다.
- id, singletonKey, createdAt, updatedAt, createdBy, updatedBy와 unknown field는 `400 INVALID_REQUEST`로 거부한다.
- 필수 문자열은 Unicode whitespace를 제거한 뒤 nonblank와 각 길이를 검사하고, 선택 문자열은 같은 정규화 뒤 비면 null이다.
- openingTime·closingTime은 정확한 `HH:mm`이며 opening이 closing보다 빠르지 않으면 `422 BUSINESS_HOURS_INVALID`다. malformed time과 weekday는 `400 INVALID_REQUEST`다.
- phone은 7~32자이고 숫자·`+ - ( )`·일반 space만 허용하며 숫자를 최소 7개 포함해야 한다.
- URL은 null 또는 2048자 이하의 absolute HTTPS URL이어야 하며 host가 필요하고 userinfo·control 문자를 허용하지 않는다.
- Hero·프로필 image id와 alt는 함께 null이거나 함께 있어야 한다. alt는 Unicode 양끝 whitespace 제거, blank→null, 최대 300 code points이며 OG에는 alt field가 없다.
- 세 image id는 nullable이고 같은 media를 재사용할 수 있다. non-null 값은 모두 mutation 전에 존재·`active`를 확인하며 response는 scalar UUID와 alt만 반환하고 media entity·storage metadata를 embed하지 않는다.
- relation 대상이 나중에 archived돼도 저장값과 audit를 자동 변경하지 않는다. GET은 UUID를 그대로 반환하고 다음 PUT은 archived 관계 유지 시 422, null 제거나 active media 교체 시 성공한다.
- 최초 PUT은 created/updated audit에 현재 actor와 microsecond 시각을 기록한다. 후속 PUT은 created audit를 보존하고 updated audit만 갱신하며, 응답과 재조회는 같은 정밀도를 사용한다.
- 정상 재시도는 같은 row를 갱신하고 DB의 TRUE CHECK와 UNIQUE가 두 번째 singleton row를 최종 차단한다.
- `POST`, `PATCH`, `DELETE`, id 기반 endpoint, anonymous/public read, build API는 제공하지 않는다.

매장정보 오류는 invalid request `400 INVALID_REQUEST`, 미초기화 `404 SHOP_SETTINGS_NOT_FOUND`, 영업시간 순서 위반 `422 BUSINESS_HOURS_INVALID`, image/alt pair·missing/archived media `422 SHOP_MEDIA_RELATION_INVALID`를 사용한다. DB constraint·repository 장애는 schema나 exception detail을 노출하지 않는 generic `5xx`다. 모든 validation·relation 실패는 기존 row와 actor/audit를 포함한 전체 상태를 보존한다.

현재 `/admin/` shop settings client는 같은 계약을 다음처럼 소비한다.

- GET 404는 장애가 아니라 실제 값이 없는 미초기화 상태로 해석하고 code seed 없는 빈 full form을 제공한다.
- PUT body는 mutable key 26개를 모두 포함하고 nullable field는 `null`을 명시하며 createdAt·updatedAt·createdBy·updatedBy를 보내지 않는다.
- 200/201 response의 exact field shape를 검증한 뒤 backend가 정규화한 값을 form canonical state로 다시 사용한다.
- `INVALID_REQUEST`, `BUSINESS_HOURS_INVALID`, `SHOP_MEDIA_RELATION_INVALID`는 allowlisted frontend 문구로 표시하고 backend raw message를 출력하지 않는다.
- Hero·미용사·OG picker는 기존 `/api/admin/media` ordering과 authenticated content GET을 재사용한다. active asset만 새 선택 대상으로 제공하고 archived/missing 현재 relation은 clear 또는 active 교체 전까지 invalid/stale로 표시한다.
- save는 명시적 사용자 action마다 한 번만 수행하며 403/network/5xx에서 자동 재전송하지 않는다.

## 현재 private media 관리자 API

모든 endpoint는 관리자 session이 필요하고 `POST`·`PUT`은 CSRF를 강제한다.

| method | path | 용도 | 성공 응답 |
|---|---|---|---|
| `GET` | `/api/admin/media` | active·archived metadata 목록 | `200 OK` |
| `GET` | `/api/admin/media/{id}` | metadata 단건 | `200 OK` |
| `GET` | `/api/admin/media/{id}/content` | private canonical master | `200 OK` |
| `POST` | `/api/admin/media` | multipart `file` upload, 항상 active | `201 Created` |
| `PUT` | `/api/admin/media/{id}` | `active | archived` status 변경 | `200 OK` |

- 목록은 `created_at DESC, id ASC`이며 archived도 관리자 조회·content 확인이 가능하다.
- content는 저장 `Content-Type`·정확한 `Content-Length`, `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`를 사용한다.
- upload는 client MIME·확장자·파일명이 아닌 실제 signature/container와 decoder를 기준으로 JPEG·PNG·HEIC·HEIF를 판정한다. 빈 MIME·`application/octet-stream`·filename 없음은 실제 byte가 유효하면 허용하고 구체적 충돌은 거부한다. HEIC still brand `heic | heix | heim | heis`는 major 또는 compatible brand에 있으면 HEIC 후보로 분류한다. HEIC sequence brand는 `hevc | hevx | hevm | hevs`, generic HEIF sequence brand는 `msf1`, AVIF brand는 `avif | avis`다.
- brand 우선순위는 AVIF 415 거부 → sequence 422 거부 → major/compatible HEIC still 인식 → 나머지 major `mif1` generic HEIF 인식 → unsupported 415 거부다.
- JPEG·PNG는 검증 원본 byte, HEIC·HEIF는 orientation 적용·sRGB 변환·metadata 제거 뒤 quality 92 JPEG master로 저장한다.
- source 20 MiB, stored 30 MiB, 폭·높이 12,000px, 총 60MP 제한을 application과 DB 역할에 맞게 강제한다.
- response는 id, status, source/stored content type, source/stored byte size, display dimension과 actor/audit만 반환한다. original filename, storage key, filesystem path, extension, SHA-256은 반환하지 않는다.
- update DTO는 status 하나만 허용한다. `PATCH`, `DELETE`, public read와 `/api/build/**` media endpoint는 없다.
- validation·normalization 실패 시 temp·final·DB orphan을 남기지 않고, DB rollback/commit 실패 시 이동한 final master를 transaction completion에서 제거한다.

오류는 missing/empty/malformed/unknown field `400 INVALID_REQUEST`, source 초과 `413 MEDIA_TOO_LARGE`, unsupported byte·AVIF 또는 명시 MIME·extension 충돌 `415 MEDIA_TYPE_UNSUPPORTED`, 손상·decode 불가·APNG·multi-image/sequence·dimension/pixel/output limit `422 MEDIA_INVALID_IMAGE`, 없는 id `404 MEDIA_NOT_FOUND`, codec unavailable `503 MEDIA_PROCESSOR_UNAVAILABLE`를 사용한다. `heim | heis`는 format detector에서 HEIC still로 인식하며 현재 decoder가 처리하지 못하면 unsupported가 아니라 `422 MEDIA_INVALID_IMAGE`로 종료한다. filesystem·DB 장애는 내부 path·constraint detail 없는 generic `5xx`다.

### 관리자 웹 소비 상태

- `/admin/` media manager는 위 endpoint를 상대경로로만 사용하며 API semantics와 backend validation authority를 변경하지 않는다.
- 목록은 query parameter 없이 전체 response의 server ordering을 유지하고 active/archived filter만 client-side view로 적용한다.
- private content는 authenticated no-store fetch와 JPEG/PNG content-type 검증 뒤 Blob object URL로 표시한다. public URL이나 storage path로 취급하지 않는다.
- upload는 `FormData`의 `file` part 하나를 사용하고 browser가 multipart boundary를 생성한다. 사용자 upload action 1회당 POST는 최대 1회다.
- status mutation은 status-only PUT이며 403·network·5xx·malformed response에서 자동 retry하거나 낙관 성공으로 확정하지 않는다.
- media request의 401은 in-memory CSRF와 dashboard state를 제거하고 login 화면으로 돌아간다. mutation 403은 CSRF를 폐기하고 다음 사용자 action에서만 fresh token을 준비한다.
- UI 오류 문구는 allowlisted status/code로 소유하며 backend raw message·exception detail을 출력하지 않는다.

## publisher event — planned

- 공개 영향 콘텐츠 transaction은 same-transaction immediate publishing event를 기록한다.
- notice create/update/publish transaction이 미래 `publishedAt` 또는 `expiresAt`을 만들면 같은 PostgreSQL transaction에 각 boundary의 durable scheduled event를 기록한다.
- scheduled event는 최소 event kind, `availableAt` 또는 동등한 `notBefore`, notice ID, event 생성 시점의 current notice/content revision과 boundary 값을 가진다.
- publisher는 immediate pending event와 `availableAt <= now` event를 처리하고 restart 뒤 overdue event를 다시 claim한다.
- 처리 시 event payload를 public authority로 사용하지 않고 current notice row와 전체 build snapshot을 다시 검증한다. reschedule, draft·archived 전환 또는 window 변경으로 stale이면 no-op하거나 최신 pending generation에 합친다.

## build API — planned

후속 Issue에서 [ADR-011](../09-decisions/ADR-011-transactional-outbox-static-publisher.md)에 따른 관리자 session과 분리된 internal namespace·service credential을 구현한다.

- 예: `/api/build/**`
- API-only service credential
- published 콘텐츠와 연결된 공개용 file metadata만 read
- create/update/delete/share 금지
- 관리자 cookie/session 재사용 금지
- credential은 single publisher에만 주입하고 `NEXT_PUBLIC_*` 금지
- public Nginx에서 `/api/build/**` 명시적 거부
- response에 `contentRevision`, target `publishGeneration`과 `generatedAt` 포함

현재 repository에는 build API나 build credential이 없다.

## 조회 범위 — planned

```text
shop_settings
services
breeds
gallery_items
notices
public media metadata
```

조회와 transformer는 모두 다음을 검증한다.

- `shop_settings` singleton 존재와 필수 매장정보 유효성
- non-null Hero·프로필·OG media가 active이고 private master·공개 파생 대상이 유효함
- collection은 `status = published`
- 갤러리는 `published_at <= build_time`
- 공지는 `published_at <= build_time`
- 공지는 `expires_at IS NULL OR expires_at > build_time`
- 갤러리 breed·service는 published이고 연결 media는 active
- 파일이 라오미펫 공개 콘텐츠에 연결됐고 공개 파생 대상임
- 정렬은 도메인 데이터 모델 기준

## content snapshot — planned

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-08-29T00:00:00Z",
  "contentRevision": 123,
  "publishGeneration": 128,
  "codeImageDigest": "sha256:<digest>",
  "shop": {},
  "services": [],
  "breeds": [],
  "galleryItems": [],
  "notices": []
}
```

- 모든 조회가 성공한 뒤 임시 file과 atomic rename으로 기록한다.
- `generatedAt`을 notice 게시·만료 eligibility의 build timestamp로 사용한다.
- `contentRevision`은 지원되는 콘텐츠 domain mutation snapshot의 monotonic revision이다. draft-only mutation에도 증가하지만 public trigger는 만들지 않으며, mutation이 없는 게시·만료 경계에서는 같은 값으로 새 snapshot을 만들 수 있다.
- `publishGeneration`은 immediate 콘텐츠 mutation, due 게시·만료 boundary, 승인된 code release와 manual rebuild/retry를 포함하는 public trigger의 monotonic sequence다.
- transient failure의 자동 attempt retry는 같은 generation을 유지하고 운영자가 승인한 새 rebuild/retry만 새 generation을 만든다.
- release manifest도 `contentRevision`, `publishGeneration`, `generatedAt`을 기록하고 `current` atomic switch는 `publishGeneration`을 stale protection authority로 사용한다.
- 일부 collection만 과거 data로 fallback하지 않는다.
- raw persistence/API response를 component에 직접 전달하지 않는다.
- runtime schema와 published/relation/file 조건을 transformer에서 다시 검증한다.
- scheduled event의 notice ID나 과거 boundary는 snapshot filter를 우회하는 입력으로 사용하지 않는다.

## 공개 media 파생 — planned

```text
backend-owned private canonical master
→ authenticated build-time download
→ MIME/signature/pixel 검증
→ metadata 제거와 최적화
→ content hash
→ /generated/media/<item-id>-<hash>-<width>.<format>
```

원본 id·storage path·내부 URL을 공개 HTML에 남기지 않는다.

## build 실패 조건 — planned

- build API 연결·인증 실패
- singleton 없음
- 필수 field 누락
- duplicate slug
- draft/archived/만료 콘텐츠 포함
- 관계 대상 없음 또는 비공개
- file scope·다운로드·decode 실패
- 지원하지 않는 image 형식
- 잘못된 외부 URL
- snapshot schema mismatch
- HTML sanitize 실패
- sitemap duplicate canonical
- target `publishGeneration`이 current generation 이하

## 호환성

계약이 바뀌면 API DTO, 도메인 데이터 모델, Flyway migration, transformer/test, 운영 data migration과 release evidence를 함께 갱신한다.
