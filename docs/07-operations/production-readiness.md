---
title: "Production readiness matrix"
status: "approved"
owner: "조치호"
reviewers: "조치호·은총쌤"
last_updated: "2026-09-01"
review_trigger: "production 구현·provisioning·외부 승인·물리 기기 증거 변경 시"
---

# Production readiness matrix

## 목적

이 문서는 Phase 1D 운영 계약의 단일 탐색 지점이다. 다음 상태는 서로 대체할 수 없다.

```text
APPROVED CONTRACT
≠ LOCAL/CI IMPLEMENTATION EVIDENCE
≠ PRODUCTION PROVISIONED
≠ EXTERNAL/CONTENT APPROVED
≠ PHYSICAL DEVICE ACCEPTED
```

Phase 1D는 `Production operating architecture CONTRACT COMPLETE`다. production 구현·provisioning·deploy는 완료되지 않았고 현재 overall production readiness는 `HOLD`다.

## 상태 vocabulary

| 상태 | 의미 |
|---|---|
| `CONTRACT_APPROVED` | ADR 또는 current 문서로 결정이 승인됐다. 구현·provisioning·운영 검증을 뜻하지 않는다. |
| `LOCAL_CI_VERIFIED` | synthetic/local/Hosted CI 범위의 구현 증거가 있다. production path·secret·domain·data 증거를 뜻하지 않는다. |
| `IMPLEMENTATION_REQUIRED` | 승인 계약을 실행할 code·image·automation 또는 integration이 아직 필요하다. |
| `PROVISIONING_REQUIRED` | 승인된 구현·계약을 실제 Mac·network·secret·path·account에 배치하고 검증해야 한다. |
| `EXTERNAL_DECISION_REQUIRED` | 실제 매장 운영자 또는 외부 자산 소유자의 값·문구·권리 승인이 필요하다. |
| `PHYSICAL_ACCEPTANCE_REQUIRED` | automated semantics로 대체할 수 없는 실제 기기·browser·보조기술 검증이 필요하다. |

matrix의 `현재 상태`는 해당 행에서 가장 가까운 다음 gate 하나를 사용한다. 이미 확보한 다른 단계의 증거는 `현재 증거`에만 기록한다. 어느 상태도 production `PASS`의 별칭이 아니다.

## canonical matrix

