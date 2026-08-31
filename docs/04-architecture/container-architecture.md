---
title: "컨테이너 구조"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-01"
review_trigger: "서비스 배치·network·image 변경 시"
---

# 컨테이너 구조

## Phase 1C-7 local 개발 구성

`compose.dev.yaml`은 운영 구성과 분리된 `dev-rhaomi` project다.

| 서비스 | 고정 image | local 공개 | 영속화 |
|---|---|---|---|
| `frontend` | `node:24.20.0-alpine3.23` | 없음, gateway 전용 내부 3000 | `node_modules`, npm cache |
| `gateway` | `nginx:1.31.4-alpine3.24` | `127.0.0.1:3000` | 없음 |
| `backend` | exact Temurin 25 base + `libheif 1.23.0-r0` custom image | `127.0.0.1:8080` | Gradle cache, private media masters |
| `postgres` | `postgres:18.6-alpine3.23` | 없음 | backend PostgreSQL data |
| `smoke` | `node:24.20.0-alpine3.23` | 없음 | 없음 |
| `contract-check` | `node:24.20.0-alpine3.23` | 없음, network disabled | 기존 `node_modules`를 사용하는 frontend·repository contract 검증 |
| `publisher-validation` | exact Temurin 25 + Node 24 + `libheif 1.23.0-r0` validation image | 없음 | temp release·PostgreSQL full pipeline test, dependency cache만 공유 |
| `publication-acceptance-runner` | exact Temurin 25 + Node 24 + `libheif 1.23.0-r0` acceptance image | 없음 | task temp public/media/state root |
| `publication-acceptance-postgres` | `postgres:18.6-alpine3.23` | 없음 | container tmpfs, durable volume 없음 |
| `publication-acceptance-admin-gateway` | `nginx:1.31.4-alpine3.24` | 없음 | fixture 생성 중 same-origin `/api/admin/**`만 proxy |
| `publication-acceptance-static` | `nginx:1.31.4-alpine3.24` | 없음 | backend 중단 뒤 `current` read-only serving |
| `publication-acceptance-smoke` | acceptance image의 Node 24 | 없음 | public-only network의 HTTP/SEO/접근성 검증 |

- frontend·gateway·PostgreSQL·smoke·contract-check는 검증한 multi-architecture manifest digest를 고정한다. backend Dockerfile은 exact Temurin manifest digest와 `libheif=1.23.0-r0`·`libheif-dev=1.23.0-r0`를 고정한다.
- `frontend`와 `gateway`는 frontend profile에서 함께 실행하고 `smoke`는 smoke profile, `contract-check`는 validation profile, `publisher-validation`은 동명 explicit profile에서만 실행한다.
- browser는 gateway `127.0.0.1:3000`만 사용한다. `/api/**`는 backend, 그 밖의 path와 HMR WebSocket은 frontend로 전달한다.
- frontend·gateway·smoke는 `frontend-local`, gateway·backend는 `backend-gateway-internal`, backend·PostgreSQL은 `backend-internal`에서 통신한다.
- gateway와 PostgreSQL은 network를 공유하지 않는다.
- backend만 별도 `dev-rhaomi-backend-local` network와 loopback port를 사용한다.
- PostgreSQL, backend, frontend와 gateway healthcheck가 통과해야 frontend stack을 정상으로 본다.
- 실제 값은 host Compose CLI가 Git 제외 `.env.dev.local`과 process 환경에서 읽어 필요한 service에만 주입한다.
- frontend는 repository root를 mount하지 않고 `src`, package manifest, Next·TypeScript config와 credential isolation script만 read-only allowlist mount한다. `.env*`, `backend/`, local secret/config와 private runtime data는 frontend filesystem에 없다.
- `contract-check`는 repository-wide Node contract에 필요한 tracked source만 read-only mount하고 network를 끈다. actual `.env.dev.local`은 mount하지 않으며 build token environment도 받지 않는다.
- `publisher-validation`은 source/config를 allowlist mount하고 test process 내부의 synthetic build token·domain·temp filesystem만 사용해 PostgreSQL→Build API→Sharp→Next→release switch→DB completion을 검증한다. default stack이나 public gateway에 publisher를 추가하지 않으며 production image·secret·path 계약으로 사용하지 않는다.
- `publication-acceptance` explicit profile은 `.env.example`로 시작해 task-scoped bootstrap/DB credential을 process에서만 구성한다. actual Admin HTTP 중에는 backend-only internal network의 gateway·runner·tmpfs PostgreSQL만 사용하고, release 후 세 service를 모두 중단한 뒤 public-only network의 static Nginx에 `current` root를 read-only mount한다.
- acceptance root는 marker가 있는 `mktemp` directory이며 Docker durable volume을 만들지 않는다. script는 일반 `down`과 exact marker root만 정리하고 `down -v`, volume/image prune·delete를 실행하지 않는다.
- local/test 관리자 bootstrap은 기본 비활성이며 production profile에서 사용할 수 없다.
- 일반 종료는 named volume을 보존하는 `docker compose ... down`을 사용한다.
- `backend-media-masters` volume은 backend만 `/var/lib/rhaomi/media`에 mount하고 frontend·smoke에는 mount하지 않는다.

