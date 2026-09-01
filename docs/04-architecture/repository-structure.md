---
title: "저장소 구조"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-01"
review_trigger: "module·배포 구조 변경 시"
---

# 저장소 구조

기존 Next.js source를 이동하지 않고 repository root에 `backend/`를 추가한다.

## D-IMP-3 현재 구조

```text
Rhaomi/
├── .github/
│   └── workflows/
│       ├── validate.yml
│       └── production-release.yml # manual exact-main release/deploy source
├── src/
│   ├── app/
│   │   ├── page.tsx               # 공개 Static Export 홈
│   │   ├── notices/[slug]/        # generated notice 정적 상세
│   │   └── admin/                 # Static Export auth shell·DOM test
│   ├── build-orchestration/       # Build API HTTP client·media provider·staging orchestration/test
│   ├── build-transformer/         # strict snapshot·responsive derivative·staging library/test
│   ├── public-site/               # generated V2 parser·safe Markdown·responsive media
│   ├── publication-release/       # Next build·validator·manifest·switch·smoke·retention
│   ├── features/admin-auth/       # relative API client·shape/error test
│   └── test/                      # Vitest DOM setup
├── backend/
│   ├── gradle/wrapper/            # Gradle 9.7.1 Wrapper
│   ├── src/main/java/kr/co/rhaomi/
│   │   ├── backend/
│   │   │   ├── admin/             # admin_users domain
│   │   │   ├── auth/              # login/me/logout/csrf API
│   │   │   ├── build/             # stateless read-only snapshot·public-scope media API
│   │   │   ├── breed/             # 견종 관리 domain/API
│   │   │   ├── content/           # 상태·audit·공통 오류 계약
│   │   │   ├── config/            # security와 bootstrap
│   │   │   ├── gallery/           # 갤러리 CRUD·관계·게시 검증 domain/API
│   │   │   ├── notice/            # 공지 관리 domain/API와 게시·기간 검증
│   │   │   ├── media/             # private upload·HEIC 정규화·storage domain/API
│   │   │   ├── publication/       # revision/outbox producer와 claim·lease·generation state service
│   │   │   ├── service/           # 미용 서비스 관리 domain/API
│   │   │   └── shop/              # 매장정보 singleton·media relation domain/API와 검증
│   │   ├── publisher/             # dedicated non-web poll/debounce/coalesce/lock control plane
│   │   └── production/            # exact CLI non-web migration/schema task root
│   ├── src/main/resources/
│   │   ├── db/migration/          # Flyway V1~V9, V8 producer·V9 claim/generation state
│   │   └── application.yml
│   ├── src/test/                  # PostgreSQL auth·콘텐츠·build snapshot/media 계약
│   ├── Dockerfile.dev             # exact Java 25 + development libheif runtime
│   ├── Dockerfile.production      # Java 25·Node 24·decoder-only canonical application image
│   ├── production-image-components.json
│   └── production-image-NOTICE.md # tracked source·license·obligation evidence
├── infra/nginx/
│   ├── dev.conf                   # local same-origin proxy와 /api/build 명시적 404
│   └── production.conf            # static/admin-only production project Nginx
├── ops/production/
│   ├── deploy-rhaomi.sh           # fixed Mac canonical-root wrapper
│   ├── deploy-rhaomi-core.sh      # strict input·lock·maintenance·digest apply core
│   ├── backup-rhaomi.sh           # fixed Mac backup wrapper
│   ├── backup-rhaomi-core.sh      # shared lock·dump/media·complete/eligibility core
│   └── com.rhaomi.backup.plist    # 미설치 03:30 KST schedule source
├── scripts/
│   ├── generate-synthetic-media-fixtures.mjs
│   ├── validate-backend-auth.mjs
│   ├── validate-backend-media.mjs
│   ├── validate-gateway.mjs
│   ├── validate-backend-compose.sh
│   ├── transform-build-snapshot.mts
│   ├── validate-frontend-credential-isolation.mjs
│   ├── validate-production-image.sh
│   ├── validate-production-compose.sh
│   ├── validate-production-compose-contract.mjs
│   ├── validate-production-deploy.sh
│   ├── rhaomi-backup-tool.mjs     # strict manifest·eligibility·restore/retention tool
│   ├── validate-production-backup-control.sh
│   ├── validate-production-backup.sh
│   ├── fixtures/fake-production-docker.sh
│   ├── fixtures/fake-production-backup-docker.sh
│   ├── validate-libheif-build-contract.sh
│   ├── finalize-production-sbom.mjs
│   ├── validate-production-supply-chain.mjs
│   ├── verify-published-production-image.mjs # published index·platform attestation evidence 검증
│   └── validate-export.mjs
├── tests/                         # frontend·runtime contract
├── docs/
├── compose.dev.yaml
├── compose.production.yaml        # canonical production service/network/mount inventory
├── compose.production.validation.yaml # task temp validation overlay
├── next.config.ts
├── package.json
├── package-lock.json
├── .env.example
└── README.md
```

