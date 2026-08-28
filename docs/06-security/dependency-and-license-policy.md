---
title: "의존성·라이선스 정책"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "주요 의존성·라이선스 변경 시"
---

# 의존성·라이선스 정책

## Directus

2026-08-28 공식 문서 기준:

- Directus는 Monospace Sustainable Core License 체계를 안내한다.
- 새 self-hosted 인스턴스는 Core tier로 실행할 수 있다.
- 추가 기능·한도는 라이선스가 필요할 수 있다.
- 공식 Open Innovation Grant 안내에는 연 매출 500만 달러 미만이며 직원 50명 미만인 entity의 무료 상업 사용 조건이 제시되어 있다.
- 적용 단위와 조건은 Studio에 로그인하는 법인 등 공식 약관에 따라 판단된다.

공식 근거:

https://directus.com/docs/licensing/overview

라오미펫의 실제 법적 주체, 매출, 직원 수, Core tier 한도 충족 여부는 이 문서 작성 시 확인되지 않았다. 운영 배포 전에 공식 최신 조건을 다시 확인한다.

## 버전 정책

- `latest` 금지
- 검증 버전 또는 image digest 고정
- lockfile 커밋
- major upgrade는 별도 Issue와 ADR 검토
- 자동 dependency PR은 생성할 수 있지만 자동 merge·운영 배포 금지
- Directus major upgrade는 스키마, 정책, 라이선스, breaking changes를 함께 검토

## 라이선스 인벤토리

출시 전에 생성:

- production dependencies
- development dependencies
- Docker images
- fonts
- icons
- images
- third-party code snippets

허용 여부가 불명확한 자산은 사용하지 않는다.

## 취약점

- package audit 또는 동등한 scanner
- container image scan
- GitHub Dependabot 등 알림
- 심각도만으로 자동 판단하지 않고 실제 노출 경로 분석
- 인터넷 노출 관리자 취약점은 우선 처리
- decoder·image processing 취약점은 업로드 공격면 때문에 우선 처리

## 폰트·아이콘·사진

- 저장소에 폰트 파일을 임의 포함하지 않는다.
- 선택한 폰트의 웹 배포 라이선스를 확인한다.
- 아이콘 세트의 attribution 조건을 확인한다.
- 실제 시술사진의 게시 권한을 운영자가 확인한다.
- 검색 결과나 다른 SNS의 이미지를 복사하지 않는다.

## 라이선스 변경 대응

- 공식 정책 변경을 확인하면 영향 분석 Issue를 만든다.
- 대체 가능성: Directus 유지, 다른 CMS 전환, 제한 기능 축소
- DB와 콘텐츠를 PostgreSQL·파일로 보유해 특정 CMS에 대한 데이터 종속을 줄인다.