## 개발 volume 경계

```text
dev-rhaomi-frontend-node-modules
dev-rhaomi-frontend-npm-cache
dev-rhaomi-backend-gradle-cache
dev-rhaomi-backend-media-masters
dev-rhaomi-postgres-18-backend-data
```

frontend dependency install과 runtime은 같은 `node_modules`·npm cache volume만 공유한다. credential은 이 cache에 주입하지 않으며 Compose smoke가 frontend environment, root mount와 generated output을 포함한 token literal 부재를 확인한다.

이전 Directus 개발 volume은 새 Compose에서 참조하지 않는다. 실제 운영 data가 아니더라도 이 Issue에서 삭제하지 않으며 별도 cleanup 승인 대상으로 남긴다.

## 운영 목표 구성 — source implemented, provisioning planned

```mermaid
flowchart TB
    Internet[Internet] --> Cloudflare[Cloudflare DNS / HTTPS / Tunnel]
    Cloudflare --> Edge[기존 host edge Nginx]
    Edge --> Web[Rhaomi project web Nginx<br/>host loopback]

    Web -->|/, /admin/, assets| Static[container /srv/rhaomi/public/current]
    Web -->|/api/admin/**| Backend[Spring Boot]

    Backend --> Postgres[(PostgreSQL)]
    Postgres --> PgVolume[(project-scoped Docker named volume)]
    Backend --> Media[(Mac /private/var/lib/rhaomi/data/media)]
    Backend --> Outbox[(immediate / scheduled publishing event)]

    Publisher[Single internal publisher] -->|internal read-only build API| Backend
    Publisher --> Releases[(Mac /private/var/lib/rhaomi/public/releases)]
    Releases --> Static

    Backup[Backup job] --> Postgres
    Backup --> Media
    Backup --> LocalBackup[(separate protected Mac local backup repository)]
    LocalBackup -. future hardening .-> SSD[(encrypted external SSD restic)]
    SSD -. future hardening .-> ICloud[(separate encrypted iCloud restic)]

    HomeOps[HomeOps] -. health / event / metric .-> Web
    HomeOps -. internal health .-> Backend
    HomeOps -. 상태 .-> Publisher
    HomeOps -. 상태 .-> Backup
```

local private media volume·upload API, same-origin development gateway, dedicated publisher control loop와 Build API→transformer→Next→immutable release/atomic switch data plane을 구현했다. network-disabled Node suites, explicit `publisher-validation`, `publication-acceptance` Java 25/Node 24 harness가 Sharp·filesystem symlink·DB completion에 더해 actual Admin HTTP·scheduled boundary·backend 중단 후 static serving을 amd64/arm64에서 검증한다. 위 production topology와 lossless wire 계약은 [ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md)~[ADR-015](../09-decisions/ADR-015-lossless-int64-json-wire-contract.md)에서 승인했다. D-IMP-1 decoder-only application image에 이어 D-IMP-2 `compose.production.yaml`, project Nginx와 task validation overlay를 구현했다. actual Secret·Mac ownership/path·production volume·ingress/deploy·backup·HomeOps provisioning은 여전히 미완료다.

