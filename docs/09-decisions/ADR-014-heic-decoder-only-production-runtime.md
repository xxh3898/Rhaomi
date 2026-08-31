---
title: "ADR-014: HEIC decoder-only production runtime"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-31"
review_trigger: "HEIC codec·native image·라이선스·지원 형식 변경 시"
---

# ADR-014: HEIC decoder-only production runtime

- 결정일: 2026-08-29
- 상태: Accepted
- 관련 결정: [ADR-004](ADR-004-static-media-copy.md), [ADR-010](ADR-010-production-topology-and-code-release.md)

## 맥락

현재 개발 backend image는 Alpine `libheif 1.23.0-r0` package를 사용한다. 이 package는 Rhaomi가 사용하지 않는 x265 HEVC encoder dependency를 포함한다. Rhaomi server는 HEIC/HEIF를 decode해 JPEG로 정규화할 뿐 HEIC를 encode하지 않으므로 production image에 encoder·CLI와 그 라이선스 경계를 그대로 포함할 이유가 없다.

공식 libheif는 codec별 build option을 제공하고 HEIC decode의 기본 backend로 libde265, HEIC encode에 x265를 사용한다. production source target은 공식 stable `v1.23.1`, exact commit `2c4bbb54c2738d4a5efbbe3e5fa1d5d76bb88eb0`으로 고정한다. 현재 개발 Dockerfile과 package는 이 문서 Issue에서 변경하지 않는다.

## 결정

### production native build

- libheif source는 `v1.23.1`과 exact commit `2c4bbb54c2738d4a5efbbe3e5fa1d5d76bb88eb0`을 둘 다 검증한 뒤 build한다.
- checksum 또는 source provenance를 release evidence에 기록한다.
- `libde265` HEVC decoder만 native codec allowlist로 활성화하고 사용하지 않는 AV1·VVC·AVC·JPEG·J2K codec backend를 비활성화한다.
- x265와 모든 HEIC encoder를 비활성화하고 x265 package·library·plugin을 final image에 포함하지 않는다.
- 사용하지 않는 다른 encoder, example·CLI, development tool, documentation, test와 fuzz binary를 final runtime에서 제거한다.
- dynamic plugin loading과 experimental codec·API를 비활성화한다.
- application runtime에서 codec·plugin·binary를 다운로드하지 않는다.
- NightMonkeys `imageio-heif 1.1.0` FFM ImageIO adapter와 기존 server-side JPEG normalization 계약을 유지한다.
- JPEG/PNG passthrough, HEIC/HEIF → JPEG quality 92, orientation, sRGB, metadata strip, source 20 MiB·stored 30 MiB·12,000px·60MP 제한을 변경하지 않는다.

구현 시 CMake configure summary와 final image를 검사해 최소한 다음 계약을 증명한다.

```text
WITH_LIBDE265=ON
ENABLE_PLUGIN_LOADING=OFF
WITH_X265=OFF
WITH_KVAZAAR=OFF
WITH_UVG266=OFF
WITH_VVDEC=OFF
WITH_VVENC=OFF
WITH_X264=OFF
WITH_OpenH264_DECODER=OFF
WITH_OpenH264_ENCODER=OFF
WITH_DAV1D=OFF
WITH_AOM_DECODER=OFF
WITH_AOM_ENCODER=OFF
WITH_SvtEnc=OFF
WITH_RAV1E=OFF
WITH_JPEG_DECODER=OFF
WITH_JPEG_ENCODER=OFF
WITH_OpenJPEG_ENCODER=OFF
WITH_OpenJPEG_DECODER=OFF
WITH_FFMPEG_DECODER=OFF
WITH_OPENJPH_ENCODER=OFF
WITH_OPENJPH_DECODER=OFF
WITH_UNCOMPRESSED_CODEC=OFF
WITH_WEBCODECS=OFF
WITH_HEADER_COMPRESSION=OFF
WITH_EXAMPLES=OFF
WITH_EXAMPLE_HEIF_THUMB=OFF
WITH_EXAMPLE_HEIF_VIEW=OFF
WITH_GDK_PIXBUF=OFF
BUILD_DEVELOPMENT_TOOLS=OFF
BUILD_DOCUMENTATION=OFF
BUILD_TESTING=OFF
ENABLE_COVERAGE=OFF
WITH_FUZZERS=OFF
```

- 위 codec별 `WITH_<codec>_PLUGIN`도 모두 `OFF`로 전달하고 configure summary에서 plugin target이 0개인지 검사한다.
- production CMake preset은 위 allowlist 밖 codec·encoder·plugin·experimental option이 새로 생기거나 `ON`이면 configure 또는 image acceptance를 실패시킨다. upstream option rename·추가를 묵시적 default로 수용하지 않는다.
- final image에서 x265 package, `libx265` shared-library link와 codec plugin file이 없음을 각각 검사한다.

### 기능·아키텍처 검증

