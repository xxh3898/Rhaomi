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

- Java 21 LTS
- Spring Boot `4.1.1`
- Gradle Wrapper `9.7.1`
- PostgreSQL `18.6-alpine3.23`
- Next.js와 frontend dependency는 `package-lock.json` 기준
- Docker image는 검증한 exact tag를 사용하고 운영에서는 가능하면 digest까지 고정

Spring Boot 버전은 구현 시점의 Spring 공식 stable과 system requirements를 확인해 선택했다. major/minor 변경은 별도 Issue에서 Java·Gradle·plugin 호환성과 보안 변경을 함께 검토한다.

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

## 공식 확인 기준

- Spring Boot: `https://spring.io/projects/spring-boot/`
- Spring Boot system requirements: `https://docs.spring.io/spring-boot/system-requirements.html`
- Gradle releases: `https://gradle.org/releases/`
- Java/Gradle compatibility: `https://docs.gradle.org/current/userguide/compatibility.html`
- PostgreSQL: `https://www.postgresql.org/docs/`

## 폰트·아이콘·사진

- 저장소에 폰트 파일을 임의 포함하지 않는다.
- 웹 배포·attribution 조건을 확인한다.
- 실제 시술사진의 게시 권한을 운영자가 확인한다.
- 검색 결과나 다른 SNS 이미지를 복사하지 않는다.
