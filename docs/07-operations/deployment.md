---
title: "배포"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "호스트·파이프라인 변경 시"
---

# 배포

## 대상

- Host: Mac mini
- Runtime: Docker Compose
- Public web: Nginx static files
- Admin: Directus
- DB: PostgreSQL
- Source: GitHub `xxh3898/Rhaomi`

## 배포 유형

### 코드 배포

```text
feature → dev 검증
→ dev → main Release PR
→ main commit
→ self-hosted runner 또는 승인된 배포 명령
→ 공통 build/release pipeline
```

### 콘텐츠 배포

```text
Directus 저장
→ Flow
→ internal deploy hook
→ 공통 build/release pipeline
```

두 경로는 최종 검증과 원자적 전환을 공유한다.

## 최초 배포 사전 조건

- [ ] 최종 도메인과 관리자 도메인
- [ ] DNS
- [ ] HTTPS
- [ ] Directus 라이선스 검토
- [ ] PostgreSQL 영속 볼륨
- [ ] Directus uploads 영속 볼륨
- [ ] 운영 비밀값
- [ ] System Administrator 2FA
- [ ] Content Owner 2FA
- [ ] offsite backup 목적지
- [ ] 초기 CMS 스키마 적용
- [ ] 실제 매장정보 승인
- [ ] 사진 사용 기준
- [ ] Nginx 404와 security headers
- [ ] 롤백 검증

## 코드 배포 단계

1. 대상 commit SHA 기록
2. working tree와 runner 상태 확인
3. 운영 DB와 uploads 최근 백업 확인
4. 의존성 설치
5. 타입·린트·테스트
6. CMS schema compatibility 확인
7. 콘텐츠 스냅샷
8. 이미지 파생본
9. Next static export
10. 산출물 검증
11. 새 release 디렉터리 설치
12. symlink 전환
13. 공개 URL 스모크
14. 실패 시 previous 복귀
15. release evidence 저장

## 콘텐츠 배포 단계

- 코드 checkout은 마지막 승인된 main commit을 사용한다.
- CMS snapshot revision을 기록한다.
- 초안 데이터는 산출물에 포함하지 않는다.
- 게시된 콘텐츠의 이미지 변환 실패 시 전체 배포를 실패시킨다.
- 현재 공개 사이트를 유지한다.
- 운영자에게 실패 대응 절차를 제공한다.

## Nginx

공개 site root 개념:

```text
root /srv/rhaomi/current;
```

- HTML은 짧은 cache 또는 재검증
- content-hashed CSS/JS/image는 장기 immutable cache
- 404는 실제 404 상태
- 관리자 host는 Directus reverse proxy
- PostgreSQL과 deploy hook route는 없음
- 관리자 응답에 `X-Robots-Tag: noindex, nofollow`

## 배포 실패 조건

- 테스트 실패
- CMS validation 실패
- 이미지 처리 실패
- `out/` 누락
- 내부 링크 오류
- canonical에 개발 도메인
- sitemap 오류
- 핵심 URL 누락
- Nginx 전환 후 healthcheck 실패
- 디스크 여유 부족

## 수행 금지

- 활성 `current`에서 직접 파일 수정
- 운영 DB 수동 스키마 변경 후 기록 누락
- 백업 확인 없는 major upgrade
- `latest` 이미지 pull 후 즉시 운영 재시작
- feature branch를 운영 배포
- Directus admin 비밀번호를 CI log에 출력
