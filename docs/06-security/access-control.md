---
title: "접근제어"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-31"
review_trigger: "역할·권한·인증 방식 변경 시"
---

# 접근제어

[ADR-009](../09-decisions/ADR-009-spring-boot-backend-admin.md)에 따라 Spring Security 서버 세션을 관리 API의 인증 경계로 사용한다.

## 현재 역할

### ADMIN

대상: 실제 운영 계정은 후속 운영 승인에서 생성

- 관리자 인증 API 사용
- 견종·서비스·공지 관리 API에서 생성·조회·전체 수정·archive와 복구 수행
- 매장정보 singleton 조회와 전체 갱신 수행
- private media upload·metadata/content 조회와 archive·restore 수행
- 갤러리 생성·전체 상태 조회·전체 수정·archive·restore 수행
- 일반 고객 회원 기능 없음
- 초기 단계에서 불필요한 다중 RBAC를 만들지 않음

현재 `ADMIN` 업무 endpoint와 `/admin/` 화면은 견종·서비스·공지·갤러리, private media relation을 포함한 매장정보 singleton과 private media master에 한정한다. anonymous public gallery·shop·media API endpoint는 없고 공개 데이터는 검증된 Static Export 안에만 포함된다.

### Public customer

- `/api/admin/auth/csrf`와 `/api/admin/auth/login`만 anonymous 호출 가능
- `/api/admin/**`의 그 밖의 endpoint 접근 금지
- 공개 사이트는 Static Export를 사용하며 runtime 관리 API 호출 없음
- PostgreSQL 직접 접근 없음

### Build service

build-time 공개 콘텐츠 조회는 [ADR-011](../09-decisions/ADR-011-transactional-outbox-static-publisher.md)과 [ADR-015](../09-decisions/ADR-015-lossless-int64-json-wire-contract.md)에 따라 internal `/api/build/**` namespace와 관리자 session과 분리된 service credential을 사용한다.

- snapshot·media read만 허용하고 create/update/delete/share를 모두 금지한다.
- public Nginx에서 `/api/build/**`를 명시적으로 거부한다.
- query 단계에서 published 상태, notice 게시·만료, relation target, media active와 file scope를 제한한다.
- publisher transformer가 같은 status·relation·file 조건을 다시 검증한다.
- Build Snapshot V2 revision/generation은 canonical decimal string만 허용하고 Node가 JSON number로 축소하지 않아 generation equality·stale protection을 precision collision로 우회하지 못하게 한다.
- raw storage path, DB credential, admin session과 private metadata를 노출하지 않는다.

build API와 stateless credential 경계, Node full release adapter와 credential을 직접 해석하지 않는 publisher control loop를 구현했다. Java executor는 같은 secret source의 `BUILD_API_CREDENTIAL`을 allowlist environment로만 child에 전달하고 URL/query/argv/output에는 넣지 않으며 no-redirect bounded GET과 manifest-scoped memory media provider만 사용한다. production publisher secret/image/path provisioning은 아직 구현되지 않았다. local token은 backend와 명시적인 validation process에만 전달하고 frontend·gateway environment에는 key를 두지 않으며 frontend filesystem에는 `.env.dev.local`, backend source와 local secret/config를 mount하지 않는다. 관리자 session을 재사용하거나 실제 token을 이 문서·저장소에 만들지 않는다.

### Static admin client

- `/admin/` HTML은 누구나 받을 수 있는 Static Export이며 그 존재나 client-side session check를 접근제어로 보지 않는다.
- 업무 API의 최종 경계는 backend session·CSRF이고 anonymous `/me`와 업무 endpoint는 계속 거부한다.
- browser는 same-origin relative `/api/admin/**`만 사용하며 CORS를 열거나 backend host를 bundle에 넣지 않는다.
- password·CSRF·session id·관리자 identity를 localStorage, sessionStorage, IndexedDB, cookie 직접 쓰기, URL query/hash에 저장하지 않는다.
- backend의 알 수 없는 `message`, exception, path, SQL detail은 UI에 표시하지 않는다.

## endpoint 정책