| 영역 | contract authority | 현재 증거 | 현재 상태 | production blocker | 후속 구현 gate |
|---|---|---|---|---:|---|
| ingress / public web | [ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md), [배포](deployment.md) | canonical project Nginx와 web-only loopback Compose source, task static/admin/deny/404 smoke | `PROVISIONING_REQUIRED` | 예 | actual loopback port/FQDN, Cloudflare DNS/HTTPS→Tunnel→host edge와 public HTTPS smoke |
| backend / admin session·CSRF | [ADR-009](../09-decisions/ADR-009-spring-boot-backend-admin.md), [접근제어](../06-security/access-control.md) | Java 25/PostgreSQL auth·session fixation·CSRF·fail-closed API 계약과 Hosted CI | `PROVISIONING_REQUIRED` | 예 | production TLS·`Secure` cookie, 운영 계정·rate limit evidence |
| admin second factor | [접근제어](../06-security/access-control.md), [위협 모델](../06-security/threat-model.md) | password/session/CSRF foundation만 구현됨 | `IMPLEMENTATION_REQUIRED` | 예 | password 위 WebAuthn/passkey 등록·인증, authenticator private key server 비수집, RP-side credential record, registration revoke/remove, recovery-code secret·rotation; password-only production 금지 |
| PostgreSQL persistence / migration | [ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md), [환경설정](../04-architecture/environment-and-configuration.md) | PostgreSQL 18.6·Flyway V1~V9, exact CLI non-web migration/schema task, task project-scoped volume identity·일반 `down` sentinel persistence | `PROVISIONING_REQUIRED` | 예 | actual production volume identity·capacity·restart persistence, approved backup 후 actual one-shot migration·schema validate |
| private media | [ADR-004](../09-decisions/ADR-004-static-media-copy.md), [컨테이너 구조](../04-architecture/container-architecture.md) | private master·HEIC 계약과 task temp backend RW/publisher RO/web-unmounted bind smoke | `PROVISIONING_REQUIRED` | 예 | `/private/var/lib/rhaomi/data/media` ownership·permission과 actual Docker Desktop bind smoke |
| publisher / static release | [ADR-011](../09-decisions/ADR-011-transactional-outbox-static-publisher.md), [정적 퍼블리싱](../04-architecture/static-publishing-pipeline.md) | same-image non-web publisher Compose argv, task public/state/lock/build-workspace RW·media RO·image source workspace 외 RO와 immediate/scheduled V2 release local/CI | `PROVISIONING_REQUIRED` | 예 | actual credential·public/state/lock/build-workspace bind, approved digest, HTTPS·HomeOps event evidence |
| code release / image / SBOM | [ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md) | canonical image, workflow_dispatch-only exact-main required build arg, multi-arch published platform manifest·SBOM/provenance/scan verification source, protected Environment job, pinned Tailscale/fixed predeploy→deploy SSH argv, Mac deploy lock·digest/revision·post-start failure quiescence task evidence | `PROVISIONING_REQUIRED` | 예 | private GHCR package/visibility, actual Environment reviewer/main policy/secret, Tailscale identity·host install, fresh approved backup gate, exact digest Mac apply |
| initial local-only backup / restore | [ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md), [백업·복구](backup-and-restore.md) | fixed shared-lock backup source, strict manifest·atomic complete set, `<24h` release eligibility·manifest freshness, mount-level read-only deploy verifier, retention guard와 A→B→isolated A restore·static/restart/down-up task evidence | `PROVISIONING_REQUIRED` | 예 | protected source와 분리된 actual Mac repository/path·owner·capacity, 03:30 scheduler installation, production DB/media predeploy backup과 first-production restore/RPO·RTO evidence |
| external SSD / iCloud offsite hardening | [ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md) 2026-08-31 amendment | 미구성; offsite RPO 증거 없음 | `CONTRACT_APPROVED` | 아니요 | 초기 production 뒤 별도 승인으로 3-2-1, recovery key, remote-sync evidence·fresh retrieval 도입; 미구성 상태를 `PASS`로 표시 금지 |
| HomeOps monitoring / recovery | [ADR-013](../09-decisions/ADR-013-homeops-monitoring-recovery-boundary.md), [모니터링](monitoring-and-incident-response.md) | privacy-safe 신호·single-restart 경계만 승인됨 | `IMPLEMENTATION_REQUIRED` | 예 | monitor/event/status와 consecutive failure·lock·30분 cooldown·pre/post health·audit를 갖춘 stateless web/backend 단일 restart |
| HEIC decoder-only runtime | [ADR-014](../09-decisions/ADR-014-heic-decoder-only-production-runtime.md) | libheif `v1.23.1` exact commit/SHA, libde265-only CMake, x265 package/link/plugin 0, actual HEIC/HEIF·sequence/AVIF, SBOM/license/scan의 amd64/arm64 gate | `PROVISIONING_REQUIRED` | 예 | approved image digest를 production backend/publisher에 배치하고 실제 Mac mount·service startup·iPhone HEIC acceptance 확인 |
| initial public domain | [ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md), [환경설정](../04-architecture/environment-and-configuration.md) | 사용자 소유 기존 도메인을 임시 public domain으로 쓰는 전략 승인 | `PROVISIONING_REQUIRED` | 예 | exact temporary FQDN 외부 입력, Cloudflare/Nginx host·`PUBLIC_SITE_URL`·canonical/OG/sitemap/robots·smoke 동기화 |
| final cousin-owned domain | [미확정 항목](../01-product/open-items.md) | 전용 도메인 구매 전 | `EXTERNAL_DECISION_REQUIRED` | 아니요 | 구매 뒤 동일 topology에서 canonical/public domain만 migration하고 DB/schema는 변경하지 않음 |
| actual content / photo rights | [미확정 항목](../01-product/open-items.md), [출시 체크리스트](../08-quality/release-checklist.md) | synthetic fixture만 검증됨 | `EXTERNAL_DECISION_REQUIRED` | 예 | 실제 매장 운영자의 NAP·영업정보·정책·문구·링크·사진·반려견/고객 사진 게시 권한 최종 승인 |
| physical device acceptance | [브라우저·기기 매트릭스](../08-quality/browser-device-matrix.md), [출시 체크리스트](../08-quality/release-checklist.md) | automated DOM·접근성 semantics와 Linux HEIC smoke | `PHYSICAL_ACCEPTANCE_REQUIRED` | 예 | actual public HTTPS에서 iPhone Safari HEIC와 `/admin`, 320px·VoiceOver·focus·rollback/recovery 표본 검증 |

