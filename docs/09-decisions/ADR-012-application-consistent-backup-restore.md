---
title: "ADR-012: Application-consistent backup과 restore"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-02"
review_trigger: "운영 데이터·백업 매체·보존·복구 목표 변경 시"
---

# ADR-012: Application-consistent backup과 restore

- 결정일: 2026-08-29
- 상태: Accepted
- 개정일: 2026-08-31 — 초기 production을 Mac mini local-only backup으로 변경하고 외장 SSD·iCloud 3-2-1을 future hardening으로 이관
- 구현일: 2026-09-01 — fixed backup entrypoint, manifest V1, fresh release eligibility bridge, read-only deploy verifier와 task-scoped isolated restore gate 구현
- 확장일: 2026-09-02 — [ADR-016](ADR-016-verified-empty-first-production-activation.md)의 one-time first-activation backup·recovery acceptance 추가
- 관련 결정: [ADR-004](ADR-004-static-media-copy.md), [ADR-010](ADR-010-production-topology-and-code-release.md)

## 맥락

Rhaomi의 복구 가능한 원본은 PostgreSQL 콘텐츠와 private canonical media다. DB dump와 파일 복사 시점이 어긋나면 media relation이 가리키는 파일이 없거나 다른 시점의 파일이 복구될 수 있다. PostgreSQL primary PGDATA는 production Compose project-scoped Docker named volume에 두되 raw volume 자체를 portable backup으로 간주하지 않는다.

2026-08-29 결정은 외장 SSD와 iCloud Drive의 별도 encrypted restic repository를 초기 production gate로 두었다. 2026-08-31 owner 결정으로 초기 production은 protected source와 분리된 Mac mini 내부 local-only backup을 사용하고, 외장 SSD·iCloud 3-2-1과 offsite RPO는 future hardening으로 이관한다. 이 개정은 application-consistent backup set과 logical restore authority를 유지하면서 single-host disaster risk를 명시적으로 수용한다.

## 결정

### 초기 production backup authority

```text
PostgreSQL portable authority = pg_dump -Fc / pg_restore
canonical media = /private/var/lib/rhaomi/data/media
backup set = DB dump + media snapshot + checksums + inventory manifest
destination = protected source와 분리된 Mac mini local backup repository/path
```

- exact local repository path, owner·permission, 여유 용량과 production source와의 분리 여부는 provisioning gate에서 확정한다. 저장소에 경로를 추측하거나 운영 directory를 생성하지 않는다.
- production source는 fixed `/private/var/lib/rhaomi/app/production.env`의 정확히 한 개 `RHAOMI_BACKUP_REPOSITORY_ROOT`만 읽는다. caller가 repository path를 argument로 바꾸지 못하며 actual 값은 계속 provisioning input이다.
- repository root와 `sets`는 owner-only `0700`, `.rhaomi-backup-repository` sentinel은 `0600` regular file이어야 한다. symlink·non-canonical physical path와 `/private/var/lib/rhaomi` source 내부 destination은 거부한다.
- local repository는 versioned backup set을 보존해야 하며 active PostgreSQL named volume, canonical media source, public release와 같은 directory를 destination으로 사용하지 않는다.
- DB dump와 media snapshot은 하나의 backup-set ID로 묶는다.
- manifest에는 dump·media checksum, byte size, file count, Git SHA, image digest, Flyway version, 생성·검증 시각을 기록한다.
- dump command 성공만으로 완료 처리하지 않고 artifact와 manifest를 다시 읽어 검증한다.
- filesystem mirror나 mirror delete를 backup authority로 사용하지 않는다.
- backup 실패 시 기존 정상 backup set과 retention 대상을 삭제하지 않는다.

production project-scoped PostgreSQL named volume과 raw PGDATA file은 required backup input이 아니다. primary persistence에는 필요하지만 portable backup/restore authority는 같은 backup-set의 `pg_dump -Fc` custom archive와 isolated target의 `pg_restore`다.

### application-consistent backup set

