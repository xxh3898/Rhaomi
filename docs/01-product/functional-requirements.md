---
title: "기능 요구사항"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
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
| FR-ADM-005 | 인증된 운영자는 매장정보 singleton의 상호, 문구, 영업정보, 주차, 외부 링크와 Hero·프로필·OG media relation을 조회하고 전체 수정할 수 있어야 한다. |
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
| FR-ADM-017 | 인증된 운영자는 JPEG·PNG·HEIC·HEIF를 multipart로 업로드하고 모든 상태의 media metadata와 private master를 조회하며 `active | archived`로 보관·복구할 수 있어야 한다. |
| FR-ADM-018 | media 입력은 client MIME·확장자·파일명이 아니라 실제 byte signature/container와 decoder를 기준으로 검증하고 20 MiB source, 30 MiB stored, 12,000px, 60MP 제한을 적용해야 한다. |
| FR-ADM-019 | JPEG·PNG는 검증한 private 원본 byte를 보존하고 HEIC·HEIF는 orientation 적용, sRGB 변환, EXIF·GPS·XMP 제거 후 quality 92 JPEG master로 정규화해야 한다. |
| FR-ADM-020 | media storage key는 server UUID로만 만들고 original filename·storage key·filesystem path·SHA-256을 API response에 노출하지 않아야 한다. |
| FR-ADM-021 | media validation·정규화 실패에는 DB row와 final/temp orphan이 없어야 하고 DB transaction 실패에는 생성한 final master를 제거해야 한다. |
| FR-ADM-022 | media API는 session·CSRF를 강제하고 status 외 field 수정, public/build read, `PATCH`와 physical `DELETE`를 제공하지 않아야 한다. |
| FR-ADM-023 | 갤러리 생성은 항상 `draft`여야 하고 목록·단건 조회와 full `PUT`만 제공해야 한다. request는 명시된 mutable field만 허용하고 slug·id·actor·audit·unknown field와 `PATCH`·`DELETE`를 거부해야 한다. |
| FR-ADM-024 | 갤러리 게시에는 breed, primary service, cover media, 사실 기반 alt text와 published timestamp가 필요하며 breed/service는 `published`, 모든 연결 media는 `active`여야 한다. draft·archived에서는 null이 아닌 관계 대상의 존재만 요구해야 한다. |
| FR-ADM-025 | cover media는 before 또는 after와 같을 수 있지만 before와 after는 서로 달라야 한다. 관계 대상의 후속 상태 변경은 갤러리에 cascade하지 않고 공개 snapshot이 gallery·relation·file eligibility를 다시 검증해야 한다. |
| FR-ADM-026 | 갤러리 performedAt·publishedAt은 저장 전에 microsecond로 정규화해야 하고 관계·게시 검증 실패에는 mutable field와 actor/audit 전체를 보존해야 한다. |
| FR-ADM-027 | 매장정보 Hero·프로필 이미지는 nullable `media_assets` relation과 Unicode trim·최대 300 code-point의 nonblank 대체텍스트를 pair로 관리해야 하며 OG relation에는 alt field를 만들지 않아야 한다. |
| FR-ADM-028 | 매장정보 PUT의 non-null Hero·프로필·OG media는 모두 존재하고 `active`여야 한다. 검증 실패에는 `422 SHOP_MEDIA_RELATION_INVALID`를 반환하고 기존 row와 audit를 변경하지 않아야 한다. |
| FR-ADM-029 | 같은 media를 Hero·프로필·OG에 재사용할 수 있어야 한다. 대상이 나중에 archived돼도 relation과 audit를 자동 변경하지 않고 후속 public build가 relation·status·file 유효성을 다시 검증해야 한다. |
| FR-ADM-030 | `/admin/` 매장정보 UI는 row 없음과 조회 장애를 구분하고 mutable field 26개를 모두 포함한 단일 full PUT으로 최초 생성·수정해야 한다. nullable field는 `null`을 명시하고 server audit field를 request에 포함하지 않아야 한다. |
| FR-ADM-031 | 매장정보 media picker는 active private media만 Hero·미용사·OG의 새 관계로 선택하고 같은 media 재사용과 선택 해제를 허용해야 한다. archived/missing 기존 relation은 숨기지 않고 clear 또는 active 교체가 필요함을 표시해야 한다. |
| FR-ADM-032 | `/admin/` 견종·서비스 UI는 strict response 검증 뒤 목록·생성·전체 수정을 제공하고 immutable slug, `draft | published | archived`, 0 이상 sortOrder를 유지해야 한다. list ordering은 backend response 배열이 authority이며 mutation 뒤 GET list로 canonical ordering을 다시 획득해야 한다. 후속 GET 실패는 저장 실패와 구분하고 사용자 action당 mutation은 한 번만 보내며 401 session expiry·403 CSRF 실패·network/5xx를 자동 재시도하지 않아야 한다. |
| FR-ADM-033 | `/admin/` 갤러리 UI는 backend 목록 순서를 그대로 보존하고 항상 draft인 생성과 mutable field 전체 PUT을 제공해야 한다. draft·archived에서는 존재하는 breed·service·active/archived media를 관계로 선택할 수 있고 published에서는 게시된 breed·service와 active media만 허용해야 한다. mutation 뒤 canonical GET 실패는 저장 실패와 구분하고 stale GET·자동 mutation 재시도를 막아야 한다. |
| FR-ADM-034 | `/admin/` 공지 UI는 backend의 pinned·publishedAt·updatedAt·id 목록 순서를 그대로 보존하고 status 없는 draft 생성과 immutable slug를 제외한 full PUT을 제공해야 한다. source Markdown을 HTML로 렌더링하지 않고, 변경하지 않은 microsecond Instant를 보존하며, mutation 뒤 canonical GET 실패를 저장 실패와 구분하고 stale GET·자동 mutation 재시도를 막아야 한다. |
| FR-ADM-035 | Breed·Service·Notice·ShopSettings·Gallery·Media의 성공 mutation은 같은 PostgreSQL transaction에서 row 기반 `contentRevision`을 정확히 한 번 증가시켜야 한다. validation·repository·outbox 실패와 rollback은 revision을 소비하거나 event를 남기지 않아야 한다. |
| FR-ADM-036 | 공개 결과에 영향을 주는 mutation은 같은 revision의 `CONTENT_CHANGED`를 기록하고, 새로 설정·변경된 Notice 게시·만료 경계와 published Gallery 게시 경계는 typed `availableAt`·expected boundary scheduled event로 내구적으로 기록해야 한다. old event 삭제에 correctness를 의존하지 않아야 한다. |

