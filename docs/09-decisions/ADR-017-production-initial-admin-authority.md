---
title: "ADR-017: Production 최초 관리자 authority"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-10"
review_trigger: "최초 관리자 생성·credential 입력·production lifecycle 경계 변경 시"
---

# ADR-017: Production 최초 관리자 authority

- 결정일: 2026-09-10
- 상태: Accepted
- 관련 결정: [ADR-009](ADR-009-spring-boot-backend-admin.md), [ADR-010](ADR-010-production-topology-and-code-release.md), [ADR-016](ADR-016-verified-empty-first-production-activation.md)

## 맥락

Production profile의 일반 `AdminBootstrap`은 default 관리자 생성을 금지하며, 기존 production one-shot task는 Flyway migration과 JPA schema validation만 제공했다. 따라서 verified-empty first activation 후에도 최초 관리자를 application authority 안에서 생성할 수 없었다. Raw SQL, production 환경변수 bootstrap 활성화, HTTP admin route 추가는 credential과 single-use 경계를 약화한다.

Issue #95의 owner decision과 Issue #96은 exact production image의 fixed one-shot non-web task를 Stage 0A source authority로 승인했다. 이 ADR은 source 계약을 고정하며 실제 production account 생성 승인을 포함하지 않는다.

## 결정

### One-shot application boundary

- `--rhaomi.production-task=initial-admin`은 `WebApplicationType.NONE`과 전용 Spring configuration을 사용한다.
- 전용 context는 `AdminUser` JPA entity·repository, Bean Validation, `BCryptPasswordEncoder(12)`, PostgreSQL lock과 runner만 구성한다.
- controller, HTTP listener, `AdminBootstrap`, publisher loop·executor, Flyway apply를 생성하지 않는다. Schema는 `ddl-auto=validate`로만 확인한다.
- 일반 backend·publisher process에는 initial-admin bean, thread, route를 추가하지 않는다.

### Zero-admin과 concurrency authority

- 하나의 Spring transaction에서 fixed PostgreSQL transaction advisory lock을 먼저 획득한다.
- lock 획득 후 `admin_users` 전체 count가 0임을 다시 확인하고 하나의 `ADMIN` row만 `saveAndFlush`한다.
- 관리자가 하나라도 있거나 count/lock/transaction authority를 확정할 수 없으면 mutation 없이 실패한다.
- 동시 invocation N개에서 commit 성공은 최대 1개다. Transaction commit 전 실패는 관리자 row를 남기지 않는다.
- DB·JPA 내부 상세는 one-shot 출력으로 전파하지 않고 fixed failure code로 축소한다.

### Credential boundary

- email과 password는 interactive `System.console()`에서만 입력하고 password·확인 input은 echo하지 않는다.
- TTY가 없으면 실행 전 실패한다. Email/password를 CLI argv, URL, Compose environment, production env file, Docker mount, machine evidence로 받는 경로는 없다.
- email은 기존 `AdminUser.normalizeEmail` authority를, password는 최소 12자·UTF-8 최대 72 byte Bean Validation과 server-side BCrypt authority를 재사용한다.
- console password buffer는 read/confirmation 성공·실패 후 즉시 제거하고 credential object의 문자열 표현은 redacted로 고정한다.
- 성공 evidence는 contract/status/admin count만 포함하며 email, password, hash, session, CSRF, passkey, recovery code를 기록하지 않는다.

### Host lifecycle boundary

- tracked fixed wrapper의 production authority는 `/private/var/lib/rhaomi/app/bin/provision-initial-admin-rhaomi.sh`이다. Production root, Compose/env/Docker config path, service, command를 caller가 바꾸지 못한다.
- wrapper는 valid `STEADY_STATE`, backend/publisher의 동일 exact GHCR digest·OCI revision, owner/mode를 writer mutation 전에 확인한다.
- deploy/backup과 같은 `rhaomi-deploy.lock`을 획득한 뒤 backend/publisher의 physical `exited`를 확인하고 task를 실행한다.
- task 성공·실패 후 동일 image의 backend health와 publisher running을 복구한 뒤에만 자신의 lock을 해제한다. Physical quiescence나 recovery를 확정할 수 없으면 lock을 보존한다.
- Compose `initial-admin` service는 exact production image, one-shot, port/mount 0, PostgreSQL internal network only, read-only root, all capabilities drop, no-new-privileges를 고정한다.

### 인증 stage

- task는 password hash와 active `ADMIN` row만 만든다. Passkey·recovery code를 생성하지 않는다.
- zero-passkey account의 기존 initial enrollment만 사용하며 `FIRST_FACTOR_VERIFIED` 상태에서 business `/api/admin/**`를 열지 않는다.
- 실제 production admin input, passkey enrollment, recovery-code one-time issuance·offline custody는 별도 Stage G 승인·physical acceptance다.

## 이유

- raw SQL이 아닌 application domain·validation·PasswordEncoder 경계를 재사용한다.
- PostgreSQL transaction lock과 zero-admin re-check를 같이 두어 process/container 동시성에서도 single-use를 보장한다.
- credential transport를 단수한 로컬 interactive console로 한정해 새 Secret authority와 durable plaintext를 만들지 않는다.
- shared lifecycle/operation lock으로 account mutation과 production writer를 동시에 활성화하지 않는다.

## 거부한 대안

### Production `AdminBootstrap` 활성화

Long-lived backend startup에 credential environment·automatic retry·idempotent skip 의미를 혼합하고 production default-admin fail-close를 약화하여 거부한다.

### Raw SQL/manual DB write

Validation, email normalization, BCrypt, transaction·audit authority를 우회하고 동시 zero-admin single-use를 보장하지 못해 거부한다.

### HTTP provisioning endpoint

Production에 영구 attack surface, authentication bootstrap paradox, public gateway 오설정 위험을 추가하여 거부한다.

### Environment·file·argv credential 주입

Compose render, Docker inspect, process list, shell history, durable config/evidence에 plaintext가 남을 수 있어 거부한다.

## 결과와 남은 gate

- Stage 0A source는 독립 unit/PostgreSQL concurrency/Compose/fixed-wrapper regression으로 검증한다.
- 이 결정은 Flyway V11, API route, dependency, public artifact를 추가하지 않는다.
- Issue #97 Stage 0B의 owner-approved initial content import와 canonical first publication은 별도 source gate로 남는다.
- #96·#97 integration, dev→main Source Release, main→dev back-sync, exact-main CI/security 재검증 전에 Issue #95 Stage A를 실행하지 않는다.
- Source·CI PASS는 actual Mac inventory, GitHub control plane, GHCR publish, production DB mutation, admin/passkey/recovery provisioning 증거가 아니다.

## 비수행 경계

이 ADR의 source 구현은 PR merge, Source Release, production workflow dispatch, GHCR publish, GitHub Environment/Secret/Variable, `/private/var/lib/rhaomi`, production Docker/DB/backup/migration, Cloudflare/HomeOps, 실제 administrator/password/passkey/recovery code/content를 변경하지 않는다.
