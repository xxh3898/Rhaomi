---
title: "저장소 구조"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "모듈·배포 구조 변경 시"
---

# 저장소 구조

구현 단계의 목표 구조다. 이 문서 ZIP에는 실제 구현 디렉터리를 생성하지 않는다.

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
