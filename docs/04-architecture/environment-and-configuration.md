---
title: "환경설정"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "환경변수·domain·version 변경 시"
---

# 환경설정

## 환경

```text
local
development
production
```

별도 staging을 장기간 운영하지 않더라도 local 또는 임시 환경에서 production과 같은 인증·DB 계약을 검증한다.

## Phase 1C-7 local 개발 계약

- Compose file: `compose.dev.yaml`
- Compose project: `dev-rhaomi`
- local 환경파일: Git 제외 `.env.dev.local`
- 공개 example: `.env.example`
- Node.js: `24.20.0-alpine3.23`
- Java: `eclipse-temurin:25.0.4_7-jdk-alpine-3.23`
- HEIC ImageIO adapter: NightMonkeys `imageio-heif 1.1.0`
- native codec: Alpine `libheif 1.23.0-r0`
- Spring Boot: `4.1.1`
- Gradle Wrapper: `9.7.1`
- PostgreSQL: `18.6-alpine3.23`
- gateway: `nginx:1.31.4-alpine3.24` exact manifest digest
- gateway port: `127.0.0.1:3000`
- frontend host port: 없음, gateway 전용 service port 3000
- backend port: `127.0.0.1:8080`
- PostgreSQL host port: 없음

재현 가능한 frontend/backend test와 stack 명령은 [프로젝트 README](../../README.md#로컬-개발)를 따른다.

관리자 browser API base 환경변수는 만들지 않는다. `/admin/` client는 현재 origin의 상대경로 `/api/admin/**`만 사용하며 `NEXT_PUBLIC_` credential·backend host·port를 주입하지 않는다.

## backend·PostgreSQL

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `POSTGRES_DB` | N | local 개발 DB명 |
| `POSTGRES_USER` | Y 취급 | local 개발 DB user |
| `POSTGRES_PASSWORD` | Y | local 개발 DB password |
| `SPRING_DATASOURCE_URL` | Y 취급 | backend에 주입하는 내부 JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Y 취급 | backend DB user |
| `SPRING_DATASOURCE_PASSWORD` | Y | backend DB password |
| `RHAOMI_SESSION_COOKIE_SECURE` | N | local HTTP는 `false`, production TLS는 `true` |

Compose는 `POSTGRES_*`에서 backend의 `SPRING_DATASOURCE_*`를 내부 service hostname으로 구성한다. production에서는 운영 전용 Secret과 정확한 runtime contract를 별도 Compose에서 사용한다.

## private media

| 변수 | 비밀 | 기본값 | 설명 |
|---|---:|---|---|
| `RHAOMI_MEDIA_ROOT` | N, 내부 경로 | Compose `/var/lib/rhaomi/media` | backend 전용 temp·master의 동일 filesystem root |

- root가 비었거나 temp/master directory를 만들고 쓸 수 없거나 두 directory가 다른 filesystem이면 backend 기동을 실패시킨다.
- local Compose는 별도 persistent `dev-rhaomi-backend-media-masters` volume을 backend에만 mount한다.
- source 20 MiB, stored 30 MiB, width·height 12,000px, total 60MP, JPEG quality 92는 현재 application contract로 고정하며 client request나 공개 env로 변경하지 않는다.
- `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED`는 pinned FFM 기반 HEIC adapter 실행에 필요하며 backend image와 Gradle test/bootRun에만 적용한다.
- production root·volume·backup은 local 값을 재사용하지 않고 별도 운영 승인에서 exact path와 restore 절차를 정한다.

## local/test 관리자 bootstrap

| 변수 | 비밀 | 기본값 | 설명 |
|---|---:|---|---|
| `RHAOMI_BOOTSTRAP_ADMIN_ENABLED` | N | `false` | 명시적 local/test bootstrap gate |
| `RHAOMI_BOOTSTRAP_ADMIN_EMAIL` | Y 취급 | 빈 값 | test/local 관리자 email |
| `RHAOMI_BOOTSTRAP_ADMIN_PASSWORD` | Y | 빈 값 | 최소 12자, UTF-8 최대 72 byte의 test/local password |

- enable flag와 두 credential이 모두 유효할 때만 account를 idempotent하게 생성한다.
- 72-byte 초과 password는 encoder 호출 전에 validation 오류로 기동을 중단한다.
- flag가 false면 credential이 있더라도 account를 만들지 않는다.
- production profile에서 flag가 true면 기동을 실패시킨다.
- 실제 은총쌤 credential을 `.env.example`, CI, 문서에 사용하지 않는다.

## 공개 frontend build — 후속

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `PUBLIC_SITE_URL` | N | canonical 기준 absolute URL |
| `SITE_ENV` | N | local/development/production |
| `BUILD_API_INTERNAL_URL` | N | Docker 내부 read-only build API URL |
| `BUILD_API_CREDENTIAL` | Y | 후속 builder 전용 credential |
| `CONTENT_SNAPSHOT_PATH` | N | 생성 파일 경로 |
| `MEDIA_OUTPUT_PATH` | N | 공개 파생본 경로 |
| `BUILD_RELEASE_ID` | N | release 식별자 |

build credential은 관리자 session과 분리하고 절대 `NEXT_PUBLIC_` 접두사를 사용하지 않는다. 이 변수와 API는 아직 구현되지 않았다.

## Deploy hook — 후속

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `DEPLOY_HOOK_SECRET` | Y | backend event 요청 인증 |
| `DEPLOY_LOCK_PATH` | N | global lock |
| `RELEASES_DIR` | N | 정적 release |
| `CURRENT_LINK` | N | 활성 symlink |
| `RELEASE_RETENTION` | N | 보존 개수 |
| `DEBOUNCE_SECONDS` | N | 연속 변경 합치기 |

## `.env.example`

- 변수 이름과 비밀 아닌 기본값
- local 전용 placeholder 설명
- 실제 key, password, 실사용 email 금지
- 운영 `.env`와 credential source를 참조하지 않음

현재 example에는 PostgreSQL, session cookie, 비활성 local/test bootstrap과 local-safe private media root만 둔다.

## domain — 후속

```text
PUBLIC_SITE_URL=https://<domain>
ADMIN_PATH=https://<domain>/admin
API_BASE_URL=https://<domain>/api
```

domain을 바꾸면 canonical, sitemap, robots, Open Graph, 검색엔진 등록, same-origin proxy/CSP, TLS와 session cookie Secure 설정을 함께 갱신한다. 관리자 browser에 CORS allowlist를 추가하는 방식으로 우회하지 않는다.

## 버전 정책

- `package-lock.json`과 Gradle Wrapper 커밋
- Docker image 명시 version 또는 digest
- Node.js와 Java는 project가 검증한 version 고정
- Spring Boot major/minor upgrade는 Java·Gradle·Spring Security 호환성 검토
- PostgreSQL major upgrade는 logical backup/restore 또는 공식 upgrade 계획 필요
