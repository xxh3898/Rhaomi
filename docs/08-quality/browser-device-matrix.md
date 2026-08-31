---
title: "브라우저·기기 매트릭스"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-31"
review_trigger: "유입 채널·지원 범위 변경 시"
---

# 브라우저·기기 매트릭스

## 최우선

| 환경 | 이유 | 검증 |
|---|---|---|
| iPhone Safari 현재·직전 주요 버전 | 운영자와 다수 모바일 고객 | 전체 고객 흐름 |
| Instagram iOS in-app browser | 주요 유입 | CTA, sticky, 외부 이동 |
| 네이버 iOS in-app browser | 검색·블로그·지도 유입 | 페이지, 전화, 지도 |
| Android Chrome 현재 버전 | 모바일 범위 | 전체 고객 흐름 |
| Instagram Android in-app browser | 주요 유입 | 핵심 CTA |
| 네이버 Android in-app browser | 검색 유입 | 핵심 CTA |

## 데스크톱

- Chrome current
- Safari current
- Edge current
- Firefox current는 핵심 흐름 표본 검증

## 관리자

현재 자동화 범위:

- 320px 기준 layout contract와 safe area CSS
- visible label, Enter submit, focus 복구와 keyboard login/logout DOM test
- same-origin gateway의 session·CSRF login/me/logout HTTP smoke
- password/CSRF browser persistence 금지 정적 검사
- 매장정보·갤러리·미디어·견종·서비스·공지 dashboard navigation과 same-page 관리 홈 복귀 component test
- shop GET loading·404 미초기화·error retry, full form control과 200/201 canonical save test
- Hero·미용사·OG relation 바로 아래의 active-only single media picker, slot 전환 시 단일 instance, Enter open·내부 focus 진입·close/selection trigger 복귀 component test
- archived/missing relation, clear·same-media reuse와 Hero·미용사 alt pair test
- 미디어 filter·upload·archive/restore native control과 aria state component test
- 20 MiB client 차단, multipart boundary, private Blob preview lazy load·object URL revoke와 401/403 test
- 견종·서비스 loading/empty/error/refresh, draft 생성, immutable slug, full PUT, published/archive/restore, server order 보존과 mutation 뒤 canonical GET component test
- inline create/edit의 Enter open·첫 input focus·취소 즉시 복귀·성공 뒤 canonical refresh ready 시 enabled trigger 복귀, pending 중복 submit·refresh 경쟁 차단과 401/403 non-retry test
- 갤러리 loading/empty/error/refresh, draft 생성·full PUT·published/archive/restore, server order 보존과 post-mutation canonical GET·stale generation test
- breed·service·media catalog 독립 오류/재시도, draft/archived 모든 상태 관계와 published 유효 관계, cover 재사용·before/after 상호 배제 test
- cover/before/after relation 직후의 active/archived single picker, Enter open·내부 focus·close/selection trigger 복귀와 private Blob preview revoke test
- 공지 loading/empty/error/refresh, draft 생성·full PUT·published/archive/restore, backend order 보존과 post-mutation canonical GET·stale generation test
- source-only Markdown, pinned, immutable slug, published/expiry window, unchanged microsecond 보존, 401/403 non-retry와 create/edit focus lifecycle test

출시 전 실제 기기 범위:

- 은총쌤 실제 iPhone
- Safari 또는 홈화면 바로가기
- 사진 선택과 업로드
- 매장정보 전체 form 입력·저장과 긴 주소·외부 URL
- Hero·미용사·OG picker, archived/missing relation 정정
- 견종·서비스 생성·수정·게시·보관·복구와 긴 이름·slug·priceText
- 갤러리 생성·수정·게시·보관·복구, 관계 상태 정정, local datetime과 긴 alt/summary
- WebAuthn/passkey 2차 인증과 recovery code 복구 흐름
- 공지 작성
- pinned·publishedAt·expiresAt과 published/archived 변경, 긴 Markdown source
- HEIC 파일
- 긴 본문과 키보드

현재 `/admin/` 인증 셸과 미디어·매장정보·견종·서비스·갤러리·공지 관리 UI는 실제 iPhone Safari·VoiceOver에서 아직 검증하지 않았다. 사진 보관함의 HEIC 선택·upload·preview, shop time/radio/url form과 picker, 견종·서비스·갤러리·공지 form의 keyboard·reflow, relation picker·datetime-local·긴 Markdown·session cookie와 archive/restore를 실제 기기에서 확인하고 WebAuthn/passkey 2차 인증·TLS gate를 충족한 뒤 운영에 사용한다.

## 화면

- 320×568
- 375×667
- 390×844
- 430×932
- 768×1024
- 1024×768
- 1440×900

## 설정

- light mode
- dark mode에서 강제 색상 문제가 없는지
- reduced motion
- 200% text zoom
- 400% browser zoom/reflow 표본
- iOS safe area
- 가로 모드
- 느린 4G/저전력 환경 표본

## 제외

Internet Explorer는 지원하지 않는다. 오래된 브라우저를 위해 접근성·보안·성능을 저해하는 polyfill을 광범위하게 추가하지 않는다.
