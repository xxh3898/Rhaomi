---
title: "환경설정"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-30"
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

## 공개 frontend build — planned

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `PUBLIC_SITE_URL` | N | canonical 기준 absolute URL |
| `SITE_ENV` | N | local/development/production |
| `BUILD_API_INTERNAL_URL` | N | Docker 내부 read-only build API URL |
| `BUILD_API_CREDENTIAL` | Y | 후속 publisher가 `RHAOMI_BUILD_SERVICE_TOKEN`과 같은 secret source에서 주입받을 read-only service credential |
| `CONTENT_SNAPSHOT_PATH` | N | 생성 파일 경로 |
| `MEDIA_OUTPUT_PATH` | N | 공개 파생본 경로 |
| `BUILD_RELEASE_ID` | N | release 식별자 |
| `BUILD_CONTENT_REVISION` | N | 콘텐츠 mutation snapshot revision |
| `BUILD_PUBLISH_GENERATION` | N | public trigger의 monotonic sequence와 stale switch authority |
| `BUILD_TIMESTAMP` | N | snapshot `generatedAt`, notice published·expiry 판정 기준 시각 |

backend의 `RHAOMI_BUILD_SERVICE_TOKEN`과 internal read-only API는 구현됐다. token은 64자 lowercase hex만 허용하고 production 누락·형식 오류는 startup failure다. browser/frontend에는 build credential이나 `NEXT_PUBLIC_` 변수를 주입하지 않고 credential file도 mount하지 않으며 dev/public Nginx가 build namespace를 차단한다. 위 `BUILD_API_INTERNAL_URL`·`BUILD_API_CREDENTIAL` 이름을 포함한 실제 publisher 주입과 production secret provisioning은 아직 구현되지 않았다.

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

현재 control loop는 immediate pending, due scheduled, same-generation retry와 expired lease recovery를 처리하고 highest generation coalesce·lease heartbeat·global lock·typed result mapping을 제공한다. cancellation은 executor wrapper의 실제 진입·종료 상태를 추적하며, interrupt 뒤에도 body가 살아 있으면 lock과 non-daemon control worker를 유지한다. shutdown timeout은 lifecycle caller의 대기만 제한하고 lock을 먼저 넘기지 않으며, 외부 process termination 때 executor와 OS lock이 함께 정리된다. placeholder executor는 항상 transient failure를 반환해 public release를 만들지 않는다. default `compose.dev.yaml`은 publisher service를 자동 기동하지 않으며 public/dev Nginx route와 Docker socket도 추가하지 않는다.

아래 release adapter 변수는 후속 Phase 1C-8f6~8f7 planned contract다.

| planned 변수 | 비밀 | 설명 |
|---|---:|---|
| `BUILD_API_CREDENTIAL` | Y | admin session과 분리된 read-only service credential |
| `PUBLISHER_STATE_DIR` | Y 취급 | publisher process의 non-DB local state |
| `RELEASES_DIR` | N | publisher container 내부 `/srv/rhaomi/public/releases` |
| `CURRENT_LINK` | N | publisher container 내부 `/srv/rhaomi/public/current` |
| `PREVIOUS_LINK` | N | publisher container 내부 `/srv/rhaomi/public/previous` |
| `RELEASE_RETENTION` | N | 성공 release 5개, current·previous 항상 보존 |

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

현재 example에는 default Compose가 쓰는 PostgreSQL, session cookie, 비활성 local/test bootstrap, 빈 backend-only build token과 local-safe private media root만 둔다. actual `.env.dev.local`은 frontend filesystem에 mount하지 않는다. publisher는 default Compose service가 아니므로 위 non-secret control 설정과 planned credential을 `.env.example`에 주입하지 않는다. production deploy, publisher adapter credential, backup와 HomeOps secret은 해당 implementation Issue 전까지 추가하지 않는다.

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
