---
title: "비밀값·데이터 보호"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "비밀관리·인증·저장소 변경 시"
---

# 비밀값·데이터 보호

## 비밀값

- PostgreSQL 비밀번호
- 관리자 bootstrap 비밀번호
- 관리자 session id
- CSRF token
- 향후 build credential과 deploy hook secret
- GitHub deploy credential
- TLS/DNS credential
- 백업 암호화 키

관리자 email과 password hash는 공개 정보가 아니며 최소 접근 데이터로 취급한다.

## 저장 원칙

- 실제 secret·password·실사용 email Git 커밋 금지
- `.env.example`에는 변수명, 안전한 비밀 아닌 기본값과 placeholder 설명만 기록
- 운영 `.env`는 최소 파일 권한과 별도 승인 적용
- Docker Compose에 실제 credential 평문 literal 금지
- password는 Spring Security의 검증된 encoder로 단방향 hash 저장
- session과 CSRF token은 URL query에 넣지 않음
- log, error response, test report, build artifact에 password/hash/session/token 출력 금지
- 사람 비밀번호와 향후 서비스 credential 분리

## cookie

- session cookie: `HttpOnly`, `SameSite=Lax`
- production: TLS와 `Secure=true` 필수
- session id를 response body나 application log에 포함하지 않음
- logout과 계정 비활성화 절차에서 session 폐기 확인
- CSRF cookie는 static admin client가 표준 double-submit 방식으로 읽을 수 있지만 인증 credential로 사용하지 않음

## bootstrap

- 기본 비활성, production profile 금지
- enable flag와 email/password가 모두 있을 때만 local/test에서 실행
- placeholder를 실제 운영 credential로 사용 금지
- bootstrap password를 명령 인자나 log에 출력 금지
- 실제 운영 계정은 별도 Secret·2FA·복구 승인 뒤 생성

## Rotation

| 비밀 | 교체 조건 |
|---|---|
| 관리자 비밀번호 | 노출 의심·담당자 변경·정책 주기 |
| 활성 session | 로그아웃·계정 비활성화·침해 의심 |
| 향후 build credential | 주기적 또는 노출 의심 |
| Deploy hook secret | 노출·로그 유출·담당자 변경 |
| DB password | 노출·계정 변경·정책 주기 |
| GitHub credential | runner 침해·권한 변경 |
| 2FA recovery code | 사용 또는 노출 |

## 데이터 분류

| 등급 | 예 | 처리 |
|---|---|---|
| 공개 | 매장명, 주소, 공개 공지, 공개 파생 이미지 | 정적 사이트 |
| 내부 | draft 콘텐츠, 운영·빌드 로그 | 인증·제한된 보존 |
| 민감 | 원본 사진, 관리자 email, IP log | 최소 접근·backup 보호 |
| 비밀 | password, session id, token, DB credential | 별도 Secret 관리 |

## 로그와 response

- Authorization, Cookie, Set-Cookie header 기록 금지
- request body 전체 logging 금지
- 로그인 실패는 계정 존재·활성 여부를 구분하지 않는 일반 메시지 사용
- `/me`와 login response는 관리자 id, email, role만 반환
- entity의 `passwordHash`는 API DTO에 포함하지 않음
- 장애 분석에는 request id, endpoint, 결과 status 중심으로 기록

## 백업

- PostgreSQL과 향후 원본 파일 storage를 project 단위로 분리
- Mac mini 고장과 분리된 offsite copy
- backup 접근권한 최소화와 restore test
- backup 삭제는 승인과 보존 정책 적용