초기 production backup은 [ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md)의 2026-08-31 개정에 따라 protected source와 분리된 Mac mini local repository만 사용한다. diagram의 외장 SSD·iCloud 경로는 future hardening이며 초기 production blocker가 아니다. 미구성 상태를 offsite `PASS`로 표시하지 않는다.

## Production application image — implemented, unprovisioned

`backend/Dockerfile.production`이 이후 backend와 non-web publisher service가 같은 code image/digest를 사용하기 위한 canonical source다.

- final base는 exact Temurin Java 25 JRE digest이고 exact Node `24.20.0` runtime을 포함한다.
- libheif `v1.23.1` exact commit·archive SHA-256에서 source build하며 Alpine libde265 `1.0.16-r0`만 codec backend로 link한다.
- CMake의 codec/plugin과 root option inventory 전체를 고정하고 libde265 외 codec, encoder, dynamic plugin, experimental path를 fail-closed `OFF`로 검증한다.
- final image에는 backend executable JAR, package-lock 기반 production Node graph, Next build용 pinned TypeScript/type package와 tracked publisher source/config만 allowlist로 포함한다.
- x265 package·link·plugin, npm·compiler·Git·CMake·header/source tree·Gradle/npm cache와 test source는 final image에서 제외한다.
- backend와 publisher의 service-specific argv/profile·container target은 D-IMP-2 Compose가 고정한다. UID/GID, Mac bind ownership, production Secret과 image registry identity는 D-IMP-3 및 host provisioning에서 확정한다. Dockerfile은 이를 임의 결정하는 `ENTRYPOINT`나 production 값을 bake하지 않는다.

`scripts/validate-production-image.sh`는 task container/network와 tmpfs PostgreSQL만 사용해 final image surface, publisher Static Export, actual Admin HTTP HEIC/HEIF normalization, sequence·AVIF 오류 계약, CycloneDX SBOM과 Grype evidence를 native amd64/arm64에서 검증한다. generated evidence는 source에 커밋하지 않고 Hosted artifact와 review evidence로 보존한다. 이 image를 build한 사실은 GHCR publish나 production 배치를 뜻하지 않는다.

## Production Compose·project Nginx — implemented, unprovisioned

`compose.production.yaml`은 external exact image만 사용하는 `rhaomi-web`, `backend`, `publisher`, `postgres` 네 service와 세 internal network를 canonical inventory로 둔다. web은 UID/GID `101:101`과 같은 owner의 bounded tmpfs로 non-root 실행하며 required loopback port만 publish하고 `/api/admin/**`만 backend로 전달한다. backend/publisher는 같은 image를 사용하며 normal process의 Flyway와 bootstrap은 비활성이다. PostgreSQL은 project-scoped `postgres-data` named volume을 `/var/lib/postgresql`에 사용하고 host bind를 사용하지 않는다.

`compose.production.validation.yaml`은 base host source를 바꾸지 않고 validation run에서만 marker-owned temp public/media/state source와 validation-only schema bootstrap을 덮어쓴다. `scripts/validate-production-compose.sh`는 rendered contract, actual mount mode·network·port, static/admin/deny route, internal Build API authentication, 일반 `down` 뒤 sentinel·volume identity를 native architecture에서 검증한다. task container/network는 정리하고 task PostgreSQL volume은 삭제하지 않는다. 이 증거는 actual `/private/var/lib/rhaomi` ownership·permission이나 production volume·Secret·FQDN provisioning이 아니다.

## 서비스 책임

| 서비스 | 책임 | 외부 공개 |
|---|---|---|
| host edge Nginx | Cloudflare Tunnel의 기존 host 진입점과 project loopback route | Tunnel을 통해서만 |
| `rhaomi-web` | 정적 파일, same-origin `/api/admin/**` reverse proxy와 public deny rule | host loopback |
| `backend` | 관리자 session/auth, 콘텐츠 API, private media 검증·정규화·master 소유 | Nginx를 통해서만 |
| `postgres` | 관리자와 후속 콘텐츠 데이터 영속화 | 금지 |
| `publisher` | immediate pending·due scheduled event, overdue recovery, 두 revision, 30초 debounce, lock, snapshot·derivative·Static Export·atomic switch | 금지 |
| `backup` | application-consistent DB·private master local backup set과 isolated restore evidence; future external/offsite hardening | 금지 |
| HomeOps | 중앙 health·event·metric, incident·Activity·Discord와 제한된 자동 복구 | Tailscale 전용 |

