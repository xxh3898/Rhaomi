---
title: "ADR-010: Production topology와 코드 릴리스"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-01"
review_trigger: "운영 진입 경로·배포·마이그레이션·릴리스 보존 변경 시"
---

# ADR-010: Production topology와 코드 릴리스

- 결정일: 2026-08-29
- 상태: Accepted
- 관련 결정: [ADR-001](ADR-001-nextjs-static-export.md), [ADR-008](ADR-008-runtime-independent-public-site.md), [ADR-009](ADR-009-spring-boot-backend-admin.md)

## 맥락

Rhaomi는 macOS Mac mini에서 다른 홈서버 서비스와 함께 운영될 예정이며 공개 정적 사이트, same-origin 관리자 API, PostgreSQL과 private media를 서로 다른 신뢰 경계로 분리해야 한다. macOS의 root system volume은 일반 Linux host와 같은 writable `/srv` 계약을 제공하지 않고 Docker Desktop의 기본 host file sharing에도 `/srv`가 포함되지 않는다. `main` merge, image 생성, 운영 반영과 Flyway migration도 하나의 암묵적 자동 단계로 묶지 않아야 한다.

이 결정의 D-IMP-2 source implementation으로 production Compose와 project Nginx, task-scoped validation overlay를 추가했다. D-IMP-3은 exact-main release workflow·fixed deploy와 one-shot migration/schema validation을, D-IMP-4는 fixed shared-lock backup·strict complete set·release eligibility와 task-scoped isolated restore를 source로 구현했다. Cloudflare Tunnel·host edge, private GHCR package/visibility, actual GitHub Environment/reviewer/secret, Tailscale identity, Mac entrypoint/config/path/volume/backup repository/schedule/FQDN은 아직 provision되지 않았고 workflow dispatch·deploy·production migration·backup을 수행하지 않았다.

Issue #43은 같은 계약의 build·validate·private manifest·immutable install·`previous/current` switch·post-switch smoke·rollback·retention primitive를 격리된 local/CI filesystem과 actual Java→Node executor로 구현했다. 이 증거는 `/private/var/lib/rhaomi` ownership·Docker Desktop bind, production image/digest·secret, Nginx·Cloudflare, public HTTPS 또는 deploy entrypoint provisioning 완료를 뜻하지 않는다.

## 결정

### 외부 진입과 route

```text
Internet
→ Cloudflare DNS/HTTPS
→ Cloudflare Tunnel
→ 기존 host edge Nginx
→ Rhaomi project web Nginx
   ├─ /, /admin/, static assets → container `/srv/rhaomi/public/current`
   │                                  ← Mac `/private/var/lib/rhaomi/public`
   └─ /api/admin/**             → Spring Boot backend
```

- 공유기 port forwarding을 사용하지 않는다.
- `cloudflared`가 외부로 연결하며 Rhaomi project web은 host loopback에만 bind한다.
- public origin은 Cloudflare/host edge에서 종료되는 HTTPS다. project Nginx의 내부 HTTP scheme·8080·provisioning loopback port는 외부 redirect authority가 아니며, Nginx-generated redirect는 relative `Location`만 사용한다.
- project Nginx는 `/api/admin/**`에 `X-Forwarded-Proto: https`, `X-Forwarded-Port: 443`을 직접 설정한다. client가 보낸 forwarded scheme/port를 신뢰하거나 내부 `$scheme`을 external origin으로 전달하지 않는다.
- 고객 공개 화면과 `/admin/`은 같은 origin을 사용한다.
- `/admin/`은 검색 제외 대상일 뿐 인증 경계가 아니다. Spring session·CSRF, 출시 전 WebAuthn/passkey 2차 인증과 rate limit이 업무 경계다. password-only production은 허용하지 않는다.
- PostgreSQL, backend direct port, publisher, backup과 HomeOps는 public exposure가 없다.
- Tailscale은 SSH, HomeOps UI와 운영 장애 대응에만 사용한다.
- public Nginx는 `/api/build/**`, `/internal/**`와 `/actuator/**`를 거부한다. HomeOps의 최소 health 조회는 내부 경로를 사용한다.
- 초기 public domain은 사용자 소유 기존 도메인을 사용하되 exact temporary FQDN은 production provisioning 입력으로 확정한다. 사촌 소유 전용 도메인으로 바꿀 때는 동일 topology에서 DNS/Tunnel/Nginx host·`PUBLIC_SITE_URL`·canonical/OG/sitemap/robots·public smoke를 동기화하고 DB/schema는 변경하지 않는다.

### macOS production 디렉터리

