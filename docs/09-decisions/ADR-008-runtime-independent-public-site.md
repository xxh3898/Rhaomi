---
title: "ADR-008: 공개 사이트의 관리 backend 런타임 독립"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-31"
review_trigger: "공개 API 연동 제안 시"
---

# ADR-008: 공개 사이트의 관리 backend 런타임 독립

- 결정일: 2026-08-28
- 상태: Accepted
- 개정일: 2026-08-31 — Issue #43 generated V2 Static Export·release foundation 반영

## 맥락

Spring Boot와 PostgreSQL은 관리자 기능에 필요하지만, 이들이 중단되어도 영업 사이트의 전화·지도·사진·공지가 계속 보여야 한다.

## 결정

공개 release는 다음을 자체 포함한다.

- HTML
- CSS/JS
- 콘텐츠 snapshot에서 생성한 markup
- 공개 이미지 파생본
- robots
- sitemap
- JSON-LD
- Open Graph asset

고객 브라우저는 Spring Boot와 PostgreSQL에 연결하지 않는다.

Issue #43은 generated V2 content/media만 읽는 홈·공지 상세, sitemap·robots·JSON-LD·responsive media Static Export와 strict final-tree validator를 구현했다. release build 중에만 internal Build API를 사용하며 완성된 `site/`에는 backend URL·credential·private path가 없어야 한다. local/CI release foundation은 이 독립성을 자동 검증하지만 실제 production backend/DB 중단 smoke는 운영 provisioning 뒤 별도 gate다.

## 검증 조건

운영 release 배포 후 관리 backend와 PostgreSQL을 중지한 상태에서 다음이 동작해야 한다.

- 홈
- 갤러리 필터
- 사진 상세
- 서비스
- 공지 목록·상세
- 지도·전화·SNS 링크
- robots와 sitemap

## 결과

### 장점

- 공개 가용성
- 공격 표면 감소
- 관리 backend maintenance 중 영업 지속
- 정적 release 단위 rollback
- 관리 API URL 내부 구조와 credential 비노출

### 비용

- 관리 API 저장이 즉시 공개되지 않음
- 실시간 정보 부적합
- 파생본과 snapshot 관리
- 별도 build/deploy 인프라

## 예외

향후 외부 분석이나 비핵심 기능이 runtime 요청을 할 수는 있다. 단, 해당 요청 실패가 핵심 정보와 CTA를 막으면 안 된다.

## 재검토 조건

- 실시간 예약 기능이 핵심 제품으로 승인
- 공개 데이터가 분 단위로 바뀌어 정적 build가 부적합
- 별도 고가용 API 계층이 도입