```text
관리자 write maintenance
→ backend/publisher physical exited 확인
→ canonical media owner-only capture state 확인
→ pg_dump -Fc
→ canonical media snapshot·manifest 확정
→ media runtime-access state와 writer health/running 복구
→ `.incomplete-<backup-set-id>` artifact·checksum·file count full-read 재검증
→ same-filesystem rename으로 read-only complete set 승격
→ local backup evidence 기록
```

- public static site는 write maintenance 중에도 계속 제공한다.
- incomplete temporary set은 정상 backup으로 승격하지 않는다.
- 완료된 backup set은 생성 중인 set과 구분하고 retention 전 check를 통과해야 한다.
- manifest V1은 exact key allowlist로 `backupPurpose`, UTC set ID·시각, source SHA·image digest·Flyway `10`, dump hash/size와 canonical byte-order media inventory·aggregate hash, `sameHostFailureDomain=true`를 기록한다. Secret·endpoint·host config byte는 포함하지 않는다.
- deploy와 backup은 같은 host `rhaomi-deploy.lock`을 사용한다. 두 writer의 physical `exited`를 확인한 뒤에만 snapshot과 capture permission state를 시작한다. runtime permission state와 writer health/running 복구가 확인된 뒤에만 complete 승격·backup success·lock release를 허용하며 permission 전환 또는 writer 복구 실패 시 own lock을 보존한다.
- Linux task validation은 validation overlay의 fixed service로만 media를 runtime(container owner, directory `0750`, file `0640`)과 capture(host validation owner, directory `0700`, file `0600`) 사이에서 전환한다. production Mac owner-only authority, backup tool strict validation과 caller command/path 부재는 바꾸지 않는다.
- backup-set ID, source revision과 release identity를 secret 없이 HomeOps status/event에 제공할 수 있어야 한다.

### release eligibility bridge

- `predeploy` mode만 검증된 complete set에서 fixed deploy state의 `backup-eligibility.json`과 4-line `backup-eligible.env`를 원자적으로 발급한다.
- JSON은 target release SHA, backup-set ID, manifest SHA-256와 source release/image/Flyway identity를 보존하고 compatibility file은 그 JSON의 exact SHA-256에 결합한다.
- GitHub `production` Environment 승인 뒤 fixed SSH argv는 deploy entrypoint보다 먼저 `backup-rhaomi.sh --mode predeploy --target-release-sha <exact SHA>`를 실행한다. backup 또는 eligibility 발급이 실패하면 deploy entrypoint 호출은 0이다. 같은 SHA 재시도도 이 순서를 반복해 새 predeploy set 없이 과거 eligibility를 재사용하지 않는다.
- deploy entrypoint는 image pull 전에 host fixed config, compatibility target/hash, evidence regular-file SHA와 repository sentinel/canonical root를 envelope로 검증한다. exact target image pull·OCI revision 확인 뒤에는 같은 image의 `backup-verifier`로 evidence exact shape와 referenced complete set의 manifest/dump/media를 full-read한다.
- full-read verifier는 eligibility `createdAt`과 referenced manifest `verifiedAt`이 각각 현재 UTC 기준 미래가 아니고 엄격히 24시간 미만인지 검증한다. 정확히 24시간, future 또는 malformed timestamp는 fail-closed한다.
- `backup-verifier`는 read-only root, `cap_drop: ALL`, no-new-privileges, network none이며 repository와 deploy-state를 read-only로만 mount한다. media, Docker socket, port, DB/build credential은 제공하지 않는다. target image code가 recovery authority를 변경할 수 없다는 보장은 command 관례가 아니라 이 mount boundary가 담당한다.
- scheduled backup은 임의 future release eligibility를 발급하지 않는다.

### 최초 production recovery bridge

