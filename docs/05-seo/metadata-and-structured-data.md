---
title: "메타데이터·구조화 데이터"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-31"
review_trigger: "도메인·매장정보·URL 구조 변경 시"
---

# 메타데이터·구조화 데이터

## 현재 구현 경계

홈과 generated 공개 공지 상세의 title·description·canonical·Open Graph, 홈 LocalBusiness JSON-LD, robots와 sitemap을 Static Export에 구현했다. 값은 Build Snapshot V2와 fail-closed `PUBLIC_SITE_URL`에서 생성하고 final release validator가 absolute HTTPS canonical, sitemap URL, admin 제외와 내부/private URL 부재를 검증한다. 실제 production domain, favicon/app icon, 최종 OG 사진·문구와 Rich Results 실서비스 증거는 미확정 출시 항목이다.

## 홈 기준

```text
title:
라오미펫 | 용인 처인구 애견미용

description:
용인 처인구에 위치한 예약제 애견미용실 라오미펫입니다. 반려견을 위한 편안한 1:1 맞춤 미용 공간을 제공합니다.
```

최종 문구는 출시 전 은총쌤이 승인한다.

## 필수 메타데이터

- `title`
- `description`
- canonical absolute URL
- `robots`
- Open Graph title
- Open Graph description
- Open Graph URL
- Open Graph image
- Open Graph locale `ko_KR`
- favicon과 app icons
- theme color는 브라우저 호환성을 확인해 선택

## Open Graph

- 기본 대표 이미지: 1200×630 내부 기준
- 실제 라오미펫 사진과 읽을 수 있는 브랜드 문구 사용
- 이미지 절대 URL
- 파일 공개 접근 가능
- 변경 후 카카오톡 등 공유 플랫폼의 캐시 때문에 즉시 갱신되지 않을 수 있음을 운영자가 인지
- 페이지별 공지 이미지를 제공하지 않으면 기본 OG 이미지 사용

## 공지 페이지

```text
<title>{공지 제목} | 라오미펫</title>
```

description 우선순위:

1. `summary`
2. 본문에서 안전하게 추출한 짧은 텍스트
3. 기본 사이트 description

## canonical

- 최종 HTTPS 도메인 하나만 사용
- trailing slash 정책을 일관되게 적용
- query 기반 갤러리 필터는 홈 canonical 유지
- 네이버·카카오 URL은 canonical 대상이 아님
- 개발·관리자 hostname은 운영 sitemap에 포함하지 않음

## LocalBusiness JSON-LD

개념 예시:

```json
{
  "@context": "https://schema.org",
  "@type": "LocalBusiness",
  "name": "라오미펫",
  "url": "https://<domain>/",
  "image": "https://<domain>/generated/og/...",
  "telephone": "0507-1391-4900",
  "address": {
    "@type": "PostalAddress",
    "streetAddress": "중부대로1158번길 12 상가동 1층 104호",
    "addressLocality": "용인시 처인구",
    "addressRegion": "경기도",
    "addressCountry": "KR"
  },
  "openingHoursSpecification": [],
  "sameAs": [
    "https://www.instagram.com/rhaomipet",
    "https://m.blog.naver.com/rhaomipet"
  ]
}
```

## 구조화 데이터 원칙

- 화면에 보이는 사실만 마크업한다.
- 휴무일과 영업시간을 실제 정보에서 생성한다.
- `aggregateRating`, `review`, `priceRange`는 검증된 데이터가 있을 때만 사용한다.
- 실제 예약 기능이 없으므로 예약 action을 허위로 추가하지 않는다.
- Rich Results Test와 schema validator로 확인한다.
- 구조화 데이터가 리치 결과 노출을 보장하지 않는다.

## robots와 sitemap

### `robots.txt`

```text
User-agent: *
Allow: /
Sitemap: https://<domain>/sitemap.xml
```

공개 사이트 기준이며 관리자 subdomain은 별도 인증과 `X-Robots-Tag`를 사용한다.

### `sitemap.xml`

포함:

- 홈
- 공개·미만료 공지
- 기준을 충족한 후속 견종별 정적 페이지. Issue #43 sitemap에는 생성하지 않는다.

제외:

- draft
- archived
- expired notice
- query/filter 상태
- 관리자
- 404
- 중복 canonical