```text
/private/var/lib/rhaomi/
├── app/
│   ├── bin/deploy-rhaomi.sh
│   ├── compose.production.yaml
│   ├── production.env
│   └── docker/config.json
├── public/
│   ├── releases/<release-id>/
│   ├── current -> releases/...
│   └── previous -> releases/...
├── data/
│   └── media/
├── state/
│   ├── publisher/
│   │   └── build-workspace/
│   ├── deploy/backup-eligible.env
│   └── locks/
└── logs/
```

- 위 경로는 Mac host의 canonical filesystem root다. `/srv/rhaomi`를 Mac host bind source로 만들거나 `synthetic.conf`, Docker Desktop custom File Sharing에 의존하지 않는다.
- public web에는 Mac `/private/var/lib/rhaomi/public`만 read-only로 mount한다.
- private DB·media를 web에 mount하지 않는다.
- backend만 canonical media를 read-write한다.
- PostgreSQL primary PGDATA는 host bind mount가 아니라 production Compose project-scoped Docker named volume을 사용한다. exact rendered volume name은 provisioning evidence에 기록하고 다른 project와 공유하지 않는다.
- named volume은 container lifecycle과 일반 `docker compose down`에서 보존한다. production에서 `docker compose down -v`, `docker volume prune`과 named volume 직접 삭제를 금지한다.
- PostgreSQL backup/restore authority는 `pg_dump -Fc`와 `pg_restore`다. raw named volume은 required restic backup input이 아니다.
- media는 Mac canonical root의 host bind mount로 container lifecycle과 분리한다.
- publisher 상태와 전역 lock은 `state`에 두고 release 산출물과 구분한다.
- `app`에는 versioned production Compose inventory와 고정 deploy entrypoint, owner-only production env·Docker credential config를 두되 production source build worktree로 사용하지 않는다. caller-supplied env/config path나 inherited Compose/Docker override를 production authority로 사용하지 않는다.
- `logs`에는 host-side deploy·publisher·backup evidence를 bounded·redacted 형태로 두고 service stdout/stderr의 Docker `local` driver rotation과 구분한다.

### host source와 container target

| 사용 주체 | Mac host source | Linux container target | mode |
|---|---|---|---|
| `rhaomi-web` | `/private/var/lib/rhaomi/public` | `/srv/rhaomi/public` | read-only |
| `publisher` release | `/private/var/lib/rhaomi/public` | `/srv/rhaomi/public` | read-write |
| `backend` media | `/private/var/lib/rhaomi/data/media` | `/var/lib/rhaomi/media` | read-write |
| `publisher`·`backup` media | `/private/var/lib/rhaomi/data/media` | `/var/lib/rhaomi/media` | read-only |
| `publisher` state | `/private/var/lib/rhaomi/state/publisher` | `/var/lib/rhaomi/publisher` | read-write |
| `publisher` isolated build workspace | `/private/var/lib/rhaomi/state/publisher/build-workspace` | `/opt/rhaomi/source/.rhaomi-publication-work` | read-write |
| `publisher` lock | `/private/var/lib/rhaomi/state/locks` | `/var/lib/rhaomi/locks` | read-write |
| `postgres` PGDATA | production project-scoped Docker named volume | image가 요구하는 PGDATA target | read-write |

container target은 Linux container 내부 경로일 뿐 Mac host filesystem authority가 아니다. application image의 source/config/dependency는 read-only로 유지하고 Next/Turbopack의 project-root 요구 때문에 필요한 `.rhaomi-publication-work` 한 경로만 별도 host state bind로 연다. actual UID/GID, ownership과 permission은 production provisioning에서 fail-closed로 검증한다.

### 코드 릴리스

```text
feature → dev
→ dev → main Release PR
→ exact main SHA GitHub-hosted 검증
→ immutable production image build
→ private GHCR
→ GitHub production environment 수동 승인
→ Tailscale SSH
→ Mac mini 고정·versioned deploy entrypoint
```

- public repository의 PR 코드를 production Mac mini runner에서 실행하지 않는다.
- `main` merge는 검증 가능한 release 후보를 만들 뿐 운영 반영을 자동 승인하지 않는다.
- production job은 보호된 environment 승인 뒤에만 environment secret에 접근한다.
- 임의 SSH command body 대신 exact SHA·digest와 제한된 매개변수만 받는 고정 entrypoint를 사용한다.
- image에는 commit SHA tag를 붙이고 실제 배포는 digest로 고정한다. `latest`는 금지한다.
- exact SHA tag가 이미 존재하면 덮어쓰지 않고 immutable publish를 fail-closed한다. canonical production Dockerfile로 `linux/amd64`·`linux/arm64` 하나의 manifest/index를 만든다.
- production publish는 canonical Dockerfile에 exact release SHA를 required build arg로 전달한다. release evidence는 published OCI index digest, amd64/arm64 manifest·attestation identity, attached SPDX SBOM·SLSA provenance, OCI source/revision과 attached SBOM 기반 scan을 machine-check해 기록한다. `SBOM_REFERENCE`는 두 platform attestation을 소유하는 OCI index digest이며 pre-publish local SBOM·scan은 auxiliary evidence로만 구분한다. public release manifest에는 [ADR-011](ADR-011-transactional-outbox-static-publisher.md)의 `contentRevision`, `publishGeneration`, `generatedAt`을 포함한다.

