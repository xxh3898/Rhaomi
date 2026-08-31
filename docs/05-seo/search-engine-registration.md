---
title: "검색엔진 등록"
status: "draft"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-31"
review_trigger: "도메인 확정 또는 검색엔진 절차 변경 시"
---

# 검색엔진 등록

production에서 사용할 현재 public FQDN과 HTTPS가 provisioning된 뒤 수행한다. 초기에는 사용자 소유 기존 도메인의 exact temporary FQDN을 외부 입력으로 확정하며, 사촌 소유 전용 도메인으로 변경하면 같은 절차를 새 canonical domain 기준으로 다시 검증한다.

## 사전 조건

- 운영 도메인 접속 성공
- HTTP → HTTPS 정책 확정
- www/apex 중 하나로 301 통일
- canonical 정확
- robots 접근 가능
- sitemap 접근 가능
- 404가 실제 404 상태 반환
- `/admin`과 `/api/**`가 sitemap에 없음
- NAP 최종 확인
- 최소 실제 사진과 콘텐츠 반영

## Google Search Console

1. 속성 등록
2. DNS 또는 제공되는 방식으로 소유권 확인
3. `sitemap.xml` 제출
4. 홈 URL 검사
5. 렌더링된 HTML과 canonical 확인
6. LocalBusiness JSON-LD 검사
7. 색인 요청
8. Core Web Vitals와 모바일 문제 관찰

## 네이버 서치어드바이저

1. 현재 production host 단위 사이트 등록
2. meta 또는 HTML 파일 방식으로 소유 확인
3. robots 수집·검증
4. 사이트맵 제출
5. 홈과 주요 공지 수집 요청
6. 사이트 간단 체크
7. 네이버 검색로봇 접근이 방화벽에서 차단되지 않는지 확인
8. 채널 정보와 공식 SNS 연결 검토

## Naver 최신 콘텐츠

공지 운영이 안정되면 전체 본문을 포함한 RSS 제공 필요성을 검토한다. RSS를 넣기 전 URL, 공개 상태, 만료 정책을 sitemap과 일치시킨다.

## 외부 프로필

- 네이버지도/플레이스에 공식 사이트 URL 추가
- 카카오맵 정보 확인
- 네이버블로그 프로필 또는 공지에 공식 사이트 연결
- 인스타그램 bio link 갱신
- 상호·전화·주소 표기 통일

## 색인 확인

- Google `site:<domain>`
- 네이버 `site:<domain>`
- 브랜드명 검색
- Search Console URL Inspection
- 네이버 수집 현황

## 주의

- sitemap 제출은 색인이나 순위를 보장하지 않는다.
- 도메인 또는 URL을 바꾸면 301, canonical, sitemap, 검색도구를 함께 변경한다.
- 검색 반영 속도를 확정 일정처럼 약속하지 않는다.
