---
title: "위협 모델"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "외부 노출·관리 기능·인증 변경 시"
---

# 위협 모델

## 보호 자산

- 관리자 계정과 password hash
- 관리자 session과 CSRF token
- PostgreSQL 데이터
- 향후 원본 시술사진
- 향후 build credential·deploy hook secret
- backup
- 공개 도메인과 배포 권한
- GitHub 저장소와 Actions 권한

## 공격 표면

- Spring Boot 관리자 login과 `/api/admin/**`
- session cookie와 CSRF token 전달
- local/test 관리자 bootstrap
- Actuator health
- Nginx와 향후 same-origin `/api/**` proxy
- PostgreSQL 연결
- 향후 파일 upload·image decoder·build hook
- GitHub Actions/self-hosted runner
- backup 파일과 운영자 휴대전화

## 주요 위협과 통제

| 위협 | 영향 | 통제 |
|---|---|---|
| 관리자 credential 탈취 | 콘텐츠 변조, 내부 데이터 접근 | BCrypt, 일반화된 로그인 실패, 2FA 배포 게이트, session 폐기 |
| session 탈취 | 관리자 권한 사용 | HttpOnly, SameSite, production Secure/TLS fail-fast, log 마스킹 |
| CSRF | 관리자 의도와 다른 변경 | Spring Security CSRF 유지, token 없는 state change 거부 |
| session fixation | 로그인 전 session 탈취 연계 | 로그인 성공 시 session id 교체 |
| 비활성 계정 로그인 | 해지 계정 재사용 | `active` 확인과 동일한 401 실패 |
| bootstrap 오용 | default 관리자 생성 | 기본 비활성, 완전한 env 요구, production profile 차단 |
| API fail-open | 미설계 endpoint 노출 | login/csrf/health만 anonymous, API·Actuator·non-API 모두 명시 전 `denyAll` |
| password hash 노출 | offline cracking | entity 직접 반환 금지, DTO allowlist, 인증 완료 credential erase, session principal·log·body 검사 |
| 인증 service 장애 오분류 | 장애 은폐, 진단 지연 | credential 401 allowlist, service/repository 장애 generic 503 |
| BCrypt 입력 경계 불일치 | 예외 기반 5xx, bootstrap 기동 실패 | login·bootstrap 공통 UTF-8 72-byte validation을 encoder 전에 적용 |
| DB 포트 노출 | 데이터 탈취 | host port 금지, 개발 전용 내부 network |
| 공급망 취약점 | 코드 실행 | exact version, Wrapper/lockfile, scanner, 별도 upgrade 검증 |
| backend 장애 | 관리자 작업 중단 | 공개 Static Export와 runtime 분리 |
| 콘텐츠 삭제 | 영업 자산 손실 | 후속 CRUD에서 archive, migration·backup gate |
| self-hosted runner 악용 | Mac mini 장악 | 전용 runner scope, untrusted PR 실행 금지, 최소 권한 |

## 출시 차단

- 관리자 2FA 없음
- TLS 없이 production session cookie 사용
- production에서 `Secure=false`
- PostgreSQL 외부 노출
- 실제 secret·password·실사용 email 커밋
- CSRF disable 또는 state-changing anonymous endpoint
- default production 관리자 자동 생성
- 미설계 `/api/**` anonymous 허용
- backup 없음

이번 Issue의 local backend는 운영 배포 대상이 아니므로 2FA·TLS·운영 account provisioning을 구현하지 않는다. 이 미구현 상태를 운영 준비 완료로 간주하지 않는다.

## 출시 후 개선

- 관리자 IP/Access 정책
- 공유 session store가 필요한 규모인지 측정
- offsite backup 암호화 강화
- 중앙 log와 login rate limit
- 정기 보안 scan
