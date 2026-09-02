---
title: "환경설정"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-02"
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
| `RHAOMI_ADMIN_WEBAUTHN_REQUIRED` | N | local 기본 `false`, production은 반드시 `true`인 WebAuthn 2차 인증 gate |
| `RHAOMI_WEBAUTHN_RP_ID` | N | browser credential scope의 exact RP ID; wildcard 금지 |
| `RHAOMI_WEBAUTHN_ORIGIN` | N | production은 path·query·fragment 없는 승인된 HTTPS origin과 443만 허용 |
| `RHAOMI_WEBAUTHN_RP_NAME` | N | passkey 등록 화면에 표시할 nonblank RP 이름 |
| `RHAOMI_WEBAUTHN_CHALLENGE_TTL` | N | challenge single-use TTL, production 1~10분·현재 5분 |
| `RHAOMI_BUILD_SERVICE_TOKEN` | Y | backend-only internal build API의 64자 lowercase hex Bearer credential |

Compose는 `POSTGRES_*`에서 backend의 `SPRING_DATASOURCE_*`를 내부 service hostname으로 구성한다. `RHAOMI_BUILD_SERVICE_TOKEN`도 backend environment에만 전달하며 frontend·gateway environment와 filesystem에는 전달하지 않는다. WebAuthn RP 설정도 backend server configuration에만 두고 browser에는 ceremony response의 public RP/origin 정보만 전달한다. production Compose는 WebAuthn을 강제로 활성화하고 RP ID·origin·name을 required interpolation으로 받으며 실제 값은 별도 provisioning gate에서 확정한다.

## private media

| 변수 | 비밀 | 기본값 | 설명 |
|---|---:|---|---|
| `RHAOMI_MEDIA_ROOT` | N, 내부 경로 | Compose `/var/lib/rhaomi/media` | backend 전용 temp·master의 동일 filesystem root |

- root가 비었거나 temp/master directory를 만들고 쓸 수 없거나 두 directory가 다른 filesystem이면 backend 기동을 실패시킨다.
- local Compose는 별도 persistent `dev-rhaomi-backend-media-masters` volume을 backend에만 mount한다.
- source 20 MiB, stored 30 MiB, width·height 12,000px, total 60MP, JPEG quality 92는 현재 application contract로 고정하며 client request나 공개 env로 변경하지 않는다.
- `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED`는 pinned FFM 기반 HEIC adapter 실행에 필요하며 development backend, canonical production image와 Gradle test/bootRun에만 적용한다.
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

## production filesystem·release inventory — source implemented, host provisioning planned

