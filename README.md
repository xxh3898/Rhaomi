---
title: "Rhaomi 프로젝트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
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

Phase 0 기준 문서와 Issue #1의 Static Export 기반, Issue #3의 Spring Boot 관리자 인증 기반을 유지한다. Phase 1C-1~6의 콘텐츠·매장정보·private media·갤러리 API와 relation, Phase 1C-7의 `/admin/` Static Export 인증 셸·local same-origin Nginx gateway, Phase 1C-8a의 미디어 UI에 이어 Phase 1C-8b에서 매장정보 전체 편집과 재사용 가능한 private media picker를 추가했다. 공개 responsive 파생본·build API와 실제 랜딩·SEO 렌더링, 나머지 콘텐츠 CRUD UI는 후속 Issue에서 구현한다.

```text
.
├── .github/
│   ├── CODEOWNERS
│   ├── workflows/validate.yml
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/
├── src/
│   ├── app/                 # 공개 홈과 /admin Static Export route
│   └── features/            # admin auth/transport, dashboard, media와 shop settings UI
├── backend/                 # Spring Boot 인증·콘텐츠·private media API와 PostgreSQL contract test
├── infra/nginx/dev.conf     # local same-origin gateway
├── scripts/                 # 정적 산출물·gateway·HEIC·Compose smoke 검증
├── tests/                   # frontend·runtime contract test
├── docs/                    # 제품·아키텍처·운영 기준 문서
├── compose.dev.yaml         # 개발 전용 gateway/frontend/backend/PostgreSQL
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

브라우저 진입점은 gateway의 `http://127.0.0.1:3000` 하나다. frontend dev server는 host port를 열지 않으며 `/api/**`만 backend로 전달한다.

```bash
docker compose --env-file .env.dev.local -f compose.dev.yaml --profile frontend up -d --wait frontend gateway
```

`/`, `/admin/`, `/api/admin/auth/**`는 모두 위 same-origin으로 확인한다. backend의 `127.0.0.1:8080`은 local 진단용 loopback 경계다.

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

현재 collection 관리 API는 `/api/admin/breeds`, `/api/admin/services`, `/api/admin/notices`, `/api/admin/gallery-items`에 `GET`, `POST`, `PUT`만 제공한다. 생성은 항상 `draft`이며 수정 요청은 전체 mutable representation을 보낸다. 공지는 게시·만료 시각을 microsecond로 정규화한 뒤 게시 본문·유효 기간을 application과 PostgreSQL에서 이중 검증한다. 갤러리는 slug 없이 scalar relation id만 반환하고 게시 필수값·관계 존재성·관계 대상 상태를 mutation 전에 검증한다.

매장정보는 상태나 공개 id가 없는 단일 현재값이다. `/api/admin/shop-settings`의 `GET`과 전체 `PUT`만 제공하며 최초 PUT은 `201`, 이후 PUT은 `200`이다. PostgreSQL UNIQUE/CHECK가 row를 하나로 제한하고 Hero·프로필 image/alt pair와 세 media FK를 방어한다. API는 핵심 NAP·영업시간·전화번호·HTTPS 외부 링크, nullable Hero·프로필·OG scalar media id, Hero·프로필 대체텍스트와 server-owned audit를 검증한다. non-null media는 존재하고 `active`여야 하며 관계가 나중에 archived돼도 자동 제거하지 않는다. 모든 state-changing 요청에는 관리자 session과 CSRF token이 필요하며 `PATCH`와 영구 `DELETE` endpoint는 제공하지 않는다. 실제 운영값과 실사진은 seed하지 않는다.

미디어는 `/api/admin/media`의 목록·단건·private content 조회, multipart upload와 status `PUT`만 제공한다. 20 MiB source, 30 MiB stored, 12,000px, 60MP 제한을 실제 byte signature와 decoder로 검증하며 client MIME·확장자·파일명을 신뢰하지 않는다. server-owned UUID storage key와 SHA-256 무결성 metadata를 사용하고 `active | archived` row와 master file을 유지한다. original filename·storage key·filesystem path·SHA-256은 API response에 노출하지 않으며 public/build media endpoint와 physical delete는 없다.

gateway routing, health, local/test bootstrap, CSRF login/me/logout, 20 MiB request 경계와 재기동 후 persistent volume을 한 번에 검증하려면 다음처럼 명시적 test credential을 process 환경으로 전달한다. 실제 운영 email/password를 사용하지 않는다.

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
- `/admin/`은 noindex인 Static Export client shell이며 backend session이 최종 보안 경계다. login POST와 identity 검증이 끝나면 form credential과 pre-login CSRF를 먼저 제거하고, 별도 fresh CSRF 준비가 성공한 뒤에만 authenticated mutation-ready 상태가 된다. 준비 실패 재시도는 `/me`로 기존 session을 확인한 뒤 fresh CSRF만 다시 획득한다.
- local browser 요청은 Nginx gateway의 same-origin `/api/**`를 사용한다. frontend는 host port를 열지 않고 gateway는 PostgreSQL network에 참여하지 않는다.
- 견종·서비스 기준정보는 관리자 session·CSRF가 적용된 API로 생성·조회·수정·보관할 수 있다.
- 공지는 같은 인증 경계에서 생성·조회·수정·보관하며 게시 필수값과 게시·만료 기간을 검증한다.
- 매장정보 singleton은 같은 인증 경계에서 조회·전체 갱신하며 DB와 application이 한 행·필수값·영업시간·HTTPS URL·Hero/프로필 image-alt pair·active media relation을 검증한다.
- private media master는 같은 인증 경계에서 업로드·조회·archive하며 HEIC/HEIF는 backend에서 canonical JPEG로 정규화한다.
- `/admin/`은 미디어 목록·authenticated Blob preview·단일 upload·active/archive filter·archive/restore와 매장정보 조회·미초기화·full PUT 편집 UI를 제공한다.
- Hero·미용사·OG는 active private media를 한 개씩 선택·해제할 수 있고, archived/missing 기존 relation은 숨기지 않고 clear/replace가 필요한 상태로 표시한다.
- 갤러리는 실제 견종·대표 서비스·private media를 FK로 참조하고 같은 인증 경계에서 생성·조회·전체 수정·보관·복구한다.
- 관계 대상의 상태 변경은 갤러리나 매장정보에 cascade하지 않으며 후속 공개 snapshot이 published/relation/file 조건과 선택된 매장 이미지를 다시 검증한다.
- `/admin/`의 갤러리·공지·견종·서비스 UI, 공개 responsive 파생본과 Builder API는 후속 Issue에서 구현한다.
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