- normal `predeploy`가 요구할 predecessor가 없는 verified-empty host는 [ADR-016](ADR-016-verified-empty-first-production-activation.md)의 explicit one-time lifecycle만 사용한다.
- `RECOVERY_ACCEPTANCE_REQUIRED`의 fixed `first-activation` mode는 public web prerequisite만 제외하고 같은 operation lock·writer quiescence·DB+media set·writer recovery·atomic complete/full-read 계약을 유지한다.
- 이 mode는 normal release eligibility를 발급하지 않고 lifecycle의 exact release SHA/image digest와 결합된 recovery candidate를 한 번만 만든다.
- read-only full-read, isolated PostgreSQL/media restore, Flyway V10/JPA·핵심 row/API/static/media smoke와 recovery writer shutdown이 모두 성공해야 `STEADY_STATE`로 전환한다.
- `STEADY_STATE` 이후 first-activation mode는 영구 거부하고 모든 normal backup/deploy는 기존 steady-state 계약을 따른다.

### 일정·보존·검증

- 정기 backup: 매일 03:30 `Asia/Seoul`
- 추가 backup: migration·major upgrade 직전 on-demand
- 보존: daily 7, weekly 4, monthly 6
- weekly: manifest·checksum·archive 구조 check
- monthly: 보존 대상 전체 data read와 retention dry-run
- quarterly: isolated full restore와 static build smoke
- prune: 월간 maintenance window에서 dry-run·검토 뒤에만 수행하고 완료 후 다시 check
- retention plan/apply는 모든 complete set을 full-read하고 daily 7·weekly 4·monthly 6, 최신 정상 3개와 모든 on-demand set을 보호한다. incomplete 또는 checksum-invalid set이 있거나 정상 set이 3개 미만이면 apply를 거부한다.
- `ops/production/com.rhaomi.backup.plist`는 host local time 03:30의 fixed `scheduled` invocation source다. production provisioning에서 macOS timezone이 `Asia/Seoul`인지 확인하며 이번 구현에서는 plist를 install/start하지 않는다.

### restore

- 초기 목표는 local RPO `<= 24h`, RTO `<= 8h`다. release eligibility machine gate는 boundary replay를 피하기 위해 두 authoritative timestamp가 엄격히 24시간 미만일 때만 통과한다. 첫 restore drill의 실제 소요 시간과 데이터량으로 목표를 조정한다.
- isolated Compose project, 새 project-scoped PostgreSQL named volume과 새 media root에 restore한다.
- `pg_restore`, manifest, checksum·file count, Flyway schema, 핵심 row/API, 대표 canonical media와 static build를 검증한다.
- PostgreSQL container restart와 일반 Compose `down`·`up` 뒤 복구 data persistence를 확인한다.
- task validator는 backup 시점 A 뒤 source writer를 physical stop하고 host capture state에서 DB/media를 B로 변경한다. fresh named volume·owner-only media root에 A를 복구한 뒤 runtime 전환에서 audit/relation row·representative media decode·동일 static publisher와 restart/down-up persistence를 검증하고, writer 종료 뒤 최종 host `0700`/`0600` 상태를 다시 확인한다.
- 운영 DB·media를 직접 overwrite하지 않는다. 운영 전환은 exact target, backup과 rollback을 확인한 별도 명시 승인 후 수행한다.
- production `docker compose down -v`, `docker volume prune`과 named volume direct delete는 backup 보유 여부와 무관하게 금지한다.

### 초기 accepted risk

초기 local-only backup은 logical deletion, application corruption과 rollback 복구에는 도움을 주지만 Mac mini host·internal storage·화재·도난 같은 전체 손실에서 production data와 backup을 함께 잃을 수 있다. 초기 production은 이 single-host disaster risk를 명시적으로 수용한다.

- 초기 상태에는 offsite copy와 offsite RPO가 없다.
- 외장 SSD·iCloud가 구성되지 않은 상태를 offsite `PASS`로 표시하지 않는다.
- 상태는 `NOT_CONFIGURED / DEFERRED` 또는 [production readiness vocabulary](../07-operations/production-readiness.md)의 `CONTRACT_APPROVED` future-hardening 행으로 표현한다.

### future hardening — 3-2-1과 offsite

