---
title: "API·빌드 계약"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-02"
review_trigger: "관리 API·build 입력 변경 시"
---

# API·빌드 계약

## 공개 사이트 runtime 계약

공개 고객 브라우저는 Spring Boot API나 PostgreSQL을 호출하지 않는다. 공개 release는 Static Export 결과만 제공한다.

## 현재 관리자 인증 API

| method | path | 최소 인증 단계 | CSRF | 응답 |
|---|---|---|---:|---|
| `GET` | `/api/admin/auth/csrf` | anonymous | N/A | header name, parameter name, token |
| `POST` | `/api/admin/auth/login` | anonymous | 필수 | id, email, role |
| `GET` | `/api/admin/auth/me` | first factor | N/A | id, email, role |
| `POST` | `/api/admin/auth/logout` | first factor | 필수 | `204 No Content` |
| `GET` | `/api/admin/auth/webauthn/status` | first factor | N/A | required, stage, active credential count, enrollment/recovery 상태 |
| `GET` | `/api/admin/auth/webauthn/registration/options` | 초기 0개면 first factor, 그 외 second factor | N/A | server challenge와 RP·user options |
| `POST` | `/api/admin/auth/webauthn/registration` | options와 같은 session/account | 필수 | 검증 뒤 second factor 상태 |
| `GET` | `/api/admin/auth/webauthn/authentication/options` | first factor | N/A | single-use assertion challenge |
| `POST` | `/api/admin/auth/webauthn/authentication` | options와 같은 session/account | 필수 | 검증 뒤 second factor 상태 |
| `POST` | `/api/admin/auth/recovery-codes/verify` | first factor | 필수 | restricted recovery rotation 상태 |
| `POST` | `/api/admin/auth/recovery-codes/rotate` | second factor 또는 recovery rotation required | 필수 | 한 번만 노출하는 새 recovery code set |
| `GET` | `/api/admin/auth/webauthn/credentials` | second factor | N/A | active credential의 secret-free metadata |
| `DELETE` | `/api/admin/auth/webauthn/credentials/{credentialId}` | second factor | 필수 | `204 No Content` |

- 인증은 server session에 `FIRST_FACTOR_VERIFIED`, `SECOND_FACTOR_VERIFIED`, `RECOVERY_ROTATION_REQUIRED` 중 하나로 저장한다. 콘텐츠·미디어를 포함한 business `/api/admin/**`는 second factor authority만 허용한다.
- login 실패는 잘못된 password, 없는 email과 inactive account를 같은 401 계약으로 처리한다.
- 인증 service 또는 repository 장애는 내부 원인을 노출하지 않는 503 `AUTH_SERVICE_UNAVAILABLE`로 처리한다.
- login password는 UTF-8 최대 72 byte이며 초과 입력은 credential 비교 전에 400 `INVALID_REQUEST`로 거부한다.
- request/response와 인증 완료 principal·저장된 `SecurityContext`에 `password_hash`를 포함하지 않는다.
- WebAuthn challenge는 최소 32 random byte이며 server session의 account·purpose에 결합하고 TTL 안에서 한 번만 사용한다. RP ID와 origin은 server 설정 authority이고 user verification은 required다.
- WebAuthn 등록이 0개인 초기 enrollment만 first factor에서 허용하며 active credential이 하나라도 있으면 추가 등록은 second factor가 필요하다. credential revoke는 마지막 usable factor를 제거하지 못한다.
- recovery code는 평문을 rotation response에 한 번만 반환하고 PostgreSQL에는 SHA-256 hash만 저장한다. 사용하면 같은 set 전체를 폐기하고 recovery rotation 전에는 business API를 허용하지 않는다.
- WebAuthn·recovery 성공은 session ID를 다시 회전하며 browser는 이전 CSRF를 폐기하고 fresh CSRF 준비 뒤에만 mutation-ready 상태가 된다.
- `/api/admin/**`는 위 ceremony/status/logout 예외 외 second factor 인증이 기본이다.
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
- 문자열은 Java `Character.isWhitespace() || Character.isSpaceChar()`인 양끝 code point를 제거하고 비면 null로 저장한다. dogName·summary·altText 최대 길이는 100·1000·300이고 sortOrder는 0 이상이다.
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
- 필수 문자열은 Java `Character.isWhitespace() || Character.isSpaceChar()`인 양끝 code point를 제거한 뒤 nonblank와 각 길이를 검사하고, 선택 문자열은 같은 정규화 뒤 비면 null이다.
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
- lower active generation은 같은 owner가 claim한 실제 higher `PROCESSING` generation으로만 `COALESCED` 처리할 수 있다. dedicated publisher control loop가 첫 accepted generation 기준 고정 30초 동안 이 primitive를 호출해 실제 highest generation으로 수렴한다.
- typed status read model은 publication package 내부에서만 제공하며 state service 자체는 HTTP endpoint·credential·환경 변수·background loop를 추가하지 않는다. 반복 실행 lifecycle은 별도 non-web publisher root가 소유한다.

