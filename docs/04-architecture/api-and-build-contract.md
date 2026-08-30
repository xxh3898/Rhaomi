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
- update DTO는 status 하나만 허용한다. `PATCH`, `DELETE`와 anonymous public read는 없다. 별도 build principal의 `/api/build/media/{id}/content`는 current public relation scope만 읽으며 관리자 media endpoint 권한을 재사용하지 않는다.
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

## publication producer — current

- Flyway V8의 transactional singleton row가 지원 콘텐츠 mutation 성공 1회당 `contentRevision`을 정확히 한 번 증가시킨다. PostgreSQL sequence를 authority로 사용하지 않아 rollback된 mutation은 revision을 소비하지 않는다.
- ShopSettings 모든 PUT, published 진입·수정·이탈 Breed·Service·Notice·Gallery, Media archive/restore는 같은 revision의 `CONTENT_CHANGED`를 기록한다. draft-only create/update와 Media upload는 revision만 증가한다.
- Notice create/update가 non-null `publishedAt` 또는 `expiresAt`을 새로 설정·변경하면 status·현재 시각과 무관하게 해당 boundary의 typed scheduled event를 기록한다.
- Gallery가 published로 진입하거나 published 상태에서 non-null `publishedAt`을 변경하면 `GALLERY_PUBLISHED_AT_DUE`를 기록한다.
- scheduled event는 `sourceType`, `sourceId`, `contentRevision`, `availableAt`, `expectedBoundaryAt`을 typed column으로 보존하고 `availableAt = expectedBoundaryAt`을 DB constraint로 강제한다.
- reschedule·boundary 제거·draft/archive 전환 시 old row를 삭제하지 않는다. 후속 consumer가 current row와 snapshot을 다시 확인하는 stale no-op 계약을 유지한다.
- content·revision·outbox insert는 같은 PostgreSQL transaction에서 commit/rollback된다. 새 HTTP endpoint, response field, credential이나 public route는 없다.

## publication claim·generation state — current

- Flyway V9은 `(1, 0)` transactional `publish_generation_state` singleton과 outbox의 `PENDING | PROCESSING | RETRY_WAIT | SUCCEEDED | NOOP | FAILED | COALESCED` 상태·고정 result code·state별 DB invariant를 추가한다.
- internal `PublicationStateService`는 HTTP나 scheduler 없이 immediate pending 또는 `availableAt <= now` row를 `(availableAt, id)` 순서와 `FOR UPDATE SKIP LOCKED`로 claim한다. fresh claim의 `publishGeneration` 할당과 첫 attempt는 같은 PostgreSQL transaction이며 rollback은 generation을 소비하지 않는다.
- scheduled claim은 current Notice·Gallery row의 `published` 상태와 expected boundary만 최소 검증한다. row가 없거나 draft·archived·rescheduled이면 generation 없이 `NOOP / STALE_TRIGGER`로 종료한다. relation·media·file과 build timestamp eligibility는 이 단계에서 복제하지 않는다.
- active lease는 owner·generation으로 갱신·완료를 guard한다. 만료 lease와 1분·5분·15분 transient retry는 같은 generation을 유지하며 총 attempt를 4회로 제한한다.
- lower active generation은 같은 owner가 claim한 실제 higher `PROCESSING` generation으로만 `COALESCED` 처리할 수 있다. 실제 30초 대기와 highest-generation 선택 orchestration은 아직 없다.
- typed status read model은 publication package 내부에서만 제공하며 새 HTTP endpoint·credential·환경 변수·background loop를 추가하지 않는다.

## publisher orchestration — planned

- single publisher는 current state service를 호출해 immediate pending, due scheduled, overdue lease와 due retry를 계속 처리한다.
- claim 뒤 current row가 바뀔 수 있으므로 event 값을 public authority로 사용하지 않고 전체 build snapshot의 status·게시/만료·relation·media/file 조건을 다시 검증한다.
- 첫 accepted trigger 뒤 실제 30초 debounce, highest-generation coalesce 선택, build·release·atomic switch와 운영 관제는 후속 Issue에서 구현한다.

## build API — current

[ADR-011](../09-decisions/ADR-011-transactional-outbox-static-publisher.md)에 따라 관리자 session과 분리된 internal namespace와 service credential을 구현했다.

