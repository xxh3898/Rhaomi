---
title: "프론트엔드 아키텍처"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-02"
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
- `/admin/`의 session 확인·password first factor·passkey 등록/assertion·recovery rotation·로그아웃 인증 셸
- `/admin/`의 dashboard navigation, private media manager, shop settings form과 견종·서비스 manager
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
→ React credential 제거 반영 뒤 fresh GET csrf → GET WebAuthn status
→ active passkey 0이면 create() 등록 → recovery code rotation·1회 표시
→ active passkey가 있으면 get() assertion
→ second-factor session ID 교체 뒤 이전 csrf 폐기 → fresh GET csrf
→ fresh csrf 성공 뒤에만 mutation-ready dashboard shell
→ fresh csrf로 POST logout
```

- API base는 상대경로 `/api/admin/**`만 사용하고 browser host·port env를 만들지 않는다.
- 모든 request는 `credentials: "same-origin"`, read request는 `cache: "no-store"`다.
- JSON shape와 status를 공통 client에서 검증하고 backend raw message를 UI에 전달하지 않는다.
- password, WebAuthn challenge/response, recovery code와 CSRF는 필요한 동안 memory에만 두며 localStorage, sessionStorage, IndexedDB, cookie 직접 쓰기, URL과 log에 저장하지 않는다.
- post-login fresh CSRF 실패는 invalid credential이나 anonymous로 바꾸지 않는다. 명시적 재시도에서 `/me`로 이미 생성된 session을 확인하고 fresh CSRF를 다시 준비한다.
- WebAuthn browser adapter는 canonical unpadded base64url만 수용하고 `navigator.credentials.create/get`의 ArrayBuffer 경계를 명시적으로 직렬화한다. ceremony failure를 password 오류·anonymous·session expiry로 바꾸거나 자동 재전송하지 않는다.
- initial registration이나 recovery verify가 server에서 성공한 뒤 recovery rotation이 실패하면 이전 first-factor 화면으로 되돌리지 않고 제한 상태에서 rotation만 명시적으로 재시도한다. 평문 recovery code는 새 set response에서 한 번만 표시하며 storage·URL·console에 쓰지 않는다.
- admin API의 401은 in-memory 인증 상태를 비우고, 403 mutation은 자동 재시도하지 않는다.
- 공통 admin transport는 authenticated JSON GET, CSRF-protected JSON·multipart mutation과 authenticated image Blob GET만 제공한다. media feature가 CSRF store나 인증 client를 별도로 만들지 않는다.
- media preview는 authenticated GET의 JPEG/PNG content-type을 검증해 object URL을 만들고 item 교체·refresh·unmount에서 revoke한다. `IntersectionObserver`로 현재 viewport 근처 항목만 bounded fetch한다.
- 현재 dashboard는 매장정보·갤러리·미디어·견종·서비스·공지가 모두 enabled다. 별도 route·query/hash authority나 fake data를 만들지 않는다.
- shop settings는 404를 빈 미초기화 form으로 처리하고 26개 mutable key를 명시한 full PUT 한 번만 보낸다. nullable input은 `null`로 보내며 server audit key는 form/request에 포함하지 않는다.
- Hero·미용사·OG는 현재 활성 slot의 relation 바로 아래에 한 inline media picker만 공유한다. picker를 열면 첫 내부 control로 focus를 이동하고, 닫기·선택 완료 후에는 해당 slot의 원래 trigger로 복귀한다. active asset만 새 선택 option이며 archived/missing 기존 relation은 field에 남겨 clear/replace 필요 상태를 표시한다.
- shop save 중 form과 picker를 잠그고 성공 response를 canonical state로 적용한다. ready form의 background refresh·auto-save·mutation retry는 없다.
- 견종·서비스 adapter와 strict response validator는 도메인별로 명시하고, shared `admin-content`에는 status·UUID·Instant·slug·sortOrder 입력 검증과 CSS primitive만 둔다. PostgreSQL ordering을 JavaScript locale comparator로 복제하거나 runtime schema-driven form·route config 기반 meta-CRUD를 만들지 않는다.
- 견종·서비스 생성은 빈 sortOrder를 null로 보내고 성공 response의 draft·backend default를 canonical state로 사용한다. 수정은 immutable slug를 읽기 전용으로 두고 status와 mutable field 전체를 저장 action 한 번의 PUT으로 보낸다.
- 서비스 published 선택 시 description·priceText를 client에서 보조 확인하되 backend의 `PUBLISH_VALIDATION_FAILED`를 최종 authority로 유지한다. archive는 삭제가 아니며 draft/published로 복구할 수 있다.
- 초기·명시적 목록 GET은 backend가 반환한 배열 순서를 그대로 적용한다. mutation 성공 response는 item canonical state로 반영한 뒤 post-mutation GET으로 전체 server ordering을 다시 획득한다.
- 목록 refresh와 mutation은 서로 잠그고 mutation 전 GET sequence를 무효화하며 post-mutation GET을 현재 authority로 삼아 stale overwrite를 막는다. post-mutation GET이 실패해도 저장 성공을 유지하고 목록 순서 refresh 경고와 explicit refresh를 제공한다. pending ref로 사용자 action당 POST/PUT 한 번만 허용하고 자동 저장·polling·mutation retry를 하지 않는다.
- create/edit inline panel은 열릴 때 첫 이름 input으로 focus를 옮기고 취소·성공 뒤 원래 생성 trigger나 item 수정 action으로 되돌린다.
- Gallery는 별도 adapter·strict validator와 inline manager를 사용한다. 초기 GET 배열은 backend ordering authority로 보존하고 create는 status 없는 draft, update는 nullable key를 포함한 full PUT 한 번만 보낸 뒤 canonical GET으로 전체 순서를 다시 받는다.
- Gallery의 breed·service·media catalog는 목록과 독립적으로 로드한다. 관계 조회 장애가 card를 지우지 않으며 retry 전에는 form mutation을 막는다. draft·archived는 존재하는 모든 상태의 관계를, published는 게시된 breed/service와 active media를 유효하게 안내하되 backend validation이 최종 authority다.
- Gallery cover/before/after control 바로 아래에는 active·archived 상태를 표시하는 inline picker 하나만 연다. open 시 첫 picker control로, close·selection 시 원 trigger로 focus를 이동하고 private Blob object URL을 교체·unmount에서 revoke한다.
- Gallery performedAt·publishedAt은 local datetime input과 ISO Instant 사이를 변환한다. 사용자가 바꾸지 않은 backend microsecond 값은 full PUT에서 그대로 보존하고 성공 response를 form canonical state로 적용한다.
- 공지는 별도 adapter·strict validator와 inline manager를 사용한다. create에는 status를 보내지 않고 update에는 immutable slug·audit를 제외한 mutable field 전체를 보낸다. source Markdown을 HTML로 렌더링하지 않으며 published 필수값과 상태 공통 window를 client에서 보조 검증하되 backend가 최종 authority다.
- Gallery와 Notice가 공유하는 `admin-content` timestamp helper는 local datetime과 UTC Instant를 변환하고, 사용자가 값을 바꾸지 않았을 때 backend microsecond 원본을 보존한다. Notice window 비교는 millisecond `Date` 비교가 아니라 canonical microsecond Instant를 사용한다.
- 공지 목록도 backend GET 배열을 그대로 authority로 사용하고 mutation response 선적용, post-mutation canonical GET, failure warning·explicit refresh, stale generation·중복 mutation 차단과 ready/enabled trigger focus 복귀를 동일하게 적용한다.
- 현재 관리 홈 이동에는 unsaved-change 확인을 두지 않으므로 저장하지 않은 shop·견종·서비스·갤러리·공지 form 변경은 화면 전환 시 폐기된다. router/blocker infrastructure 없이 유지하는 알려진 UX 제한이며 운영자는 저장 완료 feedback을 확인한 뒤 이동해야 한다.
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
- 같은 Static Export route 안에서 관리 홈·매장정보·미디어·견종·서비스 화면을 전환하며 refresh 후에는 session 확인을 거쳐 관리 홈에서 다시 시작할 수 있음

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