초기 production 이후 별도 승인으로 다음 목표를 도입할 수 있다.

```text
Copy 1: Mac mini production data
Copy 2: 외장 SSD encrypted restic repository
Copy 3: iCloud Drive separate encrypted restic repository
```

- 외장 SSD exact repository path는 `/Volumes/<provisioned-volume>/...` 아래에서 volume identity·용량·ownership과 함께 확정한다.
- 외장 SSD와 iCloud는 서로 다른 encrypted restic repository와 독립 key를 사용한다.
- repository password는 macOS Keychain 또는 root-owned `0600` secret file에서 공급하고 recovery copy는 password manager와 별도 offline copy에 둔다.
- local iCloud Drive repository snapshot 조회·`check`는 local repository integrity일 뿐 Apple remote sync 완료 증거가 아니다.
- Apple remote sync가 별도 검증된 backup set만 offsite 사본과 offsite RPO authority로 인정한다.
- second trusted device 또는 기존 local cache를 authority로 사용하지 않는 clean retrieval path의 fresh retrieval·restic check·대표 restore를 offsite acceptance로 사용한다.
- future hardening이 구현되기 전에는 offsite RPO를 `PASS`로 표시하거나 초기 local RPO로 대체하지 않는다.

## 이유

- write maintenance와 하나의 manifest는 DB relation과 media 파일을 같은 복구 시점으로 묶는다.
- `pg_dump -Fc`·`pg_restore`는 Docker volume internal layout과 분리된 portable authority를 제공한다.
- 초기 local-only 범위는 운영 진입 복잡도를 줄이면서 logical recovery를 먼저 확보한다.
- single-host disaster risk를 숨기지 않고 future 3-2-1 hardening과 분리해 readiness를 정확히 판정한다.

## 결과

### 장점

- DB와 media의 application-consistent 복구점을 초기 production 전에 검증할 수 있다.
- backup set과 release를 exact SHA·digest·schema로 연결할 수 있다.
- production overwrite 없이 restore 절차를 반복 검증할 수 있다.

### 비용·위험

- local backup도 Mac mini 전체 손실과 같은 failure domain에 있다.
- local repository 용량·permission·retention과 restore drill을 직접 관리해야 한다.
- 외장 SSD·iCloud hardening 전에는 offsite disaster recovery가 없다.

## 거부하거나 연기한 대안

### 실행 중 PostgreSQL raw volume 복사

DB 일관성과 portable restore를 보장하지 못하므로 유일한 backup으로 사용하지 않는다.

### filesystem mirror만 사용

삭제가 전파되고 versioned retention·무결성 증거가 부족해 거부한다.

### 외장 SSD·iCloud 3-2-1을 초기 production 필수 gate로 유지

2026-08-31 owner 결정으로 초기 gate에서는 제거했다. architecture hardening 목표 자체는 유지하지만 별도 implementation·provisioning 승인 뒤 도입한다.

## 실행 계획

- [ ] protected source와 분리된 Mac mini local repository exact path·용량·ownership·permission 확정
- [x] source-level write maintenance·dump·media snapshot·strict manifest와 atomic complete-set 자동화 구현
- [x] source-level retention dry-run·explicit apply·post-check와 fail-safe guard 구현
- [ ] HomeOps local backup status event 연동
- [ ] quarterly isolated full restore 첫 증거 확보
- [x] task-scoped 새 PostgreSQL named volume의 `pg_restore`·media/static 검증과 일반 Compose `down` 뒤 persistence 자동 검증
- [ ] future hardening 승인 시 외장 SSD/iCloud repository·key·remote-sync/fresh-retrieval 계약 구현

## 재검토 조건

- 첫 restore drill이 local RPO 24시간 또는 RTO 8시간을 충족하지 못함
- single-host disaster risk를 더 이상 수용할 수 없음
- 외장 SSD·iCloud·NAS 등 별도 failure domain 도입이 승인됨
- 법적 보존·삭제 요구가 현재 retention과 충돌함
