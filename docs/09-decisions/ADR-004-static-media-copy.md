---
title: "ADR-004: 원본 미디어와 정적 파생본 분리"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "이미지 저장·제공 방식 변경 시"
---

# ADR-004: 원본 미디어와 정적 파생본 분리

- 결정일: 2026-08-28
- 상태: Accepted

## 맥락

휴대전화 원본은 크고 EXIF·GPS를 포함할 수 있다. Static Export에서는 런타임 Next Image Optimization을 전제로 할 수 없다. Directus asset endpoint에 공개 사이트를 의존시키면 CMS 장애가 이미지 장애가 된다.

## 결정

- 원본은 Directus uploads에 비공개 보관
- Builder가 인증된 내부 접근으로 원본을 다운로드
- orientation, crop, resize, format, metadata strip
- content hash 파일명
- 공개 release에 responsive variants 포함
- 공개 HTML은 Directus asset URL을 사용하지 않음

## 결과

### 장점

- 원본 개인정보 보호
- CMS 장애와 공개 이미지 분리
- 정적 캐시
- 예측 가능한 크기
- 공개 사이트 전체 rollback 가능

### 비용

- build 시간과 디스크 사용 증가
- image codec 유지보수
- 원본과 파생본 manifest 필요
- HEIC 등 형식 호환성 검증 필요

## 거부한 대안

### Directus asset URL 직접 제공

간단하지만 공개 사이트가 CMS 가용성·권한·URL에 의존한다.

### 원본 그대로 public 업로드

성능과 개인정보 위험이 크다.

### DB bytea 저장

파일 관리와 백업 효율이 낮고 필요성이 없다.

## 재검토 조건

- 정적 release 미디어 용량이 과도해짐
- Object Storage/CDN 도입
- 이미지 변경 빈도와 build 비용 증가
- Directus 외부 asset provider로 이전
