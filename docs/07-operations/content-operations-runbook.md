---
title: "콘텐츠 운영 런북"
status: "proposed"
owner: "은총쌤"
reviewers: "조치호"
last_updated: "2026-09-11"
review_trigger: "관리자 UI·API·field 변경 시"
---

# 콘텐츠 운영 런북

> `/admin` UI와 콘텐츠 CRUD source는 구현됐지만 production domain/account/content는 아직 provision하지 않았다. 아래 절차는 actual HTTPS와 운영자 physical acceptance 뒤에만 사용한다.

## 최초 콘텐츠 bundle

- 실제 최초 데이터는 ADR-018 tracked schema로 작성하되 owner가 NAP·영업정보·문구·링크·사진과 게시 권리를 검토한 별도 bundle만 사용한다.
- Repository, Issue/PR comment, CI artifact나 일반 채팅에 actual bundle·media 원본을 올리지 않는다. Exact transport/provisioning은 별도 승인 전 실행하지 않는다.
- Manifest schema/version, exact file/count/size/SHA-256와 owner approval을 확인하고 fixed zero-argument entrypoint만 사용한다.
- Initial import는 exactly one active admin과 pristine DB/media에서 한 번만 허용한다. 실패했다고 bundle을 자동 재실행하거나 raw SQL로 보완하지 않는다.
- Commit 성공 뒤 public release가 실패하면 content와 pending event를 보존하고 `HOLD`로 보고한다. 기존 publisher의 별도 authorized recovery가 성공하기 전 공개 완료로 표시하지 않는다.

## 로그인

1. 공식 관리자 주소를 직접 열거나 저장된 북마크를 사용한다.
2. 이메일·비밀번호 입력
3. 2단계 인증
4. 주소창 도메인과 HTTPS 확인
5. 공용 기기에서는 로그인하지 않는다.

## 시술사진 등록

1. `/admin`의 `시술사진`
2. 새 항목
3. 상태 `draft`
4. 대표 사진 업로드
5. 강아지 이름 또는 익명
6. 견종 선택
7. 대표 서비스 선택
8. 설명
9. 실제 사진을 설명하는 대체텍스트
10. 게시일
11. 미리보기 또는 필드 재확인
12. `published`
13. 저장
14. 배포 상태 확인
15. 공개 사이트에서 필터와 상세 확인

### 게시 전 확인

- 고객·보호자 식별정보 없음
- 배경의 전화번호·차량번호 없음
- 견종 정확
- 사진 회전 정확
- 과도한 보정 없음
- 사용 권한 확인

## 사진 수정

- 텍스트만 바꾸면 기존 파일 유지
- 이미지를 교체할 때 원본 덮어쓰기보다 새 파일 사용
- 수정 후 공개 사이트 alt, 카드, 상세 확인
- 잘못된 사진은 즉시 `archived`

## 사진 삭제

운영자는 영구 삭제하지 않는다.

```text
status: published → archived
```

저장 후 공개 사이트에서 사라졌는지 확인한다. 완전 삭제가 필요하면 개발자에게 요청한다.

## 공지 등록

1. `/admin`의 `공지사항`
2. 제목
3. summary
4. 본문
5. 게시일
6. 필요 시 고정
7. 임시 공지는 만료일
8. 상태 `published`
9. 저장
10. 홈 목록과 상세 URL 확인

## 공지 수정

- 날짜나 정책 변경점을 본문에 명확히 표시
- 제목을 바꿔도 slug는 가능한 유지
- 공지 URL이 외부에 공유된 뒤 slug를 바꾸지 않음
- 만료된 안내를 최신처럼 보이게 두지 않음

## 공지 삭제

`archived`로 전환한다. 다음 배포 후 홈, 상세 URL, sitemap에서 제외되는지 확인한다.

## 견종

- 기존 이름 중복 확인
- 표기 통일: `비숑 프리제`, `포메라니안` 등
- 자유로운 별칭을 여러 개 만들지 않음
- 참조 중인 견종은 `archived`
- 공개 사진이 없는 견종은 필터에 자동 미노출

## 서비스

- 가격 미정: `상담 후 안내`
- 확정되지 않은 패키지·효과 문구 금지
- 설명이 실제 제공 범위와 일치
- 서비스를 중단하면 `archived`

## 매장정보

영업시간, 휴무, 주소, 전화 변경은:

1. `/admin`에서 수정
2. 공개 사이트 확인
3. 네이버지도/플레이스
4. 카카오맵
5. 네이버블로그
6. 인스타그램 profile
7. 필요하면 공지

## 배포 실패

- 같은 항목을 반복 저장하지 않는다.
- 현재 공개 사이트가 기존 정보로 정상인지 확인한다.
- 오류가 이미지인지 필드인지 기록한다.
- 개발자에게 항목명, 저장 시각, 화면을 전달한다.
- 긴급 오정보면 해당 항목을 draft/archive로 바꾸고 재배포를 요청한다.
