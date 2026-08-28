---
title: "디자인 방향"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "브랜드 방향 변경 시"
---

# 디자인 방향

## 컨셉

`Warm Editorial Pet Salon`

전형적인 캐릭터·발바닥 중심의 애견 사이트보다, 20대 원장이 운영하는 감도 있는 1:1 소형 펫살롱의 인상을 만든다.

## 시각 원칙

- 따뜻하지만 유아적이지 않다.
- 사진이 중심이고 장식은 보조다.
- 넓은 여백과 큰 타이포그래피를 사용한다.
- 과도한 그라디언트, 글래스모피즘, 3D 효과는 사용하지 않는다.
- 둥근 모서리는 일관된 반경 체계로 제한한다.
- 브라운 텍스트가 아이보리 배경에서 충분한 대비를 갖게 한다.

## 초기 컬러 토큰 후보

실제 대비 검증 후 확정한다.

```css
--color-bg: #F8F4ED;
--color-surface: #FFFDF9;
--color-beige: #E7D7C6;
--color-brown: #765B46;
--color-text: #2D2722;
--color-muted: #6F675F;
--color-border: #D8CABC;
--color-danger: #9B3A32;
```

색상값은 브랜드 초안이며 접근성 테스트를 통과하지 못하면 변경한다.

## 타이포그래피

- 한글 본문은 가독성이 높은 산세리프를 사용한다.
- Pretendard Variable 또는 검증된 시스템 폰트 스택을 후보로 둔다.
- 폰트 파일은 라이선스와 로딩 성능을 확인한 방식으로 제공한다.
- 본문 최소 16px를 기본으로 한다.
- 영문 소제목은 장식 목적의 대문자를 제한적으로 사용한다.
- 이미지 안에 핵심 텍스트를 넣지 않는다.

## 주요 컴포넌트

- Hero image with subtle scale
- Trust chip
- Horizontal breed filter
- Gallery card
- Accessible lightbox dialog
- Service accordion
- Notice list
- Map link buttons
- Sticky mobile CTA
- Groomer editorial block

## 브랜드 차별화

- 은총쌤을 익명의 매장 운영자가 아니라 브랜드 신뢰 요소로 보여준다.
- 실제 시술사진과 운영 방식이 디자인보다 앞선다.
- 검증되지 않은 `무스트레스`, `안전 보장`, `전문 치료` 같은 표현은 사용하지 않는다.