### 배포 순서

D-IMP-3 fixed code-image apply와 D-IMP-4 release-bound backup source는 다음 경계를 구현한다.

1. protected Environment 승인 뒤 fixed SSH argv로 exact target SHA의 fresh `predeploy` backup·eligibility 발급; 실패 시 deploy invocation 0
2. deploy strict input과 canonical host root 검증, `/private/var/lib/rhaomi/state/locks`의 atomic global deploy lock 획득
3. image pull 전 fixed Compose·environment·Docker credential 권한과 release-bound compatibility target/hash·evidence SHA·repository sentinel/canonical root envelope 검증
4. digest pull, RepoDigest·OCI revision·image ID 검증
5. read-only target-image `backup-verifier`의 complete backup-set full-read와 eligibility `createdAt`·manifest `verifiedAt` strict `<24h` 검증
6. public web·PostgreSQL을 유지하고 backend/publisher graceful stop·physical exit 확인
7. same-image non-web `migration`→Flyway-disabled `schema-validate`; 각 단계 전후 writer quiescence·public web 재확인
8. backend recreate·health 후 publisher recreate, 두 runtime image ID 일치 확인
9. bounded·redacted evidence와 explicit maintenance release

input·path·backup envelope·digest/revision·target verifier 실패는 writer 정지 전에 거부한다. verifier의 root/repository/deploy-state는 read-only이고 media·network·credential이 없다. writer maintenance가 시작된 뒤 migration/schema/backend health/publisher start/runtime image identity 중 하나라도 실패하면 backend와 publisher를 다시 정지하고 quiescence를 확인한 뒤에만 own global lock을 해제한다. quiescence를 확인할 수 없으면 own lock을 보존해 다음 deploy를 막으며 old writer를 자동 resume하지 않는다. backend health 실패 전 publisher 시작도 금지한다. D-IMP-4 backup이 actual provision되지 않으면 release-bound eligibility를 생성할 authority가 없으므로 production deploy는 fail-closed한다.

아래는 D-IMP-4~6 provisioning·content release까지 포함한 전체 production 목표 순서다.

1. global deploy lock 획득
2. exact `main` SHA와 `contentRevision`·`publishGeneration`·`generatedAt` release manifest 확인
3. Mac canonical directory ownership·permission과 public/media/state/build-workspace bind source 확인
4. production project-scoped PostgreSQL named volume identity·mount와 보존 정책 확인
5. disk 여유와 `current`·`previous` 확인
6. 최근 정상 backup 확인
7. migration·major update면 on-demand backup 생성·검증
8. image pull과 digest 검증
9. 관리자 write maintenance 활성화
10. one-shot Flyway migration 실행
11. 새 backend의 schema validation과 internal health 확인
12. [ADR-011](ADR-011-transactional-outbox-static-publisher.md)의 동일 publisher pipeline으로 static release 생성
13. 새 release directory smoke
14. 기존 `current`를 `previous`로 기록
15. `current` symlink 원자적 전환
16. public HTTPS smoke
17. 관리자 write maintenance 해제
18. release evidence와 HomeOps 상태 기록

검증 전에는 `current`를 바꾸지 않는다. `current` 전환은 monotonic `publishGeneration`을 ordering authority로 사용하며 낮거나 같은 generation은 더 새로운 release를 덮지 못한다. 전환 후 smoke가 실패하면 낮은 generation의 `previous` symlink를 직접 재활성화하지 않고, [rollback 계약](../07-operations/rollback.md)에 따라 previous code image/digest와 current content snapshot으로 더 높은 rollback generation을 build·검증해 전환한다. maintenance 해제 여부는 명시적으로 판단하며 public static site는 maintenance 중에도 계속 제공한다.

### Flyway와 schema 호환성

- production backend 일반 기동은 schema를 자동 변경하지 않고 validate만 한다.
- Flyway migration은 deploy lock과 maintenance 안의 one-shot service만 수행한다.
- one-shot mode는 exact `--rhaomi.production-task=migrate|schema-validate`로만 활성하고 non-web·admin bootstrap 0·publisher loop 0을 강제한다. migration은 Flyway 후 JPA validate, schema task는 Flyway disabled + JPA validate다.
- additive expand/contract를 우선하고 새 code와 직전 code가 전환 구간에서 공존 가능한 schema를 유지한다.
- column/table 삭제, 대량 변환과 비가역 migration은 별도 승인, on-demand backup과 isolated restore 검증이 필요하다.
- 검증되지 않은 destructive rollback을 실행하지 않는다.

