---
title: "위협 모델"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "외부 노출·관리 기능 변경 시"
---

# 위협 모델

## 보호 자산

- Directus 관리자 계정
- 빌더 static token
- Directus signing secret
- PostgreSQL 데이터
- 원본 시술사진
- deploy hook secret
- 백업
- 공개 도메인과 배포 권한
- GitHub 저장소와 Actions 권한

## 공격 표면

- 관리자 로그인
- Directus API와 파일 업로드
- Nginx
- 외부 공개 링크
- Directus Flow
- deploy hook
- 이미지 decoder
- GitHub Actions/self-hosted runner
- 백업 파일
- 운영자 휴대전화

## 주요 위협과 통제

| 위협 | 영향 | 통제 |
|---|---|---|
| 관리자 계정 탈취 | 콘텐츠 변조, 원본 접근 | 2FA, 강한 비밀번호, rate limit, 별도 운영자 역할 |
| Public role 과권한 | CMS 데이터 유출 | Public 무권한, 공개 사이트 정적화 |
| 빌드 토큰 노출 | 콘텐츠·파일 조회 | 서버 전용 env, read-only, 로그 마스킹, rotation |
| deploy hook 위조 | 자원 고갈, 임의 배포 | 내부 network, secret, rate limit, lock |
| 악성 이미지 | 빌더 장애·취약점 | MIME/signature 검사, pixel 제한, 최신 decoder, sandboxed container |
| 원본 EXIF 노출 | 위치·기기 정보 노출 | 원본 비공개, 파생본 metadata 제거 |
| DB 포트 노출 | 데이터 탈취 | host port 금지, 내부 network |
| 백업 유출 | 전체 데이터 유출 | 접근권한, 암호화, 외부 저장소 통제 |
| 공급망 취약점 | 코드 실행 | 버전 고정, 취약점 스캔, 검증된 이미지 |
| 콘텐츠 삭제 | 영업 자산 손실 | archive, revisions, backup |
| 빌드 실패 | 최신 정보 미반영 | 기존 release 유지, 경보, 재시도 |
| self-hosted runner 악용 | Mac mini 장악 | 전용 runner scope, untrusted PR 실행 금지, 최소 권한 |

## 우선순위

### 출시 차단

- 관리자 2FA 없음
- PostgreSQL 외부 노출
- 비밀값 커밋
- Public role 광범위 권한
- 백업 없음
- deploy hook 공용 무인증 노출
- 원본 파일 공개 URL 노출

### 출시 후 개선

- 관리자 IP/Access 정책
- offsite backup 암호화 강화
- 중앙 로그
- 정기 보안 스캔
- 운영자 전용 간소화 UI
