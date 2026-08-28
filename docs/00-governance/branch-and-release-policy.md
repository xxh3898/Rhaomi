---
title: "브랜치·릴리스 정책"
status: "proposed"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "GitHub 운영 방식 확정 시"
---

# 브랜치·릴리스 정책

이 문서는 새 저장소에 적용할 권장 운영안이다. 실제 브랜치 보호 설정 후 `approved`로 전환한다.

## 브랜치

```text
feature/* ──PR──> dev ──Release PR──> main
```

- `main`: 운영 배포 기준
- `dev`: 통합 검증 기준
- `feature/*`, `fix/*`, `docs/*`, `ops/*`: Issue 단위 작업 브랜치

## 원칙

- Issue 하나에 PR 하나를 기본으로 한다.
- feature PR은 `dev`를 대상으로 한다.
- `main` 직접 커밋과 강제 푸시는 금지한다.
- `dev → main`은 의미 있는 릴리스 단위로 묶는다.
- 운영 배포는 `main`의 검증된 커밋만 허용한다.
- 콘텐츠 변경 배포는 코드 브랜치와 별개지만 동일한 배포 검증 스크립트를 사용한다.

## 권장 보호 규칙

### `dev`

- PR 필수
- 필수 상태 검사 통과
- 최신 base 반영
- 최소 1회 리뷰 또는 독립 AI 리뷰 증거

### `main`

- PR 필수
- Release checklist 첨부
- 정적 빌드, E2E, 보안·SEO 검증 필수
- 관리자 승인 후 merge
- force push와 branch deletion 금지

## 머지 방식

- 기능 PR은 squash merge를 권장한다.
- Release PR은 merge commit 또는 squash 중 하나로 프로젝트 초기에 통일한다.
- 커밋 메시지는 변경 목적이 식별되도록 작성한다.

## 배포 트리거

- 코드 배포: `main` 변경
- 콘텐츠 배포: Spring Boot에서 승인된 공개 콘텐츠 변경
- 두 트리거 모두 동일한 `build → validate → atomic switch` 파이프라인으로 수렴한다.