### release 보존

- 성공 release 최근 5개를 보존한다.
- `current`와 `previous`가 가리키는 release는 보존 수와 무관하게 정리하지 않는다.
- 실패 release와 evidence는 7일 보존한다.
- build cache와 정적 derivative는 재생성 가능 대상으로 분리한다.

## 이유

- 공개 사이트를 backend·DB 장애와 분리하면서 같은 origin 관리자 API를 유지한다.
- `/private` 아래 host source는 macOS writable Data volume과 Docker Desktop 기본 file sharing 경계에 맞고, DB named volume은 host path·file-sharing coupling을 제거한다.
- merge와 production apply를 분리해 검증·수동 승인·rollback 근거를 확보한다.
- digest와 고정 entrypoint는 mutable tag와 임의 원격 shell의 범위를 줄인다.
- one-shot migration과 expand/contract는 code rollback을 schema 변경과 분리한다.

## 결과

### 장점

- public/internal/data network와 filesystem 경계가 명확하다.
- 현재·직전 정적 release를 원자적으로 전환·복구할 수 있다.
- 배포 근거를 exact SHA·digest·manifest로 재현할 수 있다.

### 비용·위험

- GitHub Environment/reviewer, private GHCR, Tailscale deploy identity와 fixed Mac inventory를 실제 계정·host에 별도 provisioning해야 한다.
- Mac canonical directory permission과 Docker named volume identity를 provisioning evidence로 관리해야 한다.
- Mac mini, host edge Nginx와 Cloudflare Tunnel은 공통 장애 지점이다.
- schema가 비호환이면 image rollback만으로 복구할 수 없다.

## 거부한 대안

### `main` merge 즉시 Mac mini 자동 배포

운영 승인과 Secret 접근이 merge에 암묵적으로 결합되고 장애 시 중단 지점이 부족해 거부한다.

### production host에서 PR source build

검토되지 않은 코드를 운영 host에서 실행하고 build toolchain을 운영면에 추가하므로 거부한다.

### mutable `latest` image

실행 중인 code와 rollback 대상을 exact하게 증명할 수 없어 거부한다.

### Mac host `/srv/rhaomi`와 custom File Sharing

macOS root에 Linux식 writable directory를 합성하거나 Docker Desktop custom File Sharing을 production prerequisite로 만들므로 거부한다. host authority는 `/private/var/lib/rhaomi`를 사용하고 `/srv/rhaomi`는 명시된 container target에만 허용한다.

### PostgreSQL host bind PGDATA와 raw-volume backup

Docker Desktop host sharing·path와 DB internal layout에 결합되고 portable restore authority가 불명확해 거부한다. project-scoped named volume과 `pg_dump -Fc`·`pg_restore`를 사용한다.

## 실행 계획

- [x] production Compose와 project Nginx source·task-scoped local/Hosted validation 구현
- [x] required exact-head build arg와 GHCR immutable multi-architecture image·published platform SBOM/provenance·scan workflow source, existing-SHA overwrite 거부 구현
- [ ] actual private GHCR package 생성·visibility·pull 권한 검증
- [x] GitHub `production` Environment deploy job·pre-approval secret isolation source 구현
- [ ] GitHub production environment·required reviewer·branch policy 설정
- [x] pinned Tailscale transport·fixed SSH argv·Mac deploy entrypoint source 구현
- [ ] actual Tailscale deploy identity·host/user/known-hosts·Mac entrypoint/config installation
- [x] one-shot Flyway/schema task·writer quiescence와 post-start/runtime identity failure maintenance hold 구현
- [ ] 실제 Mac mini에서 `/private/var/lib/rhaomi` directory ownership·permission과 public/media/state/build-workspace bind mount, publisher image source의 workspace 외 read-only 검증
- [ ] PostgreSQL project-scoped named volume restart·일반 Compose `down` persistence와 destructive volume command 금지 검증
- [x] task-scoped application-consistent backup에서 fresh named volume로 `pg_restore`·media/static·restart/down-up 검증
- [ ] actual provisioned repository의 production backup과 first-production isolated restore/RPO·RTO 검증
- [ ] release·rollback evidence를 격리 환경에서 검증

## 재검토 조건

- 운영 host 또는 ingress가 Mac mini·Cloudflare Tunnel에서 변경됨
- 무중단 다중 instance가 필요해짐
- release 빈도·복구 목표가 현재 수동 승인 모델을 초과함
- destructive schema 변경이 승인됨
