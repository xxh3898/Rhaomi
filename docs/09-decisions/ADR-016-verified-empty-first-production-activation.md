---
title: "ADR-016: Verified-empty 최초 production activation"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-02"
review_trigger: "최초 production lifecycle·backup·public activation 경계 변경 시"
---

# ADR-016: Verified-empty 최초 production activation

- 결정일: 2026-09-02
- 상태: Accepted
- 관련 결정: [ADR-010](ADR-010-production-topology-and-code-release.md), [ADR-012](ADR-012-application-consistent-backup-restore.md)

## 맥락

steady-state release는 실행 중인 backend·publisher와 Flyway V10 data authority를 application-consistent `predeploy` backup으로 보존한 뒤에만 deploy한다. 그러나 아직 predecessor가 없는 최초 production host에는 backup할 runtime과 database가 없다. 기존 backup 검증을 건너뛰거나 빈 상태를 추측하면 partial bootstrap을 새 host로 오인해 data authority를 덮을 수 있고, 기존 release의 recovery invariant도 약화한다.

Owner는 Issue #67의 선택안 A를 승인했다. 최초 한 번에 한해 predecessor 부재를 machine-verifiable evidence로 확정하고 exact release를 public ingress 없이 bootstrap한다. 그 runtime의 첫 application-consistent backup과 isolated restore acceptance가 성공한 뒤에만 normal release가 사용할 `STEADY_STATE`로 전환한다.

## 결정

### lifecycle state authority

```text
FIRST_ACTIVATION_UNINITIALIZED
→ FIRST_ACTIVATION_BOOTSTRAPPING
→ RECOVERY_ACCEPTANCE_REQUIRED
→ RECOVERY_ACCEPTANCE_IN_PROGRESS
→ STEADY_STATE
```

- `UNINITIALIZED`는 file marker가 아니라 verified-empty 검사 전 개념 상태다. state file 부재만으로 empty를 판정하지 않는다.
- `/private/var/lib/rhaomi/state/deploy/production-lifecycle.env`와 결합 evidence는 exact release SHA·image digest·UTC 시각·evidence SHA-256을 가진다.
- state/evidence는 host owner의 `0600` regular file이며 symlink를 거부하고 같은 directory의 temporary file을 atomic rename한다.
- lifecycle transition은 deploy·backup과 같은 `/private/var/lib/rhaomi/state/locks/rhaomi-deploy.lock` 안에서만 수행한다.
- unknown, contradictory, insecure, hash-drift state는 복구를 추측하지 않고 fail-closed한다.

### verified-empty

다음 predecessor authority가 모두 없어야 한다.

- `public/current`, `public/previous`, 기존 release package
- deploy/pending/success marker와 backup eligibility
- complete production backup set
- production Compose project의 web/backend/publisher/PostgreSQL container
- project-scoped PostgreSQL named volume
- canonical media의 기존 regular file과 public release authority

Provisioning이 미리 만든 canonical root, 빈 required directory와 sentinel만 가진 빈 backup repository는 허용한다. 판정 불가와 partial 흔적은 empty가 아니다. 검사 결과는 target release SHA, absence matrix와 Compose project/PostgreSQL volume identity만 기록하며 Secret·FQDN·private config 값을 포함하지 않는다.

### 비공개 bootstrap

verified-empty evidence를 먼저 확정하고 durable `FIRST_ACTIVATION_BOOTSTRAPPING` state를 기록한 뒤에만 mutation을 시작한다.

```text
exact digest pull·OCI revision 확인
→ PostgreSQL named volume 시작
→ one-shot Flyway V1~V10 migration
→ Flyway-disabled JPA schema validation
→ backend health
→ publisher running·same-image 확인
→ RECOVERY_ACCEPTANCE_REQUIRED
```

`rhaomi-web`은 시작하지 않고 backend·publisher·PostgreSQL host port도 만들지 않는다. 실패 뒤 state/evidence를 삭제하거나 `UNINITIALIZED`로 되돌리지 않으며 같은 bootstrap을 자동 retry하지 않는다. Human corrective 없이 marker 삭제, `force`, `skip-backup`으로 재진입하는 경로는 없다.

### 첫 backup과 recovery acceptance

