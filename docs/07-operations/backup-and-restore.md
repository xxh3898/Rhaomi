---
title: "백업·복구"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "저장소·보존 정책 변경 시"
---

# 백업·복구

## 보호 대상

### 필수

- PostgreSQL `pg_dump -Fc` custom archive
- `/private/var/lib/rhaomi/data/media`의 backend 소유 private canonical media
- checksum·size·file count와 source·destination snapshot ID가 있는 backup manifest
- Git SHA, image digest와 Flyway version
- Secret 값이 아닌 Compose·Nginx·publisher·backup inventory와 복구 위치
- 운영 Secret과 domain·DNS·인증서의 별도 승인된 복구 절차

### 재생성 가능

- 공개 정적 파생 이미지
- Next `out/`
- node_modules
- build cache
- publisher 재처리 가능한 임시 artifact

production project-scoped PostgreSQL named volume과 raw PGDATA file은 required restic backup input이 아니다. primary persistence는 named volume이 담당하지만 portable backup/restore authority는 `pg_dump -Fc`와 `pg_restore`다.

## 3-2-1 계약

```text
Copy 1: Mac mini production data — PostgreSQL named volume + `/private/var/lib/rhaomi/data/media`
Copy 2: 암호화 외장 SSD volume의 encrypted restic repository
Copy 3: iCloud Drive의 별도 encrypted restic repository
```

- 외장 SSD는 production release gate다.
- iCloud는 intended offsite transport/storage이고 restic이 encryption과 repository integrity를 담당한다. Apple remote sync 완료가 별도 검증된 backup set만 offsite 사본으로 인정한다.
- 두 repository와 encryption key를 분리하고 한 host automation만 writer가 된다.
- filesystem mirror와 mirror delete를 backup으로 사용하지 않는다.
- 외장 SSD repository exact path는 `/Volumes/<provisioned-volume>/...` 아래에서 volume identity·용량·ownership과 함께 확정한다. iCloud folder와 recovery key 보관 위치도 [미확정 항목](../01-product/open-items.md)의 출시 차단값이다.

## secret 경계

- repository password를 Git, shell history, command argument, process output, log와 알림에 넣지 않는다.
- automation은 root-owned `0600` password file 또는 제한된 macOS Keychain password command를 사용한다.
- recovery key는 password manager와 별도 offline 사본에 보관한다.
- password 값과 Secret inventory를 backup manifest에 기록하지 않는다.

## 권장 주기

| 작업 | 주기 | 계약 |
|---|---|---|
| application-consistent backup | 매일 03:30 `Asia/Seoul` | daily 7 / weekly 4 / monthly 6 |
| on-demand backup | migration·major upgrade 전 | 변경 검증과 rollback 종료까지 보존 |
| repository structural check | 매주 | 외장 SSD와 local iCloud Drive repository 확인 |
| remote sync evidence | 자동화 전 수동 | backup-set ID별 Apple remote sync 완료를 local check와 분리해 기록 |
| full data read | 매월 | repository pack 전체 무결성 확인 |
| isolated full restore | 분기 | 새 data directory·media root에서 전체 복구 |
| initial offsite acceptance | 최초 production 전 | second trusted device 또는 clean retrieval path에서 fresh retrieval·check·대표 restore |
| retention prune | 월간 maintenance window | dry-run·검토 후 실행, 완료 뒤 check |

소규모 데이터 기준의 초기 정책이며 실제 용량과 백업 매체에 맞춰 조정한다.

## 백업 일관성

```text
관리자 write maintenance
→ pg_dump -Fc
→ canonical media snapshot·manifest 확정
→ write maintenance 해제
→ 외장 SSD restic snapshot·structural check
→ iCloud 별도 restic repository로 snapshot copy
→ local iCloud Drive repository snapshot 조회·check
→ Apple remote sync 완료 별도 검증
→ local·offsite evidence 분리 기록
```

- public static site는 write maintenance 중에도 계속 제공한다.
- DB dump와 media는 같은 backup-set ID로 묶는다.
- manifest에 checksum, byte size, file count, Git SHA, image digest, Flyway version, 두 repository snapshot ID, local check와 remote-sync evidence 상태·시각을 기록한다.
- command exit code나 iCloud 동기화 표시만으로 성공 처리하지 않고 artifact와 local destination snapshot을 조회한다. 이 결과는 local iCloud repository integrity이지 Apple remote sync 완료가 아니다.
- remote sync 완료를 증명하지 못한 backup set은 offsite success 또는 offsite RPO `PASS`로 표시하지 않는다.
- backup 실패 시 기존 정상 snapshot·retention 대상과 recovery key를 삭제하지 않는다.

