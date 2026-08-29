---
title: "이미지 파이프라인"
status: "proposed"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "미디어 형식·저장소 변경 시"
---

# 이미지 파이프라인

## 목표

- 운영자는 휴대전화 원본을 업로드할 수 있다.
- 공개 사이트는 작은 최적화 파일만 제공한다.
- 위치정보 등 원본 메타데이터가 공개되지 않는다.
- 이미지 크기 때문에 LCP와 데이터 사용량이 악화되지 않는다.
- 관리 backend 장애와 무관하게 이미지를 제공한다.

## 흐름

```text
JPEG / PNG / HEIC / HEIF upload
→ backend private temp
→ 실제 signature/container + decoder + 크기/pixel 검증
→ JPEG/PNG: 검증 원본 byte
→ HEIC/HEIF: orientation + sRGB + metadata-free quality 92 JPEG
→ backend private canonical master                         [implemented]
→ 빌더 인증 다운로드                                      [planned]
→ JPEG/PNG source metadata 제거·재검증                     [planned]
→ crop/focal point 적용
→ responsive variants
→ content-hashed filename
→ public/generated
→ static export
```

현재 완료 범위는 upload에서 private canonical master, 갤러리 relation과 매장정보 Hero·프로필·OG scalar relation까지다. 공개 파생본, Builder credential/API와 Static Export 반영은 완료로 보지 않는다.

## 저장 정책

### private canonical master

- backend 소유 filesystem storage와 PostgreSQL metadata 분리
- 공개 Nginx root 밖
- 백업 대상
- 현재는 session 인증 ADMIN만 API 조회, 후속 Builder는 별도 read-only credential
- 수정 시 새 파일 생성 권장
- JPEG·PNG는 검증한 source byte를 유지하므로 private master에 source metadata가 남을 수 있음
- HEIC·HEIF raw byte는 temp에서만 존재하고 canonical JPEG만 장기 보관
- `active | archived` 모두 row와 master를 유지하며 physical delete 없음
- 갤러리와 매장정보 Hero·프로필·OG relation은 private `media_assets` UUID만 저장하며 storage key·path·hash를 embed하지 않음
- 매장정보 relation 대상의 후속 archive는 cascade하지 않으며 public build가 active status와 file을 다시 검증

### 공개 파생본

- 정적 release 내부
- 재생성 가능
- 백업 필수 아님
- 원본 파일명 미사용
- content hash로 cache busting
- 오래된 release 정리 시 함께 삭제

## 초기 파생 규격

구현 중 실제 디자인에 맞춰 조정한다.

| 용도 | 폭 후보 | 형식 |
|---|---|---|
| 갤러리 카드 | 360, 640, 960 | AVIF, WebP, JPEG fallback |
| 갤러리 상세 | 768, 1200, 1600 | AVIF, WebP, JPEG fallback |
| Hero | 768, 1280, 1920 | AVIF, WebP, JPEG fallback |
| OG | 1200×630 | JPEG 또는 PNG |

- 원본보다 큰 이미지를 업스케일하지 않는다.
- 이미지마다 실제 `width`와 `height`를 manifest에 기록한다.
- `<picture>`와 `srcset`을 사용한다.
- Hero LCP 후보는 preload 여부를 검토한다.
- 아래쪽 이미지는 `loading="lazy"`를 사용한다.

## 처리 도구

### 현재 upload normalization

- Java ImageIO adapter: NightMonkeys `imageio-heif 1.1.0`
- native decoder/color transform: Alpine `libheif 1.23.0-r0`
- Java 25 FFM native access를 backend image·test·bootRun에 고정
- Linux amd64 Hosted CI와 Linux arm64 container에서 같은 fixture를 실제 decode
- runtime download·client filename 기반 shell command 없음
- codec 누락·link 실패는 backend startup에서 fail-fast

### 후속 public derivative

- Node.js 기반 `sharp` 또는 동등한 검증된 도구
- 버전·codec 고정, 실패를 무시하지 않음

## HEIC 출시 게이트

합성 HEIC의 backend 업로드·orientation·sRGB·metadata 제거는 amd64 CI와 arm64 container에서 자동 검증한다. 다만 은총쌤이 iPhone을 사용할 가능성이 높으므로 후속 `/admin` UI 통합에서 실제 iPhone Safari 원본 선택·전송을 반드시 시험한다.

- 실제 Safari multipart 업로드 성공 여부
- 후속 빌더 download·decode 여부
- orientation
- 색상 프로파일
- 변환 결과
- 실패 메시지

synthetic backend 검증은 physical-device와 Builder 검증을 대체하지 않는다. 둘 중 하나라도 검증되지 않으면 사진 기능을 출시하지 않는다.

## 개인정보

- GPS EXIF 제거
- 촬영기기 정보 제거
- 얼굴, 명찰, 차량번호, 집주소 등 배경 확인
- 원본 asset URL을 공개 HTML에 넣지 않음
- 원본 접근 토큰을 URL query에 포함하지 않음

## 파일 검증

- 실제 byte 기준 JPEG·PNG·HEIC·HEIF allowlist, 구체적 MIME·extension 충돌 거부
- ISO BMFF brand는 HEIC still `heic | heix | heim | heis`, HEIC sequence `hevc | hevx | hevm | hevs`, generic HEIF sequence `msf1`, AVIF `avif | avis`로 분리한다. HEIC still은 major와 compatible brand 모두에서 인식하고, AVIF·sequence 거부를 먼저 적용한 뒤 나머지 major `mif1` 구조만 generic HEIF로 분류한다.
- source 최대 20 MiB, stored 최대 30 MiB
- width·height 각각 최대 12,000px, total 최대 60MP
- GIF·WebP·AVIF·SVG는 `415 MEDIA_TYPE_UNSUPPORTED`, APNG·multi-image/sequence HEIF는 `422 MEDIA_INVALID_IMAGE`로 거부
- 손상·truncated·decode 불가 source와 canonical output 재검증
- 실패 시 temp/final/DB orphan cleanup

## 대체텍스트

- 갤러리는 `altText`, 매장정보 Hero·프로필은 각각 소유한 `heroImageAltText`·`groomerImageAltText`를 사용
- OG image relation에는 HTML alt field를 두지 않음
- 비어 있는 경우 공개 빌드 실패
- 파일명이나 키워드 목록으로 자동 대체하지 않음
- 장식적 파생본은 빈 alt 가능하나 같은 이미지가 정보 역할이면 명시적 alt 사용