## publisher orchestration — current full local/CI release data plane

- exact mode argument로 선택한 dedicated non-web publisher는 current state service를 호출해 immediate pending, due scheduled, overdue lease와 due retry를 계속 처리한다.
- 첫 accepted trigger의 `claimedAt`부터 `T0 + 30s`를 포함하는 fixed debounce를 적용하고 lower active generation을 highest live generation으로 즉시 coalesce한다. debounce와 executor 대기 중 lease를 갱신하고 container-side global advisory lock을 획득한 뒤에만 typed executor port를 호출한다. lease 상실·shutdown으로 cancellation을 요청해도 실제 executor body가 종료됐거나 시작 불가능하다는 acknowledgment 전에는 lock scope를 빠져나가지 않는다. `Future.cancel(true)`와 `Future.isDone()`은 이 acknowledgment가 아니다.
- normal backend에는 publisher loop·lifecycle bean이 없고 publisher root에는 controller·web server가 없다. exact opt-in publisher root만 actual Node release executor를 구성한다.
- claim 뒤 current row가 바뀔 수 있으므로 event 값을 public authority로 사용하지 않고 전체 build snapshot의 status·게시/만료·relation·media/file 조건을 다시 검증한다.
- actual Java executor는 fixed Node executable·script·generation argv와 allowlist environment로 Build API HTTP client, transformer, Next Static Export, strict export validator, private manifest, stale guard, immutable install, `previous/current` switch와 post-switch serving smoke를 한 번의 typed execution으로 연결한다. production image·secret·Mac path·관제 provisioning은 후속 운영 gate다.

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

## build snapshot response V2 — current

```json
{
  "schemaVersion": 2,
  "contentRevision": "9007199254740993",
  "publishGeneration": "9007199254740993",
  "generatedAt": "2035-01-01T00:00:00.123456Z",
  "shop": {},
  "services": [],
  "breeds": [],
  "galleryItems": [],
  "notices": [],
  "mediaAssets": []
}
```

PostgreSQL `BIGINT`와 Java internal `long` domain은 유지하고 HTTP DTO 경계에서만 `Long.toString(...)`으로 변환한다. `contentRevision`은 `0` 또는 `[1-9][0-9]*`이면서 `0..9223372036854775807`, `publishGeneration`은 `[1-9][0-9]*`이면서 `1..9223372036854775807`인 canonical decimal string이다. 빈 값, sign, 공백, leading zero, 소수·지수, 범위 초과와 JSON number는 V2에서 거부한다. query의 `publishGeneration=<positive-long decimal>`과 backend service의 `long` 계약은 바뀌지 않는다.

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

## build snapshot transformer — current

