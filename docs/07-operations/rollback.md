---
title: "롤백"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-31"
review_trigger: "배포 저장구조 변경 시"
---

# 롤백

## 구현 상태

이 문서는 [ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md)~[ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md)의 목표 rollback 계약이다. publisher control loop와 local/CI release adapter는 이전 `current/previous` link snapshot을 보존하고 post-switch loopback smoke 실패 시 같은 global lock에서 복구하며, 첫 release 실패 시 `current`를 제거하는 low-level primitive를 구현했다. production service/image/path와 승인된 higher-generation rollback trigger, backup/restore runtime은 아직 구현되지 않았다.

local/CI의 same-attempt smoke rollback은 실패한 candidate가 publication success로 기록되기 전 filesystem을 복구하는 동작이다. 이미 성공한 낮은 generation release를 운영에서 직접 재활성화하는 절차와 다르며, 아래 production rollback은 계속 더 높은 새 `publishGeneration`을 요구한다.

## 대상

- 정적 공개 사이트
- 코드 release
- 콘텐츠 snapshot
- Flyway schema
- PostgreSQL 데이터

## 정적 사이트 롤백

가장 우선하는 저위험 조치다.

`previous`는 rollback 입력 후보이지 낮은 generation을 그대로 다시 활성화하는 우회 경로가 아니다. 시간 기반 notice eligibility를 다시 평가하기 위해 승인된 previous code image/digest와 current content snapshot으로 새 rollback release를 만든다.

```text
approved previous code image/digest
+ current content snapshot
→ new rollback publishGeneration
→ build·validate
→ Mac /private/var/lib/rhaomi/public/current atomic switch
  (publisher container /srv/rhaomi/public/current)
```

절차:

1. 현재 release ID 기록
2. `previous`의 exact code SHA/image digest와 정상 이력 확인
3. global deploy/publisher lock과 maintenance 상태 확인
4. 승인된 manual rollback trigger로 current보다 높은 새 `publishGeneration` 할당
5. current content snapshot을 previous code image/digest로 다시 build하고 현재 `contentRevision`·새 `publishGeneration`·새 `generatedAt` manifest 생성
6. current notice status·게시/만료, relation·media/file과 artifact 검증
7. symlink 원자적 전환
8. 홈·CTA·이미지·공지와 public HTTPS 스모크
9. HomeOps 상태 확인
10. 원인 release 격리
11. 사건 기록

Nginx 설정이 바뀌지 않았다면 reload 없이 전환하는 구조를 우선한다. 과거 release symlink를 직접 가리켜 public ordering authority인 `publishGeneration`을 감소시키지 않는다.

## 콘텐츠 롤백

### 단일 항목

- application audit/history 또는 backup 확인
- 잘못된 내용을 draft/archive
- 이전 값 복원과 새 콘텐츠 mutation `contentRevision`·immediate publishing event commit
- 단일 publisher의 새 정적 배포 결과 확인

### 다수 항목 또는 DB 손상

- 쓰기 중지
- `pg_dump -Fc` backup을 새 isolated Compose project의 새 PostgreSQL named volume에 `pg_restore`
- DB data와 후속 원본 image storage 일치 확인
- backup-set manifest와 canonical media checksum·file count 확인
- 운영 전환 전 동일 publisher pipeline의 정적 build
- 직접 운영 DB 덮어쓰기는 승인 후 수행
- production named volume raw file copy·volume swap을 rollback authority로 사용하지 않음

## 코드 롤백

- 직전 정상 exact `main` SHA와 image digest 또는 release artifact 사용
- DB schema가 forward-only로 변경되었는지 확인
- 현재 DB data와 이전 code의 호환성 확인
- 단순 `git reset`만으로 운영 데이터를 되돌리지 않는다.
- `latest` tag나 production host source rebuild를 rollback 근거로 사용하지 않는다.

## Flyway schema rollback

고위험 작업이다.

- forward migration과 reverse migration 검토
- 데이터 손실 가능성
- 컬럼 삭제 여부
- `admin_users`와 콘텐츠 table 영향
- Spring Boot/JPA/Flyway version 호환성
- 백업과 임시 restore
- one-shot Flyway evidence와 expand/contract 호환성

확인 전 실행 보류.

production backend 일반 기동으로 schema를 자동 되돌리지 않는다. destructive reverse migration과 운영 restore는 별도 승인 대상이다.

## 롤백 완료 조건

- 공개 핵심 경로 정상
- 잘못된 콘텐츠 제거
- 관리자 쓰기 정상 또는 안전하게 중지
- 데이터 손실 범위 확인
- 후속 수정 Issue 생성
- 자동 배포가 문제 release를 다시 올리지 않게 차단
- `current`·`previous`, exact SHA·digest, `contentRevision`·`publishGeneration`·`generatedAt` 기록
- Mac `/private/var/lib/rhaomi` source와 container mount mapping, PostgreSQL named volume identity·보존 상태 기록
- HomeOps incident·Activity 상태 갱신
