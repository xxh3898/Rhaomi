---
title: "의사결정 로그"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "의사결정 추가·변경 시"
---

# 의사결정 로그

| ID | 결정 | 상태 | 기준 문서 |
|---|---|---|---|
| ADR-001 | 공개 프론트엔드는 Next.js Static Export를 사용한다. | 승인 | [ADR-001](../09-decisions/ADR-001-nextjs-static-export.md) |
| ADR-002 | CMS는 Directus, DB는 PostgreSQL을 사용한다. | 대체됨 | [ADR-002](../09-decisions/ADR-002-directus-postgresql.md) |
| ADR-003 | 공개 콘텐츠 변경은 정적 사이트 재빌드를 유발한다. | 승인 | [ADR-003](../09-decisions/ADR-003-static-publish-on-content-change.md) |
| ADR-004 | 원본 이미지는 backend 소유 storage에 보관하고 공개용 파생본만 정적 배포한다. | 승인 | [ADR-004](../09-decisions/ADR-004-static-media-copy.md) |
| ADR-005 | 1차 관리자 UI는 Directus Data Studio를 사용한다. | 대체됨 | [ADR-005](../09-decisions/ADR-005-directus-admin-first.md) |
| ADR-006 | 운영자 삭제는 `archived` 전환으로 처리한다. | 승인 | [ADR-006](../09-decisions/ADR-006-soft-delete.md) |
| ADR-007 | 자체 예약·문의 폼은 만들지 않는다. | 승인 | [ADR-007](../09-decisions/ADR-007-external-contact-only.md) |
| ADR-008 | 공개 사이트는 관리 backend 장애와 무관하게 서비스되어야 한다. | 승인 | [ADR-008](../09-decisions/ADR-008-runtime-independent-public-site.md) |
| ADR-009 | 관리 backend는 Spring Boot + PostgreSQL과 서버 세션 인증을 사용한다. | 승인 | [ADR-009](../09-decisions/ADR-009-spring-boot-backend-admin.md) |

## 변경 규칙

- 승인된 결정을 뒤집을 때는 기존 ADR을 지우지 않는다.
- 기존 ADR을 `superseded`로 바꾸고 새 ADR에서 대체 이유를 설명한다.
- 구현 편의만으로 제품·보안 결정을 암묵적으로 바꾸지 않는다.