`RECOVERY_ACCEPTANCE_REQUIRED`에서만 fixed backup entrypoint의 explicit `first-activation` mode를 허용한다. 이 mode는 public web predecessor만 요구하지 않으며 나머지 shared lock, writer physical exit, media permission, `pg_dump -Fc`, media capture, writer recovery, atomic complete set과 full-read 계약은 steady-state와 동일하다. Source release SHA와 image digest가 lifecycle state와 일치해야 하며 release eligibility 대신 exact backup-set candidate evidence를 한 번만 만든다.

Recovery는 별도 Compose project에서 다음을 실행한다.

- read-only/no-network backup verifier의 complete set full-read
- tmpfs PostgreSQL에 `pg_restore`
- isolated media root에 restore와 checksum authority 보존
- Flyway V10 확인과 Flyway-disabled JPA schema validation
- 복구된 production source가 empty였는지 핵심 table로 확인
- isolated synthetic row/media를 넣은 backend API health·publisher static release·representative media smoke
- host port 없는 internal network와 recovery writer physical shutdown

격리 복구 probe는 production DB/media/public current에 쓰지 않는다. 모든 acceptance와 recovery project quiescence가 성공한 뒤에만 evidence를 확정하고 `STEADY_STATE`를 기록한다. 실패·중단·quiescence 불확실 상태에서는 public activation과 steady-state marker를 금지한다.

### steady-state와 workflow

- Production Release workflow는 default `steady-state`와 explicit `first-activation` choice를 제공하며 runtime 상태로 mode를 자동 추론하지 않는다.
- `first-activation`은 fixed SSH argv의 `bootstrap → first-activation backup → accept-recovery` 순서만 사용한다.
- `STEADY_STATE` 이후 first-activation bootstrap/backup/recovery는 영구 거부한다.
- scheduled/on-demand/predeploy backup과 normal deploy는 valid `STEADY_STATE` evidence를 요구한다.
- 이후 release는 기존 `<24h` exact-release `predeploy backup → read-only verifier → deploy` 계약을 그대로 사용한다.
- `STEADY_STATE`는 public ingress, administrator/passkey, recovery code, content, Cloudflare 또는 HomeOps activation 승인이 아니다. 이들은 별도 production gate다.

## 이유

- 최초 host에 존재하지 않는 predecessor를 가장 좁은 one-time 예외로 다룬다.
- mutation 전에 durable negative evidence를 남겨 partial state를 새 host로 재분류하지 않는다.
- 첫 실제 runtime에서 생성한 DB/media backup을 실제 isolated restore로 읽어 본 뒤 normal lifecycle에 진입한다.
- steady-state의 backup eligibility와 deploy fail-close를 변경하지 않는다.

## 결과

### 장점

- 최초 activation deadlock을 backup bypass 없이 해결한다.
- exact release와 첫 recovery authority가 hash-bound state로 연결된다.
- public/admin/content 활성화 전에 restore 가능성을 검증한다.

### 비용과 위험

- partial bootstrap과 failed recovery는 자동 복구하지 않으므로 human diagnosis가 필요하다.
- isolated publication smoke가 시간과 임시 disk/memory를 사용한다.
- source와 CI evidence는 actual Mac provisioning·production backup/RPO·physical acceptance를 대신하지 않는다.

## 거부한 대안

### B. 별도 non-public predecessor bootstrap 뒤 normal predeploy만 실행

one-time verified-empty evidence와 recovery-required state 없이 bootstrap runtime을 곧바로 정상 predecessor로 취급하면 partial state와 accepted recovery authority를 구분할 수 없다. 첫 backup의 isolated restore 성공 전에도 normal deploy가 가능해지므로 거부한다.

### C. 별도 empty baseline release를 먼저 운영

실제 사업 content가 없는 synthetic release를 production predecessor와 rollback authority로 만들어 release 수를 늘리고, public activation과 recovery acceptance의 순서를 모호하게 한다. 동일한 verified-empty·restore gate보다 안전 근거가 늘지 않아 거부한다.

## 비수행 경계

이 결정의 source 구현은 production workflow dispatch, GHCR publish, GitHub Environment/Secret/Variable 변경, `/private/var/lib/rhaomi` provisioning, production Docker/DB/backup/restore/migration/deploy, Cloudflare/FQDN, 실제 administrator/passkey/recovery code/content 또는 HomeOps activation을 수행하지 않는다.