- transformer는 backend HTTP·credential이나 Spring DTO class에 의존하지 않고 JSON 값과 `MediaContentProvider` port를 입력으로 받는다.
- top-level·모든 entity exact key, `schemaVersion = 2`, canonical int64 decimal string revision/generation, canonical UUID·slug·microsecond Instant, backend/build API가 정의한 field별 문자열·URL·number limit과 Shop/media pair를 fail-closed로 검증한다. Breed·Service description은 canonical/nonblank 계약만 재검증하며 transformer 전용 길이 제한을 추가하지 않는다.
- revision/generation은 Node object와 `content.json`·`media-manifest.json`에서 string을 그대로 유지하고 범위·요청 equality·향후 stale ordering에만 `BigInt`를 사용한다. `Number`, `parseInt`, unary `+`로 변환하지 않는다.
- text acceptance는 ECMAScript `String.trim()`이나 `\S`를 공통 authority로 사용하지 않고 backend field family를 그대로 따른다. Breed·Service·Notice는 `ContentFields`의 Java `String.strip()`과 `BuildContentValidator`의 `String.isBlank()`·UTF-16 `String.length()`를 따른다. Shop은 `ShopSettingsValues`의 `Character.isWhitespace() || Character.isSpaceChar()` strip과 code-point length를 따르며, Gallery는 같은 strip 결과와 최종 Build API의 UTF-16 length를 모두 만족해야 한다. 따라서 U+00A0·U+2007·U+202F는 `ContentFields` text edge에서 보존될 수 있고 U+FEFF는 Shop/Gallery edge에서도 보존될 수 있다.
- published/time eligibility, Breed·Service·Gallery·Notice 관계와 before/after·alt, exact distinct media manifest를 독립적으로 다시 확인한다. unknown/missing field나 explicit invalid relation은 silent omission하지 않는다.
- Breed·Service는 snapshot의 canonical server order를 보존하고, media processing과 manifest는 media UUID·고정 profile·format·width 순서를 사용한다.
- `MediaContentProvider`는 distinct media UUID당 한 번 호출한다. provider 결과의 content type, JPEG·PNG signature/decode, byte·dimension·pixel·single-image 조건을 manifest와 다시 대조한다.
- orientation 적용·sRGB·metadata 제거 뒤 Gallery card `360/640/960`, Gallery large `768/1200/1600`, Hero `768/1280/1920`의 no-upscale AVIF·WebP·JPEG를 생성한다. 미용사·OG는 최종 layout 결정 전 최대 1200 JPEG fallback을 사용한다.
- output도 decode·format·metadata를 다시 확인하고 encoded byte SHA-256을 filename으로 사용한다. 동일 byte는 하나의 public file로 deduplicate한다.
- `GeneratedContentV2`의 `src/generated/content.json`과 `PublicMediaManifestV2`의 `src/generated/media-manifest.json`은 모두 `schemaVersion = 2`와 fetched snapshot의 canonical decimal string revision/generation을 byte-for-byte 보존한다. `public/generated/media`와 함께 target sibling temp에서 모두 만든 뒤 새 target으로 rename한다. target이 이미 존재하거나 어느 단계든 실패하면 기존 target을 교체하지 않고 partial temp를 제거한다.
- `SNAPSHOT_INVALID`, `MEDIA_NOT_FOUND`, `MEDIA_INVALID`, `MEDIA_TRANSFORM_FAILED`, `OUTPUT_FAILED`의 fixed code/message만 호출 경계에 제공한다. UUID·path·decoder/exception detail을 포함하지 않는다.
- filesystem CLI는 `<media-root>/<uuid>.jpg|png` fixture adapter일 뿐 build API HTTP client나 production publisher가 아니다.

## Build API adapter·staging orchestration — current

