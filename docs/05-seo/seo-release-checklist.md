---
title: "SEO 출시 체크리스트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "SEO 구현 변경 시"
---

# SEO 출시 체크리스트

## HTML

- [ ] 홈 H1이 매장과 페이지 목적을 설명한다.
- [ ] heading 순서가 논리적이다.
- [ ] 핵심 매장정보가 정적 HTML에 있다.
- [ ] 공지 상세가 정적 HTML로 생성된다.
- [ ] JavaScript를 끈 상태에서도 핵심 링크를 찾을 수 있다.
- [ ] 이미지 alt가 실제 내용과 일치한다.

## Metadata

- [ ] 홈 title 정확
- [ ] 홈 description 정확
- [ ] 공지별 고유 title
- [ ] canonical 절대 URL
- [ ] Open Graph title/description/image
- [ ] OG 이미지 200 응답
- [ ] 개발 도메인이 남아 있지 않음
- [ ] `noindex`가 공개 페이지에 없음

## Structured data

- [ ] JSON 문법 유효
- [ ] LocalBusiness 화면 정보와 일치
- [ ] 주소·전화·영업시간 최종 확인
- [ ] 가짜 rating/review 없음
- [ ] Rich Results Test 치명 오류 없음

## Crawling

- [ ] `/robots.txt` 200
- [ ] `/sitemap.xml` 200
- [ ] sitemap URL이 canonical과 일치
- [ ] draft/archived/expired URL 제외
- [ ] 404가 HTTP 404
- [ ] 관리자 subdomain이 검색 차단이 아니라 인증으로 보호
- [ ] Nginx가 Yeti/Googlebot을 임의 차단하지 않음

## Local

- [ ] 사이트 NAP
- [ ] 네이버지도 NAP
- [ ] 카카오맵 NAP
- [ ] 네이버블로그 NAP
- [ ] 인스타그램 프로필 링크
- [ ] 외부 지도 링크 실제 동작

## 등록

- [ ] Google Search Console 소유권
- [ ] Google sitemap 제출
- [ ] 네이버 서치어드바이저 소유권
- [ ] 네이버 sitemap 제출
- [ ] 홈 URL 검사/수집 요청

## 기록

- [ ] 출시 시각
- [ ] 배포 commit/release
- [ ] 제출한 sitemap URL
- [ ] 검증 화면 또는 결과
- [ ] 미해결 경고
