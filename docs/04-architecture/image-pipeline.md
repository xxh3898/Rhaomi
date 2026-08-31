---
title: "이미지 파이프라인"
status: "proposed"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-31"
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
→ internal build API + authenticated Node HTTP provider  [implemented staging data plane]
→ MediaContentProvider 입력·JPEG/PNG metadata 제거·재검증 [implemented]
→ no-upscale responsive variants + content-hashed filename [implemented]
→ atomic staging의 src/generated + public/generated       [implemented]
→ manifest 기반 <picture>·Next static render·release       [implemented]
→ 최종 crop/focal point·visual profile polish              [planned]
```

현재 완료 범위는 upload에서 private canonical master, 갤러리 relation과 매장정보 Hero·프로필·OG scalar relation, internal build API, authenticated memory-only HTTP media provider, transport-independent transformer, manifest 기반 responsive `<picture>`, Next Static Export와 immutable release/switch의 실제 publisher binding까지다. 최종 crop/focal-point 정책, 디자인별 profile 조정과 production image/path provisioning은 완료로 보지 않는다.

## 저장 정책

### private canonical master

- backend 소유 filesystem storage와 PostgreSQL metadata 분리
- 공개 Nginx root 밖
- 백업 대상
- session 인증 ADMIN과 별도로 Node staging adapter는 public relation scope에 한정된 read-only build credential 사용
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

Gallery·Hero profile은 Phase 1C-8f4의 구현 계약이다. source가 후보 폭보다 작으면 실제 source 폭만 사용하고 업스케일하지 않는다.

| 용도 | 폭 후보 | 형식 |
|---|---|---|
| 갤러리 카드 | 360, 640, 960 | AVIF, WebP, JPEG fallback |
| 갤러리 상세 | 768, 1200, 1600 | AVIF, WebP, JPEG fallback |
| Hero | 768, 1280, 1920 | AVIF, WebP, JPEG fallback |
| 미용사·OG 임시 fallback | 최대 1200 | JPEG |

- 원본보다 큰 이미지를 업스케일하지 않는다.
- 이미지마다 실제 `width`와 `height`를 manifest에 기록한다.
- 같은 source byte와 encode 결과는 output SHA-256으로 file을 deduplicate한다.
- Hero·Gallery·OG는 manifest `publicPath`만 사용한 정적 `<picture>`/`srcset`에 연결한다.
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

### Production upload normalization runtime — planned

- [ADR-014](../09-decisions/ADR-014-heic-decoder-only-production-runtime.md)의 official libheif `v1.23.1`, exact commit `2c4bbb54c2738d4a5efbbe3e5fa1d5d76bb88eb0`
- libde265 HEVC decoder만 native codec allowlist로 활성화
- x265·다른 encoder·사용하지 않는 decoder·CLI·plugin·experimental feature 비활성
- NightMonkeys, JPEG/PNG passthrough, JPEG quality 92, orientation·sRGB·metadata strip과 크기 제한 유지
- SBOM·source/license notice와 final image의 x265 package·link·plugin 부재 검사

production decoder-only image는 아직 구현되지 않았다. 현재 local/CI Alpine package runtime과 future production source build를 같은 상태로 표현하지 않는다.

### 현재 public derivative transformer

- Node.js 24와 exact `sharp 0.35.4`
- Gallery card `360/640/960`, large `768/1200/1600`, Hero `768/1280/1920`
- AVIF quality 50/effort 4, WebP quality 80/effort 4, progressive JPEG quality 82
- orientation 적용, sRGB 변환, metadata 제거, alpha JPEG는 white background로 flatten
- JPEG·PNG actual signature·decode·30 MiB·12,000px·60MP·single-image 재검증과 output decode·format·metadata 재검증
- output byte SHA-256 filename과 결정적 media manifest
- format·decode·transform·write 실패를 silent skip하지 않고 typed build 오류로 종료

실제 page crop/focal point와 미용사·OG 최종 profile은 후속 디자인 Issue에서 확정한다. 현재 구현된 `<picture>` binding과 최대 1200 JPEG fallback을 최종 OG 1200×630 crop 계약으로 해석하지 않는다.

## HEIC 출시 게이트

합성 HEIC의 backend 업로드·orientation·sRGB·metadata 제거는 amd64 CI와 arm64 container에서 자동 검증한다. 다만 은총쌤이 iPhone을 사용할 가능성이 높으므로 후속 `/admin` UI 통합에서 실제 iPhone Safari 원본 선택·전송을 반드시 시험한다.

- 실제 Safari multipart 업로드 성공 여부
- implemented full publisher의 download·decode·responsive derivative·release 반영
- orientation
- 색상 프로파일
- 변환 결과
- 실패 메시지

synthetic backend 검증은 physical-device와 publisher 검증을 대체하지 않는다. 둘 중 하나라도 검증되지 않으면 사진 기능을 출시하지 않는다.

## 개인정보

- GPS EXIF 제거
- 촬영기기 정보 제거
- 얼굴, 명찰, 차량번호, 집주소 등 배경 확인
- 원본 asset URL을 공개 HTML에 넣지 않음
- 원본 접근 토큰을 URL query에 포함하지 않음

## 파일 검증

- upload는 실제 byte 기준 JPEG·PNG·HEIC·HEIF allowlist와 구체적 MIME·extension 충돌을 거부한다. public transformer 입력은 canonical master 계약에 따라 JPEG·PNG만 허용한다.
- ISO BMFF brand는 HEIC still `heic | heix | heim | heis`, HEIC sequence `hevc | hevx | hevm | hevs`, generic HEIF sequence `msf1`, AVIF `avif | avis`로 분리한다. HEIC still은 major와 compatible brand 모두에서 인식하고, AVIF·sequence 거부를 먼저 적용한 뒤 나머지 major `mif1` 구조만 generic HEIF로 분류한다.
- source 최대 20 MiB, stored 최대 30 MiB
- width·height 각각 최대 12,000px, total 최대 60MP
- upload에서 GIF·WebP·AVIF·SVG는 `415 MEDIA_TYPE_UNSUPPORTED`, APNG·multi-image/sequence HEIF는 `422 MEDIA_INVALID_IMAGE`로 거부한다. transformer도 APNG·multi-page와 manifest content type/signature mismatch를 `MEDIA_INVALID`로 거부한다.
- 손상·truncated·decode 불가 source와 canonical output 재검증
- 실패 시 temp/final/DB orphan cleanup

## 대체텍스트

- 갤러리는 `altText`, 매장정보 Hero·프로필은 각각 소유한 `heroImageAltText`·`groomerImageAltText`를 사용
- OG image relation에는 HTML alt field를 두지 않음
- 비어 있는 경우 공개 빌드 실패
- 파일명이나 키워드 목록으로 자동 대체하지 않음
- 장식적 파생본은 빈 alt 가능하나 같은 이미지가 정보 역할이면 명시적 alt 사용
