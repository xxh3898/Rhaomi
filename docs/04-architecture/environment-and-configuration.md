---
title: "환경설정"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "환경변수·도메인·버전 변경 시"
---

# 환경설정

## 환경

```text
local
development
production
```

별도 staging을 장기간 운영하지 않더라도 로컬 또는 임시 환경에서 production compose와 같은 구조를 검증한다.

## Phase 1A 로컬 개발 계약

- Compose file: `compose.dev.yaml`
- Compose project: `dev-rhaomi`
- local 환경파일: Git 제외 `.env.dev.local`
- 공개 example: `.env.example`
- Node.js: `24.20.0` LTS image
- Directus: `12.3.1`
- PostgreSQL: `18.6-alpine3.23`
- frontend port: `127.0.0.1:3000`
- Directus port: `127.0.0.1:8055`
- PostgreSQL host port: 없음

재현 가능한 설치, lint, typecheck, test, build와 stack 명령은 [프로젝트 README](../../README.md#로컬-개발)를 따른다. Phase 1A 공개 화면은 CMS를 읽지 않으며 아래 공개 프론트 빌드 변수는 후속 정적 콘텐츠 파이프라인에서 사용한다.

## 공개 프론트 빌드

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `PUBLIC_SITE_URL` | N | canonical 기준 절대 URL |
| `SITE_ENV` | N | local/development/production |
| `DIRECTUS_INTERNAL_URL` | N | Docker 내부 CMS URL |
| `DIRECTUS_BUILD_TOKEN` | Y | read-only API token |
| `CONTENT_SNAPSHOT_PATH` | N | 생성 파일 경로 |
| `MEDIA_OUTPUT_PATH` | N | 공개 파생본 경로 |
| `BUILD_RELEASE_ID` | N | release 식별자 |

`DIRECTUS_BUILD_TOKEN`은 절대 `NEXT_PUBLIC_` 접두사를 사용하지 않는다.

## Directus

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `PUBLIC_URL` | N | 최종 관리자 절대 URL |
| `SECRET` | Y | 토큰 서명용 강한 무작위 값 |
| `DB_CLIENT` | N | PostgreSQL driver |
| `DB_HOST` | N | 내부 service name |
| `DB_PORT` | N | 내부 포트 |
| `DB_DATABASE` | N | DB명 |
| `DB_USER` | Y 취급 | DB 사용자 |
| `DB_PASSWORD` | Y | DB 비밀번호 |
| `ADMIN_EMAIL` | Y 취급 | 최초 bootstrap에서만 사용 |
| `ADMIN_PASSWORD` | Y | 최초 bootstrap에서만 사용 |
| `STORAGE_*` | 혼합 | 업로드 경로와 driver |
| `RATE_LIMITER_*` | N | 로그인/API 제한 |
| `CORS_*` | N | 필요한 origin만 허용 |
| `LICENSE_KEY` | Y | 필요하고 적법한 경우만 |

정확한 변수명과 지원 여부는 잠금한 Directus 버전의 공식 문서를 기준으로 구현한다.

## Deploy hook

| 변수 | 비밀 | 설명 |
|---|---:|---|
| `DEPLOY_HOOK_SECRET` | Y | Directus Flow 요청 인증 |
| `DEPLOY_LOCK_PATH` | N | 전역 lock |
| `RELEASES_DIR` | N | 정적 릴리스 |
| `CURRENT_LINK` | N | 활성 symlink |
| `RELEASE_RETENTION` | N | 보존 개수 |
| `DEBOUNCE_SECONDS` | N | 연속 변경 합치기 |

## PostgreSQL

- host port 비공개
- 전용 DB와 전용 user
- 최소 권한
- data volume 영속화
- healthcheck
- backup user를 분리할 수 있음

## `.env.example`

- 변수 이름
- 비밀 여부
- 샘플 형식
- 생성 방법 설명
- 실제 키와 비밀번호 금지
- 운영 `.env`의 파일 권한 제한

Phase 1A example에는 다음 local bootstrap key만 둔다.

- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `DIRECTUS_SECRET`
- `DIRECTUS_ADMIN_EMAIL`, `DIRECTUS_ADMIN_PASSWORD`
- `DIRECTUS_PUBLIC_URL`

Compose는 `DIRECTUS_*` bootstrap 변수를 container의 `SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `PUBLIC_URL`로 명시적으로 매핑한다. example placeholder를 실제 운영 credential로 사용하지 않는다.

## 도메인

미확정:

```text
PUBLIC_SITE_URL=https://<domain>
PUBLIC_URL=https://admin.<domain>
```

도메인을 바꾸면 함께 갱신:

- Directus `PUBLIC_URL`
- canonical
- sitemap
- robots sitemap URL
- Open Graph
- Google Search Console
- 네이버 서치어드바이저
- Directus 라이선스 binding 여부
- CORS/CSP
- TLS 인증서

## 버전 정책

- `package-lock.json` 등 lockfile 커밋
- Docker 이미지 명시 버전 또는 digest
- Node.js는 프로젝트가 검증한 LTS 버전 고정
- PostgreSQL major upgrade는 dump/restore 또는 공식 upgrade 계획 필요
- Directus major upgrade는 라이선스·breaking changes·schema migration 검토 필요
