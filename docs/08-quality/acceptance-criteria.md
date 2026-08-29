---
title: "수용 기준"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
review_trigger: "제품 기능 변경 시"
---

# 수용 기준

## AC-01 첫 화면

**Given** 모바일 390px 화면에서 홈을 열었을 때  
**When** 초기 HTML이 표시되면  
**Then** 라오미펫, 용인 처인구 애견미용 맥락, 대표 문구, 예약 문의·전화 CTA를 확인할 수 있다.

## AC-02 조건부 문의 채널

**Given** 네이버톡톡 URL이 비어 있을 때  
**When** 고객이 홈을 열면  
**Then** 네이버톡톡 버튼이 렌더링되지 않는다.

**Given** 유효 URL이 저장되고 배포되었을 때  
**Then** 버튼이 표시되고 정확한 URL로 이동한다.

## AC-03 견종 필터

**Given** 비숑과 푸들 공개 사진이 있을 때  
**When** 고객이 `비숑 프리제` 필터를 선택하면  
**Then** 해당 견종 사진만 표시되고 선택 상태가 접근성 API에 전달된다.

## AC-04 필터 목록

**Given** 활성 견종이지만 공개 사진이 없을 때  
**When** 홈을 생성하면  
**Then** 해당 견종은 홈 필터에 표시되지 않는다.

## AC-05 갤러리 상세

**Given** 키보드 사용자가 갤러리 카드를 열었을 때  
**Then** 포커스가 dialog로 이동하고 Escape로 닫히며 원래 카드로 복귀한다.

## AC-06 공지 CRUD

**Given** 운영자가 공지를 draft로 저장했을 때  
**Then** 공개 사이트에 보이지 않는다.

**When** published로 변경하고 배포가 성공하면  
**Then** 홈과 상세 URL, sitemap에 나타난다.

**When** archived로 변경하면  
**Then** 다음 배포 후 모두에서 제외된다.

## AC-07 갤러리 CRUD

**Given** 운영자가 사진, 견종, 서비스, alt를 입력하고 게시할 때  
**Then** 공개 파생 이미지가 생성되고 해당 견종 필터에 표시된다.

**Given** 지원하지 않거나 손상된 이미지일 때  
**Then** 새 공개 배포는 실패하고 기존 사이트가 유지된다.

## AC-08 정적 독립성

**Given** 공개 사이트가 정상 배포된 후 Spring Boot와 PostgreSQL을 중지했을 때
**Then** 고객은 홈, 공지, 이미지, CTA를 계속 사용할 수 있다.

## AC-09 SEO

**Given** 운영 release가 생성되었을 때  
**Then** 홈은 고유 title, description, canonical, OG, LocalBusiness JSON-LD를 포함한다.

## AC-10 접근성

**Given** `prefers-reduced-motion: reduce`일 때  
**Then** 비필수 reveal, parallax, scale animation이 제거된다.

## AC-11 오류

**Given** 존재하지 않는 URL 요청  
**Then** 맞춤 안내와 홈 링크를 제공하고 HTTP 404를 반환한다.

## AC-12 배포 안전성

**Given** 새 release의 검증이 실패할 때  
**Then** `current`가 변경되지 않는다.

**Given** 전환 직후 스모크가 실패할 때  
**Then** `previous`로 복귀할 수 있다.

## AC-13 관리자 매장정보 편집

**Given** 인증된 운영자가 row가 없는 `/admin/` 매장정보 화면을 열었을 때

**Then** 장애 문구 대신 실제 값이 없는 빈 full form과 명시적 주차 선택을 확인할 수 있다.

**When** 필수값을 입력하고 저장하면

**Then** mutable field 26개와 nullable `null`만 포함한 PUT 한 번을 보내고 server canonical response를 form에 반영한다.

**Given** 기존 Hero·미용사·OG relation이 active일 때

**When** 운영자가 media picker를 사용하면

**Then** 현재 slot의 relation 바로 아래에 picker 하나만 표시하고 active private media를 선택·해제하며 같은 media를 여러 slot에 재사용할 수 있다.

**Given** keyboard 사용자가 Hero·미용사·OG의 `미디어 선택` trigger를 활성화했을 때

**Then** focus가 중간 form control을 거치지 않고 해당 inline picker의 첫 control로 이동하고, 닫기·선택 완료 후 원래 slot trigger로 복귀한다.

**Given** 기존 relation이 archived이거나 목록에서 찾을 수 없을 때

**Then** 관계를 숨기지 않고 제거 또는 active media 교체가 필요함을 표시하며 invalid 관계를 그대로 정상 저장하지 않는다.

**Given** Hero 또는 미용사 이미지를 선택했을 때

**Then** 300 code-point 이하의 공개 이미지 대체텍스트를 요구하고 OG에는 alt input을 만들지 않는다.
