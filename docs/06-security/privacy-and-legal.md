---
title: "개인정보·법적 검토"
status: "draft"
owner: "은총쌤"
reviewers: "조치호"
last_updated: "2026-08-30"
review_trigger: "데이터 수집·사진·외부 도구 변경 시"
---

# 개인정보·법적 검토

이 문서는 법률 자문이 아니다. 실제 적용 의무는 운영 주체와 수집 방식에 따라 별도 확인한다.

## 1차 제품의 데이터 최소화

고객용 사이트는 아래 기능을 만들지 않는다.

- 문의 폼
- 회원가입
- 예약 정보 저장
- 결제
- 고객 프로필
- 쿠키 기반 광고 추적

따라서 고객이 직접 입력하는 개인정보는 없다.

## 남아 있는 데이터 처리

기능을 최소화해도 다음 데이터가 생길 수 있다.

- 웹 서버 access log의 IP, user-agent, 시각
- 관리자 로그인 기록
- Spring Boot 관리자 audit·변경 이력
- JPEG·PNG private master에 남을 수 있는 source EXIF
- 고객 또는 보호자가 포함된 사진
- 외부 분석 도구를 추가할 경우 이벤트·기기 정보

로그 보존 목적과 기간을 정하고 불필요하게 오래 보관하지 않는다.

## 사진

- 시술사진 공개에 대한 매장 운영 기준을 확정한다.
- 보호자 또는 사람이 식별되는 사진은 동의 없이 게시하지 않는다.
- 차량번호, 명찰, 전화번호, 주소, 문서가 배경에 포함되지 않게 확인한다.
- HEIC·HEIF upload는 backend에서 EXIF·GPS·XMP·기기 metadata를 제거한 JPEG master만 보관한다.
- JPEG·PNG는 현재 private 원본 byte를 보존하므로 EXIF가 남을 수 있다. private master를 공개 URL로 사용하지 않고 build transformer가 공개 파생 byte에서 EXIF·GPS·XMP·orientation·comment를 제거하고 결과를 다시 검사한다.
- original filename·storage key·filesystem path·master SHA-256은 관리자 API response와 공개 HTML에 노출하지 않는다.
- Hero·프로필·OG 설정은 private media의 scalar UUID만 참조하며 relation 설정만으로 master를 공개하지 않는다. build API는 게시 권한·active 상태와 canonical file을, transformer는 snapshot relation·manifest와 metadata 제거 파생본을 각각 다시 검증한다.
- Hero·프로필 대체텍스트는 사진 내용을 사실대로 설명하되 불필요한 개인 식별정보를 추가하지 않는다. OG에는 별도 alt field를 두지 않는다.
- 게시 철회 요청이 들어오면 공개 상태를 즉시 `archived`로 전환하고 재배포한다.
- 백업에서의 보존·삭제 절차는 별도로 검토한다.

## 외부 링크

전화, 인스타그램, 네이버톡톡, 지도는 외부 서비스다.

- 버튼에서 대상 서비스를 명확히 표시한다.
- 외부 서비스의 개인정보 처리와 장애를 라오미펫 사이트가 통제한다고 표현하지 않는다.
- 지도 iframe과 SNS tracker embed는 1차에서 사용하지 않는다.
- 단순 링크에도 referrer 정책을 검토한다.

## 분석 도구

분석을 추가하기 전 확인:

- 수집 항목
- 쿠키 사용
- 국외 이전 또는 제3자 제공
- 보존 기간
- 개인정보 처리방침 필요 여부
- 동의 배너 필요 여부
- opt-out
- 데이터 삭제

확인 전 분석 스크립트 운영 설치를 보류한다.

## 정책 문구

예약 취소, 노쇼, 노령견, 질환, 공격성, 안전 관련 문구는 실제 운영 기준과 일치해야 한다. 확정되지 않은 책임 제한이나 보장 문구를 임의 작성하지 않는다.

## 출시 게이트

- 사진 게시 동의 기준
- 운영 주체 명칭
- 서버 로그 보존정책
- 개인정보 처리방침 필요 여부
- 외부 분석 도입 여부
- 저작권·폰트·아이콘·사진 라이선스
- 실제 iPhone Safari upload와 실제 촬영 원본의 공개 파생본 metadata 제거 증거. 합성 transformer test만으로 physical-device 증거를 대체하지 않음
