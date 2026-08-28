---
title: "저장소 구조"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "모듈·배포 구조 변경 시"
---

# 저장소 구조

전체 제품 구현 단계의 목표 구조다. Phase 1A는 아래 최소 실행 기반만 생성하며 나머지 module은 관련 Issue에서 추가한다.

```text
Rhaomi/
├── src/
│   ├── app/
│   │   ├── layout.tsx
│   │   ├── page.tsx
│   │   ├── not-found.tsx
│   │   ├── robots.ts
│   │   ├── sitemap.ts
│   │   ├── opengraph-image.*
│   │   └── notice/
│   │       └── [slug]/
│   │           └── page.tsx
│   ├── components/
│   │   ├── layout/
│   │   ├── hero/
│   │   ├── gallery/
│   │   ├── groomer/
│   │   ├── services/
│   │   ├── notices/
│   │   ├── location/
│   │   └── ui/
│   ├── config/
│   │   └── site.ts
│   ├── generated/
│   │   ├── content.json
│   │   └── media-manifest.json
│   ├── lib/
│   │   ├── cms/
│   │   ├── content/
│   │   ├── media/
│   │   ├── seo/
│   │   └── validation/
│   ├── styles/
│   └── types/
├── public/
│   ├── brand/
│   └── generated/
├── directus/
│   ├── schema/
│   ├── seed/
│   └── README.md
├── infra/
│   ├── compose/
│   ├── nginx/
│   ├── deploy-hook/
│   └── backup/
├── scripts/
│   ├── sync-content.*
│   ├── sync-media.*
│   ├── build-static.*
│   ├── validate-export.*
│   ├── deploy-static.*
│   ├── rollback.*
│   ├── backup.*
│   └── restore-test.*
├── tests/
│   ├── unit/
│   ├── component/
│   ├── e2e/
│   ├── accessibility/
│   └── smoke/
├── docs/
├── .github/
├── AGENTS.md
├── next.config.ts
├── package.json
├── tsconfig.json
├── .env.example
└── README.md
```

## Phase 1A 현재 구조

```text
Rhaomi/
├── .github/
│   └── workflows/
│       └── validate.yml
├── src/
│   └── app/
│       ├── globals.css
│       ├── layout.tsx
│       ├── page.module.css
│       └── page.tsx
├── scripts/
│   ├── validate-cms-compose.sh
│   └── validate-export.mjs
├── tests/
│   └── bootstrap-contract.test.mjs
├── docs/
├── compose.dev.yaml
├── next.config.ts
├── package.json
├── package-lock.json
├── tsconfig.json
└── README.md
```

- `compose.dev.yaml`은 `dev-rhaomi` project 이름과 개발 전용 network/volume만 사용한다.
- `next-env.d.ts`, `.next/`, `out/`, `node_modules/`는 생성 파일 또는 local dependency이므로 Git에 포함하지 않는다.
- `directus/schema`, CMS sync, image pipeline, Nginx와 배포 script는 Phase 1A에 포함하지 않는다.

## 디렉터리 계약

### `src/config`

코드 소유 설정만 둔다.

- fallback이 아닌 기술 설정
- route 이름
- 정적 생성 규칙
- 지원 이미지 폭
- 사이트 언어

실제 매장 콘텐츠를 중복 저장하지 않는다.

### `src/generated`

CMS 동기화 스크립트가 만든 빌드 입력이다.

- 수동 수정 금지
- 기본적으로 Git에 커밋하지 않음
- schema version 포함
- 빌드마다 새로 생성

### `public/generated`

공개용 이미지 파생본이다.

- 원본 업로드 금지
- 수동 수정 금지
- 내용 hash가 파일명에 포함
- 빌드 작업 디렉터리에서 생성 후 export에 포함

### `directus/schema`

- Directus 스키마 snapshot/diff
- 정책과 Flow 설정의 재현 가능한 정의
- 비밀값 제외
- 운영 UI에서 스키마를 바꿨다면 같은 PR 또는 긴급 변경 후속 PR로 반영

### `infra`

- Docker Compose
- Nginx
- deploy hook
- backup job
- 운영 명령과 healthcheck

### `scripts`

- 로컬과 CI가 공유하는 단일 진실 공급원
- GitHub Actions에 긴 shell을 직접 중복 작성하지 않는다.
