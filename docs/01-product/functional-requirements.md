---
title: "기능 요구사항"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "기능 범위 변경 시"
---

# 기능 요구사항

## 공개 사이트

| ID | 요구사항 |
|---|---|
| FR-PUB-001 | 홈은 매장명, 지역, 업종, 대표 문구, 대표 이미지와 주요 CTA를 제공해야 한다. |
| FR-PUB-002 | 전화 버튼은 `tel:` 링크로 연결해야 한다. |
| FR-PUB-003 | 링크 값이 비어 있으면 해당 CTA를 렌더링하지 않아야 한다. |
| FR-PUB-004 | 갤러리는 `published` 상태의 항목만 표시해야 한다. |
| FR-PUB-005 | 갤러리 견종 필터는 현재 공개 사진이 있는 활성 견종만 표시해야 한다. |
| FR-PUB-006 | `전체` 필터는 기본 선택 상태여야 한다. |
| FR-PUB-007 | 사진 상세는 키보드와 터치로 열고 닫을 수 있어야 한다. |
| FR-PUB-008 | 서비스는 정렬순서대로 표시하고 가격 미확정 시 `상담 후 안내`를 보여야 한다. |
| FR-PUB-009 | 공지는 고정 여부, 게시일 내림차순으로 표시해야 한다. 같은 조건에서는 결정적인 후속 정렬을 적용해야 한다. |
| FR-PUB-010 | build 시점에 `published`이고 게시 시각이 도래했으며 만료 전인 공지만 공개해야 한다. 보관·초안 공지는 공개하지 않아야 한다. |
| FR-PUB-011 | 위치 섹션은 주소, 영업시간, 휴무, 주차, 지도 링크를 제공해야 한다. |
| FR-PUB-012 | 하단 고정 CTA는 본문을 가리지 않고 안전영역을 고려해야 한다. |
| FR-PUB-013 | 페이지는 JavaScript가 지연되어도 핵심 텍스트와 링크를 HTML에 포함해야 한다. |
| FR-PUB-014 | 공지 상세 URL은 빌드 시 정적으로 생성해야 한다. |
| FR-PUB-015 | 견종별 SEO 페이지는 콘텐츠 기준을 충족한 경우에만 생성할 수 있다. |

## 관리자

| ID | 요구사항 |
|---|---|
| FR-ADM-001 | 운영자는 이메일·비밀번호와 2단계 인증으로 로그인해야 한다. |
| FR-ADM-002 | 운영자는 갤러리 항목을 생성·조회·수정·보관할 수 있어야 한다. |
| FR-ADM-003 | 인증된 운영자는 공지를 생성·전체 상태 조회·전체 수정하고 `archived`로 보관하거나 복구할 수 있어야 한다. |
| FR-ADM-004 | 인증된 운영자는 견종과 서비스를 생성·조회·전체 수정하고 `archived`로 보관하거나 `draft`·`published`로 복구할 수 있어야 한다. |
| FR-ADM-005 | 인증된 운영자는 매장정보 singleton의 상호, 문구, 영업정보, 주차, 외부 링크를 조회하고 전체 수정할 수 있어야 한다. |
| FR-ADM-006 | 운영자는 시스템 컬렉션, 권한, Flow, 환경설정을 변경할 수 없어야 한다. |
| FR-ADM-007 | 공개 상태 전환은 `PUT`의 최종 entity 전체를 검증해야 하며 서비스 게시 필수값은 Spring application과 PostgreSQL constraint로 이중 강제해야 한다. |
| FR-ADM-008 | 영구 삭제는 시스템 관리자만 수행할 수 있어야 한다. |
| FR-ADM-009 | 변경 이력과 작업 사용자를 추적할 수 있어야 한다. |
| FR-ADM-010 | 견종·서비스 생성은 항상 `draft`여야 하고 slug는 lowercase kebab-case이며 생성 후 변경할 수 없어야 한다. |
| FR-ADM-011 | 견종·서비스·공지·매장정보 관리 API는 session 인증을 요구하고 state-changing 요청에는 CSRF를 강제하며 계약에 없는 `PATCH`·`DELETE`를 제공하지 않아야 한다. |
| FR-ADM-012 | 공지 생성은 항상 `draft`여야 하고 slug는 lowercase kebab-case이며 생성 후 변경할 수 없어야 한다. id·actor·audit·unknown field는 요청에서 거부해야 한다. |
| FR-ADM-013 | 공지 게시에는 whitespace가 아닌 문자를 포함한 본문과 게시 시각이 필요하고, 만료 시각이 있으면 상태와 무관하게 게시 시각보다 늦어야 한다. 게시·만료 시각은 microsecond로 먼저 정규화한 최종 값으로 application과 PostgreSQL이 이중 검증하고 실패 시 row와 audit를 변경하지 않아야 한다. |
| FR-ADM-014 | 매장정보는 상태·공개 id가 없는 단일 현재값이어야 하며, 최초 전체 `PUT`은 생성하고 이후 `PUT`은 같은 row를 갱신해야 한다. PostgreSQL은 우회 입력에도 TRUE singleton row를 최대 하나만 허용해야 한다. |
| FR-ADM-015 | 매장정보의 필수 NAP text, `HH:mm` 영업시간, 선택 휴무 요일, 전화번호, absolute HTTPS URL을 저장 전에 검증하고 실패 시 row와 audit를 변경하지 않아야 한다. |
| FR-ADM-016 | 매장정보 request는 mutable field만 허용하고 id·singleton guard·actor·audit·unknown field를 거부하며, response는 mutable field와 server-owned audit만 반환해야 한다. |

## 배포

| ID | 요구사항 |
|---|---|
| FR-DEP-001 | 공개 콘텐츠 변경은 인증된 내부 빌드 요청을 생성해야 한다. |
| FR-DEP-002 | 연속 변경은 중복 빌드를 합칠 수 있어야 한다. |
| FR-DEP-003 | 빌드 시 backend 콘텐츠와 이미지를 고정 snapshot으로 동기화해야 한다. |
| FR-DEP-004 | 정적 빌드·링크·SEO·스모크 검증이 성공한 산출물만 공개해야 한다. |
| FR-DEP-005 | 배포 실패 시 기존 공개 릴리스를 유지해야 한다. |
| FR-DEP-006 | 직전 정상 릴리스로 되돌릴 수 있어야 한다. |