| 경로 | anonymous | authenticated ADMIN | 비고 |
|---|---:|---:|---|
| `GET /api/admin/auth/csrf` | 허용 | 허용 | CSRF token 발급 |
| `POST /api/admin/auth/login` | 허용 | 허용 | 유효 CSRF 필요 |
| `GET /api/admin/auth/me` | 거부 | 허용 | 최소 식별 정보만 반환 |
| `POST /api/admin/auth/logout` | 거부 | 허용 | 유효 CSRF 필요, session 무효화 |
| `GET /api/admin/breeds[/<id>]` | 거부 | 허용 | 전체 상태 조회, deterministic sort |
| `POST /api/admin/breeds` | 거부 | 허용 | 유효 CSRF 필요, 항상 draft 생성 |
| `PUT /api/admin/breeds/<id>` | 거부 | 허용 | 유효 CSRF 필요, 전체 mutable field 수정 |
| `GET /api/admin/services[/<id>]` | 거부 | 허용 | 전체 상태 조회, deterministic sort |
| `POST /api/admin/services` | 거부 | 허용 | 유효 CSRF 필요, 항상 draft 생성 |
| `PUT /api/admin/services/<id>` | 거부 | 허용 | 유효 CSRF 필요, 전체 mutable field 수정 |
| `GET /api/admin/notices[/<id>]` | 거부 | 허용 | 모든 상태·시각 조회, deterministic sort |
| `POST /api/admin/notices` | 거부 | 허용 | 유효 CSRF 필요, 항상 draft 생성 |
| `PUT /api/admin/notices/<id>` | 거부 | 허용 | 유효 CSRF 필요, immutable slug·전체 mutable field 수정 |
| `GET /api/admin/gallery-items[/<id>]` | 거부 | 허용 | 모든 상태 조회, deterministic sort, scalar relation id만 반환 |
| `POST /api/admin/gallery-items` | 거부 | 허용 | 유효 CSRF 필요, 항상 draft 생성 |
| `PUT /api/admin/gallery-items/<id>` | 거부 | 허용 | 유효 CSRF 필요, 전체 mutable field·관계·게시 상태 검증 |
| `GET /api/admin/shop-settings` | 거부 | 허용 | 현재 singleton 조회, 미초기화 404 |
| `PUT /api/admin/shop-settings` | 거부 | 허용 | 유효 CSRF 필요, 최초 201·이후 200 full update와 active media 검증 |
| `GET /api/admin/media[/<id>]` | 거부 | 허용 | active·archived metadata 조회 |
| `GET /api/admin/media/<id>/content` | 거부 | 허용 | private no-store canonical master |
| `POST /api/admin/media` | 거부 | 허용 | 유효 CSRF 필요, 실제 byte 검증 후 active upload |
| `PUT /api/admin/media/<id>` | 거부 | 허용 | 유효 CSRF 필요, status만 archive·restore |
| `/api/admin/**` 나머지 | 거부 | 기본 인증 필요 | 명시 controller가 없으면 업무 수행 불가 |
| `/api/**` 나머지 | 거부 | 거부 | 명시 설계 전 fail closed |
| `GET /actuator/health` | 허용 | 허용 | application-level 최소 health, production public Nginx는 차단하고 HomeOps internal probe만 사용 |
| `/actuator/**` 나머지 | 거부 | 거부 | health 외 노출 금지 |
| 그 밖의 모든 path | 거부 | 거부 | 명시 정책 전 `denyAll` |

## 세션·CSRF

- 인증 상태는 서버 `HttpSession`에 저장한다.
- session cookie는 `HttpOnly`와 `SameSite=Lax`를 명시한다.
- 운영에서는 TLS와 함께 `Secure=true`를 강제하며 production profile이 false 설정으로 기동되지 않게 한다.
- 로그인 성공 시 session fixation 방어로 session id를 교체한다.
- static admin client는 login POST 성공 response의 identity를 검증한 직후 password·form email과 pre-login CSRF를 제거한다. credential input이 제거된 뒤에만 fresh CSRF를 요청하고 성공 전에는 authenticated mutation을 노출하지 않는다.
- post-login fresh CSRF 실패는 credential 실패나 anonymous로 축소하지 않는다. 재시도는 `/me`로 기존 session을 확인한 뒤 fresh CSRF를 다시 준비하며 login·logout을 자동 재전송하지 않는다.
- `ProviderManager` 인증 완료 시 principal credential을 지우고 password hash가 없는 `SecurityContext`만 session에 저장한다.
- CSRF 보호를 비활성화하지 않는다.
- static admin client는 CSRF endpoint에서 받은 token을 state-changing request header에 보낸다.
- admin API 401은 client의 in-memory 인증 상태를 제거하고 403 mutation은 권한/CSRF 오류로 구분하며 자동 반복하지 않는다.
- password, password hash, session id, CSRF token을 application log에 남기지 않는다.

## 관리자 bootstrap

- 기본값은 비활성이다.
- local/test 환경에서 explicit enable flag, email, password가 모두 있을 때만 idempotent하게 생성한다.
- password는 최소 12자이면서 UTF-8 최대 72 byte여야 하며 초과 입력은 `PasswordEncoder` 호출 전에 명시적 validation 오류로 중단한다.
- credential이 일부만 있거나 빈 값이면 기동을 실패시켜 잘못된 보안 상태를 숨기지 않는다.
- production profile에서는 bootstrap을 실행하지 않는다.
- `.env.example`에는 실제 email/password를 넣지 않는다.
- 실사용 은총쌤 계정 생성은 운영 Secret provisioning, WebAuthn/passkey 2차 인증 registration·복구 절차를 확인하는 별도 승인 작업이다.