## local·offsite 성공 판정

| 상태 | authority | 성공 의미 |
|---|---|---|
| local backup RPO | 외장 SSD snapshot/check와 manifest | Mac mini production data 밖의 local 복구점 |
| local iCloud repository integrity | 현재 Mac의 iCloud Drive path에서 restic snapshot/check | local cached repository가 읽히고 무결함 |
| offsite RPO | Apple remote sync 완료가 별도 검증된 backup-set ID | 검증 시점에 remote iCloud에 도달한 가장 최근 복구점 |

- automated remote-sync verification이 구현되기 전에는 세 상태를 별도로 기록한다.
- remote 증거가 없거나 모호하면 offsite 상태를 `UNKNOWN`으로 두고 local RPO 성공으로 대체하지 않는다.
- 수동 offsite success는 second trusted device 또는 기존 local cache를 사용하지 않는 clean retrieval path에서 exact backup-set/snapshot ID를 remote로 다시 확인한 경우에만 기록한다. 확인하지 않은 daily backup은 offsite RPO 시점을 전진시키지 않는다.
- 최초 production gate는 second trusted device를 우선한다. 불가하면 기존 local cache를 authority로 사용하지 않는 clean retrieval path에서 remotely synced backup set을 fresh retrieval한 뒤 restic check와 대표 dump/media restore를 수행한다.
- fresh retrieval 증거에는 backup-set ID, retrieval path 유형, restic check, 대표 restore checksum/file count와 수행 시각을 남기되 Secret은 기록하지 않는다.

## 복구 순서

1. 장애 범위 확인
2. source repository와 backup-set manifest 선택
3. isolated Compose project와 새 project-scoped PostgreSQL named volume·media root 준비
4. 외장 SSD snapshot 또는 clean retrieval한 remotely verified iCloud snapshot을 새 target에 restore
5. custom dump를 새 PostgreSQL에 `pg_restore`
6. media checksum·file count와 manifest 검증
7. Flyway schema, 핵심 table·row count와 actor/audit 확인
8. Spring Boot를 임시 DB·media root에 연결
9. 핵심 API와 대표 canonical media 확인
10. 동일 publisher pipeline으로 정적 build·validation
11. 복구 시간, RPO와 문제 기록
12. 운영 전환이 필요하면 별도 명시 승인 요청

운영 DB·media를 직접 overwrite하지 않는다.

## 복구 테스트

분기 1회:

- isolated Compose project와 새 project-scoped PostgreSQL named volume·media root로 실제 full restore
- 새 PostgreSQL named volume에 `pg_restore`하고 container restart·일반 Compose `down`·`up` 뒤 row/schema persistence 확인
- `shop_settings`, `gallery_items`, `notices` 조회
- 대표 private canonical media checksum·decode
- 정적 사이트 build
- source·destination snapshot, 복구 시간·RPO/RTO와 문제 기록
- 운영 DB를 덮어쓰지 않음

최초 production 전에는 분기 restore와 별개로 remotely synced iCloud backup set의 fresh retrieval·restic check·대표 restore를 통과해야 한다. 이후 자동 remote-sync 검증이 없으면 수동 evidence 주기와 마지막 검증 시각을 HomeOps에 별도로 노출한다.

production implementation gate는 `docker compose down -v`, `docker volume prune`과 named volume direct delete가 deploy·backup·restore runbook에 없음을 확인한다. 해당 명령은 backup 검증 여부와 무관하게 production에서 실행하지 않는다.

## 삭제 사고

- 운영자 삭제는 archive이므로 먼저 status 복구
- hard delete면 audit/history와 backup 복구 가능 여부를 확인
- 원본 파일 삭제까지 발생했으면 image storage backup 복구
- DB와 파일 참조 시점을 일치
- 복구 후 정적 재배포

## RPO/RTO

초기 목표:

- local RPO: 최대 24시간
- offsite RPO: 최대 24시간. remote-sync evidence가 있는 backup set만 계산하며 증명할 수 없으면 `PASS` 금지
- RTO: 8시간 이내 목표

첫 restore drill 결과로 목표를 조정하고 사업 영향이 커지면 실제 증거를 근거로 강화한다.

## 구현 상태

[ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md)의 계약만 승인됐다. 외장 SSD·iCloud repository, password source, backup automation, remote-sync verification과 restore environment는 아직 생성·실행하지 않았다.