| 항목 | 비밀 | 계약 |
|---|---:|---|
| Mac application root | N | `/private/var/lib/rhaomi/app` |
| Fixed Compose inventory | N | `/private/var/lib/rhaomi/app/compose.production.yaml` |
| Fixed production environment | Y | `/private/var/lib/rhaomi/app/production.env`, owner-only `0600`, caller-supplied path 금지 |
| Fixed Docker credential config | Y | `/private/var/lib/rhaomi/app/docker/config.json`, directory `0700`·file `0600` |
| Fixed deploy entrypoint | N | `/private/var/lib/rhaomi/app/bin/deploy-rhaomi.sh` |
| Fixed backup entrypoint | N | `/private/var/lib/rhaomi/app/bin/backup-rhaomi.sh`; repository path CLI override 금지 |
| Fixed first-activation entrypoint | N | `/private/var/lib/rhaomi/app/bin/first-activate-rhaomi.sh`; `bootstrap|accept-recovery` fixed action과 exact SHA/digest만 허용 |
| Fixed first-activation Compose | N | `/private/var/lib/rhaomi/app/compose.production.first-activation.yaml`; host port·production public/media bind 없는 recovery-only inventory |
| Fixed HomeOps status entrypoint | N | `/private/var/lib/rhaomi/app/bin/status-rhaomi.py`; argument·public route 없음 |
| Fixed HomeOps event adapter | N | `/private/var/lib/rhaomi/app/bin/report-rhaomi-event.py`; reporter/URL/Secret override 없음 |
| Fixed recovery target | N | `/private/var/lib/rhaomi/app/bin/recover-rhaomi-service.py restart rhaomi-web\|backend`; shared lock과 exact allowlist |
| Backup Docker CLI | Y | fixed wrapper `PATH`의 `docker` + standalone `docker-compose`; owner-only Docker config 사용, actual binary/version/권한 provisioning 필요 |
| Tracked backup schedule source | N | `ops/production/com.rhaomi.backup.plist`; host-local 03:30, actual install 전 `Asia/Seoul` timezone 확인 |
| Mac release root | N | `/private/var/lib/rhaomi/public/releases` |
| Mac current/previous | N | `/private/var/lib/rhaomi/public/current`, `/private/var/lib/rhaomi/public/previous` |
| PostgreSQL primary PGDATA | Y 취급 | production Compose project-scoped Docker named volume, host bind source 없음 |
| Mac canonical media | Y 취급 | `/private/var/lib/rhaomi/data/media` |
| Mac publisher state | Y 취급 | `/private/var/lib/rhaomi/state/publisher` |
| Mac publisher build workspace | Y 취급 | `/private/var/lib/rhaomi/state/publisher/build-workspace`; image source는 RO, 이 target만 RW |
| Mac lock root | N | `/private/var/lib/rhaomi/state/locks`; deploy/backup은 같은 `rhaomi-deploy.lock`, publisher executor는 별도 `publisher.lock` 사용 |
| Deploy backup eligibility | Y 취급 | `/private/var/lib/rhaomi/state/deploy/backup-eligibility.json`과 exact hash에 결합된 4-line `backup-eligible.env`, 모두 `0600` |
| Production lifecycle | Y 취급 | `/private/var/lib/rhaomi/state/deploy/production-lifecycle.env`와 exact hash-bound `first-activation-bootstrap.json`·`first-activation-recovery.json`; owner-only `0600`, symlink 거부, atomic write |
| Local backup repository | Y 취급 | exact path는 provisioning input; fixed `production.env`의 단일 `RHAOMI_BACKUP_REPOSITORY_ROOT`, owner-only root/sets와 exact sentinel 필요 |
| Mac logs | Y 취급 | `/private/var/lib/rhaomi/logs` |

production release manifest에는 exact `main` SHA, image tag·digest, Flyway version, release ID, SBOM reference와 public build의 `contentRevision`, `publishGeneration`, `generatedAt`을 기록한다. actual ownership, UID/GID, rendered named-volume name과 Secret source는 provisioning에서 확정하며 Git에 실제 값을 기록하지 않는다.

### production mount mapping — canonical Compose source

| Mac source 또는 Docker source | Linux container target | access |
|---|---|---|
| `/private/var/lib/rhaomi/public` | web·publisher `/srv/rhaomi/public` | web RO, publisher RW |
| `/private/var/lib/rhaomi/data/media` | backend·publisher·backup `/var/lib/rhaomi/media` | backend RW, publisher·backup RO |
| `/private/var/lib/rhaomi/state/publisher` | publisher `/var/lib/rhaomi/publisher` | RW |
| `/private/var/lib/rhaomi/state/publisher/build-workspace` | publisher `/opt/rhaomi/source/.rhaomi-publication-work` | RW |
| `/private/var/lib/rhaomi/state/locks` | publisher `/var/lib/rhaomi/locks` | RW |
| provisioned `RHAOMI_BACKUP_REPOSITORY_ROOT` | `backup-tool` `/var/lib/rhaomi/backup-repository` | RW |
| `/private/var/lib/rhaomi/state/deploy` | `backup-tool` `/var/lib/rhaomi/deploy-state` | RW |
| provisioned `RHAOMI_BACKUP_REPOSITORY_ROOT` | `backup-verifier` `/var/lib/rhaomi/backup-repository` | RO |
| `/private/var/lib/rhaomi/state/deploy` | `backup-verifier` `/var/lib/rhaomi/deploy-state` | RO |
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
| `RHAOMI_FLYWAY_VERSION` | N | release DB contract version, 현재 `10` |
| `RHAOMI_SBOM_REFERENCE` | N | published OCI index의 `sha256:<64 lowercase hex>` digest. amd64/arm64 attached SBOM·provenance attestation을 소유하는 index authority |
| `RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS` | N | Next build timeout, 1,000~3,600,000ms; 미설정 600,000ms |
| `RHAOMI_RELEASE_RETENTION` | N | 1~100, 기본 5; current·previous 별도 보호 |

