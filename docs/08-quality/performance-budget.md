---
title: "성능 예산"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "디자인·미디어·측정 기준 변경 시"
---

# 성능 예산

## 사용자 경험 목표

Google Core Web Vitals의 현재 권장 기준을 운영 목표로 사용한다.

- LCP ≤ 2.5초
- INP ≤ 200ms
- CLS ≤ 0.1
- 가능하면 모바일·데스크톱 75번째 백분위에서 충족

필드 데이터가 쌓이기 전에는 lab 테스트와 실제 저속 네트워크 검증을 사용한다.

## 초기 전송 예산

내부 목표:

| 자원 | 모바일 초기 예산 |
|---|---:|
| HTML gzip | 100 KiB 이하 |
| CSS gzip | 50 KiB 이하 |
| 초기 JS gzip | 120 KiB 이하 |
| Hero 이미지 | 250 KiB 이하 권장 |
| 초기 총 전송 | 1.2 MiB 이하 권장 |
| 웹폰트 총합 | 150 KiB 이하 권장 |

실제 이미지 품질을 확인하며 조정하되, 초과 시 PR에 이유와 대안을 기록한다.

## 이미지

- Hero만 eager/high priority 검토
- gallery below fold lazy
- 명시적 width/height 또는 aspect-ratio
- responsive source
- 원본 미제공
- content hash cache
- 첫 화면에 불필요한 gallery 전체 이미지 prefetch 금지

## JavaScript

- 필터·dialog·accordion에 필요한 코드만 Client Component
- animation library는 CSS/작은 observer로 해결 가능하면 추가하지 않음
- map/SNS SDK 금지
- analytics는 검증 전 제외
- barrel import로 bundle이 커지는지 확인

## 폰트

- 시스템 폰트 또는 필요한 weight만
- `font-display` 전략
- Hero 텍스트 FOIT 방지
- 한글 전체 glyph 파일 용량 확인
- 장식 폰트는 이미지 안 핵심 텍스트 대체 수단으로 사용하지 않음

## Lighthouse 목표

Release preview 모바일 기준:

- Performance 90 이상
- Accessibility 95 이상
- Best Practices 95 이상
- SEO 95 이상

점수는 실사용 성능의 전부가 아니다. 실패 시 원인과 실제 사용자 영향으로 판정한다.

## 회귀 방지

- build asset size report
- 초기 JS budget CI
- 이미지 manifest size 검증
- CLS 스크린샷 또는 Playwright 측정
- Search Console field data 월간 확인
