---
title: "환경설정"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-31"
review_trigger: "환경변수·domain·version 변경 시"
---

# 환경설정

## 환경

```text
local
development
production
```

별도 staging을 장기간 운영하지 않더라도 local 또는 임시 환경에서 production과 같은 인증·DB 계약을 검증한다.

## Phase 1C-7 local 개발 계약

- Compose file: `compose.dev.yaml`
- Compose project: `dev-rhaomi`
- local 환경파일: Git 제외 `.env.dev.local`
- 공개 example: `.env.example`
- Node.js: `24.20.0-alpine3.23`
- Java: `eclipse-temurin:25.0.4_7-jdk-alpine-3.23`
- HEIC ImageIO adapter: NightMonkeys `imageio-heif 1.1.0`
- native codec: Alpine `libheif 1.23.0-r0`
- Spring Boot: `4.1.1`
- Gradle Wrapper: `9.7.1`
- PostgreSQL: `18.6-alpine3.23`
- gateway: `nginx:1.31.4-alpine3.24` exact manifest digest
- gateway port: `127.0.0.1:3000`
- frontend host port: 없음, gateway 전용 service port 3000
- backend port: `127.0.0.1:8080`
- PostgreSQL host port: 없음

재현 가능한 frontend/backend test와 stack 명령은 [프로젝트 README](../../README.md#로컬-개발)를 따른다.

관리자 browser API base 환경변수는 만들지 않는다. `/admin/` client는 현재 origin의 상대경로 `/api/admin/**`만 사용하며 `NEXT_PUBLIC_` credential·backend host·port를 주입하지 않는다.

host Compose CLI는 Git 제외 `.env.dev.local`을 interpolation source로 읽지만 frontend container는 repository root를 mount하지 않는다. frontend dependency install·runtime에는 `src`, package manifest와 Next·TypeScript config만 allowlist mount하고 `.env*`, `backend/`, local secret/config와 private runtime data를 제외한다. repository contract 검증용 `contract-check`도 actual env file 없이 명시적 tracked source만 read-only mount하고 network를 끈다.

## backend·PostgreSQL

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `POSTGRES_DB` | N | local 개발 DB명 |
| `POSTGRES_USER` | Y 취급 | local 개발 DB user |
| `POSTGRES_PASSWORD` | Y | local 개발 DB password |
| `SPRING_DATASOURCE_URL` | Y 취급 | backend에 주입하는 내부 JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Y 취급 | backend DB user |
| `SPRING_DATASOURCE_PASSWORD` | Y | backend DB password |
| `RHAOMI_SESSION_COOKIE_SECURE` | N | local HTTP는 `false`, production TLS는 `true` |
| `RHAOMI_BUILD_SERVICE_TOKEN` | Y | backend-only internal build API의 64자 lowercase hex Bearer credential |

Compose는 `POSTGRES_*`에서 backend의 `SPRING_DATASOURCE_*`를 내부 service hostname으로 구성한다. `RHAOMI_BUILD_SERVICE_TOKEN`도 backend environment에만 전달하며 frontend·gateway environment와 filesystem에는 전달하지 않는다. production에서는 운영 전용 Secret과 정확한 runtime contract를 별도 Compose에서 사용한다.

## private media

| 변수 | 비밀 | 기본값 | 설명 |
|---|---:|---|---|
| `RHAOMI_MEDIA_ROOT` | N, 내부 경로 | Compose `/var/lib/rhaomi/media` | backend 전용 temp·master의 동일 filesystem root |

- root가 비었거나 temp/master directory를 만들고 쓸 수 없거나 두 directory가 다른 filesystem이면 backend 기동을 실패시킨다.
- local Compose는 별도 persistent `dev-rhaomi-backend-media-masters` volume을 backend에만 mount한다.
- source 20 MiB, stored 30 MiB, width·height 12,000px, total 60MP, JPEG quality 92는 현재 application contract로 고정하며 client request나 공개 env로 변경하지 않는다.
- `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED`는 pinned FFM 기반 HEIC adapter 실행에 필요하며 backend image와 Gradle test/bootRun에만 적용한다.
- production canonical media의 Mac host path는 `/private/var/lib/rhaomi/data/media`이며 web에는 mount하지 않는다. backend container에는 `/var/lib/rhaomi/media`로 read-write mount하고 publisher·backup에는 필요한 경우 read-only로 제한한다. exact ownership·permission, backup과 restore는 별도 provisioning·운영 승인에서 검증한다.

## local/test 관리자 bootstrap

| 변수 | 비밀 | 기본값 | 설명 |
|---|---:|---|---|
| `RHAOMI_BOOTSTRAP_ADMIN_ENABLED` | N | `false` | 명시적 local/test bootstrap gate |
| `RHAOMI_BOOTSTRAP_ADMIN_EMAIL` | Y 취급 | 빈 값 | test/local 관리자 email |
| `RHAOMI_BOOTSTRAP_ADMIN_PASSWORD` | Y | 빈 값 | 최소 12자, UTF-8 최대 72 byte의 test/local password |

- enable flag와 두 credential이 모두 유효할 때만 account를 idempotent하게 생성한다.
- 72-byte 초과 password는 encoder 호출 전에 validation 오류로 기동을 중단한다.
- flag가 false면 credential이 있더라도 account를 만들지 않는다.
- production profile에서 flag가 true면 기동을 실패시킨다.
- 실제 은총쌤 credential을 `.env.example`, CI, 문서에 사용하지 않는다.

## local publication acceptance

`sh scripts/validate-local-publication-acceptance.sh`는 `.env.example`만으로 시작하며 실제 `.env.dev.local`이나 production secret을 요구하지 않는다. script가 exact Git HEAD와 task temp root를 확인한 뒤 synthetic admin/DB credential을 process environment에서만 구성하고 raw 값을 log·artifact에 기록하지 않는다.

| 변수 | 범위 | 설명 |
|---|---|---|
| `RHAOMI_LOCAL_PUBLICATION_ACCEPTANCE` | runner-only | opt-in integration test gate |
| `RHAOMI_PUBLICATION_ACCEPTANCE_ROOT` | runner/static/smoke | marker가 있는 task-scoped temp public/media/state root |
| `RHAOMI_ADMIN_BASE_URL` | runner-only | internal same-origin Admin Nginx origin |
| `RHAOMI_ACCEPTANCE_GIT_HEAD` | runner-only | release manifest에 기록할 exact test HEAD |

acceptance PostgreSQL은 `/var/lib/postgresql` tmpfs를 사용하고 host port·named volume이 없다. Admin gateway·runner·DB를 중단한 후 static Nginx와 smoke client만 별도 internal public network에 남긴다. 이 설정은 `local|test` 수용 검증이며 production environment/mount 계약이 아니다.

## production filesystem·release inventory — planned

| 항목 | 비밀 | 계약 |
|---|---:|---|
| Mac application root | N | `/private/var/lib/rhaomi/app` |
| Mac release root | N | `/private/var/lib/rhaomi/public/releases` |
| Mac current/previous | N | `/private/var/lib/rhaomi/public/current`, `/private/var/lib/rhaomi/public/previous` |
| PostgreSQL primary PGDATA | Y 취급 | production Compose project-scoped Docker named volume, host bind source 없음 |
| Mac canonical media | Y 취급 | `/private/var/lib/rhaomi/data/media` |
| Mac publisher state | Y 취급 | `/private/var/lib/rhaomi/state/publisher` |
| Mac global lock | N | `/private/var/lib/rhaomi/state/locks` |
| Mac logs | Y 취급 | `/private/var/lib/rhaomi/logs` |

production release manifest에는 exact `main` SHA, image tag·digest, Flyway version, release ID, SBOM reference와 public build의 `contentRevision`, `publishGeneration`, `generatedAt`을 기록한다. actual ownership, UID/GID, rendered named-volume name과 Secret source는 provisioning에서 확정하며 Git에 실제 값을 기록하지 않는다.

### production mount mapping — planned

| Mac source 또는 Docker source | Linux container target | access |
|---|---|---|
| `/private/var/lib/rhaomi/public` | web·publisher `/srv/rhaomi/public` | web RO, publisher RW |
| `/private/var/lib/rhaomi/data/media` | backend·publisher·backup `/var/lib/rhaomi/media` | backend RW, publisher·backup RO |
| `/private/var/lib/rhaomi/state/publisher` | publisher `/var/lib/rhaomi/publisher` | RW |
| `/private/var/lib/rhaomi/state/locks` | publisher `/var/lib/rhaomi/locks` | RW |
| production project-scoped named volume | PostgreSQL image PGDATA target | PostgreSQL RW |

`/srv/rhaomi`는 이 표의 Linux container target에만 허용하고 Mac host path로 해석하지 않는다. PostgreSQL named volume은 일반 `docker compose down`에서 보존하며 production `down -v`, `docker volume prune`과 direct delete를 금지한다.

## 공개 frontend·release build — current local foundation / production provisioning planned

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `PUBLIC_SITE_URL` | N | canonical 기준 absolute URL |
| `BUILD_API_INTERNAL_URL` | N | Node adapter가 사용하는 absolute root `http|https` backend origin; userinfo/query/fragment/path 금지 |
| `BUILD_API_CREDENTIAL` | Y | `RHAOMI_BUILD_SERVICE_TOKEN`과 같은 secret source에서 publisher process에만 주입하는 64자 lowercase hex credential |
| `RHAOMI_PUBLISHER_NODE_EXECUTABLE` | N | absolute executable regular file |
| `RHAOMI_PUBLISHER_RELEASE_SCRIPT` | N | `scripts/publish-static-release.mts` absolute regular file |
| `RHAOMI_PUBLISHER_PROCESS_TERMINATION_GRACE` | N | child process graceful terminate, 기본 `2s`, 100ms~10s |
| `RHAOMI_PUBLISHER_SOURCE_ROOT` | N, 내부 경로 | package·tracked render source와 preinstalled `node_modules` root |
| `RHAOMI_PUBLISHER_WORK_ROOT` | Y 취급 | private per-run staging·HOME root |
| `RHAOMI_PUBLIC_RELEASE_ROOT` | N, 내부 경로 | immutable release package root |
| `RHAOMI_PUBLIC_CURRENT_LINK` | N, 내부 경로 | active site symlink |
| `RHAOMI_PUBLIC_PREVIOUS_LINK` | N, 내부 경로 | 직전 site symlink |
| `RHAOMI_CODE_SHA` | N | exact 40 lowercase hex code identity |
| `RHAOMI_CODE_IMAGE_TAG` | N | non-secret immutable image identity |
| `RHAOMI_CODE_IMAGE_DIGEST` | N | `sha256:<64 lowercase hex>` |
| `RHAOMI_FLYWAY_VERSION` | N | release DB contract version, 현재 `9` |
| `RHAOMI_SBOM_REFERENCE` | N | `sha256:<64 lowercase hex>` reference |
| `RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS` | N | Next build timeout, 1,000~3,600,000ms; 미설정 600,000ms |
| `RHAOMI_RELEASE_RETENTION` | N | 1~100, 기본 5; current·previous 별도 보호 |

backend의 `RHAOMI_BUILD_SERVICE_TOKEN`과 internal read-only API, 위 설정을 사용하는 Node full release adapter·Java executor는 구현됐다. Java publisher bean 생성 시 build/public root URL, credential, absolute source/work/release/current/previous 관계, build timeout·retention을 Node acceptance와 같은 범위로 fail-fast 검증한다. adapter는 URL/credential과 generation을 request 전에 다시 fail-closed 검증하고 credential을 argv·query·path·출력에 넣지 않으며 request body까지 fixed 10초 runtime default 안에 완료한다. browser/frontend/gateway에는 build credential이나 `NEXT_PUBLIC_` 변수를 주입하지 않고 credential file도 mount하지 않으며 dev/public Nginx가 build namespace를 차단한다. 실제 production publisher process의 environment·secret/image/path provisioning은 아직 구현되지 않았다.

## Static publisher control loop

publisher는 normal backend profile이나 환경변수만으로 시작하지 않는다. executable jar에 exact `--rhaomi.publisher.mode=control-loop` command-line argument를 전달했을 때만 별도 non-web root를 선택한다. 이 process는 controller·web server·admin bootstrap을 구성하지 않고 Flyway migration도 실행하지 않는다.

| 변수 | 비밀 | 기본값 | 설명 |
|---|---:|---|---|
| `RHAOMI_PUBLISHER_OWNER` | N | 빈 값, publisher mode에서 기동 실패 | process lifetime 동안 stable한 최대 128 code-point owner |
| `RHAOMI_PUBLISHER_IDLE_POLL_INTERVAL` | N | `1s` | claim 없음·safe iteration failure 뒤 bounded wait |
| `RHAOMI_PUBLISHER_LEASE_DURATION` | N | `2m` | existing state service claim lease |
| `RHAOMI_PUBLISHER_LEASE_RENEWAL_INTERVAL` | N | `30s` | debounce·executor heartbeat, lease의 절반 이하 |
| `RHAOMI_PUBLISHER_SHUTDOWN_TIMEOUT` | N | `10s` | lifecycle caller의 bounded worker join; 미종료 executor의 lock lifetime을 단축하지 않음 |
| `RHAOMI_PUBLISHER_LOCK_FILE` | N, 내부 경로 | `/var/lib/rhaomi/locks/publisher.lock` | executor 직전 획득하고 physical termination acknowledgment까지 유지하는 empty advisory lock file |
| `RHAOMI_PUBLISHER_AUTO_START` | N | `true` | explicit publisher root 안의 lifecycle gate, normal backend activation 수단 아님 |

첫 accepted generation 기준 debounce는 고정 30초이며 환경변수로 바꿀 수 없다. `T0 + 30s` trigger를 포함하고 이후 trigger는 다음 window에 남긴다. poll·lease·renew·shutdown duration은 positive bounded 값이고 owner에는 credential·path를 넣지 않는다.

현재 control loop는 immediate pending, due scheduled, same-generation retry와 expired lease recovery를 처리하고 highest generation coalesce·lease heartbeat·global lock·typed result mapping을 제공한다. 실제 executor는 fixed argv Node release CLI를 호출해 Build API→transformer→Next→manifest→atomic switch를 수행한다. cancellation은 wrapper의 callable 종료뿐 아니라 Node root·관찰한 descendant의 physical exit까지 확인하고, 그 전에는 lock과 non-daemon control worker를 유지한다. shutdown timeout은 lifecycle caller의 대기만 제한하고 lock을 먼저 넘기지 않으며 외부 process termination 때 child와 OS lock이 함께 정리된다. default `compose.dev.yaml`은 publisher service를 자동 기동하지 않으며 public/dev Nginx route와 Docker socket도 추가하지 않는다.

아래 release filesystem 값은 구현된 executor의 container-side production target이다. local/CI는 temp path를 사용하며 production secret·mount provisioning은 후속 gate다.

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `BUILD_API_CREDENTIAL` | Y | admin session과 분리된 read-only service credential |
| `RHAOMI_PUBLISHER_WORK_ROOT` | Y 취급 | publisher process의 non-DB local staging state, planned `/var/lib/rhaomi/publisher` |
| `RHAOMI_PUBLIC_RELEASE_ROOT` | N | publisher container 내부 `/srv/rhaomi/public/releases` |
| `RHAOMI_PUBLIC_CURRENT_LINK` | N | publisher container 내부 `/srv/rhaomi/public/current` |
| `RHAOMI_PUBLIC_PREVIOUS_LINK` | N | publisher container 내부 `/srv/rhaomi/public/previous` |
| `RHAOMI_RELEASE_RETENTION` | N | 성공 release 5개, current·previous 항상 보존 |

`compose.dev.yaml`의 `publisher-validation` profile은 Java 25·Node 24·libheif를 한 image에 고정하고 synthetic PostgreSQL/credential/domain/temp release root로 실제 full pipeline test만 실행한다. default service 집합이나 public port를 늘리지 않으며 runtime `npm install`을 수행하지 않는다. 이 validation image는 ADR-014 production decoder-only image가 아니다. int64 E2E 하네스는 `RHAOMI_INT64_NODE_MODULES_VOLUME`·`RHAOMI_INT64_GRADLE_CACHE_VOLUME`로 해당 task의 사전 생성·label 확인 cache volume을 명시하고, `RHAOMI_CLEANUP_TASK`를 모든 임시 container/network label에 동일하게 적용한다. 지정 volume이 없으면 암묵 생성하지 않고 중단한다.

## Production deploy·migration — planned

- protected GitHub `production` environment의 수동 승인 뒤에만 environment secret을 사용한다.
- exact `main` SHA tag와 image digest를 모두 확인하고 digest 기준으로 배포한다.
- Tailscale SSH와 고정·versioned deploy entrypoint를 사용하며 임의 shell body를 전달하지 않는다.
- production backend 일반 기동은 Flyway mutation을 수행하지 않고 schema validate만 한다.
- Flyway는 global deploy lock·write maintenance 안의 one-shot service로만 실행한다.
- production session cookie는 TLS에서 `Secure=true`가 아니면 기동을 실패시킨다.
- 실제 Mac mini에서 canonical directory 생성·ownership·permission, public/media/state bind mount와 PostgreSQL named volume identity를 검증한다.
- PostgreSQL restart와 일반 Compose `down`·`up` 뒤 data persistence를 검증하고 `down -v`·volume prune/delete가 고정 entrypoint·runbook에 없음을 확인한다.
- application-consistent backup을 새 isolated PostgreSQL named volume에 `pg_restore`해 복구 authority를 확인한다.

이 environment, secret, entrypoint와 one-shot service는 아직 생성하지 않았다.

## Backup·HomeOps inventory — planned

- 외장 SSD exact repository path는 `/Volumes/<provisioned-volume>/...` 아래에서 volume identity·용량·ownership과 함께 확정하고, iCloud Drive exact folder도 provisioning 전 출시 차단값으로 둔다.
- 두 destination은 별도 encrypted restic repository와 독립 key를 사용한다.
- local iCloud Drive repository snapshot/check와 Apple remote sync evidence를 분리하고 local/offsite RPO를 별도 상태로 제공한다.
- 최초 production은 second trusted device 또는 local cache를 authority로 쓰지 않는 clean retrieval path의 fresh retrieval·restic check·대표 restore를 요구한다.
- restic password는 root-owned 제한 파일 또는 macOS Keychain password command로 공급하고 값 자체를 env example·log에 넣지 않는다.
- HomeOps endpoint·identity와 Discord 수신자는 Rhaomi public configuration에 넣지 않고 Tailscale·운영 Secret 경계에서 관리한다.
- Rhaomi는 privacy-safe health/status/event/metric source만 제공한다.

backup repository, key와 HomeOps 설정은 아직 생성·변경하지 않았다.

## `.env.example`

- 변수 이름과 비밀 아닌 기본값
- local 전용 placeholder 설명
- 실제 key, password, 실사용 email 금지
- 운영 `.env`와 credential source를 참조하지 않음

현재 example에는 default Compose가 쓰는 PostgreSQL, session cookie, 비활성 local/test bootstrap, 빈 backend-only build token·Build API adapter 값과 local-safe private media root만 둔다. actual `.env.dev.local`은 frontend filesystem에 mount하지 않는다. publisher는 default Compose service가 아니므로 full executor의 path·code identity 설정을 `.env.example`에 기본 주입하지 않는다. production deploy, publisher credential/path, backup와 HomeOps secret은 해당 implementation Issue 전까지 추가하지 않는다.

## domain — 후속

```text
PUBLIC_SITE_URL=https://<domain>
ADMIN_PATH=https://<domain>/admin
API_BASE_URL=https://<domain>/api
```

domain을 바꾸면 canonical, sitemap, robots, Open Graph, 검색엔진 등록, same-origin proxy/CSP, TLS와 session cookie Secure 설정을 함께 갱신한다. 관리자 browser에 CORS allowlist를 추가하는 방식으로 우회하지 않는다.

## 버전 정책

- `package-lock.json`과 Gradle Wrapper 커밋
- Docker image 명시 version 또는 digest
- Node.js와 Java는 project가 검증한 version 고정
- Spring Boot major/minor upgrade는 Java·Gradle·Spring Security 호환성 검토
- PostgreSQL major upgrade는 logical backup/restore 또는 공식 upgrade 계획 필요