```text
GET /api/build/snapshot?publishGeneration=<positive-long>
GET /api/build/media/{id}/content?publishGeneration=<positive-long>
Authorization: Bearer <64 lowercase hex token>
```

- `RHAOMI_BUILD_SERVICE_TOKEN`은 256-bit random token의 lowercase hex 표현인 `^[0-9a-f]{64}$`만 허용한다. test 외 실제 값은 repository에 기록하지 않는다.
- 정확히 하나의 `Authorization` header와 exact `Bearer ` scheme만 받고 유효 형식 token은 timing-safe 비교한다. raw token·header·hash를 log, response, error에 넣지 않는다.
- build principal·SecurityFilterChain은 관리자 principal·session·CSRF와 분리되며 stateless, null security-context repository와 null request cache를 사용한다.
- build token은 `/api/admin/**` 권한이 아니고 관리자 session은 `/api/build/**` 권한이 아니다. GET allowlist 밖 mutation·unknown route는 모두 거부한다.
- non-production에서 token이 비어 있거나 잘못되면 build API만 503 fail-closed이고, production profile은 application startup을 거부한다.
- credential은 후속 single publisher에만 주입하고 browser storage, `NEXT_PUBLIC_*`, URL에 넣지 않는다.
- dev/public Nginx는 `/api/build/**`를 일반 `/api/**` proxy보다 먼저 404로 거부한다.

## snapshot transaction과 generation gate — current

- request는 `publishGeneration`만 받는다. `generatedAt`, `contentRevision`, event ID는 backend가 외부 입력으로 받지 않는다.
- 요청 generation은 실제 outbox row의 `state = PROCESSING`, `leaseUntil > generatedAt`을 만족해야 한다. unknown·pending·retry-wait·terminal·expired lease는 409다.
- `generatedAt`은 UTC system clock에서 한 번 읽고 microsecond로 절삭한다.
- active generation 확인, current `contentRevision`, Shop·Breed·Service·Gallery·Notice와 relation/media/file 검증을 하나의 read-only PostgreSQL `REPEATABLE READ` transaction에서 수행한다.
- response `contentRevision`은 event가 생성될 때의 revision이 아니라 같은 database snapshot의 current singleton 값이다.
- build API는 generation 상태 확인만 하며 claim, lease renewal, attempt·terminal transition, revision/outbox/content/media mutation을 수행하지 않는다.

## build snapshot response v1 — current

```json
{
  "schemaVersion": 1,
  "contentRevision": 123,
  "publishGeneration": 128,
  "generatedAt": "2035-01-01T00:00:00.123456Z",
  "shop": {},
  "services": [],
  "breeds": [],
  "galleryItems": [],
  "notices": [],
  "mediaAssets": []
}
```

Build API response에는 `codeImageDigest`를 넣지 않는다. top-level과 item DTO는 다음 exact allowlist만 사용한다.

- Shop: `shopName`, `regionLabel`, `businessType`, `phone`, `address`, `openingTime`, `closingTime`, `closedWeekday`, `parkingAvailable`, `parkingNote`, `heroTitle`, `heroDescription`, `groomerName`, `groomerIntro`, `reservationNotice`, `heroImageId`, `heroImageAltText`, `groomerImageId`, `groomerImageAltText`, `ogImageId`, 여섯 HTTPS 외부 URL
- Breed: `id`, `name`, `slug`, `description`, `sortOrder`
- Service: `id`, `name`, `slug`, `description`, `priceText`, `sortOrder`
- Gallery: `id`, `dogName`, `breedId`, `primaryServiceId`, `coverImageId`, `beforeImageId`, `afterImageId`, `summary`, `altText`, `featured`, `sortOrder`, `performedAt`, `publishedAt`
- Notice: `id`, `title`, `slug`, `summary`, source `bodyMarkdown`, `pinned`, `publishedAt`, `expiresAt`
- Media: `id`, `contentType`, `byteSize`, `width`, `height`

audit actor/timestamp, status, storage key/path, extension, persisted SHA-256, source content type·filename, claim owner·lease·event ID, password/session/CSRF는 response에 없다.

조회 조건과 정렬은 다음과 같다.