## readiness 판정 규칙

- synthetic/local/CI evidence를 production `PASS`로 승격하지 않는다.
- ADR의 `approved / Accepted`는 deployed·provisioned를 뜻하지 않는다.
- 실제 운영자 승인 전 synthetic NAP·문구·사진을 공개 승인으로 취급하지 않는다.
- 초기 local-only backup에는 offsite copy가 없다. 외장 SSD/iCloud가 구성되지 않은 상태를 offsite `PASS`로 표시하지 않는다.
- automated DOM·ARIA·HEIC test는 iPhone Safari·VoiceOver 물리 acceptance를 대체하지 않는다.
- production blocker가 `예`인 행은 해당 evidence가 확보되기 전 production deploy 승인을 받을 수 없다.

## dependency-aware implementation slices

| 순서 | slice | 범위 | 선행 이유 |
|---:|---|---|---|
| 1 | D-IMP-1 | production decoder-only image + SBOM/supply-chain evidence | 이후 production runtime과 release artifact의 immutable input을 먼저 고정한다. |
| 2 | D-IMP-2 | production Compose/project Nginx + Mac host bind/PostgreSQL named-volume provisioning validator | image를 실제 topology·filesystem에 안전하게 배치할 경계를 만든다. |
| 3 | D-IMP-3 | private GHCR + GitHub production Environment + fixed Tailscale deploy entrypoint + one-shot Flyway/schema validate/maintenance | exact image·host contract 뒤에 code delivery와 migration gate를 연결한다. |
| 4 | D-IMP-4 | application-consistent local-only backup + isolated `pg_restore`/media restore evidence | first production 전에 deploy·data recovery authority를 실제 증거로 만든다. |
| 5 | D-IMP-5 | HomeOps monitoring/event/status + bounded stateless restart | 배치된 service·backup/publisher identity를 기준으로 관제와 복구를 연결한다. |
| 6 | D-IMP-6 | first-production acceptance + actual domain/content/public HTTPS/iPhone Safari·VoiceOver/rollback·recovery | 모든 기술 gate 뒤 실제 외부값·물리 기기·운영 복구를 최종 확인한다. |

외장 SSD/iCloud 3-2-1은 D-IMP-4의 초기 production 범위가 아니라 후속 hardening이다. 새 architecture 결정이 필요해지면 구현 Issue에서 임의 선택하지 않고 별도 결정 gate로 되돌린다.

D-IMP-1 canonical image, D-IMP-2 production Compose/project Nginx, D-IMP-3 exact-main release workflow·fixed deploy entrypoint·one-shot Flyway/schema task와 D-IMP-4 shared-lock backup/manifest/eligibility/isolated restore source 및 task-scoped local/Hosted validator는 구현·검증됐다. actual `/private/var/lib/rhaomi`, production named volume·backup repository·schedule, private GHCR package/visibility, GitHub `production` Environment·reviewer·secret, Tailscale identity, Mac entrypoint/config, Secret·FQDN·host edge·Cloudflare는 `PROVISIONING_REQUIRED`다. production workflow dispatch·package push·deploy·migration·backup·restore는 수행하지 않았다.

## accepted residual risks

- 초기 local-only backup은 logical deletion·corruption·rollback recovery에는 도움을 주지만 Mac mini host/storage 전체 손실 시 production data와 backup을 함께 잃을 수 있다. 이 single-host disaster risk는 초기 production에서 명시적으로 수용한다.
- HomeOps가 같은 Mac mini에 있어 전원·회선·Docker 전체 장애는 관제와 alert를 동시에 중단할 수 있다.
- final cousin-owned domain migration과 offsite backup hardening은 초기 production 이후의 외부·운영 개선이다.

## 비수행 경계

D-IMP-4까지 release/deploy·backup/restore source와 synthetic/task-scoped validation만 구현했다. 실제 GitHub Environment·GHCR package·Tailscale identity, Mac canonical path·entrypoint·production volume·backup repository·schedule·Secret·FQDN·HomeOps·Cloudflare를 생성·변경하지 않았고 workflow dispatch, package push, merge, release, deploy, production migration·backup·restore·mutation도 수행하지 않았다.