- Linux amd64 Hosted CI에서 actual HEIC fixture를 decode·normalize한다.
- Mac mini Linux arm64 smoke에서 같은 fixture의 orientation, sRGB와 metadata strip을 검증한다.
- supported HEIC still brand와 generic HEIF decode를 유지한다.
- HEIC sequence는 `422 MEDIA_INVALID_IMAGE`, AVIF는 `415 MEDIA_TYPE_UNSUPPORTED`인 기존 application 계약을 유지한다.
- final image에서 `x265` package 목록, shared-library dependency와 plugin file이 모두 없음을 검사한다.
- runtime에 compiler, source tree와 build cache를 남기지 않는다.

### 라이선스·공급망

- libheif, libde265, NightMonkeys와 포함된 transitive runtime의 source URL, exact version·commit, license와 notice를 기록한다.
- production image SBOM과 dependency/image scan을 release evidence에 포함한다.
- LGPL source·notice·재연결 등 실제 배포 의무는 release 전에 검토한다.
- encoder 제거를 license 검토 자체의 대체로 보지 않는다.

### D-IMP-1 구현 상태

`backend/Dockerfile.production`을 backend·dedicated publisher가 공유할 canonical production image source로 구현했다. 이 image는 exact Temurin Java 25 JRE와 Node `24.20.0` digest, libheif `v1.23.1` exact commit·archive SHA-256, Alpine libde265 `1.0.16-r0`을 고정한다. CMake source option inventory와 cache를 machine-check하고 libde265만 built-in codec으로 허용한다. final stage에는 application JAR, lockfile 기반 production Node dependency와 Next build에 필요한 type package, tracked publisher source/config만 allowlist로 복사하며 npm·compiler·Git·CMake·source tree·cache를 남기지 않는다.

`sh scripts/validate-production-image.sh`는 native architecture에서 다음을 하나의 fail-closed acceptance로 수행한다.

- Java·Node와 backend JAR·publisher Static Export runtime 확인
- x265 package, `libx265` linkage, codec plugin, encoder·example·build tool 부재 확인
- actual HEIC·generic HEIF의 JPEG orientation·sRGB·metadata strip과 sequence 422·AVIF 415 확인
- exact final image ID·Git HEAD를 포함한 CycloneDX SBOM과 pinned Syft·Grype evidence 생성
- tracked `production-image-components.json`의 source·version·commit/checksum·license·obligation 상태 확인

generated SBOM·scanner report는 PR/CI evidence이며 저장소에 stale artifact로 커밋하지 않는다. canonical image source와 validation gate 구현은 완료됐지만 GHCR publish, production Compose service argv/profile, Secret·Mac filesystem provisioning과 deploy는 D-IMP-2~3 범위다. 따라서 이 결정의 runtime 상태는 production `PASS`가 아니라 `PROVISIONING_REQUIRED`다.

## 이유

- server 기능에 필요한 decode capability만 남겨 native 공격면과 image 크기를 줄인다.
- 사용하지 않는 x265 encoder와 GPL dependency를 production artifact에서 제거한다.
- source commit, SBOM과 dual-architecture fixture는 package metadata만으로는 확인할 수 없는 runtime capability를 증명한다.

## 결과

### 장점

- 기존 HEIC upload UX와 server-side normalization을 유지한다.
- 사용하지 않는 encoder·CLI·plugin과 x265 dependency를 제거한다.
- amd64와 arm64에서 같은 production native runtime을 검증할 수 있다.

### 비용·위험

- source build, CVE tracking, multi-architecture cache와 license notice를 직접 유지해야 한다.
- libheif·libde265 upgrade마다 FFM adapter와 actual fixture 회귀 검증이 필요하다.
- 잘못된 build option은 backend startup 또는 HEIC upload 때만 드러날 수 있어 image-level assertion이 필수다.

## 거부한 대안

### 개발 Alpine `libheif` package를 production에 그대로 사용

불필요한 x265 encoder dependency와 넓은 codec surface를 포함하므로 거부한다.

### client-side HEIC 변환

browser·기기별 품질과 metadata 처리에 의존하고 현재 server-side 검증 계약을 약화하므로 거부한다.

### runtime codec download

재현성, 공급망과 무네트워크 운영 경계를 깨므로 거부한다.

## 실행 계획

- [x] pinned source를 사용하는 multi-stage production image 구현
- [x] libde265만 `ON`인 fail-closed CMake codec allowlist와 모든 encoder·plugin·experimental path `OFF` configure summary assertion
- [x] final image x265 package·link·plugin absence 검사
- [x] SBOM, source·license notice와 scanner evidence 생성
- [x] Linux amd64 Hosted CI actual HEIC gate 연결
- [x] Mac mini Linux arm64 HEIC smoke gate 구현

## 재검토 조건

- libheif·libde265 보안 update 또는 NightMonkeys 호환성 변경
- HEIC encode나 AVIF 지원이 제품 요구로 승인됨
- JDK FFM 또는 native packaging 방식 변경
- decoder-only build 유지 비용이 검증된 대안보다 커짐
