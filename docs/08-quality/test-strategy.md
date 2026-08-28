---
title: "테스트 전략"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "기술·기능 범위 변경 시"
---

# 테스트 전략

## 목표

- 정적 사이트 생성 계약 보호
- CMS 데이터 오류의 공개 유출 방지
- 모바일 문의 흐름 보호
- 검색 메타데이터 보호
- 배포 실패 시 기존 사이트 보호
- 운영자 CRUD 검증

## 계층

### 단위 테스트

- URL validation
- phone link 변환
- 상태·만료 필터
- 정렬
- slug/canonical 생성
- LocalBusiness JSON-LD 생성
- alt validation
- image manifest 변환
- content snapshot schema

### 컴포넌트 테스트

- 견종 필터
- 갤러리 빈 상태
- lightbox focus
- 서비스 아코디언
- 공지 목록
- 조건부 CTA
- sticky CTA
- reduced motion class/behavior

### 통합 테스트

- Directus fixture → snapshot
- 관계 resolve
- draft/archived 제외
- 파일 다운로드
- 이미지 변환
- notice static params
- sitemap generation
- build failure conditions

### E2E

- 홈 → 갤러리 필터 → 상세 → 문의
- 홈 → 공지 → 상세 → 홈
- 홈 → 지도 링크
- 전화 CTA href
- 네이버톡톡 값 유무
- 모바일 sticky CTA
- 404
- keyboard only

### 접근성

- axe
- heading
- landmark
- focus visible
- dialog focus trap/restore
- button accessible name
- contrast
- zoom/reflow
- reduced motion
- VoiceOver 표본

### 배포 테스트

- content publish
- content archive
- build hook auth
- debounce
- build lock
- failed build does not switch
- atomic switch
- rollback
- stale build ordering

## 테스트 데이터

- 모든 필수 서비스
- 4개 이상 견종
- 각 견종 공개/초안/보관 사진
- 고정 공지
- 일반 공지
- 만료 공지
- 빈 선택형 URL
- 긴 제목과 긴 견종명
- portrait/landscape 이미지
- 손상 이미지
- HEIC 실제 iPhone 파일

## CI 게이트

PR:

- typecheck
- lint
- unit/component
- static build with deterministic fixture
- internal link
- accessibility smoke
- docs link

Release:

- 실제 CMS read-only snapshot
- image pipeline
- E2E
- SEO
- Nginx preview
- actual device checklist
- rollback evidence