- `compose.dev.yaml`은 `dev-rhaomi` project와 개발 전용 network/volume만 사용한다. frontend는 repository root나 `.env*`를 mount하지 않고 runtime source/config allowlist만 받으며, network-disabled `contract-check`도 actual local env file 없이 tracked 검증 source만 받는다.
- `backend/build`, `.gradle`, `.next`, `out`, `node_modules`는 생성 파일이므로 Git에 포함하지 않는다.
- Directus runtime, schema snapshot, permission artifact와 provisioning script는 현재 구조에 없다.
- 관리자 collection controller는 견종·서비스·공지·갤러리의 `GET`, `POST`, `PUT`을 제공한다. 매장정보 singleton은 `GET`, `PUT`, private media는 list/detail/content `GET`, upload `POST`, status `PUT`만 제공하며 모든 domain에서 `PATCH`·`DELETE`를 제공하지 않는다.
- `/admin/`은 인증 상태·로그인·로그아웃과 매장정보·미디어·견종·서비스·갤러리·공지 관리 component를 same-page Static Export shell에서 제공한다.
- `src/features/admin-auth`는 relative `/api/admin/**`, same-origin credential, GET no-store, response shape와 고정 오류 mapping을 한 경계에서 처리한다.
- `infra/nginx/dev.conf`는 local 개발 전용이다. `infra/nginx/production.conf`는 project loopback Nginx의 static/admin/deny/cache 계약이며 host edge TLS·Cloudflare 설정은 포함하지 않는다.
- `backend/.../publication`은 domain transaction 밖에서 호출할 수 없는 `MANDATORY` producer recorder와 deterministic JDBC state service를 둔다. state service는 due claim, source/boundary 최소 stale 판정, generation·lease·retry·terminal/coalesce primitive만 제공하며 HTTP controller, scheduler, background executor나 범용 queue framework를 제공하지 않는다.
- `kr.co.rhaomi.publisher`는 exact mode argument 전용 non-web root와 state adapter, fixed debounce/highest coalesce, lease heartbeat, advisory lock과 fixed-process Node release executor를 둔다. child·descendant physical exit 전에는 Java body와 lock을 종료하지 않는다.
- `kr.co.rhaomi.production`은 exact `--rhaomi.production-task=migrate|schema-validate`만 허용하는 별도 non-web root다. backend controller·admin bootstrap·publisher worker를 scan하지 않고 migration은 Flyway+JPA validate, schema task는 Flyway-disabled JPA validate 후 종료한다.
- `backend/.../build`는 별도 stateless principal과 GET allowlist, active generation gate, read-only `REPEATABLE READ` snapshot, exact DTO와 current public relation media 조회만 제공한다. admin session을 재사용하거나 publication/content state를 변경하지 않는다.
- `src/build-transformer`는 backend나 browser transport에 의존하지 않는 strict `BuildSnapshotV2` parser, lossless int64 canonical string validator, `MediaContentProvider` port, responsive image transformer와 staging writer를 제공한다. publisher loop·HTTP·release filesystem은 포함하지 않는다.
- `src/build-orchestration`은 environment-only Build API config, bounded no-redirect snapshot client, manifest-scoped memory media provider와 transformer staging을 제공하고, `src/publication-release`가 이를 Next render·manifest·switch에 조합한다.
- `src/public-site`는 tracked synthetic fixture와 per-release generated V2를 같은 exact parser로 읽고 safe Markdown·responsive media component를 제공한다. browser runtime fetch를 만들지 않는다.
- `src/publication-release`는 isolated workspace, static export validator, private release manifest, BigInt stale guard, immutable install, previous/current switch, loopback serving smoke·rollback과 retention을 담당한다.
- `scripts/transform-build-snapshot.mts`는 test·수동 fixture 검증용 filesystem adapter다. media UUID나 local path를 성공·오류 출력에 기록하지 않는다.
- `scripts/prepare-publication-staging.mts`는 generation과 private output path만 argv로 받고 URL/credential은 environment에서 읽으며 safe JSON/exit family만 출력한다.
- `scripts/publish-static-release.mts`는 generation만 argv로 받고 full release result를 safe one-line JSON과 fixed exit family로 출력한다.
- `.github/workflows/production-release.yml`은 exact current `main` SHA의 수동 release만 받고 validation→immutable multi-arch GHCR publish→protected Environment·Tailscale→fixed Mac argv를 분리한다. PR Validate는 이 workflow를 dispatch하거나 package를 push하지 않는다.
- `ops/production` source는 production host의 fixed `/private/var/lib/rhaomi/app/bin` inventory로 provision할 대상이지 현재 Git worktree에서 production root를 변경하는 installer가 아니다. task validator는 marker temp root와 fake Docker만 사용한다.

## 전체 제품 목표 구조 — planned

