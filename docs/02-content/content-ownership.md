---
title: "콘텐츠 소유권"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
review_trigger: "관리 범위 변경 시"
---

# 콘텐츠 소유권

## `/admin`에서 운영자가 관리

아래 관리자 API와 private media master는 구현됐다. `/admin/`의 media upload·목록·private preview·archive/restore UI는 Phase 1C-8a, 매장정보 전체 편집과 Hero·미용사·OG single media picker는 Phase 1C-8b, 견종·서비스 목록·생성·전체 수정·게시·보관·복구 UI는 Phase 1C-8c, 갤러리 콘텐츠·관계 편집 UI는 Phase 1C-8d, 공지 콘텐츠·게시기간 편집 UI는 Phase 1C-8e에서 구현됐다.

| 콘텐츠 | 컬렉션 | 운영자 권한 |
|---|---|---|
| 대표 문구와 매장정보 | `shop_settings` | 조회·수정 |
| 서비스와 가격 문구 | `services` | 생성·수정·보관 |
| 견종 필터 | `breeds` | 생성·수정·보관 |
| 시술사진 | `gallery_items` | 생성·수정·보관 |
| 공지 | `notices` | 생성·수정·보관 |
| 업로드 파일 | `media_assets` | 라오미펫 원본 생성·조회·보관 |

미디어 UI와 picker는 storage path·hash·원본 파일명을 노출하지 않고 backend의 validation·archive authority를 그대로 사용한다. 매장정보 picker는 active asset만 새 관계로 선택하며 archived/missing 기존 UUID를 숨기지 않고 제거하거나 교체하도록 안내한다. 같은 asset을 Hero·미용사·OG에 재사용할 수 있고 Hero·미용사 alt는 선택한 공개 이미지 의미를 별도로 입력한다.

견종·서비스 UI는 UUID와 audit를 핵심 카드 정보로 노출하지 않고 server response allowlist만 canonical state로 사용한다. slug는 생성 뒤 읽기 전용이며 archive를 삭제로 표현하지 않는다. 실제 라오미펫 견종·서비스 값은 코드 기본값이나 seed로 넣지 않고 운영 입력·검증을 별도로 수행한다.

갤러리 UI는 backend 목록 순서와 canonical response를 authority로 사용하고 UUID·actor·audit를 수정 field로 노출하지 않는다. draft·archived 편집에서는 존재하는 관계와 보관 media를 유지·선택할 수 있지만 published 전환 전에는 게시된 견종·서비스와 active cover/before/after media를 요구한다. 실제 시술사진과 반려견 정보는 seed하지 않고 사용 권한·대체텍스트를 확인한 운영 입력으로만 등록한다.

공지 UI는 backend 목록 순서와 canonical response를 authority로 사용한다. slug와 audit은 읽기·표시 범위로 제한하고 수정 request에는 포함하지 않으며, Markdown source를 HTML로 실행하거나 preview하지 않는다. 게시·만료 시각은 운영자가 명시적으로 입력하고 자동 상태 변경·실제 공지 seed를 추가하지 않는다.

## 코드에서 개발자가 관리

- URL 구조
- SEO 메타데이터 생성 규칙
- JSON-LD 스키마
- 레이아웃과 컴포넌트
- 디자인 토큰
- 배포와 이미지 파이프라인
- 환경변수 이름
- 권한, publishing outbox와 single publisher
- 관리자 역할과 시스템 설정
- 검색엔진 인증 파일 또는 메타 태그

## 공동 승인

아래는 운영자가 입력하더라도 출시 전 개발자와 함께 검증한다.

- 상호·주소·전화번호
- canonical 도메인
- OG 대표 이미지
- 네이버톡톡·카카오 링크
- 예약·취소·노령견·질환 관련 정책
- 개인정보 처리 문구
- 검색 결과 title과 description

## 변경 원칙

- 콘텐츠 값은 가능한 관리 UI에서 다루되 기술 규칙까지 운영자에게 노출하지 않는다.
- 운영자는 마크업이나 임의 스크립트를 입력할 수 없어야 한다.
- HTML WYSIWYG를 사용할 경우 허용 태그를 제한한다.
- 외부 URL은 `https://`, `tel:` 등 허용 스킴을 검증한다.
