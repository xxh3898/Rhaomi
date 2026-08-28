---
title: "모니터링·장애 대응"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "모니터링 도구·장애 등급 변경 시"
---

# 모니터링·장애 대응

## 모니터링 대상

### 공개 사이트

- HTTPS 200
- 홈 핵심 문구 존재
- 정적 asset 200
- 응답시간
- 인증서 만료
- DNS
- 404 동작

### 관리자

- Spring Boot 최소 health endpoint
- 로그인 가능 여부
- PostgreSQL 연결
- 후속 원본 image storage 쓰기
- 로그인 실패 급증
- 관리자 certificate

### 배포

- 마지막 성공 release
- 마지막 실패 원인
- build queue
- lock 장기 점유
- 디스크 여유
- release 보존 개수

### 백업

- 마지막 성공 시각
- dump 크기
- 후속 원본 image storage backup 크기
- offsite 전송
- 복구 테스트일

## 장애 등급

| 등급 | 예 | 대응 |
|---|---|---|
| SEV-1 | 공개 사이트 전체 불가, 도메인 탈취, 관리자 침해 | 즉시 격리·롤백·자격 증명 폐기 |
| SEV-2 | 문의 링크 오류, 잘못된 영업정보, 콘텐츠 게시 불가 | 당일 수정 |
| SEV-3 | 일부 이미지 오류, 비핵심 UI 결함 | 계획된 수정 |
| SEV-4 | 문구·정렬 개선 | backlog |

## 초기 대응

1. 현재 공개 site가 정상인지 확인
2. 마지막 변경이 코드인지 콘텐츠인지 구분
3. release ID와 commit 기록
4. 새 배포 중지
5. 침해 의심 시 관리자 세션·토큰 차단
6. 기존 정상 release가 있으면 rollback
7. DB/파일 손상이 의심되면 쓰기 중지
8. 증거 보존
9. 사건 문서 작성

## 콘텐츠 오정보

영업시간, 휴무, 전화번호가 잘못된 경우:

1. 후속 `/admin`에서 수정
2. 재배포 결과 확인
3. 네이버지도·카카오맵·블로그도 확인
4. 캐시된 공유 미리보기와 검색 결과는 즉시 바뀌지 않을 수 있음을 기록
5. 중요한 경우 공지 또는 SNS로 정정

## 침해 의심

- 운영자 계정 비활성화
- 모든 관리자 session 폐기
- 후속 build credential rotation
- Deploy hook secret rotation
- GitHub runner와 credential 확인
- Nginx·Spring Boot access log 보존
- 파일·콘텐츠 변경 이력 확인
- clean release 재배포
- 재발 방지 조치

## 종료 조건

- 고객 영향 제거
- 원인과 변경 범위 확인
- 복구 검증
- 남은 위험 기록
- 후속 Issue
- 문서·런북 갱신