## 배포

| ID | 요구사항 |
|---|---|
| FR-DEP-001 | 공개 콘텐츠 변경은 인증된 내부 빌드 요청을 생성해야 한다. |
| FR-DEP-002 | 연속 변경은 중복 빌드를 합칠 수 있어야 한다. |
| FR-DEP-003 | 빌드 시 backend 콘텐츠와 이미지를 고정 snapshot으로 동기화해야 한다. |
| FR-DEP-004 | 정적 빌드·링크·SEO·스모크 검증이 성공한 산출물만 공개해야 한다. |
| FR-DEP-005 | 배포 실패 시 기존 공개 릴리스를 유지해야 한다. |
| FR-DEP-006 | 직전 정상 릴리스로 되돌릴 수 있어야 한다. |
| FR-DEP-007 | future Notice 게시·만료와 Gallery 게시 시각은 추가 관리자 mutation 없이 due event가 후속 publisher에 의해 처리될 수 있어야 한다. |
| FR-DEP-008 | immediate pending 또는 due event의 claim, `publishGeneration` 할당과 첫 attempt는 같은 PostgreSQL transaction이어야 하며 rollback은 generation을 소비하지 않아야 한다. 만료 lease와 자동 transient retry는 새 generation 없이 최대 총 4회까지 복구해야 한다. |
| FR-DEP-009 | scheduled claim은 current Notice·Gallery의 published 상태와 expected boundary를 최소 검증하고 stale이면 generation 없이 terminal no-op이어야 한다. 관계·media·file을 포함한 전체 공개 eligibility는 후속 build API와 transformer가 다시 검증해야 한다. |
| FR-DEP-010 | generation을 가진 active claim의 완료·lease 갱신은 current owner와 generation을 확인해야 하며, lower generation은 존재하는 higher active generation으로만 coalesce할 수 있어야 한다. 실제 30초 debounce와 build orchestration은 후속 publisher가 담당해야 한다. |
