---
title: "브라우저·기기 매트릭스"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
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

- 은총쌤 실제 iPhone
- Safari 또는 홈화면 바로가기
- 사진 선택과 업로드
- 2FA
- 공지 작성
- published/archived 변경
- HEIC 파일
- 긴 본문과 키보드

후속 `/admin` UI는 실제 iPhone에서 login, CSRF, form validation과 image upload를 검증한 뒤 운영에 사용한다.

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