backend의 `RHAOMI_BUILD_SERVICE_TOKEN`과 internal read-only API, 위 설정을 사용하는 Node full release adapter·Java executor는 구현됐다. Java publisher bean 생성 시 build/public root URL, credential, absolute source/work/release/current/previous 관계, build timeout·retention을 Node acceptance와 같은 범위로 fail-fast 검증한다. adapter는 URL/credential과 generation을 request 전에 다시 fail-closed 검증하고 credential을 argv·query·path·출력에 넣지 않으며 request body까지 fixed 10초 runtime default 안에 완료한다. browser/frontend/web에는 build credential이나 `NEXT_PUBLIC_` 변수를 주입하지 않고 credential file도 mount하지 않으며 dev/public Nginx가 build namespace를 차단한다. D-IMP-2 Compose는 backend `RHAOMI_BUILD_SERVICE_TOKEN`과 publisher `BUILD_API_CREDENTIAL`을 같은 required source에서 서로 다른 key로 주입한다. actual production Secret·image digest·FQDN 값의 provisioning은 아직 수행하지 않았다.

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

아래 release filesystem 값은 구현된 executor와 D-IMP-2 Compose의 container-side production target이다. local/CI는 overlay temp path를 사용하며 production secret·host mount provisioning은 후속 gate다.

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `BUILD_API_CREDENTIAL` | Y | admin session과 분리된 read-only service credential |
| `RHAOMI_PUBLISHER_WORK_ROOT` | Y 취급 | publisher process의 non-DB local staging state, planned `/var/lib/rhaomi/publisher` |
| `RHAOMI_PUBLIC_RELEASE_ROOT` | N | publisher container 내부 `/srv/rhaomi/public/releases` |
| `RHAOMI_PUBLIC_CURRENT_LINK` | N | publisher container 내부 `/srv/rhaomi/public/current` |
| `RHAOMI_PUBLIC_PREVIOUS_LINK` | N | publisher container 내부 `/srv/rhaomi/public/previous` |
| `RHAOMI_RELEASE_RETENTION` | N | 성공 release 5개, current·previous 항상 보존 |

`compose.dev.yaml`의 `publisher-validation` profile은 Java 25·Node 24·libheif를 한 image에 고정하고 synthetic PostgreSQL/credential/domain/temp release root로 실제 full pipeline test만 실행한다. default service 집합이나 public port를 늘리지 않으며 runtime `npm install`을 수행하지 않는다. 이 validation image는 ADR-014 production decoder-only image가 아니다. int64 E2E 하네스는 `RHAOMI_INT64_NODE_MODULES_VOLUME`·`RHAOMI_INT64_GRADLE_CACHE_VOLUME`로 해당 task의 사전 생성·label 확인 cache volume을 명시하고, `RHAOMI_CLEANUP_TASK`를 모든 임시 container/network label에 동일하게 적용한다. 지정 volume이 없으면 암묵 생성하지 않고 중단한다.

## production image foundation — implemented, not deployed

| 항목 | canonical 값 |
|---|---|
| Dockerfile | `backend/Dockerfile.production` |
| Java final base | `eclipse-temurin:25.0.4_7-jre-alpine-3.23` exact digest |
| Node runtime | `node:24.20.0-alpine3.23` exact digest에서 복사한 runtime |
| libheif | `v1.23.1`, commit `2c4bbb54c2738d4a5efbbe3e5fa1d5d76bb88eb0`, archive SHA-256 고정 |
| libde265 | Alpine `1.0.16-r0`, 유일한 native codec backend |
| application | `/opt/rhaomi/backend.jar` |
| publisher source | `/opt/rhaomi/source`, runtime install 없이 preinstalled lockfile dependency 사용 |
| acceptance | `sh scripts/validate-production-image.sh` |