- `BUILD_API_INTERNAL_URL`은 root absolute `http|https` origin만, `BUILD_API_CREDENTIAL`은 exact 64자 lowercase hex만 request 전에 허용한다. userinfo/query/fragment/path, credential argv/query/path와 `NEXT_PUBLIC_*`는 거부한다.
- snapshot은 redirect를 따르지 않는 bounded `GET /api/build/snapshot?publishGeneration=<positive-long>`의 `200 application/json` 응답만 허용하고 raw JSON을 unknown field 제거 없이 `parseBuildSnapshotV2()`에 직접 전달한다. parsed canonical string generation은 요청 `BigInt`와 exact 일치해야 한다.
- parsed manifest를 authority로 `HttpMediaContentProvider`를 만들고 manifest 밖 UUID를 network 전에 거부한다. UUID별 in-flight/success/failure Promise를 memoize해 중복 relation과 concurrent `get()`도 실제 HTTP request 최대 1회다.
- media response는 HTTP 200, manifest와 exact `Content-Type`, canonical `Content-Length`와 실제 body length를 모두 만족해야 한다. canonical raw byte는 durable input file 없이 memory cache에서 기존 transformer로 전달한다.
- fixed runtime timeout은 snapshot/media headers와 body 완료까지 10초이며 redirect, 401/403, 409, 422, 429/5xx, malformed 2xx와 transformer code를 `TERMINAL | TRANSIENT | GENERATION`의 safe category로 분리한다. `BUILD_OUTPUT_FAILED`는 이번 staging-only 경계에서 terminal이다.
- process entrypoint는 `--publish-generation`과 `--output`만 argv로 받고 safe one-line JSON과 exit `0 | 20 | 21 | 22`만 제공한다. token·Authorization·internal URL/path·media UUID·raw response/stack은 출력하지 않는다.
- orchestration은 manifest media를 cache에 먼저 채워 HTTP retry category를 보존한 뒤 기존 transformer를 호출하고 configurable private target에 atomic staging을 만든다. `PublicationStagingResult`와 machine JSON CLI의 `contentRevision`, `publishGeneration`도 fetched snapshot의 canonical string을 그대로 보존한다.
- 이 staging 성공 자체는 public publication `SUCCESS`나 `NO_PUBLIC_CHANGE`가 아니다. full release entrypoint가 동일 결과를 Next render·검증·manifest·switch까지 전달한 뒤에만 Java executor가 publication state를 완료한다.

## publisher content snapshot·release manifest — current

transformer가 만든 `content.json`은 Build Snapshot V2의 public content를 유지하고, release package root의 private `release-manifest.json`은 정적 site와 code identity를 결합한다. production에서는 승인된 image metadata를 주입하고 local/CI에서는 contract-valid synthetic metadata만 사용한다.

```json
{
  "schemaVersion": 2,
  "generatedAt": "2035-01-01T00:00:00.123456Z",
  "contentRevision": "9007199254740993",
  "publishGeneration": "9007199254740993",
  "shop": {},
  "services": [],
  "breeds": [],
  "galleryItems": [],
  "notices": [],
  "mediaAssets": []
}
```

```json
{
  "schemaVersion": 1,
  "releaseId": "g-9007199254740993.r-9007199254740993.c-0123456789ab",
  "contentRevision": "9007199254740993",
  "publishGeneration": "9007199254740993",
  "generatedAt": "2035-01-01T00:00:00.123456Z",
  "codeSha": "0123456789abcdef0123456789abcdef01234567",
  "codeImageTag": "sha-0123456789abcdef0123456789abcdef01234567",
  "codeImageDigest": "sha256:<64 lowercase hex>",
  "flywayVersion": "10",
  "sbomReference": "sha256:<64 lowercase hex>",
  "siteSha256": "<64 lowercase hex>"
}
```

