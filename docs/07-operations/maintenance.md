---
title: "정기 유지보수"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "운영 주기 변경 시"
---

# 정기 유지보수

## 매일 자동

- 공개 사이트 healthcheck
- 관리자 healthcheck
- PostgreSQL backup
- uploads backup
- 디스크 여유 확인
- 인증서 만료 확인
- 마지막 콘텐츠 배포 상태

## 매주

- 최근 공지 만료 확인
- 실패한 빌드·백업 로그 확인
- 이미지 깨짐 검사
- 외부 링크 스모크
- 보관 대상 임시 파일 확인
- 관리자 로그인 이상 확인

## 매월

- dependency·container 보안 알림 검토
- 공개 사이트 Lighthouse 기준 측정
- Search Console·네이버 노출 확인
- NAP 일치 확인
- 관리자 사용자·세션 검토
- release와 build cache 정리
- backups checksum 표본 확인

## 분기

- 실제 restore test
- Directus·PostgreSQL upgrade 필요성 검토
- 계정·token rotation 검토
- 개인정보·로그 보존 검토
- 콘텐츠 운영 불편 수집
- 견종별 SEO 페이지 생성 기준 검토

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
- 유일한 offsite backup
- Directus 원본 uploads
- 현재·직전 정상 release
- 사고 조사 중 로그
- 라이선스 또는 도메인 복구 정보
