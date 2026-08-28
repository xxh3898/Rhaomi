---
title: "ADR-005: Directus 관리자 UI 우선"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "관리자 UX 변경 요구 발생 시"
---

# ADR-005: Directus 관리자 UI 우선

- 결정일: 2026-08-28
- 상태: Accepted

## 맥락

운영자는 모바일에서 콘텐츠를 관리해야 하지만, 전용 관리자 화면을 직접 만들면 인증, CRUD, 업로드, validation, 권한, 오류 처리 비용이 증가한다.

## 결정

1차 운영은 Directus Data Studio를 사용한다. 라오미펫 전용 `/manage` UI는 실제 사용성 문제가 측정된 뒤에만 개발한다.

## 필요한 설정

- 한글 collection/field label
- 불필요한 module 숨김
- 운영자 최소 권한
- field 순서와 도움말
- default draft
- publish validation
- 업로드 폴더
- presets
- 2FA
- 실제 iPhone 테스트

## 결과

### 장점

- 출시 범위 감소
- 검증된 CRUD·파일 UI 활용
- 스키마 변경과 UI 자동 연계
- 관리자 기능보다 고객 디자인에 집중

### 비용

- 브랜드 전용 UI가 아님
- 모바일 사용성이 완벽하지 않을 수 있음
- Directus 개념 교육 필요
- 공개 반영 상태를 별도 확인해야 함

## 전용 UI 전환 기준

- 반복 작업에 필요한 탭 수가 과도함
- 사진 업로드 실패가 잦음
- 운영자가 시스템 필드로 혼란
- 게시 오류율이 높음
- Directus 업데이트가 업무를 자주 깨뜨림
- 다중 운영자 승인 절차 필요

## 재검토 방법

운영 첫 1~3개월의 실제 문제를 수집하고, 전용 UI 개발 비용과 절감 시간을 비교한다.
