---
title: "로드맵"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "릴리스 범위 변경 시"
---

# 로드맵

## Phase 0 — 기준 문서

- 제품·범위·아키텍처 결정
- ADR 작성
- GitHub 템플릿
- 출시 전 미확정 항목 분리

## Phase 1 — 실행 기반과 관리 backend

### Phase 1A — 공개 frontend 부트스트랩 완료

- Next.js App Router + TypeScript + Static Export
- 개발 전용 Docker Compose와 PostgreSQL
- local 환경변수 예시와 persistent volume
- lint, typecheck, contract test, static build/export gate

### Phase 1B — Spring Boot 관리자 인증 기반

- Directus 실행 경로 제거
- Java 25 LTS + Spring Boot + PostgreSQL
- Flyway V1 `admin_users`
- Spring Security 서버 세션과 CSRF
- login, me, logout, 최소 health
- local/test bootstrap과 실제 PostgreSQL contract test
- Frontend, Backend, Compose Smoke Hosted CI

Phase 1B는 콘텐츠 CRUD나 관리자 화면의 완료를 의미하지 않는다.

### Phase 1C 이후 — 콘텐츠 도메인

- 공지·견종·서비스·갤러리·매장정보 table과 Flyway migration
- 관리자 DTO field allowlist와 archive 정책
- `/admin` 로그인·콘텐츠 관리 UI
- 이미지 원본 storage와 upload validation
- build-time read-only API와 credential 분리
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

- content snapshot
- 이미지 최적화 파생본
- 정적 route 생성
- backend 콘텐츠 변경 event 또는 outbox
- 내부 deploy hook
- 원자적 배포·rollback

## Phase 4 — SEO·출시 품질

- metadata, canonical, Open Graph
- LocalBusiness JSON-LD
- robots, sitemap
- Google Search Console
- 네이버 서치어드바이저
- 성능·접근성·실기기 검증
- 운영 backup·monitoring
- 관리자 2FA와 TLS/session cookie production gate

## Phase 5 — 운영 검증 후 선택

- 견종별 독립 SEO 페이지
- 공지 RSS
- Before/After 비교
- 관리자 UX 간소화
- 외부 객체 storage
- 개인정보 최소형 분석
- 임시휴무 배너
- 다중 이미지 갤러리

## 제외 유지

예약·결제·고객 계정·문의 폼은 별도 사업 요구가 확인되기 전 로드맵에 넣지 않는다.