- 모든 조회·transform·Next export와 candidate validation이 성공한 뒤 manifest를 기록하고 complete candidate를 release root 안에서 atomic rename한다.
- `contentRevision`은 지원되는 콘텐츠 domain mutation snapshot revision이고 `publishGeneration`은 immediate mutation, due boundary, 승인된 code release와 manual rebuild/retry를 포함하는 public trigger sequence다.
- 같은 `contentRevision`에서도 publish/expiry boundary별 generation과 snapshot을 만들 수 있다. 자동 attempt retry는 같은 generation을 유지한다.
- release manifest도 canonical decimal string `contentRevision`, `publishGeneration`, code identity와 site tree digest를 기록한다. `generatedAt`은 `YYYY-MM-DDTHH:mm:ssZ` 또는 1~6자리 fractional second + `Z`이며 정규식뿐 아니라 UTC year/month/day/hour/minute/second가 원본과 exact 일치하는 actual calendar Instant만 허용한다. immutable package와 `current`·`previous` authority는 release root의 one-direct-child만 허용하고, `current` atomic switch는 generation을 `BigInt`로 비교한 stale protection authority로 사용한다.
- D-IMP-3 production code release에서 `sbomReference`는 amd64/arm64 attached SBOM·provenance attestation을 소유하는 published OCI index digest다. pre-publish local SBOM file hash를 actual published artifact authority로 기록하지 않는다.
- 일부 collection만 과거 data로 fallback하거나 scheduled event ID·과거 boundary로 current snapshot filter를 우회하지 않는다.
- transformer는 API response schema와 published/relation/file 조건을 다시 검증하고 raw response를 component에 직접 전달하지 않는다.

## 공개 media 파생 staging — current

```text
backend-owned private canonical master
→ MediaContentProvider                           [implemented]
→ MIME/signature/decode/byte/pixel 재검증        [implemented]
→ orientation·sRGB·metadata 제거와 no-upscale   [implemented]
→ output decode·format·metadata 재검증           [implemented]
→ /generated/media/<output-sha256>.<format>      [implemented staging]
```

원본 id·storage path·내부 URL을 public path에 남기지 않는다. authenticated build-time HTTP download와 staging을 거친 variant는 manifest `publicPath` authority로 공개 `<picture>`에 연결되고 immutable release site에 포함된다.

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

## transformer 실패 조건 — current

- snapshot unknown/missing/schema/semantic/relation/media manifest 불일치
- media missing, content-type/signature mismatch, corrupt·APNG·oversized source
- output encode/decode·format·metadata 검증 실패
- staging parent/write/rename 실패 또는 이미 존재하는 target

어느 경우도 partial target을 성공으로 반환하지 않고 fixed typed error로 종료한다.

## staging adapter·release 실패/무변경 조건 — current

- build API 연결·인증 실패
- transformer typed failure
- snapshot/media response contract mismatch와 active generation mismatch
- HTML sanitize·sitemap canonical·link·asset 검증 실패
- malformed current manifest·release collision·symlink escape·unexpected filesystem type
- HTML/link/canonical/sitemap/robots/media hash 또는 secret/private marker 검증 실패

target `publishGeneration`이 trusted current generation 이하이면 오류나 success가 아니라 `NO_PUBLIC_CHANGE`다. candidate build 뒤 race가 생길 수 있으므로 switch 직전 같은 비교를 다시 수행하고 current/previous를 변경하지 않는다.

full release machine result는 `status`, `retentionStatus`, canonical string revision/generation, `generatedAt`, `releaseId`의 exact shape다. `PUBLISHED`는 `retentionStatus=COMPLETE|DEFERRED`, `NO_PUBLIC_CHANGE`는 `NOT_APPLICABLE`만 허용한다. post-switch smoke를 통과해 public authority가 바뀐 뒤 retention housekeeping만 실패하면 publication을 실패로 되돌리지 않고 safe `DEFERRED`로 표시하며 current·previous를 보존한다.

## 호환성

계약이 바뀌면 API DTO, 도메인 데이터 모델, Flyway migration, transformer/test, 운영 data migration과 release evidence를 함께 갱신한다.
