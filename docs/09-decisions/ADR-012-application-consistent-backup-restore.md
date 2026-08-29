---
title: "ADR-012: Application-consistent backup과 restore"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "운영 데이터·백업 매체·보존·복구 목표 변경 시"
---

# ADR-012: Application-consistent backup과 restore

- 결정일: 2026-08-29
- 상태: Accepted
- 관련 결정: [ADR-004](ADR-004-static-media-copy.md), [ADR-010](ADR-010-production-topology-and-code-release.md)

## 맥락

Rhaomi의 복구 가능한 원본은 PostgreSQL 콘텐츠와 private canonical media다. DB dump와 파일 복사 시점이 어긋나면 media relation이 가리키는 파일이 없거나 다른 시점의 파일이 복구될 수 있다. Mac mini와 같은 host·disk에만 둔 사본은 host 고장에 취약하다. local iCloud Drive path에서 restic snapshot을 조회하거나 `check`가 성공해도 해당 Mac의 local repository integrity만 증명하며 Apple remote iCloud sync 완료는 증명하지 않는다.

실제 외장 SSD, iCloud 폴더와 restic repository는 아직 초기화하지 않았다. exact mount path·용량·폴더와 key 보관 위치는 provisioning 전에 확인한다.

## 결정

### 3-2-1 사본

```text
Copy 1: Mac mini production data
Copy 2: 외장 SSD의 encrypted restic repository
Copy 3: iCloud Drive의 별도 encrypted restic repository
```

- 외장 SSD는 production release gate이며 초기 단계에서 NAS를 필수로 두지 않는다.
- 두 backup destination은 서로 다른 restic repository와 암호화 key를 사용한다.
- iCloud Drive는 intended offsite transport/storage이고 restic이 encryption과 repository integrity를 담당한다. 특정 backup set은 Apple remote sync 완료가 별도 검증된 뒤에만 offsite 사본으로 인정한다.
- 한 host automation만 repository writer가 된다.
- filesystem mirror와 mirror delete를 backup으로 사용하지 않는다.
- local snapshot을 검증한 뒤 restic repository 간 snapshot copy로 intended offsite 후보를 만든다. Apple remote sync evidence 전에는 offsite 사본으로 판정하지 않는다.

### secret과 recovery key

- repository password를 Git, shell history, process argument, log, notification과 release evidence에 저장하지 않는다.
- automation은 root-owned `0600` password file 또는 macOS Keychain에서 값을 출력하는 제한된 password command를 사용한다.
- recovery key는 password manager와 별도 offline 사본에 보관한다.
- 두 repository의 key·접근 복구 절차를 같은 host에만 두지 않는다.

### 보호 대상

- PostgreSQL `pg_dump -Fc` custom archive
- `/srv/rhaomi/data/media`의 private canonical media
- backup manifest
- exact Git SHA, image digest와 Flyway version
- secret 값이 아닌 운영 inventory와 복구 위치 식별자

`public/releases`, static derivative, `out`, dependency cache와 build cache는 재생성 가능하므로 필수 backup에서 제외한다.

### application-consistent backup set

```text
관리자 write maintenance
→ pg_dump -Fc
→ media snapshot·manifest 확정
→ write maintenance 해제
→ 외장 SSD repository snapshot·check
→ iCloud repository로 snapshot copy
→ local iCloud Drive repository snapshot 조회·check
→ Apple remote sync 완료 별도 검증
→ local·offsite evidence를 분리해 manifest 기록
```

- public static site는 write maintenance 중에도 계속 제공한다.
- DB dump와 media snapshot은 하나의 backup-set ID로 묶는다.
- manifest에 checksum, byte size, file count, source·destination snapshot ID, Git SHA, image digest, Flyway version, local repository 검증 시각과 remote-sync 검증 상태·시각을 기록한다.
- dump command 성공만으로 완료 처리하지 않고 artifact와 snapshot을 조회한다.
- local iCloud Drive repository의 snapshot 조회·`check` 성공을 offsite success 또는 remote-sync 완료로 표시하지 않는다.
- remote sync 증거를 만들지 못한 backup set의 offsite RPO는 `PASS`로 표시하지 않고 `UNKNOWN` 또는 실패 상태로 둔다.
- backup 실패 시 기존 정상 snapshot과 retention 대상 삭제를 금지한다.

### local·offsite evidence

