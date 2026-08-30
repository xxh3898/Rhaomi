---
title: "정보 구조"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
review_trigger: "페이지·섹션 순서 변경 시"
---

# 정보 구조

## 공개 URL

### 1차 필수

| URL | 목적 | 생성 방식 |
|---|---|---|
| `/` | 메인 랜딩페이지 | 정적 |
| `/notice/[slug]/` | 공개 공지 상세 | `generateStaticParams` 기반 정적 |
| `/404.html` | 잘못된 URL 안내 | 정적 |

### 조건부 또는 이후 단계

| URL | 조건 |
|---|---|
| `/grooming/` | 홈 갤러리 이상의 탐색 수요가 확인될 때 |
| `/grooming/[breed-slug]/` | 견종별 공개 사진과 고유 설명이 충분할 때 |
| `/rss.xml` | 공지 운영이 안정화되고 네이버 최신글 수집 활용이 필요할 때 |

## 관리자 URL

| URL | 목적 | 생성 방식 |
|---|---|---|
| `/admin/` | session 기반 관리자 login·관리 홈·매장정보·미디어·견종·서비스 UI | Static Export client shell |

`/admin/`은 공개 navigation과 sitemap에 링크하지 않고 `noindex, nofollow, noarchive`를 유지한다. 관리 홈, 매장정보 form, 미디어·견종·서비스 manager는 query/hash나 추가 route 없이 같은 client state에서 전환한다. 갤러리·공지만 `준비 중` disabled 상태다.

### 현재 관리자 화면 구조

```text
관리 홈
├── 매장정보
│   ├── 기본 정보
│   ├── 영업·주차
│   ├── Hero + private media picker
│   ├── 미용사 소개 + private media picker
│   ├── 예약 안내
│   ├── 외부 채널
│   └── OG private media picker
├── 미디어
    ├── 단일 upload
    ├── active/archived 목록·private preview
    └── archive/restore
├── 견종
│   ├── draft 생성과 immutable slug
│   └── status/name/description/sortOrder 전체 수정
└── 서비스
    ├── draft 생성과 immutable slug
    └── status/name/description/priceText/sortOrder 전체 수정
```

private media picker는 별도 URL이나 public asset route를 만들지 않고 active asset의 authenticated Blob preview를 사용한다.

## 홈 섹션 순서

```text
Header
Hero
Trust Chips
Gallery
Groomer
Services
Reservation Guide
Notices
Location & Hours
Final CTA
Footer
Sticky Mobile CTA
```

## 섹션 목적

### Header

- 워드마크
- 섹션 이동
- 데스크톱 문의 CTA
- 모바일에서는 복잡한 메뉴를 만들지 않는다.

### Hero

- 라오미펫이 무엇인지 첫 화면에서 설명
- 용인 처인구, 예약제, 1:1 맞춤 미용의 핵심 정보
- 대표 이미지
- 예약 문의와 전화

### Trust Chips

- `1:1 예약제`
- `주차 가능`
- `10:00–19:00`
- 정보가 확정되지 않으면 노출하지 않는다.

### Gallery

- 실제 시술 결과
- 견종 필터
- 사진 상세
- 공개 이미지가 없을 때는 빈 갤러리 대신 섹션 전체를 숨기거나 명시적 준비 상태를 표시한다.

### Groomer

- 은총쌤 사진과 소개
- 미용 철학과 상담 방식
- 검증되지 않은 경력·자격은 표시하지 않는다.

### Services

- 전체미용, 부분미용, 목욕, 위생미용, 스파/케어
- 가격 미정이면 `상담 후 안내`
- 견종·모질·엉킴에 따라 달라질 수 있음을 안내

### Reservation Guide

- 예약제 운영
- 반려견 특이사항 사전 상담
- 정책 확정 전에는 `정확한 정책은 상담 시 안내` 문구 사용

### Notices

- 최근 공개 공지
- 고정 공지 우선
- 상세 URL 제공
- 임시휴무 등 시간 민감 정보는 만료일 사용 가능

### Location

- 주소, 영업시간, 휴무, 주차
- 네이버지도, 카카오맵 외부 링크
- 지도 iframe은 사용하지 않는다.

## 네비게이션 원칙

- 홈 섹션 앵커는 `#gallery`, `#services`, `#notice`, `#location`처럼 안정적으로 유지한다.
- 앵커 이동 시 고정 헤더 높이를 고려한다.
- 필터 상태는 URL을 변경하지 않아도 되지만, 견종별 SEO 페이지가 생기면 링크를 별도로 제공한다.