image에는 production credential, domain, Mac host path를 bake하지 않는다. `RHAOMI_PUBLISHER_NODE_EXECUTABLE`, release script와 source root의 container 내부 non-secret default만 제공한다. D-IMP-2 Compose는 backend/publisher argv·profile·credential key·public/media/state target과 source-root 아래 isolated build-workspace bind를 고정하고, 나머지 image source/config/dependency는 read-only로 유지한다. D-IMP-3 workflow와 fixed entrypoint는 exact digest·SHA·SBOM을 주입하는 소스 경계를 구현한다. actual image package·Secret·FQDN·ownership은 외부/host provisioning에서 확정한다.

## Production Compose inventory — implemented, not deployed

| 항목 | source contract |
|---|---|
| base | `compose.production.yaml` |
| validation overlay | `compose.production.validation.yaml` |
| project Nginx | `infra/nginx/production.conf` |
| runtime validator | `scripts/validate-production-compose.sh` |
| default service inventory | `rhaomi-web`, `backend`, `publisher`, `postgres` |
| opt-in one-shot inventory | `production-task` profile의 `migration`, `schema-validate`; `production-backup` profile의 network-disabled RW `backup-tool`과 read-only `backup-verifier`; 별도 first-activation Compose의 no-port verifier/tmpfs restore/backend/publisher/static smoke |
| network | web 전용 non-internal `loopback-edge`; `web-backend`, `build-internal`, `data-internal`은 internal |
| published port | `127.0.0.1:${RHAOMI_WEB_LOOPBACK_PORT}:8080`만 허용 |
| external origin | redirect는 relative `Location`; backend forwarded origin은 config가 고정한 `https:443` |
| DB persistence | Compose project-scoped `postgres-data`, container `/var/lib/postgresql` |

base는 `RHAOMI_PRODUCTION_COMPOSE_PROJECT`, exact `RHAOMI_PRODUCTION_IMAGE`, loopback port, PostgreSQL credential, build service token, publisher owner, public site URL과 release metadata를 required input으로 받는다. actual 값은 repository나 `.env.example`에 두지 않는다. validation overlay만 `RHAOMI_PRODUCTION_VALIDATION_ROOT`, cleanup task/head를 받아 task temp bind와 one-shot service label seam을 추가한다. normal backend/publisher의 `SPRING_FLYWAY_ENABLED=false`, bootstrap 비활성과 secure session cookie는 overlay에서도 바뀌지 않는다.

validator는 exact-HEAD production image를 재사용하고 Darwin에서는 `/private/var/tmp`, Hosted Linux에서는 runner temp에 marker root를 만든다. base `/private/var/lib/rhaomi`는 생성·수정하지 않는다. external형 synthetic Host의 `/admin` 요청이 `308`과 exact relative `Location: /admin/`을 반환하고 runtime loaded config가 client 입력이 아닌 `https:443`을 backend forwarded origin으로 사용하는지 확인한다. general `down`→`up` 뒤 task named-volume sentinel과 Flyway history를 확인하며 `down -v`, volume/image delete 또는 prune을 실행하지 않는다. task volume은 evidence와 함께 retained resource로 보고한다.

## Production deploy·migration — source implemented, external/host provisioning planned

