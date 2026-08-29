---
title: "관리자 콘텐츠 규칙"
status: "approved"
owner: "은총쌤"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "관리 API field·운영 절차 변경 시"
---

# 관리자 콘텐츠 규칙

## 상태

아래 상태는 견종·서비스·공지와 후속 gallery 같은 collection에 적용한다. 단일 현재값인 `shop_settings`에는 상태를 두지 않는다.

| 상태 | 고객 노출 | 용도 |
|---|---|---|
| `draft` | 안 됨 | 작성 중, 검토 전 |
| `published` | 됨 | 공개 대상 |
| `archived` | 안 됨 | 삭제처럼 숨기되 복구 가능 |

## 게시 원칙

- 저장만으로 공개되지 않게 기본 상태는 `draft`다.
- `published` 전환 시 필수 필드를 검증한다.
- 견종은 이름과 slug가 유효해야 게시할 수 있다.
- 서비스는 이름·slug·설명·가격 문구가 모두 있어야 게시할 수 있다.
- 게시 중인 서비스의 설명이나 가격 문구를 비우는 수정도 거부한다.
- 시술사진은 견종·대표 이미지·대체 텍스트가 없으면 게시할 수 없다.
- 공지는 제목·slug·본문·게시일이 없으면 게시할 수 없다.
- 외부 링크는 실제로 열어 본 후 저장한다.

## 삭제

- 운영자 화면의 삭제 의미는 `archived` 전환이다.
- 영구 삭제는 정기 정리 시 시스템 관리자가 백업 확인 후 수행한다.
- 참조 중인 견종과 서비스는 영구 삭제하지 않는다.
- 현재 media API는 physical delete를 제공하지 않는다. 후속 정리 기능은 연결된 공개 콘텐츠가 없고 백업·retention 승인이 있을 때만 별도 구현한다.

## 견종·서비스

- 생성 요청은 상태를 받지 않고 항상 `draft`로 저장한다.
- 수정과 상태 전환은 slug를 제외한 전체 mutable representation을 `PUT`으로 보낸다.
- slug는 lowercase ASCII kebab-case로 생성하며 저장 후 일반 관리 API에서 바꾸지 않는다.
- 목록은 상태와 무관하게 보관 항목까지 포함하고 `sort_order`, 이름, id 순으로 정렬한다.
- `sort_order`를 생략한 생성은 `100`을 사용하며 음수는 허용하지 않는다.
- 가격 미확정 서비스는 운영자가 `상담 후 안내`를 직접 입력한다.
- `archived` 항목은 row를 유지하며 유효한 전체 값으로 `draft` 또는 `published`로 복구할 수 있다.
- id, actor, audit timestamp와 내부 field는 요청에서 받지 않는다.

## 공지

- 생성 요청은 status를 받지 않고 항상 `draft`로 저장한다.
- slug는 lowercase ASCII kebab-case로 생성하며 저장 후 일반 관리 API에서 바꾸지 않는다.
- 수정과 상태 전환은 slug를 제외한 전체 mutable representation을 `PUT`으로 보낸다.
- 제목·slug는 앞뒤 공백을 제거하고 선택형 요약·본문은 공백뿐이면 null로 저장한다.
- 게시하려면 제목·slug·본문·게시 시각이 모두 있어야 한다. 미래 게시 시각은 허용하지만 공개 build에는 시각이 도래한 뒤 포함한다.
- 제목과 게시 본문은 space·tab·newline만으로 구성할 수 없으며 whitespace가 아닌 문자를 최소 하나 포함해야 한다.
- 만료 시각이 있으면 `draft`, `published`, `archived` 모두 게시 시각이 있어야 하고 만료 시각은 게시 시각보다 늦어야 한다.
- 게시·만료 시각은 API 입력을 microsecond 정밀도로 절삭한 뒤 기간을 검증하고 저장한다. 정규화 후 같은 시각은 거부하고 정확히 1µs 차이는 허용한다.
- 만료 시각이 지나도 status를 자동 변경하지 않으며 관리자 목록에는 모든 상태와 미래·만료 공지를 유지한다.
- 목록은 `pinned DESC`, `published_at DESC NULLS LAST`, `updated_at DESC`, `id ASC` 순으로 정렬한다.
- id, actor, audit timestamp와 내부 field는 요청에서 받지 않으며 검증 실패 시 기존 row와 audit를 바꾸지 않는다.
- 고정 공지는 최소한으로 사용한다.
- 임시휴무 공지는 `expires_at`을 설정한다.
- 날짜가 지난 공지가 자동으로 사실과 충돌하지 않는지 확인한다.
- 단순 예약 현황은 외부 채널과 불일치할 가능성이 크면 게시하지 않는다.
- 정책 변경 공지는 기존 본문을 덮어쓰기보다 변경 날짜를 분명히 쓴다.

