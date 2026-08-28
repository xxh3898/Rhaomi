---
title: "비밀값·데이터 보호"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "비밀관리·저장소 변경 시"
---

# 비밀값·데이터 보호

## 비밀값

- PostgreSQL 비밀번호
- Directus `SECRET`
- Directus 초기 관리자 비밀번호
- Site Builder token
- Deploy Hook secret
- GitHub deploy credential
- TLS/DNS credential
- Directus license key
- 백업 암호화 키

## 저장 원칙

- Git 커밋 금지
- `.env.example`에는 실제 값 금지
- 운영 `.env`는 최소 파일 권한
- Docker Compose에 평문 literal을 직접 넣지 않음
- 가능하면 운영 host의 secret 파일 또는 검증된 secret manager 사용
- 로그, 에러, build artifact에 출력 금지
- query string에 토큰을 넣지 않고 Authorization header 사용
- 사람이 쓰는 비밀번호와 서비스 토큰 분리

## Rotation

| 비밀 | 교체 조건 |
|---|---|
| Builder token | 주기적 또는 노출 의심 |
| Deploy hook secret | 노출·로그 유출·담당자 변경 |
| DB password | 노출·계정 변경·정책 주기 |
| Directus secret | 침해 사고; 세션 영향 검토 후 |
| GitHub credential | runner 침해·권한 변경 |
| 2FA recovery code | 사용 또는 노출 |

## 데이터 분류

| 등급 | 예 | 처리 |
|---|---|---|
| 공개 | 매장명, 주소, 공개 공지, 공개 파생 이미지 | 정적 사이트 |
| 내부 | draft 콘텐츠, 운영 로그, 빌드 로그 | 인증·제한된 보존 |
| 민감 | 원본 사진, 관리자 이메일, IP 로그 | 최소 접근·백업 보호 |
| 비밀 | 토큰, 비밀번호, signing secret | 별도 secret 관리 |

## 로그

- Authorization header 금지
- URL query token 금지
- 관리자 이메일은 필요 이상 반복하지 않음
- 요청 body 전체 로깅 금지
- 이미지 원본 경로 최소화
- 보존 기간을 명시하고 오래된 로그 정리
- 장애 분석에 필요한 request ID와 결과 상태 중심

## 백업

- DB와 uploads를 모두 포함
- Mac mini 고장과 분리된 offsite copy
- 백업 접근권한 최소화
- 복구 테스트
- 백업 삭제도 승인과 보존정책 적용