- `.github/workflows/production-release.yml`은 `workflow_dispatch` only며 exact current `main` SHA와 요청 40자 SHA 일치를 검증한다. `deployment_mode=steady-state|first-activation`을 caller가 명시하고 runtime state로 추론하지 않는다. validation은 read-only, publish만 `packages: write`, deploy만 `environment: production`과 environment secret을 받는다.
- `backend/Dockerfile.production`의 required `RHAOMI_GIT_HEAD` build arg에 exact release SHA를 전달해 `linux/amd64`·`linux/arm64`를 exact SHA tag에 publish하고, 이미 존재하는 SHA tag는 덮어쓰지 않는다. apply authority는 returned manifest digest다. publish 뒤 platform manifest·attestation, OCI source/revision, attached SPDX SBOM·SLSA provenance와 attached-SBOM scan을 machine-check하며 local pre-publish evidence와 분리한다.
- protected GitHub `production` Environment 승인 뒤에만 pinned Tailscale identity와 fixed SSH target을 사용한다. remote argv는 `--release-sha`, `--image`, `--sbom`만 허용하고 credential을 전달하지 않는다.
- tracked `ops/production/deploy-rhaomi.sh`는 production에서 `/private/var/lib/rhaomi/app/bin/deploy-rhaomi.sh`로 versioned provisioning할 fixed wrapper다. fixed Compose/env/Docker config, backup eligibility와 global deploy lock를 검증하고 requested digest·OCI revision을 writer 정지 전에 확인한다.
- tracked `ops/production/first-activate-rhaomi.sh`는 predecessor 없는 host의 별도 fixed wrapper다. mutation 전에 verified-empty absence matrix와 `FIRST_ACTIVATION_BOOTSTRAPPING` evidence를 고정하고, public web 없는 exact image bootstrap 뒤 `RECOVERY_ACCEPTANCE_REQUIRED`만 만든다. fixed `first-activation` backup과 별도 no-port recovery Compose의 full-read/restore acceptance가 성공한 뒤에만 `STEADY_STATE`를 원자 기록한다.
- tracked HomeOps integration inventory는 compatibility JSON, shared Python core와 status/event/recovery entrypoint를 같은 fixed bin root에 versioned provisioning한다. actual HomeOps reporter absolute path를 Rhaomi env에 저장하지 않고 OS account home 아래 current HomeOps runtime inventory와 pinned owner·mode·SHA를 검증한다. HomeOps endpoint/HMAC secret은 Rhaomi `production.env`, container environment와 CLI에 없다.
- production backend/publisher 일반 기동은 Flyway mutation을 수행하지 않는다. global deploy lock을 보유한 채 public web을 유지하고 두 writer의 physical exit를 확인한 뒤에만 `migration`→`schema-validate`를 실행한다.
- migration은 기존 V1~V9를 수정하지 않고 additive V10까지 적용한 뒤 JPA validate를 수행하며, schema task는 Flyway를 끈 채 JPA validate만 수행한다. 두 task는 exact CLI opt-in, non-web, admin bootstrap·publisher worker 0이고 성공 후 종료한다.
- writer maintenance 시작 뒤 migration/schema/backend health/publisher start/runtime image identity 실패는 false success를 금지하고 backend/publisher를 다시 정지한다. quiescence 확인 뒤에만 own lock을 해제하며 확인 실패 시 lock을 보존한다. old writer를 자동 resume하지 않는다.
- production session cookie는 TLS에서 `Secure=true`가 아니면 기동을 실패시킨다.
- 실제 Mac mini에서 canonical directory 생성·ownership·permission, public/media/state/build-workspace bind mount, publisher image source의 workspace 외 read-only와 PostgreSQL named volume identity를 검증한다.
- PostgreSQL restart와 일반 Compose `down`·`up` 뒤 data persistence를 검증하고 `down -v`·volume prune/delete가 고정 entrypoint·runbook에 없음을 확인한다.
- application-consistent backup을 새 isolated PostgreSQL named volume에 `pg_restore`해 복구 authority를 확인한다.

workflow·fixed deploy/first-activation entrypoint·one-shot task source와 task-only validator는 구현했지만 private GHCR package/visibility, GitHub `production` Environment·required reviewer·branch policy·secret, Tailscale identity, actual Mac entrypoint/config/path/volume·loopback/FQDN은 생성·provision하지 않았다. production workflow도 dispatch하지 않았고 GHCR push·deploy·migration·backup·restore를 수행하지 않았다.

## Backup·HomeOps inventory — source implemented, provisioning planned

