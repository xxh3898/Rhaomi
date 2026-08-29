---
title: "정기 유지보수"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "운영 주기 변경 시"
---

# 정기 유지보수

## 구현 상태

아래 주기는 [ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md)와 [ADR-013](../09-decisions/ADR-013-homeops-monitoring-recovery-boundary.md)의 production 목표다. backup repository, HomeOps monitor와 maintenance automation은 아직 구현·실행되지 않았다.

## 매일 자동

- HomeOps의 public HTTPS·핵심 문구와 internal healthcheck
- Docker container·host CPU/memory/load·disk/inode 확인
- 매일 03:30 `Asia/Seoul` application-consistent PostgreSQL·canonical media backup
- 외장 SSD snapshot, local iCloud Drive repository snapshot/check와 별도 remote-sync evidence 상태 확인
- local backup RPO와 remotely verified offsite RPO를 분리해 기록하고 remote evidence가 없으면 offsite `PASS` 금지
- publisher immediate/due event·overdue backlog·lock·마지막 `contentRevision`·`publishGeneration` build/release 상태
- 인증서 만료 단계 확인
- HomeOps Activity·Discord·daily summary

## 매주

- 최근 공지 publish/expiry boundary와 public snapshot 일치 audit·reconciliation. 이 점검을 correctness trigger로 사용하지 않음
- 외장 SSD와 local iCloud Drive repository의 restic structural check
- Apple remote sync 완료 증거와 마지막 remotely verified backup-set 확인
- 실패한 publisher·release·backup 로그 확인
- 이미지 깨짐 검사
- 외부 링크 스모크
- 보관 대상 임시 파일 확인
- 관리자 로그인 이상 확인
- incident hold와 bounded log rotation 상태 확인

## 매월

- dependency·container 보안 알림 검토
- 공개 사이트 Lighthouse 기준 측정
- Search Console·네이버 노출 확인
- NAP 일치 확인
- 관리자 사용자·세션 검토
- 성공 release 최근 5개, 실패 artifact 7일과 build cache 정리 후보 검토
- restic retention dry-run 뒤 daily 7 / weekly 4 / monthly 6 적용·prune·post-check
- 외장 SSD와 local iCloud Drive repository full data read. 이 성공을 Apple remote sync 증거로 해석하지 않음
- service당 약 100 MiB·일반 14일 로그 보존과 incident hold 확인

## 분기

- isolated Compose와 새 data directory의 실제 full restore test
- iCloud offsite 표본은 second trusted device 또는 local cache를 authority로 사용하지 않는 clean retrieval path에서 fresh retrieval·restic check·대표 restore
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
- 외장 SSD·local iCloud repository의 정상 retention snapshot과 remotely verified offsite backup set
- backend 소유 원본 image storage
- 현재·직전 정상 release
- 사고 조사 중 로그
- 라이선스 또는 도메인 복구 정보

## 자동화 금지 경계

- backup·deploy lock이 있을 때 stateless service restart
- Compose `down`·`up`, PostgreSQL restart와 volume mutation
- migration·restore·backup 삭제
- `cloudflared`·HomeOps 자체 제어
- `current`·`previous` target 정리