```text
Rhaomi/
├── src/
│   ├── app/
│   │   ├── page.tsx
│   │   ├── admin/                 # 현재 auth shell, 후속 콘텐츠 UI
│   │   └── notices/[slug]/
│   ├── components/
│   ├── generated/
│   │   ├── content.json
│   │   └── media-manifest.json
│   ├── lib/
│   │   ├── content/
│   │   ├── media/
│   │   ├── seo/
│   │   └── validation/
│   └── types/
├── backend/
│   └── src/main/
│       ├── java/                  # admin/build API와 dedicated publisher control plane
│       └── resources/db/migration/
├── public/
│   ├── brand/
│   └── generated/
├── infra/
│   ├── compose/
│   ├── nginx/
│   ├── publisher/
│   └── backup/
├── scripts/
├── tests/
└── docs/
```

planned 경로는 관련 Issue가 구현할 때만 추가한다.

## 디렉터리 계약

### `backend/src/main/java`

- controller는 entity를 직접 response로 반환하지 않는다.
- 관리자 request DTO는 명시적 field allowlist를 사용한다.
- 인증·인가·domain·persistence 경계를 package로 구분하되 불필요한 layer를 만들지 않는다.
- publication recorder는 최종 domain persistence와 같은 transaction에서 한 mutation당 한 번만 호출한다.
- publication state service는 전달받은 `now`·lease를 microsecond로 정규화하고 owner·generation·active lease를 확인한다. 실제 poll/debounce/build orchestration이나 full public eligibility를 이 package에 복제하지 않는다.
- `kr.co.rhaomi.publisher`는 normal backend component scan 밖의 별도 root다. exact mode argument가 선택된 `WebApplicationType.NONE` process에서만 state adapter, fixed debounce/coalesce, lease heartbeat, filesystem lock과 typed executor port를 구성한다.
- normal `BackendApplication`은 publisher mode argument가 없으면 기존 HTTP context만 기동한다. publisher root는 controller, servlet security chain, JPA repository, public route와 admin bootstrap을 scan하지 않는다.
- publisher executor는 Node full release CLI의 exact result만 typed state transition에 연결한다. malformed/multiple-line output, child launch failure와 descendant 잔존은 success로 처리하지 않는다.
- build package는 64자 lowercase hex service token을 timing-safe 비교하고 session을 만들지 않는다. snapshot/media response는 exact allowlist만 사용하며 raw entity·storage path·hash·audit·claim 내부 상태를 노출하지 않는다.
- password hash, session id와 credential을 log에 남기지 않는다.

### `backend/src/main/resources/db/migration`

- PostgreSQL schema source of truth
- 적용된 migration 수정 금지, 변경은 새 version migration
- JPA `ddl-auto`로 schema 생성 금지
- destructive migration은 별도 data/backup/rollback 승인 필요

### `src/generated` — synthetic tracked build fixture와 per-release transformer 산출물

- standard `npm run build` 재현을 위한 실제 운영정보가 없는 최소 V2 fixture를 repository에 둔다.
- full release는 source를 수정하지 않고 격리 workspace copy의 이 두 파일을 transformer staging 결과로 교체한다.
- schema version 포함
- 일부 실패에서 과거 데이터와 혼합 금지
- 공개 Next render는 exact parser를 통해서만 이 산출물을 소비한다.

### `public/generated` — transformer 산출물, repository에는 미커밋

- 공개용 image 파생본
- 원본 upload 금지
- 내용 hash 기반 파일명
- metadata 제거 후 export에 포함
- full publisher가 immutable release 설치·retention·current switch까지 연결한다.

### `infra`

- `infra/nginx/dev.conf`는 local same-origin gateway만 정의
- `compose.production.yaml`과 `infra/nginx/production.conf`는 four-service default topology, opt-in migration/schema·network-disabled backup tool과 project Nginx를 정의하고 validation overlay가 task temp source만 치환
- workflow·fixed deploy entrypoint source는 구현됐지만 actual production Secret·Mac ownership·volume·ingress/GHCR/Environment/Tailscale provisioning과 backup job은 후속
- local 개발 Compose와 운영 credential·volume을 공유하지 않음
- production Compose는 repository 밖 Mac host `/private/var/lib/rhaomi`의 public/media/state 및 publisher isolated build-workspace bind source와 production project-scoped PostgreSQL named volume을 명시적으로 구분
- Linux container `/srv/rhaomi/public` target을 Mac host `/srv/rhaomi` source로 해석하거나 `synthetic.conf`·custom File Sharing 전제로 사용하지 않음
- production data, named volume과 초기 Mac local backup repository를 Git worktree·`infra/` 아래에 생성하지 않음
- future 외장 SSD `/Volumes/<provisioned-volume>/...`·iCloud repository도 별도 provisioning 전 생성하지 않음

### `scripts`

- local과 CI가 공유하는 검증 진입점
- GitHub Actions에 긴 shell을 중복 작성하지 않음
- 실제 credential 출력 금지
- frontend filesystem token 검증은 raw token 대신 SHA-256 digest를 비교하고 match된 literal을 출력하지 않음
