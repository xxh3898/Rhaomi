---
title: "Rhaomi 프로젝트 문서 패키지"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "프로젝트 구조 또는 핵심 범위 변경 시"
---

# Rhaomi

라오미펫 애견미용실의 실제 영업용 모바일 중심 랜딩페이지 프로젝트다.

- 저장소: `xxh3898/Rhaomi`
- 공개 사이트: 도메인 확정 전
- 운영 관리자: 은총쌤
- 개발·인프라 담당: 조치호
- 배포 대상: Mac mini
- 공개 프론트엔드: Next.js App Router + TypeScript + Static Export
- 콘텐츠 관리: Directus Data Studio
- 데이터베이스: PostgreSQL
- 정적 웹 서버·리버스 프록시: Nginx

## 이 패키지의 범위

이 ZIP은 구현 코드가 아니라 개발 착수 전 기준 문서와 GitHub 템플릿을 제공한다.

```text
.
├── README.md
├── AGENTS.md
├── PACKAGE-MANIFEST.md
├── .github/
│   ├── CODEOWNERS
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/
└── docs/
    ├── 00-governance/
    ├── 01-product/
    ├── 02-content/
    ├── 03-design/
    ├── 04-architecture/
    ├── 05-seo/
    ├── 06-security/
    ├── 07-operations/
    ├── 08-quality/
    ├── 09-decisions/
    └── 10-templates/
```

## 문서 적용 순서

1. ZIP 내용을 저장소 루트에 복사한다.
2. `docs/01-product/open-items.md`에서 출시 전 미확정 항목을 확인한다.
3. `docs/09-decisions/`의 ADR을 기준으로 첫 구현 이슈를 설계한다.
4. 실제 구현으로 계약이 바뀌면 코드와 문서를 같은 PR에서 동기화한다.
5. 문서만 먼저 커밋할 때는 아래 메시지를 권장한다.

```text
docs: 라오미펫 제품 및 시스템 기준 문서 초기화
```

## 현재 핵심 결론

- 고객 페이지는 정적 HTML로 배포하며 SSR을 사용하지 않는다.
- 공개 사이트는 런타임에 CMS나 PostgreSQL에 의존하지 않는다.
- 은총쌤은 Directus에서 사진·공지·견종·서비스·기본 매장정보를 관리한다.
- 공개 콘텐츠 변경은 정적 사이트 재빌드·검증·원자적 교체를 유발한다.
- 고객용 예약 시스템, 결제, 회원가입, 문의 폼은 만들지 않는다.
- 전화, 인스타그램, 네이버톡톡 등 외부 문의 채널로 연결한다.
- 검색 노출은 기술 선택만으로 보장되지 않는다. 정적 HTML, 로컬 SEO, NAP 일치, 콘텐츠 품질, 검색엔진 등록과 운영이 함께 필요하다.

## 주요 문서

- [문서 인덱스](docs/README.md)
- [제품 개요](docs/01-product/product-brief.md)
- [기능 범위](docs/01-product/scope.md)
- [시스템 구조](docs/04-architecture/system-context.md)
- [CMS 데이터 모델](docs/04-architecture/cms-data-model.md)
- [정적 퍼블리싱 파이프라인](docs/04-architecture/static-publishing-pipeline.md)
- [SEO 전략](docs/05-seo/seo-strategy.md)
- [배포 및 롤백](docs/07-operations/deployment.md)
- [출시 체크리스트](docs/08-quality/release-checklist.md)
