---
title: "백업·복구"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-31"
review_trigger: "저장소·보존 정책 변경 시"
---

# 백업·복구

## 현재 authority와 구현 상태

[ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md)의 2026-08-31 개정에 따라 초기 production backup은 Mac mini 내부 local-only application-consistent backup이다.

```text
PostgreSQL = pg_dump -Fc / pg_restore
canonical media = /private/var/lib/rhaomi/data/media
backup-set manifest = DB dump + media + checksum·size·file count + Git SHA/image digest/Flyway
destination = protected source와 분리된 Mac mini local backup repository/path
```

이는 승인 계약이며 실제 local repository/path, automation, backup set과 restore evidence는 아직 생성되지 않았다. [Production readiness matrix](production-readiness.md)의 초기 backup 행은 `IMPLEMENTATION_REQUIRED`다.

## 보호 대상

### 필수

- PostgreSQL `pg_dump -Fc` custom archive
- `/private/var/lib/rhaomi/data/media`의 backend 소유 private canonical media
- checksum·size·file count가 있는 backup manifest
- Git SHA, image digest와 Flyway version
- Secret 값이 아닌 Compose·Nginx·publisher·backup inventory와 복구 위치 식별자
- 운영 Secret과 domain·DNS·인증서의 별도 승인된 복구 절차

### 재생성 가능

- 공개 정적 파생 이미지
- Next `out/`
- `node_modules`
- build cache
- publisher 재처리 가능한 임시 artifact

production project-scoped PostgreSQL named volume과 raw PGDATA file은 required backup input이 아니다. primary persistence는 named volume이 담당하지만 portable backup/restore authority는 `pg_dump -Fc`와 `pg_restore`다.

## 초기 local-only repository 계약

- exact repository path, owner·permission, capacity와 source 분리는 production provisioning에서 확정한다. 이 문서나 source code에 실제 path를 추측하지 않는다.
- active PostgreSQL named volume, canonical media source와 public release directory를 backup destination으로 재사용하지 않는다.
- versioned backup-set directory 또는 동등한 immutable-complete-set 경계를 사용하고 incomplete set을 정상 backup으로 승격하지 않는다.
- filesystem mirror나 mirror delete를 backup authority로 사용하지 않는다.
- backup 실패 시 기존 정상 backup set과 retention 대상을 삭제하지 않는다.
- local repository가 같은 Mac mini failure domain에 있음을 release evidence와 HomeOps 상태에 명시한다.

## application-consistent backup 순서

```text
관리자 write maintenance
→ pg_dump -Fc
→ canonical media snapshot·manifest 확정
→ local repository의 새 backup set 완성
→ dump/media checksum·size·file count 재검증
→ write maintenance 해제
→ local backup evidence 기록
```

- public static site는 write maintenance 중에도 계속 제공한다.
- DB dump와 media는 같은 backup-set ID로 묶는다.
- manifest에 Git SHA, image digest, Flyway version, backup-set ID, 시작·완료·검증 시각을 기록한다.
- command exit code만으로 성공 처리하지 않고 완료 artifact와 manifest를 다시 읽는다.
- secret, repository password, session/token과 private endpoint를 manifest에 기록하지 않는다.

## 주기·보존

| 작업 | 주기 | 계약 |
|---|---|---|
| application-consistent local backup | 매일 03:30 `Asia/Seoul` | daily 7 / weekly 4 / monthly 6 |
| on-demand backup | migration·major upgrade 전 | 변경 검증과 rollback 종료까지 보존 |
| structural check | 매주 | dump archive·manifest·media checksum 확인 |
| full data read | 매월 | 보존 대상 전체 byte 무결성 확인 |
| isolated full restore | 분기 | 새 PostgreSQL named volume·media root에서 전체 복구 |
| retention prune | 월간 maintenance window | dry-run·검토 뒤 실행, 완료 뒤 post-check |

- 새 backup set이 검증되지 않았거나 기존 정상 set이 3개 미만이면 prune하지 않는다.
- initial production gate에는 최초 isolated representative restore evidence가 필요하다.

## local success와 accepted risk

| 상태 | authority | 의미 |
|---|---|---|
| local backup RPO | 검증된 local backup-set manifest·artifact | logical deletion·corruption·rollback에 사용할 같은 Mac의 복구점 |
| offsite backup | 없음 | `NOT_CONFIGURED / DEFERRED`; 초기 production blocker 아님 |

초기 목표는 local RPO 최대 24시간, RTO 8시간이다. 첫 restore drill 결과로 조정한다.

초기 local-only backup은 Mac mini host·internal storage·화재·도난 같은 전체 손실에서 production data와 함께 사라질 수 있다. logical recovery에는 유효하지만 host/disk disaster recovery를 보장하지 않는 accepted risk다. local success를 offsite `PASS`로 표현하지 않는다.

## restore 순서

1. 장애 범위와 source backup-set manifest 확인
2. isolated Compose project, 새 project-scoped PostgreSQL named volume과 새 media root 준비
3. local repository의 선택한 backup set을 새 target에 복원
4. custom dump를 새 PostgreSQL에 `pg_restore`
5. media checksum·file count와 manifest 검증
6. Flyway schema, 핵심 table·row count와 actor/audit 확인
7. Spring Boot를 임시 DB·media root에 연결
8. 핵심 API와 대표 canonical media 확인
9. 동일 publisher pipeline으로 정적 build·validation
10. PostgreSQL restart와 일반 Compose `down`·`up` 뒤 persistence 확인
11. 복구 시간, RPO와 문제 기록
12. 운영 전환이 필요하면 별도 명시 승인 요청

운영 DB·media를 직접 overwrite하지 않는다. production `docker compose down -v`, `docker volume prune`과 named volume direct delete는 backup 상태와 무관하게 금지한다.

## quarterly restore drill

- isolated Compose project와 새 PostgreSQL named volume·media root
- `pg_restore`와 Flyway V1~V9 schema validation
- `shop_settings`, `gallery_items`, `notices` 조회
- 대표 private canonical media checksum·decode
- 정적 사이트 build
- container restart·일반 Compose `down`·`up` 뒤 row/schema persistence
- source backup-set ID, 복구 시간·RPO/RTO와 문제 기록
- 운영 DB·media overwrite 0

## 삭제 사고

- 운영자 삭제는 archive이므로 먼저 status 복구
- hard delete면 audit/history와 backup 복구 가능 여부 확인
- 원본 파일 삭제까지 발생했으면 같은 backup set의 media 복구
- DB와 파일 참조 시점을 일치
- 복구 후 정적 재배포

## future hardening — 초기 production 이후

별도 승인 뒤 외장 SSD encrypted restic repository와 iCloud Drive separate encrypted restic repository를 추가해 3-2-1을 구성할 수 있다.

- 외장 SSD exact path는 `/Volumes/<provisioned-volume>/...` 아래에서 identity·capacity·ownership과 함께 확정한다.
- 두 repository key를 분리하고 password source는 macOS Keychain 또는 root-owned `0600` file, recovery copy는 password manager+별도 offline copy를 사용한다.
- local iCloud Drive repository snapshot/check는 Apple remote sync 완료가 아니다.
- remotely verified backup-set ID만 offsite RPO를 전진시킨다.
- second trusted device 또는 clean retrieval path의 fresh retrieval·restic check·representative restore를 offsite acceptance로 사용한다.

구현 전 external/offsite 상태는 `NOT_CONFIGURED / DEFERRED`이며 recovery key도 초기 production blocker가 아니다.
