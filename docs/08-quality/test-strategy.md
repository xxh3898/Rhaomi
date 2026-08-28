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

## 현재 Phase 1B 자동 검증

### Frontend

- lint
- TypeScript typecheck
- Node contract test
- Next Static Export
- `out/index.html`·runtime server artifact 부재 검증
- 공개 source의 관리자 API·DB 환경변수 비노출

### Backend unit

- bootstrap 기본 비활성
- bootstrap credential 불완전 시 fail closed
- production profile bootstrap 거부
- email 정규화·password hash 생성

### Backend PostgreSQL integration

- Spring context와 실제 PostgreSQL 연결
- Flyway V1 `admin_users` 생성
- JPA `ddl-auto=validate`
- plaintext password 비저장과 BCrypt match
- bad/inactive/good login
- anonymous `/me`와 보호 endpoint 거부
- login 후 `/me`
- logout CSRF deny/allow와 session 무효화
- login CSRF deny/allow
- HttpOnly·SameSite session cookie와 fixation 후 id 변경
- response의 password/hash 비노출
- health 외 미설계 API deny

H2 전용 통과는 DB contract 증거로 인정하지 않는다. Hosted CI Backend job은 실제 PostgreSQL service를 사용한다.
Gradle test는 `RHAOMI_TEST_DATABASE_ALLOWED=true`가 명시되지 않으면 application context를 시작하기 전에 중단한다. fixture 정리는 지정된 test email에만 한정한다.

### Compose Smoke

- exact service/image와 config validation
- backend/PostgreSQL health
- backend loopback bind와 PostgreSQL host port 부재
- explicit local/test bootstrap
- 실제 HTTP CSRF login/me/logout
- backend/PostgreSQL restart 후 Flyway·account 지속성
- Directus service 부재
- 종료 시 named volume 보존

## 후속 콘텐츠 단위·통합 테스트

- URL, phone link, status·expiry filter, sort
- slug/canonical, JSON-LD, alt validation
- final entity 상태 기준 publish validation과 partial update 우회 방지
- 관리자 DTO field allowlist와 id/audit/system field 불변성
- published 관계와 file scope
- build API read-only와 모든 mutation deny
- snapshot schema와 image manifest
- content fixture → transformer → static snapshot

## 후속 UI/E2E

- 홈 → gallery filter → 상세 → 문의
- 홈 → 공지 → 상세 → 홈
- map/phone/external CTA
- mobile sticky CTA, 404, keyboard only
- `/admin` login/logout, validation, archive
- axe, heading/landmark/focus/dialog/contrast/reflow/reduced motion
- 실제 iPhone image upload와 session cookie 동작

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
- 공개/초안/보관 콘텐츠와 만료 공지
- 긴 title·견종명, 선택형 URL 빈 값
- portrait/landscape/손상/HEIC image

실사용 email, 실제 운영 password, token과 운영 DB/API는 test에 사용하지 않는다.

## CI gate

PR:

- Frontend
- Backend PostgreSQL/auth contract
- Compose Smoke
- diff·secret·문서 link 검사

Release는 실제 build snapshot, image pipeline, E2E, SEO, Nginx preview, actual device와 rollback evidence를 추가한다.
