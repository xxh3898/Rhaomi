---
title: "로드맵"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "릴리스 범위 변경 시"
---

# 로드맵

## Phase 0 — 기준 문서

- 제품·범위·아키텍처 결정
- ADR 작성
- GitHub 템플릿
- 출시 전 미확정 항목 분리

## Phase 1 — 기반 인프라와 CMS

- Next.js 프로젝트 초기화
- Directus + PostgreSQL Docker 구성
- CMS 컬렉션·관계·권한
- 스키마 스냅샷
- 로컬 개발 환경
- 샘플 콘텐츠

## Phase 2 — 공개 랜딩 MVP

- Hero
- 갤러리·견종 필터
- 은총쌤 소개
- 서비스
- 예약 전 안내
- 공지
- 위치·영업시간
- 고정 CTA
- 반응형·접근성

## Phase 3 — 정적 콘텐츠 배포

- CMS 데이터 스냅샷
- 이미지 최적화 파생본
- 정적 라우트 생성
- Directus Flow
- 내부 deploy hook
- 원자적 배포·롤백

## Phase 4 — SEO·출시 품질

- metadata, canonical, Open Graph
- LocalBusiness JSON-LD
- robots, sitemap
- Google Search Console
- 네이버 서치어드바이저
- 성능·접근성·실기기 검증
- 운영 백업·모니터링

## Phase 5 — 운영 검증 후 선택

다음은 초기 운영 데이터가 확인된 뒤에만 검토한다.

- 견종별 독립 SEO 페이지
- 공지 RSS
- Before/After 비교
- 전용 모바일 관리자 화면
- 외부 객체 스토리지
- 개인정보 최소형 분석
- 임시휴무 배너
- 다중 이미지 갤러리

## 제외 유지

예약·결제·고객 계정·문의 폼은 별도 사업 요구가 확인되기 전 로드맵에 넣지 않는다.
