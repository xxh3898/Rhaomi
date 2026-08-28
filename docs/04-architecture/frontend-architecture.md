---
title: "프론트엔드 아키텍처"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "Next.js 구조 또는 렌더링 방식 변경 시"
---

# 프론트엔드 아키텍처

## 기술

- Next.js App Router
- TypeScript strict mode
- CSS Modules 중심
- Static Export
- 정적 콘텐츠 스냅샷
- 최소한의 Client Component

## Next.js 설정

구현 기준:

```ts
const nextConfig = {
  output: 'export',
  trailingSlash: true,
};

export default nextConfig;
```

이미지 처리는 자체 정적 파생본 파이프라인을 사용한다. `next/image`를 사용할 경우 런타임 최적화 API에 의존하지 않도록 별도 설정 또는 custom loader를 적용한다.

## Server Component와 Client Component

Static Export에서도 빌드 시 Server Component를 사용할 수 있다. 단, 요청 시점 서버 기능은 사용하지 않는다.

### Server Component 우선

- 페이지 구조
- Hero
- 서비스
- 공지 목록·상세
- 위치
- SEO metadata
- JSON-LD
- 정적 갤러리 마크업

### Client Component 허용

- 견종 필터 상태
- 접근 가능한 lightbox
- 서비스 아코디언
- 문의 channel bottom sheet
- 제한적인 section reveal

Client boundary는 가장 작은 상호작용 단위에 둔다.

## 금지

- `cookies()`, `headers()` 등 요청별 API 의존
- Server Actions
- Next API Routes 또는 Route Handler 기반 운영 API
- 런타임 dynamic rendering
- 브라우저에서 Directus SDK 토큰 사용
- 고객 페이지 최초 렌더 뒤 CMS fetch로 핵심 콘텐츠 주입
- 검색봇 user-agent에 따라 다른 콘텐츠 제공

## 콘텐츠 입력

```text
Directus
→ sync script
→ validated content snapshot
→ Next build
→ static HTML
```

페이지 컴포넌트는 CMS 네트워크 호출이 아니라 검증된 로컬 스냅샷을 읽는다.

## 라우트

### `/`

- 모든 핵심 섹션을 HTML에 포함
- 갤러리 전체 데이터가 과도하면 초기 카드만 표시하고 나머지는 같은 정적 JSON chunk에서 hydrate할 수 있으나, 검색 가치가 있는 설명은 HTML에 남긴다.

### `/notice/[slug]/`

- 공개 공지를 `generateStaticParams`로 생성
- 빌드에 없는 slug는 정적 404
- canonical과 페이지별 Open Graph 생성
- 공지가 보관되면 다음 빌드에서 URL이 사라지고 sitemap에서 제외

## 데이터 검증

- Zod 또는 동등한 runtime schema
- 스냅샷 `schemaVersion`
- 중복 slug
- 잘못된 URL
- 필수 이미지 누락
- 게시 상태와 필수 필드 불일치
- 참조가 끊긴 견종·서비스

검증 실패는 fallback 콘텐츠로 무시하지 않고 빌드를 실패시킨다.

## CSS

- 전역 reset, token, typography만 global CSS
- 섹션별 CSS Module
- class name과 구조를 컴포넌트와 함께 유지
- JS로 viewport layout을 계산하지 않고 CSS를 우선
- `prefers-reduced-motion`, `prefers-contrast`를 고려