- Shop singleton과 기존 NAP·phone·time·HTTPS URL·image/alt final-state가 유효해야 한다.
- Breed·Service는 `published`만 포함하고 `sortOrder ASC, name ASC, id ASC`다. published Service의 description·price final-state를 다시 검증한다.
- Gallery는 `published`이면서 `publishedAt <= generatedAt`만 포함하고 `featured DESC, sortOrder ASC, publishedAt DESC, id ASC`다. breed·service published, cover required, optional before/after active와 서로 다름, alt, 실제 file을 검증한다.
- Notice는 `publishedAt <= generatedAt < expiresAt` 또는 expiresAt null인 `published`만 포함하고 `pinned DESC, publishedAt DESC, updatedAt DESC, id ASC`다. Markdown은 변환하지 않은 source string이다.
- `mediaAssets`는 유효한 Shop/Gallery가 참조하는 active canonical master의 distinct union이며 `id ASC`다. 실제 file size·SHA를 검증한다.
- 명시적 relation이나 file이 missing·archived·corrupt면 해당 item만 생략하지 않고 전체 snapshot을 422로 거부한다.

## build media content — current

- media content 요청도 active generation과 live lease를 다시 확인한다.
- active media 가운데 current Shop Hero/Groomer/OG relation 또는 현재 시각에 공개 가능한 Gallery의 cover/before/after relation만 허용한다. Gallery breed·service도 published여야 한다.
- unlinked active upload, draft·archived·future Gallery-only relation, archived·missing media는 존재 여부를 구분하지 않는 404다.
- canonical master를 `verifiedContent()`로 실제 size·SHA 검증하고 `Content-Type`, exact `Content-Length`, `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`만 반환한다.
- Range, ETag, original filename, path, SHA header는 제공하지 않는다. snapshot 뒤 scope가 바뀌면 안전하게 거부하며 과거 file로 fallback하지 않는다.

## publisher content snapshot·release manifest — planned

후속 publisher가 Build API response를 승인된 production code image와 결합해 저장하는 최종 파일은 다음 정보를 포함한다.

```json
{
  "schemaVersion": 1,
  "generatedAt": "2035-01-01T00:00:00.123456Z",
  "contentRevision": 123,
  "publishGeneration": 128,
  "codeImageDigest": "sha256:<digest>",
  "shop": {},
  "services": [],
  "breeds": [],
  "galleryItems": [],
  "notices": [],
  "mediaAssets": []
}
```

- 모든 조회·transform이 성공한 뒤 임시 file과 atomic rename으로 기록한다.
- `contentRevision`은 지원되는 콘텐츠 domain mutation snapshot revision이고 `publishGeneration`은 immediate mutation, due boundary, 승인된 code release와 manual rebuild/retry를 포함하는 public trigger sequence다.
- 같은 `contentRevision`에서도 publish/expiry boundary별 generation과 snapshot을 만들 수 있다. 자동 attempt retry는 같은 generation을 유지한다.
- release manifest도 `contentRevision`, `publishGeneration`, `generatedAt`, 승인된 `codeImageDigest`를 기록하고 `current` atomic switch는 generation을 stale protection authority로 사용한다.
- 일부 collection만 과거 data로 fallback하거나 scheduled event ID·과거 boundary로 current snapshot filter를 우회하지 않는다.
- transformer는 API response schema와 published/relation/file 조건을 다시 검증하고 raw response를 component에 직접 전달하지 않는다.

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

## build API 오류 — current

```text
400 INVALID_REQUEST
401 BUILD_UNAUTHORIZED
403 BUILD_FORBIDDEN
404 BUILD_MEDIA_NOT_FOUND
409 BUILD_GENERATION_NOT_ACTIVE
422 BUILD_SNAPSHOT_INVALID
503 BUILD_SERVICE_UNAVAILABLE
503 BUILD_MEDIA_UNAVAILABLE
500 BUILD_INTERNAL_ERROR
```

고정 JSON은 SQL, constraint, filesystem path, token, private metadata나 내부 exception detail을 포함하지 않는다. partial snapshot 성공 response는 없다.

## transformer·release 실패 조건 — planned

- build API 연결·인증 실패
- snapshot schema mismatch 또는 build API가 거부한 invalid singleton/content/relation/file
- file download·decode·지원 형식 실패
- HTML sanitize·sitemap canonical·link·asset 검증 실패
- target `publishGeneration`이 current generation 이하

## 호환성

계약이 바뀌면 API DTO, 도메인 데이터 모델, Flyway migration, transformer/test, 운영 data migration과 release evidence를 함께 갱신한다.
