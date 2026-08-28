---
title: "롤백"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "배포 저장구조 변경 시"
---

# 롤백

## 대상

- 정적 공개 사이트
- 코드 release
- 콘텐츠 snapshot
- Directus schema
- PostgreSQL 데이터

## 정적 사이트 롤백

가장 우선하는 저위험 조치다.

```text
current → previous release
```

절차:

1. 현재 release ID 기록
2. previous가 정상 릴리스인지 확인
3. symlink 원자적 전환
4. 홈·CTA·이미지·공지 스모크
5. 모니터링 확인
6. 원인 release 격리
7. 사건 기록

Nginx 설정이 바뀌지 않았다면 reload 없이 전환하는 구조를 우선한다.

## 콘텐츠 롤백

### 단일 항목

- Directus revision 또는 version 확인
- 잘못된 내용을 draft/archive
- 이전 값 복원
- 새 정적 배포

### 다수 항목 또는 DB 손상

- 쓰기 중지
- 백업을 임시 환경에 복구
- 데이터와 uploads 일치 확인
- 운영 전환 전 정적 build
- 직접 운영 DB 덮어쓰기는 승인 후 수행

## 코드 롤백

- 직전 정상 main commit 또는 release artifact 사용
- DB schema가 forward-only로 변경되었는지 확인
- 현재 CMS 데이터와 이전 코드의 호환성 확인
- 단순 `git reset`만으로 운영 데이터를 되돌리지 않는다.

## Directus schema 롤백

고위험 작업이다.

- schema snapshot/diff
- 데이터 손실 가능성
- 컬럼 삭제 여부
- Directus system table 영향
- 라이선스·버전 호환성
- 백업과 임시 restore

확인 전 실행 보류.

## 롤백 완료 조건

- 공개 핵심 경로 정상
- 잘못된 콘텐츠 제거
- 관리자 쓰기 정상 또는 안전하게 중지
- 데이터 손실 범위 확인
- 후속 수정 Issue 생성
- 자동 배포가 문제 release를 다시 올리지 않게 차단
