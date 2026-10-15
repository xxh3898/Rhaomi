---
title: "브랜치·릴리스 정책"
status: "proposed"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-10-14"
review_trigger: "GitHub 운영 방식 확정 시"
---

# 브랜치·릴리스 정책

이 문서는 저장소와 운영 release에 적용할 목표 계약이다. 실제 설정의 존재와 정책 문서의 승인은 별개이므로 `proposed`를 유지한다. 아래 권장 보호 규칙을 현재 GitHub에서 모두 강제하는 것으로 해석하지 않는다.

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
- 운영 image는 `main`의 검증된 exact commit만 만들 수 있다.
- `main` merge와 production apply를 분리하고, merge만으로 Mac mini 배포를 시작하지 않는다.
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

### `production` environment — 목표 계약

- `main` exact SHA의 GitHub-hosted 검증과 immutable image build
- required reviewer의 수동 승인
- 승인 전 environment secret 접근 금지
- exact commit tag와 image digest를 release evidence에 기록
- Tailscale SSH와 고정·versioned deploy entrypoint만 허용
- production Mac mini에서 PR source build 금지

2026-10-14 GitHub metadata 조회에서 `main`·`dev`의 active `PR-only main-dev` ruleset, deletion·non-fast-forward 금지와 bypass actor 0을 확인했다. required status check 강제는 확인되지 않았으므로 위 검증 목표의 충족으로 표시하지 않는다. `production` Environment는 reviewer `xxh3898`, main-only branch policy, admin bypass 금지와 `prevent_self_review=false`로 존재한다. 이 snapshot은 Secret의 유효성, GHCR artifact, 실제 host deploy 또는 production acceptance 증거가 아니다. 설정 변경과 정책 승인은 별도 gate다.

## 머지 방식

- 기능 PR은 squash merge를 권장한다.
- Release PR은 적용 AGENTS.md에 따라 dev ancestry와 fast-forward back-sync를 보존하는 merge commit을 사용하고, merge 뒤 main→dev back-sync를 별도 승인 범위에서 확인한다.
- 커밋 메시지는 변경 목적이 식별되도록 작성한다.

## 배포 트리거

- 코드 release 후보: `main` 변경
- 코드 production apply: 검증된 exact SHA·digest에 대한 별도 수동 승인
- 콘텐츠 release 후보: Spring Boot가 같은 transaction에 기록한 publishing outbox
- 두 트리거 모두 동일한 `build → validate → atomic switch` 파이프라인으로 수렴한다.

[ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md)과 [ADR-011](../09-decisions/ADR-011-transactional-outbox-static-publisher.md)이 production release와 콘텐츠 publisher의 상세 계약을 정의한다. publisher DB state, dedicated non-web control loop, local/CI full release adapter와 workflow_dispatch-only Production Release workflow source는 구현됐다. workflow는 `validate_release → publish_image → deploy_production`이며 production Environment 승인은 마지막 deploy job에만 적용된다. Immutable GHCR publish와 production host deploy는 별도 승인 gate다. Source Release·Hosted CI와 actual image·credential·Mac runtime provisioning/acceptance는 [readiness](../07-operations/production-readiness.md)에서 분리한다.
