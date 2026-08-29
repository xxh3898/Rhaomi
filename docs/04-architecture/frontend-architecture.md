---
title: "프론트엔드 아키텍처"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-30"
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
- `/admin/`의 session 확인·로그인·로그아웃 인증 셸
- `/admin/`의 dashboard navigation, private media manager와 shop settings form
- active private media를 선택하는 reusable single media picker

Client boundary는 가장 작은 상호작용 단위에 둔다.

## 관리자 Static Client

`/admin/`도 `out/admin/index.html`로 export한다. route HTML은 공개 정적 파일이므로 숨김 경로나 client-side redirect를 접근제어로 사용하지 않는다. 실제 업무 데이터와 mutation은 Spring Security session·CSRF가 최종 방어한다.

```text
/admin/ hydration
→ GET /api/admin/auth/me
→ 기존 session이면 fresh GET csrf
→ anonymous이면 pre-login GET csrf → POST login → identity 검증
→ password·form email 제거 + pre-login csrf 폐기
→ React credential 제거 반영 뒤 fresh GET csrf
→ fresh csrf 성공 뒤에만 dashboard shell
→ fresh csrf로 POST logout
```

- API base는 상대경로 `/api/admin/**`만 사용하고 browser host·port env를 만들지 않는다.
- 모든 request는 `credentials: "same-origin"`, read request는 `cache: "no-store"`다.
- JSON shape와 status를 공통 client에서 검증하고 backend raw message를 UI에 전달하지 않는다.
- password와 CSRF는 필요한 동안 memory에만 두며 localStorage, sessionStorage, IndexedDB, cookie 직접 쓰기, URL과 log에 저장하지 않는다.
- post-login fresh CSRF 실패는 invalid credential이나 anonymous로 바꾸지 않는다. 명시적 재시도에서 `/me`로 이미 생성된 session을 확인하고 fresh CSRF를 다시 준비한다.
- admin API의 401은 in-memory 인증 상태를 비우고, 403 mutation은 자동 재시도하지 않는다.
- 공통 admin transport는 authenticated JSON GET, CSRF-protected JSON·multipart mutation과 authenticated image Blob GET만 제공한다. media feature가 CSRF store나 인증 client를 별도로 만들지 않는다.
- media preview는 authenticated GET의 JPEG/PNG content-type을 검증해 object URL을 만들고 item 교체·refresh·unmount에서 revoke한다. `IntersectionObserver`로 현재 viewport 근처 항목만 bounded fetch한다.
- 현재 dashboard는 매장정보와 미디어가 enabled이고 갤러리·공지·견종·서비스는 disabled placeholder다. 별도 route·query/hash authority나 fake data를 만들지 않는다.
- shop settings는 404를 빈 미초기화 form으로 처리하고 26개 mutable key를 명시한 full PUT 한 번만 보낸다. nullable input은 `null`로 보내며 server audit key는 form/request에 포함하지 않는다.
- Hero·미용사·OG는 현재 활성 slot의 relation 바로 아래에 한 inline media picker만 공유한다. picker를 열면 첫 내부 control로 focus를 이동하고, 닫기·선택 완료 후에는 해당 slot의 원래 trigger로 복귀한다. active asset만 새 선택 option이며 archived/missing 기존 relation은 field에 남겨 clear/replace 필요 상태를 표시한다.
- shop save 중 form과 picker를 잠그고 성공 response를 canonical state로 적용한다. ready form의 background refresh·auto-save·mutation retry는 없다.
- 현재 관리 홈 이동에는 unsaved-change 확인을 두지 않으므로 저장하지 않은 shop form 변경은 화면 전환 시 폐기된다. router/blocker infrastructure 없이 유지하는 알려진 UX 제한이며 운영자는 저장 완료 feedback을 확인한 뒤 이동해야 한다.
- upload `accept`와 20 MiB client check는 UX 보조이며 실제 type·size·decoder validation authority는 backend다.
- mutation 403은 CSRF를 폐기하되 자동 재실행하지 않는다. 다음 명시적 사용자 action에서 fresh CSRF를 획득하고 mutation을 한 번만 보낸다.

## 금지

- `cookies()`, `headers()` 등 요청별 API 의존
- Server Actions
- Next API Routes 또는 Route Handler 기반 운영 API
- 런타임 dynamic rendering
- 공개 고객 bundle에서 관리/build API credential 사용
- 고객 페이지 최초 렌더 뒤 backend fetch로 핵심 콘텐츠 주입
- 검색봇 user-agent에 따라 다른 콘텐츠 제공

## 콘텐츠 입력

```text
Spring Boot read-only build API
→ sync script
→ validated content snapshot
→ Next build
→ static HTML
```

페이지 컴포넌트는 backend network 호출이 아니라 검증된 로컬 snapshot을 읽는다.

## 라우트

### `/`

- 모든 핵심 섹션을 HTML에 포함
- 갤러리 전체 데이터가 과도하면 초기 카드만 표시하고 나머지는 같은 정적 JSON chunk에서 hydrate할 수 있으나, 검색 가치가 있는 설명은 HTML에 남긴다.

### `/notice/[slug]/`

- 공개 공지를 `generateStaticParams`로 생성
- 빌드에 없는 slug는 정적 404
- canonical과 페이지별 Open Graph 생성
- 공지가 보관되면 다음 빌드에서 URL이 사라지고 sitemap에서 제외

### `/admin/`

- Static Export와 `noindex, nofollow, noarchive`
- client hydration 뒤에만 session을 확인
- 공개 navigation과 sitemap에 링크하지 않음
- backend session이 없으면 업무 API 접근 불가
- 같은 Static Export route 안에서 관리 홈·매장정보·미디어 화면을 전환하며 refresh 후에는 session 확인을 거쳐 관리 홈에서 다시 시작할 수 있음

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
