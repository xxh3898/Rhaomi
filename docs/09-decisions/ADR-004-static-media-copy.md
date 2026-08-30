---
title: "ADR-004: 원본 미디어와 정적 파생본 분리"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
review_trigger: "이미지 저장·제공 방식 변경 시"
---

# ADR-004: 원본 미디어와 정적 파생본 분리

- 결정일: 2026-08-28
- 개정일: 2026-08-29 — Issue #11 private upload/master 구간 구현 반영
- 개정일: 2026-08-30 — Issue #37 public derivative transformer·staging 구간 구현 반영
- 상태: Accepted

## 맥락

휴대전화 원본은 크고 EXIF·GPS를 포함할 수 있다. Static Export에서는 런타임 Next Image Optimization을 전제로 할 수 없다. 관리 backend의 원본 endpoint에 공개 사이트를 의존시키면 backend 장애가 이미지 장애가 된다.

## 결정

- backend가 private filesystem master와 PostgreSQL metadata를 소유하고 공개 Nginx root와 분리
- JPEG·PNG upload는 actual-byte·decoder·size/pixel 검증 뒤 source byte를 private canonical master로 보존
- HEIC·HEIF upload는 client 변환에 의존하지 않고 backend에서 orientation 적용·sRGB 변환·metadata 제거 후 quality 92 JPEG master로 정규화
- HEIC·HEIF raw source는 temp에서만 사용하고 장기 보관하지 않음
- server-owned UUID storage key와 SHA-256 무결성 metadata를 사용하고 filename/path를 client가 지정하지 못하게 함
- `active | archived`는 row와 master를 유지하고 physical delete는 후속 retention/backup gate로 분리
- [ADR-011](ADR-011-transactional-outbox-static-publisher.md)의 publisher가 별도 read-only credential의 인증된 내부 접근으로 canonical master를 다운로드
- 공개 파생 단계에서 JPEG·PNG source metadata 제거, crop, resize와 format 변환 수행
- content hash 파일명
- 공개 release에 responsive variants 포함
- 공개 HTML은 backend 원본 asset URL을 사용하지 않음

Issue #11에서 upload→private canonical master를 구현했고 Gallery·매장정보 relation과 internal build API가 후속 Issue에서 구현됐다. Issue #37은 API transport와 분리된 `MediaContentProvider` 기반으로 JPEG·PNG source를 다시 검증하고 orientation·sRGB·metadata 제거, no-upscale AVIF·WebP·JPEG responsive derivative, output-byte SHA-256 filename과 atomic staging을 구현했다. publisher의 authenticated HTTP download, Next Static Export 연결과 release/current switch는 planned 상태를 유지한다.

## 결과

### 장점

- 원본 개인정보 보호
- iPhone HEIC를 browser 변환 없이 안정적인 JPEG master로 정규화
- DB rollback과 filesystem orphan cleanup 경계 명확화
- 관리 backend 장애와 공개 이미지 분리
- 정적 캐시
- 예측 가능한 크기
- 공개 사이트 전체 rollback 가능

### 비용

- build 시간과 디스크 사용 증가
- image codec 유지보수
- private master volume의 backup·retention 필요
- 원본과 파생본 manifest 필요
- amd64·arm64 codec 호환성과 실제 iPhone Safari 검증 필요

## 거부한 대안

### backend 원본 asset URL 직접 제공

간단하지만 공개 사이트가 backend 가용성·권한·URL에 의존한다.

### 원본 그대로 public 업로드

성능과 개인정보 위험이 크다.

### DB bytea 저장

파일 관리와 백업 효율이 낮고 필요성이 없다.

## 재검토 조건

- 정적 release 미디어 용량이 과도해짐
- Object Storage/CDN 도입
- 이미지 변경 빈도와 build 비용 증가
- 외부 asset provider로 이전
