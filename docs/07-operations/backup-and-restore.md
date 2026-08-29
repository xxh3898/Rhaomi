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
- backend 소유 private canonical media
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

## 3-2-1 계약

```text
Copy 1: Mac mini production data
Copy 2: 암호화 외장 SSD volume의 encrypted restic repository
Copy 3: iCloud Drive의 별도 encrypted restic repository
```

- 외장 SSD는 production release gate다.
- iCloud는 offsite transport/storage이고 restic이 encryption과 snapshot integrity를 담당한다.
- 두 repository와 encryption key를 분리하고 한 host automation만 writer가 된다.
- filesystem mirror와 mirror delete를 backup으로 사용하지 않는다.
- 정확한 SSD mount path·용량, iCloud folder와 recovery key 보관 위치는 [미확정 항목](../01-product/open-items.md)의 출시 차단값이다.

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
| repository structural check | 매주 | 외장 SSD와 iCloud destination 확인 |
| full data read | 매월 | repository pack 전체 무결성 확인 |
| isolated full restore | 분기 | 새 data directory·media root에서 전체 복구 |
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
→ destination snapshot 조회·manifest 기록
```

- public static site는 write maintenance 중에도 계속 제공한다.
- DB dump와 media는 같은 backup-set ID로 묶는다.
- manifest에 checksum, byte size, file count, Git SHA, image digest, Flyway version과 두 repository snapshot ID를 기록한다.
- command exit code나 iCloud 동기화 표시만으로 성공 처리하지 않고 artifact와 destination snapshot을 조회한다.
- backup 실패 시 기존 정상 snapshot·retention 대상과 recovery key를 삭제하지 않는다.

## 복구 순서

1. 장애 범위 확인
2. source repository와 backup-set manifest 선택
3. isolated Compose project와 새 PostgreSQL data directory·media root 준비
4. 외장 SSD 또는 iCloud snapshot을 새 target에 restore
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

- isolated Compose project와 새 data directory로 실제 full restore
- `shop_settings`, `gallery_items`, `notices` 조회
- 대표 private canonical media checksum·decode
- 정적 사이트 build
- source·destination snapshot, 복구 시간·RPO/RTO와 문제 기록
- 운영 DB를 덮어쓰지 않음

## 삭제 사고

- 운영자 삭제는 archive이므로 먼저 status 복구
- hard delete면 audit/history와 backup 복구 가능 여부를 확인
- 원본 파일 삭제까지 발생했으면 image storage backup 복구
- DB와 파일 참조 시점을 일치
- 복구 후 정적 재배포

## RPO/RTO

초기 목표:

- RPO: 최대 24시간
- RTO: 8시간 이내 목표

첫 restore drill 결과로 목표를 조정하고 사업 영향이 커지면 실제 증거를 근거로 강화한다.

## 구현 상태

[ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md)의 계약만 승인됐다. 외장 SSD·iCloud repository, password source, backup automation과 restore environment는 아직 생성·실행하지 않았다.