## 2FA와 계정 수명주기

- 관리자 2차 인증의 기본 target은 기존 password/session/CSRF 위의 WebAuthn/passkey다. SMS 2FA는 사용하지 않고 TOTP fallback은 별도 근거 없이 추가하지 않는다.
- WebAuthn/passkey는 운영 배포 게이트다. 현재 backend bootstrap에는 포함하지 않으며 password-only 상태를 production-ready로 표현하지 않는다.
- passkey private key는 authenticator/device authority이며 Rhaomi server가 수집·저장·로그하지 않는다. registration ceremony는 credential ID·public key와 필요한 authenticator metadata를, authentication ceremony는 assertion을 RP에 전달한다.
- server는 credential ID, public key, 관리자 account binding과 필요한 authenticator/sign-counter metadata로 RP-side credential record를 유지한다. 이 record는 private key나 recovery secret과 구분하고 일반 API response·log·release evidence에 노출하지 않는다.
- authenticator 분실·폐기, 무단 등록 의심 또는 운영자 변경 시 해당 WebAuthn registration을 revoke/remove한다. 이는 server가 private key를 rotate하는 절차가 아니다.
- recovery code만 관련 server secret inventory에 두고 password manager와 별도 offline copy에 보관한다. code 사용·노출 의심·재발급 또는 운영자 변경 시 기존 code를 무효화하고 새 set으로 rotation한다.
- 공유 계정을 만들지 않는다.
- 운영자 변경 시 계정을 즉시 비활성화하고 활성 session을 폐기한다.
- 강한 고유 비밀번호와 검증된 `PasswordEncoder`를 사용한다.
- 비활성 계정은 credential이 맞아도 로그인할 수 없다.
- 계정 존재 여부와 활성 여부를 로그인 실패 응답으로 구분해 노출하지 않는다.

## 콘텐츠 권한 원칙

- 운영자용 update DTO는 명시적 field allowlist를 사용한다.
- 현재 견종·서비스·공지·갤러리 create/update DTO와 매장정보 PUT DTO는 unknown JSON field를 거부한다.
- `id`, `slug` 수정값, actor, `password_hash`, audit timestamp와 내부/system field는 일반 update API에서 받지 않는다.
- 매장정보 request는 명시된 scalar media UUID와 Hero·프로필 alt만 추가로 받고 `id`, `singletonKey`, actor, audit, storage metadata를 받지 않는다. response는 mutable field·scalar relation id·server-owned audit만 반환하고 DB id·singleton guard·media entity는 노출하지 않는다.
- media upload는 multipart `file` 하나만 받고 filename을 path·DB·response에 사용하지 않는다. status update는 `active | archived` 하나만 허용한다.
- admin media response는 storage key, filesystem path, extension과 SHA-256을 노출하지 않는다. archived content도 ADMIN만 조회할 수 있고 anonymous public route는 없다. build media route는 active generation과 current public relation scope의 verified bytes만 private no-store로 허용한다.
- gallery request는 scalar breed/service/media id만 받고 response도 id만 반환한다. target 객체와 private media storage metadata를 embed하지 않는다.
- gallery publish 전 breed/service `published`, 연결 media `active`를 검증한다. target의 후속 상태 변경은 cascade하지 않고 후속 build snapshot에서 다시 검증한다.
- shop PUT은 Hero·프로필 image-alt pair와 모든 non-null media의 `active` 상태를 mutation 전에 검증한다. target의 후속 archive는 cascade하지 않고 후속 build snapshot에서 relation·file을 다시 검증한다.
- 화면상 삭제는 `archived` 전환이며 영구 delete는 별도 관리·백업 승인 없이는 제공하지 않는다.
- 현재 견종·서비스·공지·갤러리·매장정보·media controller/service에는 hard delete 경로와 `PATCH` endpoint가 없다. 매장정보에는 `POST`와 id 기반 endpoint도 없다.
- schema, role, user, setting 변경 endpoint를 일반 콘텐츠 API에 포함하지 않는다.
- 공개 build API와 관리자 write API의 credential·DTO·감사 경계를 분리한다.
- build service는 snapshot·media read 외 권한을 받지 않고 `POST`, `PUT`, `PATCH`, `DELETE`와 share 동작을 사용할 수 없다.
- HomeOps는 CSRF endpoint를 probe하지 않고 privacy-safe internal health/status만 읽는다. HomeOps identity에 관리자 콘텐츠 write 권한을 주지 않는다.