- `local backup RPO`는 외장 SSD에서 검증한 repository snapshot을 기준으로 계산한다.
- `local iCloud repository integrity`는 local iCloud Drive path의 restic snapshot 조회·`check` 결과이며 별도 상태다.
- `offsite RPO`는 Apple remote sync 완료가 별도 검증된 가장 최근 backup-set ID만 기준으로 계산한다.
- automated remote-sync verification이 구현되기 전에는 local RPO와 offsite RPO를 분리하고, 수동 remote evidence의 주체·시각·backup-set ID를 기록한다.
- 수동 offsite success는 second trusted device 또는 기존 local cache를 사용하지 않는 clean retrieval path에서 exact backup-set/snapshot ID를 remote로 다시 확인한 경우에만 기록한다. 확인하지 않은 daily backup은 마지막 verified offsite 시점을 전진시키지 않는다.
- 최초 production gate에서는 local cache를 authority로 쓰지 않는 fresh retrieval이 필요하다. second trusted device를 우선하고, 불가하면 기존 local iCloud cache를 배제한 clean retrieval path에서 동일 backup set을 다시 가져와 restic check와 대표 DB/media restore를 검증한다.
- fresh retrieval은 단순 파일 placeholder·동기화 아이콘 확인으로 대체하지 않는다.

### 일정·보존·검증

- 정기 backup: 매일 03:30 `Asia/Seoul`
- 추가 backup: migration·major upgrade 직전 on-demand
- 보존: daily 7, weekly 4, monthly 6
- weekly: repository structural check
- monthly: 전체 pack을 읽는 full data check
- quarterly: isolated full restore와 static build smoke
- 최초 production: remotely synced backup set의 fresh retrieval·restic check·대표 restore 증거
- prune: 월간 maintenance window에서 retention dry-run·검토 뒤에만 수행하고 완료 후 다시 check

### restore

- 초기 목표는 local RPO와 remotely verified offsite RPO 각각 `<= 24h`, RTO `<= 8h`다. offsite evidence가 없으면 offsite RPO는 `PASS`가 아니다.
- 첫 restore drill의 실제 소요 시간과 데이터량으로 목표를 조정한다.
- isolated Compose project, 새 PostgreSQL data directory와 새 media root에 restore한다.
- manifest, checksum·file count, Flyway schema, 핵심 row/API, 대표 canonical media와 static build를 검증한다.
- 운영 DB·media를 직접 overwrite하지 않는다. 운영 전환은 exact target, backup과 rollback을 확인한 별도 명시 승인 후 수행한다.

## 이유

- 서로 다른 두 encrypted repository는 host·media·동기화 장애의 공통 실패 범위를 줄인다.
- write maintenance와 하나의 manifest는 DB relation과 media 파일을 같은 복구 시점으로 묶는다.
- local check, remote sync evidence와 fresh retrieval restore는 local snapshot 존재·Apple remote 보관·실제 복구 가능성을 구분한다.

## 결과

### 장점

- remote sync가 검증된 backup set에 한해 Mac mini 손실과 local backup 손상에 대비한 offsite 사본을 갖는다.
- backup set과 release를 exact SHA·digest·schema로 연결할 수 있다.
- production overwrite 없이 복구 절차를 반복 검증할 수 있다.

### 비용·위험

- 외장 SSD, iCloud 공간과 full data check의 I/O·시간 비용이 필요하다.
- iCloud 동기화 지연·오류를 local destination snapshot 조회와 구분해 별도 확인해야 한다.
- automated remote-sync verification 전에는 offsite RPO가 수동 evidence 주기에 의존하고 `UNKNOWN`이 될 수 있다.
- backup password와 recovery key 분실은 snapshot을 복구 불가능하게 만든다.

## 거부한 대안

### 실행 중 PostgreSQL raw volume 복사

DB 일관성과 portable restore를 보장하지 못하므로 유일한 backup으로 사용하지 않는다.

### iCloud filesystem mirror만 사용

삭제가 전파되고 snapshot·retention·무결성 증거가 부족해 거부한다.

### 동일 Mac mini 내부 사본만 보존

host·전원·storage 장애와 분리되지 않아 3-2-1 목표를 충족하지 못한다.

## 실행 계획

- [ ] 외장 SSD mount path·용량과 host 접근보호 확인
- [ ] 외장 SSD·iCloud restic repository와 독립 key provisioning
- [ ] maintenance·dump·media manifest·copy 자동화 구현
- [ ] local iCloud repository integrity와 Apple remote-sync evidence·local/offsite RPO 분리 구현
- [ ] second trusted device 또는 clean retrieval path의 최초 fresh retrieval·check·대표 restore 증거 확보
- [ ] automated remote-sync verification 구현 여부를 후속 결정하고 미구현 동안 수동 evidence runbook 유지
- [ ] HomeOps backup status event 연동
- [ ] retention dry-run·prune·check runbook 구현
- [ ] quarterly isolated full restore 첫 증거 확보

## 재검토 조건

- 데이터량이 daily window 또는 iCloud 용량을 초과함
- 첫 restore drill이 RTO 8시간을 충족하지 못함
- Mac mini 외 별도 backup writer나 NAS가 승인됨
- 법적 보존·삭제 요구가 현재 retention과 충돌함