## network 원칙

```text
host ingress
- cloudflared → host edge Nginx → loopback rhaomi-web

project application
- rhaomi-web → backend

data internal
- backend → PostgreSQL
- backend → private media

publisher internal
- publisher → read-only build API
- publisher → public releases/state/locks

ops internal
- backup → PostgreSQL/private media/local backup repository
- HomeOps → privacy-safe health/status/event/metric
```

- public path에서는 Rhaomi project web만 진입점이 되고 backend direct port와 PostgreSQL은 공개하지 않는다.
- public Nginx는 `/api/build/**`, `/internal/**`와 `/actuator/**`를 명시적으로 거부한다.
- publisher는 public network와 Docker socket을 사용하지 않는다.
- backup과 HomeOps는 project public network에 join하지 않는다.
- HomeOps UI·운영 endpoint와 Tailscale SSH는 public site와 분리한다.

## 운영 영속 경로 — source contract implemented, host provisioning planned

```text
/private/var/lib/rhaomi/
├── app/
├── public/
│   ├── releases/<release-id>/
│   ├── current -> releases/...
│   └── previous -> releases/...
├── data/
│   └── media/
├── state/
│   ├── publisher/
│   └── locks/
└── logs/
```

위 경로가 macOS host canonical contract다. `/srv/rhaomi`는 host bind source가 아니며 `synthetic.conf`나 Docker Desktop custom File Sharing을 production dependency로 두지 않는다.

| data | Mac host source | container target | lifecycle |
|---|---|---|---|
| public release | `/private/var/lib/rhaomi/public` | web·publisher `/srv/rhaomi/public` | host bind, web RO·publisher RW |
| canonical media | `/private/var/lib/rhaomi/data/media` | backend·publisher·backup `/var/lib/rhaomi/media` | host bind, backend RW·그 외 RO |
| publisher state | `/private/var/lib/rhaomi/state/publisher` | publisher `/var/lib/rhaomi/publisher` | host bind RW |
| global lock | `/private/var/lib/rhaomi/state/locks` | publisher `/var/lib/rhaomi/locks` | host bind RW |
| PostgreSQL PGDATA | production project-scoped named volume | PostgreSQL image PGDATA target | Docker-managed persistent volume |

base Compose의 source/target·mode와 task overlay의 rendered mount·named-volume persistence는 local/CI에서 검증한다. actual host ownership·permission, production rendered volume identity와 bind smoke는 provisioning에서 검증한다. container 삭제와 일반 Compose `down`은 PostgreSQL data를 삭제하지 않아야 하며 production `down -v`, volume prune/delete를 금지한다. DB backup/restore는 raw volume copy가 아니라 `pg_dump -Fc`·`pg_restore`를 authority로 사용한다.

## 공개 route — project Nginx implemented, ingress provisioning planned

```text
https://<public-domain>/          → 정적 사이트와 /admin auth shell
https://<public-domain>/api/admin/** → Spring Boot
```

- 최종 domain은 미정이다.
- 관리자 route의 noindex는 접근제어가 아니며 backend session·CSRF가 업무 요청을 보호한다.
- production session cookie는 TLS와 `Secure=true`가 필수다.
- `/api/build/**`, `/internal/**`, `/actuator/**`는 public Nginx에서 거부한다.

## 버전 고정

- `latest` tag 금지
- Node.js, Java image, Spring Boot, Gradle Wrapper, PostgreSQL, Nginx를 검증한 명시 버전으로 고정
- 운영 update는 backup, staging 검증과 rollback 계획이 있는 별도 PR로 수행
- production backend native image는 pinned source의 decoder-only libheif·libde265를 사용하고 x265 package·link 부재를 검증
