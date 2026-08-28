---
title: "이미지 파이프라인"
status: "proposed"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "미디어 형식·저장소 변경 시"
---

# 이미지 파이프라인

## 목표

- 운영자는 휴대전화 원본을 업로드할 수 있다.
- 공개 사이트는 작은 최적화 파일만 제공한다.
- 위치정보 등 원본 메타데이터가 공개되지 않는다.
- 이미지 크기 때문에 LCP와 데이터 사용량이 악화되지 않는다.
- 관리 backend 장애와 무관하게 이미지를 제공한다.

## 흐름

```text
휴대전화 원본
→ backend 소유 원본 storage
→ 빌더 인증 다운로드
→ 형식·크기 검증
→ EXIF orientation 적용
→ 메타데이터 제거
→ crop/focal point 적용
→ responsive variants
→ content-hashed filename
→ public/generated
→ static export
```

## 저장 정책

### 원본

- backend 소유 원본 image storage
- 공개 Nginx root 밖
- 백업 대상
- 운영자와 빌더만 접근
- 수정 시 새 파일 생성 권장

### 공개 파생본

- 정적 release 내부
- 재생성 가능
- 백업 필수 아님
- 원본 파일명 미사용
- content hash로 cache busting
- 오래된 release 정리 시 함께 삭제

## 초기 파생 규격

구현 중 실제 디자인에 맞춰 조정한다.

| 용도 | 폭 후보 | 형식 |
|---|---|---|
| 갤러리 카드 | 360, 640, 960 | AVIF, WebP, JPEG fallback |
| 갤러리 상세 | 768, 1200, 1600 | AVIF, WebP, JPEG fallback |
| Hero | 768, 1280, 1920 | AVIF, WebP, JPEG fallback |
| OG | 1200×630 | JPEG 또는 PNG |

- 원본보다 큰 이미지를 업스케일하지 않는다.
- 이미지마다 실제 `width`와 `height`를 manifest에 기록한다.
- `<picture>`와 `srcset`을 사용한다.
- Hero LCP 후보는 preload 여부를 검토한다.
- 아래쪽 이미지는 `loading="lazy"`를 사용한다.

## 처리 도구

- Node.js 기반 `sharp` 또는 동등한 검증된 도구
- 버전 고정
- 빌드 컨테이너에 필요한 codec 포함
- 실패를 무시하지 않음

## HEIC 출시 게이트

은총쌤이 iPhone을 사용할 가능성이 높으므로 실제 iPhone HEIC 업로드를 반드시 시험한다.

- 관리자 API 업로드 성공 여부
- 빌더 decode 여부
- orientation
- 색상 프로파일
- 변환 결과
- 실패 메시지

HEIC 처리가 검증되지 않으면 운영자에게 지원 형식을 안내하거나 업로드 시 변환 기능을 추가하기 전 출시하지 않는다.

## 개인정보

- GPS EXIF 제거
- 촬영기기 정보 제거
- 얼굴, 명찰, 차량번호, 집주소 등 배경 확인
- 원본 asset URL을 공개 HTML에 넣지 않음
- 원본 접근 토큰을 URL query에 포함하지 않음

## 파일 검증

- 허용 MIME allowlist
- 확장자와 실제 signature 비교
- 업로드 최대 크기
- 최소·최대 pixel 수
- 애니메이션 이미지 제한
- 손상 파일 decode test
- 압축 폭탄과 과도한 이미지 크기 방어

## 대체텍스트

- 콘텐츠 API의 `alt_text`를 사용
- 비어 있는 경우 공개 빌드 실패
- 파일명이나 키워드 목록으로 자동 대체하지 않음
- 장식적 파생본은 빈 alt 가능하나 같은 이미지가 정보 역할이면 명시적 alt 사용
