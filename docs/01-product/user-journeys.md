---
title: "사용자 여정"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-09-02"
review_trigger: "페이지 흐름 변경 시"
---

# 사용자 여정

## 현재 관리자 진입 여정

```text
/admin/ 정적 화면 진입
→ same-origin /api/admin/auth/me로 session 확인
→ 미인증이면 CSRF 획득 후 로그인
→ password와 pre-login CSRF 제거
→ session fixation 뒤 fresh CSRF와 WebAuthn 상태 확인
→ passkey가 없으면 초기 등록 후 recovery code set rotation·1회 보관
→ passkey가 있으면 assertion으로 second factor 검증
→ second-factor session fixation 뒤 fresh CSRF 재획득
→ 관리자 identity와 관리 영역 확인
→ fresh CSRF로 로그아웃
```

- network·5xx·잘못된 response는 credential 실패로 위장하지 않고 재시도 화면을 표시한다.
- 로그인 실패 뒤 password를 지우고 해당 입력으로 focus를 되돌린다.
- password first factor만으로 dashboard나 business API를 사용할 수 없다. recovery code 사용 뒤에는 새 set rotation 전까지 제한 상태를 유지한다.
- 현재 dashboard의 매장정보·갤러리·미디어·견종·서비스·공지 영역은 same-page UI로 제공하며 존재하지 않는 CRUD route나 fake data를 만들지 않는다.
- `/admin/` HTML의 존재나 client session 확인은 보안 경계가 아니며 backend session·CSRF가 요청을 최종 방어한다.

## 여정 A: 검색 유입 후 신규 문의

```text
Google/Naver 검색
→ 검색 결과 제목·설명 확인
→ Hero에서 지역·서비스·예약제 확인
→ 시술사진과 견종 필터 확인
→ 은총쌤·예약 전 안내 확인
→ 하단 고정 CTA
→ 전화 또는 인스타그램 문의
```

### 성공 조건

- 첫 화면에서 매장명, 지역, 업종, 문의 CTA가 보인다.
- 갤러리까지 과도한 스크롤 없이 접근한다.
- CTA가 실제 앱 또는 전화 기능으로 연결된다.
- 사용자가 자체 예약이 가능한 것으로 오해하지 않는다.

## 여정 B: 인스타그램 유입 후 시술 비교

```text
Instagram 프로필 링크
→ 갤러리
→ 견종 필터 선택
→ 사진 상세 확인
→ 서비스·가격 안내 확인
→ Instagram으로 복귀해 DM
```

### 성공 조건

- 필터가 페이지 새로고침 없이 즉시 반영된다.
- 선택 상태가 텍스트와 시각 요소 모두로 구분된다.
- 갤러리 모달을 닫으면 기존 스크롤과 필터 상태가 유지된다.

## 여정 C: 지도 유입 후 위치·영업 확인

```text
네이버지도/카카오맵 링크
→ 사이트
→ 영업시간·휴무·주차 확인
→ 지도 앱 버튼
→ 길찾기 또는 전화
```

### 성공 조건

- 상호, 주소, 전화번호가 지도 플랫폼과 일치한다.
- 휴무일과 임시 공지가 충돌하지 않는다.
- 지도 버튼은 명확한 외부 이동임을 나타낸다.

## 여정 D: 운영자의 사진 게시

현재 구현된 관리자 여정이며 실제 production RP/FQDN·운영 passkey·콘텐츠 provisioning 전에는 운영 완료로 보지 않는다.

```text
관리자 로그인
→ 갤러리 항목 생성
→ 사진 업로드
→ 강아지 이름·견종·서비스·대체텍스트 입력
→ 공개 상태로 저장
→ 배포 훅
→ 정적 빌드 검증
→ 고객 사이트 반영
```

### 성공 조건

- 필수값 누락 시 저장 전에 명확히 안내한다.
- 사진 원본은 보존된다.
- 공개용 파생본에서 위치정보 등 메타데이터가 제거된다.
- 빌드 실패 시 기존 사이트가 유지된다.

## 여정 E: 운영자의 공지 수정·삭제

현재 구현된 관리자 여정이며 실제 production provisioning·콘텐츠 승인 전에는 운영 완료로 보지 않는다.

```text
공지 선택
→ 내용 수정 또는 보관
→ 저장
→ 재빌드
→ 공개 목록·상세·사이트맵 동기화
```

### 성공 조건

- 보관한 공지는 고객 화면과 sitemap에서 제외된다.
- 영구 삭제 없이 복구할 수 있다.
- 고정 공지는 일반 공지보다 먼저 표시된다.
