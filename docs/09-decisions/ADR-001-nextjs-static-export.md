---
title: "ADR-001: Next.js Static Export"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "렌더링·호스팅 방식 변경 제안 시"
---

# ADR-001: Next.js Static Export

- 결정일: 2026-08-28
- 상태: Accepted

## 맥락

라오미펫은 공개 정보와 사진 중심의 소규모 영업 사이트다. 검색엔진 접근성, 모바일 성능, Mac mini 운영 단순성, 관리 backend 장애 격리가 중요하다. 요청별 개인화나 서버 렌더링은 필요하지 않다.

## 결정

공개 프론트엔드는 Next.js App Router와 TypeScript를 사용하고 `output: 'export'`로 정적 산출물을 만든다.

- SSR 사용 금지
- Server Actions 사용 금지
- Next API Routes 사용 금지
- 공개 콘텐츠는 빌드 시 HTML로 생성
- 정적 Nginx 호스팅
- 동적 route는 빌드 시 params 생성
- 핵심 콘텐츠의 client-only fetch 금지

## 이유

- 완성된 HTML을 검색엔진과 사용자에게 제공
- 공개 요청마다 애플리케이션 서버가 필요하지 않음
- Nginx와 파일만으로 서비스 가능
- 관리 backend/DB 장애와 고객 사이트 분리
- 캐시와 rollback 단순화
- 공격 표면 감소

## 결과

### 장점

- 빠른 정적 제공
- 낮은 런타임 복잡도
- 마지막 정상 release 유지
- 검색 메타데이터를 빌드에서 검증 가능
- 저비용 Mac mini 운영

### 비용

- 콘텐츠 변경마다 build 필요
- 요청 시점의 실시간 데이터에 부적합
- dynamic route는 build 전에 목록을 알아야 함
- 이미지 최적화를 별도로 처리해야 함
- build pipeline이 제품의 핵심 운영 기능이 됨

## 거부한 대안

### CSR-only React

핵심 콘텐츠가 JavaScript 실행 뒤에 나타나는 구조는 검색 수집 안정성과 초기 경험에 불필요한 의존성을 만든다.

### Next.js SSR

이 사이트에 요청별 서버 렌더링 가치가 낮고 공개 가용성을 Node server와 관리 backend에 결합한다.

### 순수 HTML 수작업

콘텐츠 관리와 정적 route 생성, TypeScript 구성요소 재사용, SEO 자동 검증이 불편하다.

## 재검토 조건

- 실시간 예약·재고 등 요청 시점 데이터가 핵심이 됨
- 페이지 수와 빌드 시간이 운영 불가능한 수준으로 증가
- 다중 매장과 개인화가 제품 핵심으로 확정
- 정적 배포 파이프라인이 콘텐츠 운영을 지속적으로 방해
