---
title: "ADR-002: Directus + PostgreSQL"
status: "superseded"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "CMS·DB 변경 또는 라이선스 영향 발생 시"
---

# ADR-002: Directus + PostgreSQL

- 결정일: 2026-08-28
- 상태: Superseded by [ADR-009](ADR-009-spring-boot-backend-admin.md)

> 이 결정은 2026-08-29에 ADR-009로 대체됐다. 아래 내용은 Directus를 채택했던 역사적 근거이며 현재 구현 기준이 아니다.

## 맥락

은총쌤이 개발자 도움 없이 사진, 공지, 견종, 서비스, 매장정보를 관리해야 한다. 관리자 인증, 파일 업로드, 관계형 데이터, CRUD, 권한, 변경 이력, Flow가 필요하다.

## 결정

- CMS/API: self-hosted Directus
- Database: PostgreSQL
- 관리자 UI: Directus Data Studio
- 파일 원본: Directus local persistent storage
- 공개 사이트: Directus 데이터를 빌드 시 읽음

## 이유

Directus가 제공하는 데이터 모델, Data Studio, 파일, 접근제어, API, Flow를 활용하면 영업 사이트와 무관한 범용 관리자 UI를 직접 개발하는 비용을 줄일 수 있다. PostgreSQL은 구조화된 관계와 백업·이전 가능성을 제공한다.

## 데이터 모델

- `shop_settings`
- `services`
- `breeds`
- `gallery_items`
- `notices`
- Directus system collections

## 결과

### 장점

- 관리자 CRUD를 직접 구현하지 않음
- 관계형 견종·서비스 데이터
- 운영자와 시스템 관리자 권한 분리
- 파일 관리와 변경 이력
- Flow를 통한 정적 빌드 요청
- DB와 원본을 자체 보유

### 비용과 위험

- Directus 운영·업그레이드 필요
- 관리자 UI가 라오미펫 전용 UX는 아님
- 라이선스와 tier 조건을 지속 확인해야 함
- Directus와 PostgreSQL 백업이 필요
- 관리자 서비스 인터넷 노출 보안 필요

## 라이선스 게이트

Directus의 2026-08-28 공식 라이선스 문서는 Core tier와 추가 라이선스 체계를 설명한다. 라오미펫의 실제 적용 조건은 확인되지 않았으므로 운영 배포 전에 최신 공식 조건과 사용량을 검토한다.

## 거부한 대안

### PocketBase

초기 단순성은 높지만 PostgreSQL 사용 요구와 장기 데이터 표준화 방향에 맞지 않는다.

### Spring Boot 관리자 API 직접 개발

개발자 포트폴리오 가치는 있으나 현재 영업 목적 대비 인증, 파일, CRUD, 관리자 화면에 과도한 비용이 든다.

### CMS 없는 코드 관리

운영자가 직접 관리할 수 없고 변경마다 개발자 배포가 필요하다.

## 재검토 조건

- Directus 라이선스가 비용·제약상 부적합
- Data Studio 모바일 UX가 실제 운영을 방해
- 보안 또는 유지보수 비용이 자체 관리자 UI보다 커짐
- 다중 매장·예약 도메인으로 확장
