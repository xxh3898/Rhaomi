---
title: "접근성"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "UI 컴포넌트 또는 WCAG 기준 변경 시"
---

# 접근성

## 목표

공개 사이트는 WCAG 2.2 AA를 목표로 한다. 법적 적합성 인증을 의미하지 않으며, 자동 검사와 수동 검사를 함께 수행한다.

## 구조

- `<html lang="ko">`
- 페이지마다 고유한 `<title>`
- `header`, `nav`, `main`, `section`, `footer` 사용
- 의미 있는 H1 하나와 순차적인 heading
- `main`으로 이동하는 skip link
- 링크와 버튼을 역할에 맞게 구분

## 키보드

- 모든 버튼, 필터, 아코디언, 모달, 외부 링크에 접근 가능
- 논리적인 포커스 순서
- 명확한 `:focus-visible`
- 포커스 트랩은 모달이 열린 동안에만 사용
- 갤러리 스크롤이 키보드 사용자를 가두지 않음

## 텍스트와 색상

- 일반 본문은 최소 4.5:1 대비를 목표로 한다.
- 큰 텍스트는 기준을 별도로 검증한다.
- 브라운·베이지 조합을 감성만으로 승인하지 않는다.
- 글자 확대 200%, 브라우저 zoom 400%에서 주요 기능이 유지되어야 한다.
- 상태는 색상과 텍스트 또는 아이콘을 함께 사용한다.

## 터치

- 내부 목표는 핵심 대상 44×44 CSS px 이상이다.
- 인접한 필터 버튼의 오탭을 방지할 간격을 둔다.
- sticky CTA는 iOS safe area를 고려한다.
- drag 전용 기능을 만들지 않는다.

## 이미지

- 정보 이미지에 실제 내용을 설명하는 alt
- 장식 이미지에는 `alt=""`
- 배경 이미지에만 핵심 정보를 담지 않음
- Before/After에는 각각의 상태를 구분하는 텍스트 제공
- 이미지 확대 기능이 없어도 본문 의미를 이해할 수 있어야 함

## 모션

```css
@media (prefers-reduced-motion: reduce) {
  /* 비필수 transition과 animation 제거 */
}
```

- 깜빡임, 자동 이동, 반복 애니메이션 금지
- 스크롤 애니메이션은 모션 감소 환경에서 `auto`
- 모션이 기능 완료를 기다리게 만들지 않음

## 콘텐츠

- `여기를 클릭` 대신 목적이 드러나는 링크명 사용
- 날짜는 문맥이 명확한 형식 사용
- 전화번호는 화면 표시와 accessible name이 일치
- 외부 사이트 이동을 필요한 경우 안내
- 에러나 빈 상태는 원인과 다음 행동을 함께 제시

## 관리자 인증 셸

- `/admin/`은 의미 있는 H1 하나와 email·password의 visible label을 제공한다.
- email은 `autocomplete="username"`, password는 `autocomplete="current-password"`를 사용한다.
- session 확인과 로그인·로그아웃 pending 상태를 텍스트와 `aria-live`/`aria-busy`로 전달한다.
- 로그인·validation 실패는 `role="alert"`로 알리고 password를 지운 뒤 password input으로 focus를 이동한다.
- 재시도·로그인·로그아웃은 native button/form으로 Enter와 키보드만으로 동작한다.
- 준비 중 관리 영역은 disabled button과 텍스트를 함께 사용해 실제 동작으로 오인시키지 않는다.
- touch target은 최소 44 CSS px, layout은 320px과 iOS safe area를 기준으로 한다.
- spinner animation은 `prefers-reduced-motion`에서 제거한다.

## 검증

- axe 자동 검사
- Lighthouse 접근성
- 키보드만으로 전체 여정
- iOS VoiceOver 기본 탐색
- Android TalkBack 또는 동등한 검사 1회 이상
- 320px reflow
- reduced motion
- 고대비 또는 색각 시뮬레이션

DOM component test는 label, live alert, focus 복구, pending 중복 제출, keyboard login/logout과 disabled 영역을 검증한다. 실제 iOS VoiceOver·iPhone Safari와 contrast 수동 검증은 출시 gate로 남긴다.
