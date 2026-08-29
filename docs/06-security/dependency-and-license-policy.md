---
title: "의존성·라이선스 정책"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "주요 의존성·라이선스 변경 시"
---

# 의존성·라이선스 정책

## 현재 핵심 의존성

- Java 25 LTS
- Spring Boot `4.1.1`
- Gradle Wrapper `9.7.1`
- PostgreSQL `18.6-alpine3.23`
- NightMonkeys `imageio-heif 1.1.0` — MIT, Java 22+ FFM ImageIO adapter
- Alpine `libheif 1.23.0-r0` — LGPL-3.0-or-later, amd64·arm64 native HEIC/HEIF decode/color transform
- Next.js와 frontend dependency는 `package-lock.json` 기준
- Docker image는 검증한 exact tag를 사용하고 운영에서는 가능하면 digest까지 고정

Spring Boot 버전은 구현 시점의 Spring 공식 stable과 system requirements를 확인해 선택했다. major/minor 변경은 별도 Issue에서 Java·Gradle·plugin 호환성과 보안 변경을 함께 검토한다.

## 현재 개발 HEIC runtime 인벤토리

backend Dockerfile은 exact Temurin 25 manifest와 Alpine 3.23의 `libheif=1.23.0-r0`, `libheif-dev=1.23.0-r0`를 고정한다. application runtime에서 codec이나 모델을 다운로드하지 않으며 client 문자열로 외부 process를 실행하지 않는다.

Alpine package가 동적 link하는 현재 runtime package와 Alpine metadata의 license는 다음과 같다.

| package | version | license | 현재 용도 |
|---|---|---|---|
| NightMonkeys `imageio-heif` | `1.1.0` | MIT | Java ImageIO adapter |
| `libheif` | `1.23.0-r0` | LGPL-3.0-or-later | HEIF container·decode·color transform |
| `libde265` | `1.0.16-r0` | LGPL-3.0-or-later | HEVC decode |
| `x265-libs` | `4.1-r0` | GPL-2.0-or-later | Alpine `libheif`의 linked HEVC encoder dependency, application 미사용 |
| `aom-libs` | `3.14.1-r0` | BSD-2-Clause AND custom | Alpine `libheif` transitive codec library |
| `libsharpyuv` | `1.6.0-r0` | BSD-3-Clause | color conversion dependency |

현재 workflow는 위 image를 test용으로 build할 뿐 registry에 publish하지 않는다. 개발 package의 x265 encoder는 application에서 사용하지 않는다.

## Production HEIC runtime target — planned

[ADR-014](../09-decisions/ADR-014-heic-decoder-only-production-runtime.md)에 따라 production은 Alpine `libheif` package를 그대로 복사하지 않고 decoder-only source build를 사용한다.

| 구성 | production 계약 |
|---|---|
| libheif | official `v1.23.1`, commit `2c4bbb54c2738d4a5efbbe3e5fa1d5d76bb88eb0` |
| HEIC decoder | pinned libde265 활성 |
| x265·HEIC encoder | 비활성, final image package·link·plugin 부재 |
| 다른 encoder·CLI·example·dev tool | 비활성 또는 runtime에서 제거 |
| plugin loading·experimental feature | 비활성 |
| Java adapter | NightMonkeys `imageio-heif 1.1.0` 유지 |
| 검증 | Linux amd64 CI, Mac mini Linux arm64 actual HEIC fixture |

production image build·publish는 아직 구현되지 않았다. 구현 시 source·license notice, LGPL 의무 검토, SBOM, vulnerability scan, CMake configure summary와 final image의 x265 absence를 release evidence에 포함한다. encoder 제거는 남은 native dependency의 license 검토를 생략하는 근거가 아니다.

## Directus 결정 기록

Directus 12.3.1 Core의 custom permission entitlement가 라오미펫의 item filter, field allowlist, file folder filter 계약을 차단해 [ADR-009](../09-decisions/ADR-009-spring-boot-backend-admin.md)에서 자체 Spring Boot backend로 대체했다.

- Directus runtime·SDK·Docker image·Data Studio·license key는 현재 dependency가 아니다.
- 과거 선정 근거와 공식 링크는 superseded ADR에 역사로 보존한다.
- Directus Core/OIG 자격 검토는 현 release gate가 아니다.

## 버전 정책

- `latest` tag 금지
- Gradle Wrapper와 lockfile 커밋
- runtime image·tool version 명시
- major upgrade는 별도 Issue와 ADR 검토
- 자동 dependency PR은 허용하지만 자동 merge·운영 배포 금지
- Spring Security와 image decoder처럼 인터넷 입력에 닿는 dependency를 우선 갱신

## 라이선스 인벤토리

출시 전에 생성한다.

- production dependencies
- development/test dependencies
- Docker images
- fonts, icons, images
- third-party code snippets

허용 여부가 불명확한 자산은 사용하지 않는다.

## 취약점

- npm/Gradle dependency audit 또는 동등한 scanner
- container image scan
- GitHub Dependabot 등 알림
- 심각도뿐 아니라 실제 노출 경로 분석
- 관리자 인증·Spring Security 취약점 우선 처리
- decoder·image processing 취약점은 향후 upload 공격면 도입 전에 검토
- pinned libheif·libde265 source는 production image build마다 upstream security advisory와 더 최신 stable release를 확인하고 version 변경은 별도 검증·문서 동기화

## 공식 확인 기준

- Spring Boot: `https://spring.io/projects/spring-boot/`
- Spring Boot system requirements: `https://docs.spring.io/spring-boot/system-requirements.html`
- Gradle releases: `https://gradle.org/releases/`
- Java/Gradle compatibility: `https://docs.gradle.org/current/userguide/compatibility.html`
- PostgreSQL: `https://www.postgresql.org/docs/`
- libheif: `https://github.com/strukturag/libheif/`
- libheif v1.23.1: `https://github.com/strukturag/libheif/releases/tag/v1.23.1`
- libde265: `https://github.com/strukturag/libde265/`

## 폰트·아이콘·사진

- 저장소에 폰트 파일을 임의 포함하지 않는다.
- 웹 배포·attribution 조건을 확인한다.
- 실제 시술사진의 게시 권한을 운영자가 확인한다.
- 검색 결과나 다른 SNS 이미지를 복사하지 않는다.