- 초기 production은 protected source와 분리된 Mac mini local backup repository/path를 provisioning 입력으로 확정한다. exact path·owner·permission·capacity는 저장소에 추측하지 않는다.
- local backup은 `pg_dump -Fc`와 canonical media를 동일 backup-set ID로 묶고 retention/check·isolated restore를 제공한다. raw PostgreSQL volume은 backup authority가 아니다.
- 초기 local-only backup은 Mac mini 전체 손실에서 production data와 함께 손실될 수 있는 accepted risk가 있다.
- 외장 SSD·iCloud 3-2-1, restic recovery key와 offsite RPO는 future hardening이며 초기 production blocker가 아니다. 미구성 상태는 `NOT_CONFIGURED / DEFERRED`로 표시한다.
- future hardening 도입 시 외장 SSD exact path는 `/Volumes/<provisioned-volume>/...` 아래에서 확인하고 iCloud local repository integrity와 Apple remote sync evidence를 분리한다. restic password는 root-owned 제한 파일 또는 macOS Keychain password command로 공급한다.
- HomeOps endpoint·identity와 Discord 수신자는 Rhaomi public configuration에 넣지 않고 Tailscale·운영 Secret 경계에서 관리한다.
- Rhaomi는 fixed privacy-safe bounded status, deployment/backup event adapter와 bounded recovery target source만 제공한다. Incident/notification/automatic recovery decision은 HomeOps authority다. HomeOps D-IMP-5b application과 V14 schema는 production에 배포됐지만 Rhaomi mapping·Agent capability는 provision하지 않았다.
- [Activation preflight](../../ops/production/homeops-activation-preflight.json)는 production HomeOps `main@0a8ce9090c76f5ad7afba19ca896e923b96b0cbf` pin, run `33569523762` application deploy·V14 `APPLIED`, Agent artifact `PUBLISHED`와 Agent rollout `NOT_RUN`, public HTTPS expected HTTP status 3회→`rhaomi-web` only mapping, backend unmapped, 30분 cooldown/no-auto-retry와 release 순서를 secret 없는 tracked contract로 분리한다. Keyword/body/content probe는 별도 HomeOps monitoring implementation 전 current trigger에서 제외한다.

Local backup repository와 HomeOps monitored-service/control/notification 설정은 아직 생성·변경하지 않았다. D-IMP-5a source도 actual Mac fixed inventory에 설치하지 않았고 mapping create/enable·Agent rollout·restart/drill도 수행하지 않았다. Production 순서는 HomeOps release → live compatibility 재검증 → Rhaomi release/provisioning이며 앞의 두 단계만 완료했다. Rhaomi release/provisioning은 별도 승인이다.

## `.env.example`

- 변수 이름과 비밀 아닌 기본값
- local 전용 placeholder 설명
- 실제 key, password, 실사용 email 금지
- 운영 `.env`와 credential source를 참조하지 않음

현재 example에는 default Compose가 쓰는 PostgreSQL, session cookie, 비활성 local/test bootstrap, 빈 backend-only build token·Build API adapter 값과 local-safe private media root만 둔다. actual `.env.dev.local`은 frontend filesystem에 mount하지 않는다. publisher는 default Compose service가 아니므로 full executor의 path·code identity 설정을 `.env.example`에 기본 주입하지 않는다. production deploy, publisher credential/path, backup와 HomeOps endpoint/HMAC secret은 추가하지 않는다.

## domain — provisioning

```text
PUBLIC_SITE_URL=https://<domain>
ADMIN_PATH=https://<domain>/admin
API_BASE_URL=https://<domain>/api
```

domain을 바꾸면 canonical, sitemap, robots, Open Graph, 검색엔진 등록, same-origin proxy/CSP, TLS와 session cookie Secure 설정을 함께 갱신한다. 관리자 browser에 CORS allowlist를 추가하는 방식으로 우회하지 않는다.

초기 production은 사용자 소유 기존 도메인을 임시 public domain으로 사용한다. exact FQDN은 repository에 하드코딩하지 않고 provisioning 입력으로 확정한다. 사촌 소유 전용 도메인이 준비되면 동일 topology에서 위 설정과 public smoke만 교체하며 DB migration이나 content schema 변경을 요구하지 않는다.

## 버전 정책

- `package-lock.json`과 Gradle Wrapper 커밋
- Docker image 명시 version 또는 digest
- Node.js와 Java는 project가 검증한 version 고정
- Spring Boot major/minor upgrade는 Java·Gradle·Spring Security 호환성 검토
- PostgreSQL major upgrade는 logical backup/restore 또는 공식 upgrade 계획 필요
