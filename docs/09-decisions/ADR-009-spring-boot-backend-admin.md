---
title: "ADR-009: Spring Boot 관리자 백엔드와 서버 세션 인증"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "관리자 인증·콘텐츠 API·DB·배포 구조 변경 시"
---

# ADR-009: Spring Boot 관리자 백엔드와 서버 세션 인증

- 결정일: 2026-08-29
- 상태: Accepted
- 대체: [ADR-002](ADR-002-directus-postgresql.md), [ADR-005](ADR-005-directus-admin-first.md)

## 맥락

기존 결정은 Directus Data Studio, API, 권한, 파일 저장과 PostgreSQL을 조합했다. Issue #3의 실제 API 검증에서 Directus 12.3.1 Core는 Site Builder의 item filter·file folder filter와 Content Owner의 field allowlist를 custom permission entitlement로 거부했다. 전체 item·전체 field 권한은 라오미펫의 최소권한 계약을 충족하지 못한다.

별도 license나 Directus 앞의 우회 계층을 추가하면 작은 관리자 기능 범위에 비해 운영·보안 경계가 늘어난다. 현재 필요한 자체 기능은 관리자 인증 기반이며 콘텐츠 CRUD, 파일 처리, 관리자 화면은 후속 단계로 나눌 수 있다.

## 결정

- Directus runtime, Data Studio, role/policy/permission, schema apply, file storage, license 의존을 제거한다.
- 관리 API는 Java 25 LTS와 Spring Boot 4.1.1을 사용하고 PostgreSQL을 유지한다.
- DB schema 변경은 Flyway만 수행하며 JPA는 schema를 자동 생성하지 않고 검증한다.
- 관리자 인증은 Spring Security 서버 세션을 사용한다.
- session cookie는 HttpOnly이고 SameSite를 명시하며, 운영 환경에서 Secure를 강제할 수 있어야 한다.
- 세션 fixation 방어와 CSRF 보호를 유지한다.
- 1차 role은 `ADMIN` 하나로 제한하고 일반 고객 회원과 JWT를 만들지 않는다.
- 공개 Next.js 사이트는 Static Export를 유지하고 고객 브라우저에서 backend나 PostgreSQL을 호출하지 않는다.
- 관리자 browser는 같은 origin의 `/api/**`로 backend를 사용한다. Phase 1C-7에서 exact image/digest의 local Nginx gateway로 이 경계를 검증하고 production Nginx·TLS·배포는 후속 운영 승인으로 남긴다.

## 이번 단계의 구현 경계

- `admin_users` Flyway V1
- local/test 전용 fail-safe 관리자 bootstrap
- `GET /api/admin/auth/csrf`
- `POST /api/admin/auth/login`
- `GET /api/admin/auth/me`
- `POST /api/admin/auth/logout`
- 최소 Actuator health
- PostgreSQL을 사용하는 인증 contract test와 개발 Compose·Hosted CI

공지·견종·서비스·갤러리·매장정보 CRUD API, private 이미지 storage·변환과 `/admin/` 인증 셸은 이 ADR의 방향을 따라 후속 Phase에서 구현했다. 콘텐츠 CRUD UI, build API, 콘텐츠 snapshot과 자동 재빌드 hook은 아직 구현하지 않았다.

### Phase 1C-7 same-origin client 경계

- `/admin/`은 `out/admin/index.html`로 생성되는 noindex Static Export client shell이다.
- 정적 HTML과 client auth check는 보안 경계가 아니며 backend session·CSRF가 업무 요청을 최종 방어한다.
- client는 relative `/api/admin/**`, `credentials: same-origin`, read `cache: no-store`만 사용한다.
- login POST 성공 response의 identity를 검증한 직후 password·form email과 pre-login CSRF를 제거하고, credential 제거가 반영된 뒤 fresh CSRF를 별도 획득한다. fresh CSRF 성공 전에는 authenticated mutation-ready 상태로 전환하지 않는다.
- post-login CSRF 준비 실패는 credential 실패·anonymous로 축소하지 않으며, 재시도는 `/me`로 기존 session을 확인한 뒤 fresh CSRF만 다시 획득한다.
- password·CSRF·identity는 browser storage·URL·log에 저장하지 않고 logout 403 mutation을 자동 재시도하지 않는다.
- local gateway만 구현했으며 CORS, cookie Domain rewrite, production TLS·domain 설정은 추가하지 않았다.

## 보안 계약

- `/api/admin/**`는 명시한 login/csrf endpoint를 제외하고 인증이 기본이다.
- anonymous 허용은 `GET /api/admin/auth/csrf`, `POST /api/admin/auth/login`, `GET /actuator/health`로 한정하고 그 밖의 모든 request는 명시적 정책이 생기기 전 `denyAll`로 거부한다.
- state-changing request는 CSRF token 없이는 거부한다.
- 로그인 실패는 계정 존재·활성 여부를 구분해 노출하지 않는다.
- 잘못된 계정·password·비활성 계정만 같은 401로 처리하고 인증 service·repository 장애는 내부 정보를 숨긴 503으로 구분한다.
- password 입력은 BCrypt 계약에 맞춰 login과 bootstrap 모두 UTF-8 최대 72 byte로 제한한다.
- password는 검증된 Spring Security `PasswordEncoder`로 해시하고 인증 완료 직후 principal credential을 지운 뒤 `SecurityContext`를 저장한다. response와 log에도 hash·session id를 남기지 않는다.
- bootstrap은 명시적 local/test 활성화와 완전한 credential이 모두 있어야 실행하며 production profile에서는 실행하지 않는다.
- backend와 frontend 개발 port는 loopback에만 bind하고 PostgreSQL host port는 열지 않는다.

## 결과

### 장점

- license entitlement와 vendor별 permission 모델에 의존하지 않는다.
- 필요한 인증·인가 규칙을 코드와 PostgreSQL contract test로 직접 검증한다.
- 콘텐츠 기능을 작은 도메인 단위로 순차 구현할 수 있다.
- 공개 사이트의 runtime 독립과 PostgreSQL 데이터 소유권을 유지한다.

### 비용과 위험

- 관리자 콘텐츠 UI, 변경 이력과 게시 UX를 직접 구현해야 한다. 현재 `/admin/`은 인증 셸과 준비 중 영역만 제공한다.
- Spring Boot 보안 업데이트, Flyway migration과 세션 운영을 관리해야 한다.
- 현재 in-memory session은 backend 재시작 시 로그인을 다시 요구한다.
- 콘텐츠 backend와 정적 빌드 연동이 구현되기 전에는 운영자가 콘텐츠를 관리할 수 없다.

## 검토한 대안

### Directus 12 Core의 넓은 권한

구현은 가능하지만 draft·전체 파일·system field 접근을 허용하므로 거부한다.

### Directus license 또는 OIG

기존 구조 변경은 작지만 entitlement·자격·갱신·Secret 관리가 추가되고 라오미펫의 작은 기능 범위에 비해 의존 비용이 크므로 선택하지 않는다.

### Directus 앞의 trusted gateway

Directus와 별도 authorization backend를 함께 운영해야 하므로 경계와 장애 지점이 늘어난다.

### JWT 인증

외부 client나 모바일 API 요구가 없고 단일 same-origin 관리자 웹만 계획돼 있어 저장·refresh·rotation 복잡성이 불필요하다.

## 재검토 조건

- 외부 관리자 client 또는 다중 서비스 인증이 승인됨
- 수평 확장으로 공유 session store가 필요함
- 다중 역할·승인 workflow가 실제 요구로 확정됨
- 자체 콘텐츠 관리 비용이 검증된 대안보다 커짐
