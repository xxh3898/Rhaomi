---
title: "정기 유지보수"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-09-01"
review_trigger: "운영 주기 변경 시"
---

# 정기 유지보수

## 구현 상태

아래 주기는 [ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md)와 [ADR-013](../09-decisions/ADR-013-homeops-monitoring-recovery-boundary.md)의 production 목표다. local/CI release retention에 더해 D-IMP-4 fixed backup/retention source와 task-scoped isolated restore는 구현됐다. tracked plist와 explicit retention apply는 actual Mac에 install/run하지 않았고 local repository·HomeOps monitor·maintenance provisioning도 미완료다. `retentionStatus=DEFERRED`인 public release나 backup incomplete/checksum failure를 cleanup 성공으로 오기록하지 않는다.

## 매일 자동

- HomeOps의 public HTTPS·핵심 문구와 internal healthcheck
- Docker container·host CPU/memory/load·disk/inode 확인
- Mac `/private/var/lib/rhaomi` filesystem과 production PostgreSQL named volume capacity·mount identity 확인
- 매일 03:30 `Asia/Seoul` application-consistent PostgreSQL·canonical media backup
- protected source와 분리된 Mac mini local repository의 backup-set manifest/check와 local RPO 확인
- external SSD/iCloud hardening은 미구성 시 `NOT_CONFIGURED / DEFERRED`로 유지하고 local success를 offsite `PASS`로 표현하지 않음
- publisher immediate/due event·overdue backlog·lock·마지막 `contentRevision`·`publishGeneration` build/release 상태
- 인증서 만료 단계 확인
- HomeOps Activity·Discord·daily summary

## 매주

- 최근 공지 publish/expiry boundary와 public snapshot 일치 audit·reconciliation. 이 점검을 correctness trigger로 사용하지 않음
- local backup repository의 dump archive·manifest·media checksum structural check
- 실패한 publisher·release·backup 로그 확인
- 이미지 깨짐 검사
- 외부 링크 스모크
- 보관 대상 임시 파일 확인
- public/media/state/build-workspace bind mount access mode, publisher image source의 workspace 외 write 거부와 PostgreSQL named volume의 container restart persistence 표본 확인
- 관리자 로그인 이상 확인
- incident hold와 bounded log rotation 상태 확인

## 매월

- dependency·container 보안 알림 검토
- 공개 사이트 Lighthouse 기준 측정
- Search Console·네이버 노출 확인
- NAP 일치 확인
- 관리자 사용자·세션 검토
- 성공 release 최근 5개, 실패 artifact 7일과 build cache 정리 후보 검토
- local backup retention dry-run 뒤 모든 complete set full-read, incomplete/latest-invalid/<3 verified 부재 확인 후 daily 7 / weekly 4 / monthly 6 explicit apply·post-check
- local backup repository의 보존 대상 full data read
- service당 약 100 MiB·일반 14일 로그 보존과 incident hold 확인

## 분기

- isolated Compose와 새 data directory의 실제 full restore test
- Spring Boot·Java·PostgreSQL upgrade 필요성 검토
- 계정·token rotation 검토
- 개인정보·로그 보존 검토
- 콘텐츠 운영 불편 수집
- 견종별 SEO 페이지 생성 기준 검토
- HomeOps 임계값, 자동 restart cooldown·audit와 same-host blind spot 재검토
- decoder-only libheif·libde265 security advisory와 x265 absence 증거 재검토

## 업그레이드 원칙

1. 공식 release note와 breaking change
2. 라이선스 영향
3. 백업
4. 임시 환경 복구
5. schema compatibility
6. 정적 build
7. 관리자 CRUD
8. rollback
9. 운영 적용
10. 문서 갱신

## 정리 금지

아래는 자동 정리하지 않는다.

- 최신 DB backup
- local repository의 정상 retention backup set
- backend 소유 원본 image storage
- production project-scoped PostgreSQL named volume과 raw PGDATA
- 현재·직전 정상 release
- 사고 조사 중 로그
- 라이선스 또는 도메인 복구 정보

## 자동화 금지 경계

- backup·deploy lock이 있을 때 stateless service restart
- Compose `down`·`up`, PostgreSQL restart와 volume mutation
- `docker compose down -v`, `docker volume prune`, PostgreSQL named volume direct delete
- migration·restore·backup 삭제
- `cloudflared`·HomeOps 자체 제어
- `current`·`previous` target 정리

일반 maintenance 종료가 Compose `down`을 필요로 하면 `-v` 없이 실행하고 재기동 뒤 PostgreSQL data persistence를 확인한다. volume 정리 후보라는 이유로 production named volume을 prune/delete하지 않는다.

## future hardening 유지보수

외장 SSD·iCloud 3-2-1이 별도 승인으로 도입된 뒤에만 repository restic check, Apple remote-sync evidence, second trusted device 또는 clean retrieval path의 fresh retrieval·대표 restore를 이 주기에 추가한다. 그 전에는 존재하지 않는 offsite 작업을 성공으로 기록하지 않는다.
