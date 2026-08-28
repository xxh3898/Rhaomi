---
title: "문서 인덱스"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "문서 추가·이동 시"
---

# 문서 인덱스

## 상태 정의

| 상태 | 의미 |
|---|---|
| `approved` | 현재 구현과 의사결정의 기준 |
| `proposed` | 구현 전 검토가 필요한 권장안 |
| `draft` | 외부 정보 또는 운영 확인이 남은 문서 |
| `deprecated` | 더 이상 기준이 아니며 대체 문서를 명시해야 함 |

## 00. 거버넌스

- [문서 작성 규칙](00-governance/document-conventions.md)
- [의사결정 로그](00-governance/decision-log.md)
- [용어집](00-governance/glossary.md)
- [완료 정의](00-governance/definition-of-done.md)
- [브랜치·릴리스 정책](00-governance/branch-and-release-policy.md)
- [공식 참고자료](00-governance/references.md)

## 01. 제품

- [제품 개요](01-product/product-brief.md)
- [범위](01-product/scope.md)
- [사용자 여정](01-product/user-journeys.md)
- [기능 요구사항](01-product/functional-requirements.md)
- [비기능 요구사항](01-product/non-functional-requirements.md)
- [성공 지표](01-product/success-metrics.md)
- [로드맵](01-product/roadmap.md)
- [미확정 항목](01-product/open-items.md)

## 02. 콘텐츠

- [정보 구조](02-content/information-architecture.md)
- [기준 매장정보](02-content/shop-data-baseline.md)
- [문구 초안](02-content/copy-deck.md)
- [콘텐츠 소유권](02-content/content-ownership.md)
- [미디어 가이드](02-content/media-guidelines.md)
- [관리자 콘텐츠 규칙](02-content/admin-content-rules.md)

## 03. 디자인

- [디자인 방향](03-design/design-direction.md)
- [반응형 레이아웃](03-design/responsive-layout.md)
- [인터랙션](03-design/interactions.md)
- [접근성](03-design/accessibility.md)

## 04. 아키텍처

- [시스템 컨텍스트](04-architecture/system-context.md)
- [컨테이너 구조](04-architecture/container-architecture.md)
- [저장소 구조](04-architecture/repository-structure.md)
- [프론트엔드 구조](04-architecture/frontend-architecture.md)
- [CMS 데이터 모델](04-architecture/cms-data-model.md)
- [API·빌드 계약](04-architecture/api-and-build-contract.md)
- [정적 퍼블리싱 파이프라인](04-architecture/static-publishing-pipeline.md)
- [이미지 파이프라인](04-architecture/image-pipeline.md)
- [환경설정](04-architecture/environment-and-configuration.md)

## 05. SEO

- [SEO 전략](05-seo/seo-strategy.md)
- [메타데이터·구조화 데이터](05-seo/metadata-and-structured-data.md)
- [검색엔진 등록](05-seo/search-engine-registration.md)
- [SEO 출시 체크리스트](05-seo/seo-release-checklist.md)

## 06. 보안·법적 검토

- [위협 모델](06-security/threat-model.md)
- [접근제어](06-security/access-control.md)
- [비밀값·데이터 보호](06-security/secrets-and-data-protection.md)
- [개인정보·법적 검토](06-security/privacy-and-legal.md)
- [의존성·라이선스 정책](06-security/dependency-and-license-policy.md)

## 07. 운영

- [배포](07-operations/deployment.md)
- [백업·복구](07-operations/backup-and-restore.md)
- [모니터링·장애 대응](07-operations/monitoring-and-incident-response.md)
- [콘텐츠 운영 런북](07-operations/content-operations-runbook.md)
- [롤백](07-operations/rollback.md)
- [정기 유지보수](07-operations/maintenance.md)

## 08. 품질

- [테스트 전략](08-quality/test-strategy.md)
- [수용 기준](08-quality/acceptance-criteria.md)
- [성능 예산](08-quality/performance-budget.md)
- [브라우저·기기 매트릭스](08-quality/browser-device-matrix.md)
- [콘텐츠 배포 테스트](08-quality/content-publishing-tests.md)
- [출시 체크리스트](08-quality/release-checklist.md)

## 09. ADR

- [ADR-001: Next.js Static Export](09-decisions/ADR-001-nextjs-static-export.md)
- [ADR-002: Directus + PostgreSQL](09-decisions/ADR-002-directus-postgresql.md)
- [ADR-003: 콘텐츠 변경 시 정적 재빌드](09-decisions/ADR-003-static-publish-on-content-change.md)
- [ADR-004: 원본 미디어와 정적 파생본 분리](09-decisions/ADR-004-static-media-copy.md)
- [ADR-005: Directus 관리자 UI 우선](09-decisions/ADR-005-directus-admin-first.md)
- [ADR-006: 운영 삭제는 보관 처리](09-decisions/ADR-006-soft-delete.md)
- [ADR-007: 자체 예약·문의 폼 제외](09-decisions/ADR-007-external-contact-only.md)
- [ADR-008: 공개 사이트의 CMS 런타임 독립](09-decisions/ADR-008-runtime-independent-public-site.md)

## 10. 템플릿

- [ADR 템플릿](10-templates/adr-template.md)
- [기능 명세 템플릿](10-templates/feature-spec-template.md)
- [릴리스 증거 템플릿](10-templates/release-evidence-template.md)
- [장애 보고 템플릿](10-templates/incident-template.md)
- [콘텐츠 변경 템플릿](10-templates/content-change-template.md)
- [테스트 증거 템플릿](10-templates/test-evidence-template.md)