## 갤러리

- 필터 정확도를 위해 자유 입력 대신 견종 관계 필드를 사용한다.
- `기타`와 `믹스견`을 구분해 운영 기준을 통일한다.
- 대표 서비스는 한 개만 선택한다.
- 공개 순서는 `sort`와 게시일을 함께 사용한다.
- 동일 사진을 여러 항목에 중복 업로드하지 않는다. 현재 backend는 SHA-256을 무결성 metadata로만 기록하며 자동 dedupe나 409를 수행하지 않는다.

## 미디어

- upload는 항상 `active`로 생성하고 화면상 삭제는 `archived` 전환으로 처리한다. archive·restore는 row와 master byte를 이동하거나 지우지 않는다.
- JPEG·PNG·HEIC·HEIF만 허용한다. generic 또는 빈 MIME도 실제 byte가 유효하면 받을 수 있지만 구체적인 MIME·확장자 충돌은 거부한다.
- JPEG·PNG private master는 검증한 원본 byte이므로 EXIF가 남을 수 있다. public derivative 생성 전 metadata 제거가 필수다.
- HEIC·HEIF raw byte는 temp에서만 처리하고 orientation·sRGB·metadata 제거 후 JPEG master만 장기 보관한다.
- client filename은 저장 경로로 사용하지 않고 API·DB에도 보존하지 않는다.
- 20 MiB source, 30 MiB stored, 폭·높이 각 12,000px, 60MP 제한을 넘는 이미지는 등록하지 않는다.
- APNG, GIF, WebP, AVIF, SVG, multi-image·sequence HEIF와 손상 이미지는 등록하지 않는다.
- 현재 API에서 수정 가능한 media field는 `status` 하나이며 id·actor·audit·storage metadata는 server-owned다.

## 매장정보

- 매장정보는 하나의 현재 row만 유지하며 `GET`과 전체 mutable representation `PUT`으로 관리한다.
- 최초 PUT은 row를 만들고 이후 PUT은 같은 row를 갱신한다. id와 singleton guard는 운영자에게 노출하지 않는다.
- 매장명·지역·업종·전화·주소는 필수이며 앞뒤 Unicode whitespace 제거 후 비어 있을 수 없다.
- 영업시간은 `HH:mm`의 동일 일자 시작·종료이고 시작이 종료보다 빨라야 한다. 야간 영업, 요일별 복수 시간과 복수 정기휴무는 후속 계약이다.
- 휴무 요일은 없거나 `MONDAY`부터 `SUNDAY` 중 하나다. 임시휴무는 기본 휴무 필드를 바꾸기보다 공지로 처리한다.
- 전화번호는 숫자·`+ - ( )`·일반 space만 사용하고 숫자를 최소 7개 포함한 7~32자로 저장한다.
- 외부 URL은 absolute HTTPS, host 존재, userinfo·control 문자 없음 조건을 확인한다. 특정 채널 값이 없으면 빈 문자열이나 가짜 링크 대신 null로 저장한다.
- 선택형 주차·Hero·소개·예약 문구는 앞뒤 whitespace 제거 후 비면 null로 저장한다.
- id, singleton guard, actor, audit timestamp와 unknown field는 요청에서 받지 않으며 검증 실패 시 기존 row와 audit를 바꾸지 않는다.
- Hero·프로필·OG image id나 임시 path는 저장하지 않는다. 구현된 `media_assets`를 참조하는 실제 FK relation은 후속 migration에서 추가한다.
- 실제 매장값은 migration이나 source에 seed하지 않고 운영 승인 뒤 입력한다.
- 영업시간·휴무·전화·주소 변경은 네이버지도·카카오맵·블로그와 함께 갱신한다.
