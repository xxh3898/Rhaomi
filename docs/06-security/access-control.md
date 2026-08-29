---
title: "접근제어"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
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
- 일반 고객 회원 기능 없음
- 초기 단계에서 불필요한 다중 RBAC를 만들지 않음

현재 `ADMIN` 업무 endpoint는 견종·서비스·공지, text 기반 매장정보 singleton과 private media master에 한정한다. 갤러리·이미지 relation, public/build media endpoint와 관리자 UI는 아직 없다.

### Public customer

- `/api/admin/auth/csrf`와 `/api/admin/auth/login`만 anonymous 호출 가능
- `/api/admin/**`의 그 밖의 endpoint 접근 금지
- 공개 사이트는 Static Export를 사용하며 runtime 관리 API 호출 없음
- PostgreSQL 직접 접근 없음

### Build service

build-time 공개 콘텐츠 조회는 후속 Issue에서 `/api/build/**` 같은 별도 namespace와 별도 credential로 설계한다. 관리자 session을 재사용하거나 이번 Issue에서 미리 endpoint·token을 만들지 않는다.

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
| `GET /api/admin/shop-settings` | 거부 | 허용 | 현재 singleton 조회, 미초기화 404 |
| `PUT /api/admin/shop-settings` | 거부 | 허용 | 유효 CSRF 필요, 최초 201·이후 200 full update |
| `GET /api/admin/media[/<id>]` | 거부 | 허용 | active·archived metadata 조회 |
| `GET /api/admin/media/<id>/content` | 거부 | 허용 | private no-store canonical master |
| `POST /api/admin/media` | 거부 | 허용 | 유효 CSRF 필요, 실제 byte 검증 후 active upload |
| `PUT /api/admin/media/<id>` | 거부 | 허용 | 유효 CSRF 필요, status만 archive·restore |
| `/api/admin/**` 나머지 | 거부 | 기본 인증 필요 | 명시 controller가 없으면 업무 수행 불가 |
| `/api/**` 나머지 | 거부 | 거부 | 명시 설계 전 fail closed |
| `GET /actuator/health` | 허용 | 허용 | health만 노출 |
| `/actuator/**` 나머지 | 거부 | 거부 | health 외 노출 금지 |
| 그 밖의 모든 path | 거부 | 거부 | 명시 정책 전 `denyAll` |

## 세션·CSRF

- 인증 상태는 서버 `HttpSession`에 저장한다.
- session cookie는 `HttpOnly`와 `SameSite=Lax`를 명시한다.
- 운영에서는 TLS와 함께 `Secure=true`를 강제하며 production profile이 false 설정으로 기동되지 않게 한다.
- 로그인 성공 시 session fixation 방어로 session id를 교체한다.
- `ProviderManager` 인증 완료 시 principal credential을 지우고 password hash가 없는 `SecurityContext`만 session에 저장한다.
- CSRF 보호를 비활성화하지 않는다.
- static admin client는 CSRF endpoint에서 받은 token을 state-changing request header에 보낸다.
- password, password hash, session id, CSRF token을 application log에 남기지 않는다.

## 관리자 bootstrap

- 기본값은 비활성이다.
- local/test 환경에서 explicit enable flag, email, password가 모두 있을 때만 idempotent하게 생성한다.
- password는 최소 12자이면서 UTF-8 최대 72 byte여야 하며 초과 입력은 `PasswordEncoder` 호출 전에 명시적 validation 오류로 중단한다.
- credential이 일부만 있거나 빈 값이면 기동을 실패시켜 잘못된 보안 상태를 숨기지 않는다.
- production profile에서는 bootstrap을 실행하지 않는다.
- `.env.example`에는 실제 email/password를 넣지 않는다.
- 실사용 은총쌤 계정 생성은 운영 Secret·2FA·복구 절차를 확인하는 별도 승인 작업이다.

## 2FA와 계정 수명주기

- 관리자 2FA는 운영 배포 게이트다. 이번 backend bootstrap에는 포함하지 않으며 2FA 없는 상태를 production-ready로 표현하지 않는다.
- 공유 계정을 만들지 않는다.
- 운영자 변경 시 계정을 즉시 비활성화하고 활성 session을 폐기한다.
- 강한 고유 비밀번호와 검증된 `PasswordEncoder`를 사용한다.
- 비활성 계정은 credential이 맞아도 로그인할 수 없다.
- 계정 존재 여부와 활성 여부를 로그인 실패 응답으로 구분해 노출하지 않는다.

## 콘텐츠 권한 원칙

- 운영자용 update DTO는 명시적 field allowlist를 사용한다.
- 현재 견종·서비스·공지 create/update DTO와 매장정보 PUT DTO는 unknown JSON field를 거부한다.
- `id`, `slug` 수정값, actor, `password_hash`, audit timestamp와 내부/system field는 일반 update API에서 받지 않는다.
- 매장정보 request는 `id`, `singletonKey`, actor, audit를 받지 않는다. response는 mutable field와 server-owned audit만 반환하고 DB id·singleton guard는 노출하지 않는다.
- media upload는 multipart `file` 하나만 받고 filename을 path·DB·response에 사용하지 않는다. status update는 `active | archived` 하나만 허용한다.
- media response는 storage key, filesystem path, extension과 SHA-256을 노출하지 않는다. archived content도 ADMIN만 조회할 수 있고 public/build route는 없다.
- 화면상 삭제는 `archived` 전환이며 영구 delete는 별도 관리·백업 승인 없이는 제공하지 않는다.
- 현재 견종·서비스·공지·매장정보·media controller/service에는 hard delete 경로와 `PATCH` endpoint가 없다. 매장정보에는 `POST`와 id 기반 endpoint도 없다.
- schema, role, user, setting 변경 endpoint를 일반 콘텐츠 API에 포함하지 않는다.
- 공개 build API와 관리자 write API의 credential·DTO·감사 경계를 분리한다.
