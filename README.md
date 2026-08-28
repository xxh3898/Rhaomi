---
title: "Rhaomi 프로젝트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "프로젝트 구조 또는 핵심 범위 변경 시"
---

# Rhaomi

라오미펫 애견미용실의 실제 영업용 모바일 중심 랜딩페이지 프로젝트다.

- 저장소: `xxh3898/Rhaomi`
- 공개 사이트: 도메인 확정 전
- 운영 관리자: 은총쌤
- 개발·인프라 담당: 조치호
- 배포 대상: Mac mini
- 공개 프론트엔드: Next.js App Router + TypeScript + Static Export
- 관리 API: Java 25 LTS + Spring Boot 4.1.1
- 데이터베이스: PostgreSQL
- 정적 웹 서버·리버스 프록시: Nginx

## 현재 구현 범위

Phase 0 기준 문서와 Issue #1의 Static Export 기반을 유지하면서 Issue #3의 Spring Boot 관리자 인증 기반을 제공한다. 현재 backend 범위는 `admin_users`, Flyway, 세션 인증과 CSRF 계약까지다. 콘텐츠 CRUD, 관리자 화면과 실제 랜딩 디자인은 후속 Issue에서 구현한다.

```text
.
├── .github/
│   ├── CODEOWNERS
│   ├── workflows/validate.yml
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/
├── src/app/                 # 최소 App Router 화면
├── backend/                 # Spring Boot 관리 API와 인증 contract test
├── scripts/                 # 정적 산출물·Compose smoke 검증
├── tests/                   # 공개 frontend contract test
├── docs/                    # 제품·아키텍처·운영 기준 문서
├── compose.dev.yaml         # 개발 전용 frontend/backend/PostgreSQL
├── next.config.ts
├── package.json
├── package-lock.json
├── AGENTS.md
└── README.md
```

## 작업 원칙

1. `docs/01-product/open-items.md`에서 출시 전 미확정 항목을 확인한다.
2. `docs/09-decisions/`의 accepted ADR을 구현 기준으로 사용한다.
3. 실제 구현으로 계약이 바뀌면 코드와 문서를 같은 PR에서 동기화한다.
4. feature PR은 `dev`를 대상으로 하고 검증된 release만 `main`으로 승격한다.

## 로컬 개발

host에 Node.js나 Java를 설치하지 않고 `compose.dev.yaml`의 고정된 runtime image를 사용한다. 먼저 example을 복사하고 placeholder를 local 개발 전용 값으로 교체한다.

```bash
cp .env.example .env.dev.local
```

### 프론트엔드

```bash
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend run --rm frontend npm ci
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend run --rm frontend npm run lint
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend run --rm frontend npm run typecheck
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend run --rm frontend npm test
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend run --rm frontend npm run build
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend run --rm frontend npm run validate:export
```

개발 서버는 `127.0.0.1:3000`에만 공개한다.

```bash
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend up frontend
```

### Spring Boot와 PostgreSQL

PostgreSQL은 host port를 공개하지 않으며 backend만 `127.0.0.1:8080`에 bind한다. 기본 설정에서는 관리자 계정을 만들지 않는다.

```bash
docker compose --env-file .env.dev.local -f compose.dev.yaml config
docker compose --env-file .env.dev.local -f compose.dev.yaml up -d --wait postgres backend
docker compose --env-file .env.dev.local -f compose.dev.yaml ps
docker compose --env-file .env.dev.local -f compose.dev.yaml down
```

마지막 `down`은 container와 network만 종료하고 개발 named volume은 보존한다. 운영 Compose, 운영 data, 운영 credential은 이 개발 구성을 사용하지 않는다.

backend test는 실제 PostgreSQL을 사용한다.

```bash
docker compose --env-file .env.dev.local -f compose.dev.yaml up -d --wait postgres
RHAOMI_TEST_DATABASE_ALLOWED=true \
docker compose --env-file .env.dev.local -f compose.dev.yaml run --rm --no-deps backend ./gradlew test --no-daemon
docker compose --env-file .env.dev.local -f compose.dev.yaml down
```

health, local/test bootstrap, CSRF login/me/logout, 재기동 후 persistent volume을 한 번에 검증하려면 다음처럼 명시적 test credential을 process 환경으로 전달한다. 실제 운영 email/password를 사용하지 않는다.

```bash
RHAOMI_BOOTSTRAP_ADMIN_ENABLED=true \
RHAOMI_BOOTSTRAP_ADMIN_EMAIL=admin.smoke@example.com \
RHAOMI_BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-local-test-password' \
sh scripts/validate-backend-compose.sh .env.dev.local
```

## 현재 핵심 결론

- 고객 페이지는 정적 HTML로 배포하며 SSR을 사용하지 않는다.
- 공개 사이트는 런타임에 Spring Boot나 PostgreSQL에 의존하지 않는다.
- 관리자 인증은 HttpOnly session cookie와 CSRF 보호를 사용하는 Spring Security 기반이다.
- 은총쌤용 콘텐츠 CRUD와 `/admin` 화면은 후속 Issue에서 구현한다.
- 공개 콘텐츠 변경은 정적 사이트 재빌드·검증·원자적 교체를 유발한다.
- 고객용 예약 시스템, 결제, 회원가입, 문의 폼은 만들지 않는다.
- 전화, 인스타그램, 네이버톡톡 등 외부 문의 채널로 연결한다.
- 검색 노출은 기술 선택만으로 보장되지 않는다. 정적 HTML, 로컬 SEO, NAP 일치, 콘텐츠 품질, 검색엔진 등록과 운영이 함께 필요하다.

## 주요 문서

- [문서 인덱스](docs/README.md)
- [제품 개요](docs/01-product/product-brief.md)
- [기능 범위](docs/01-product/scope.md)
- [시스템 구조](docs/04-architecture/system-context.md)
- [도메인 데이터 모델](docs/04-architecture/cms-data-model.md)
- [정적 퍼블리싱 파이프라인](docs/04-architecture/static-publishing-pipeline.md)
- [SEO 전략](docs/05-seo/seo-strategy.md)
- [배포 및 롤백](docs/07-operations/deployment.md)
- [출시 체크리스트](docs/08-quality/release-checklist.md)
