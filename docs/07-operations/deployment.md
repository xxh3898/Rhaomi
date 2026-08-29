---
title: "배포"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "호스트·파이프라인 변경 시"
---

# 배포

## 대상

- Host: Mac mini
- Runtime: Docker Compose
- Public ingress: Cloudflare Tunnel → 기존 host edge Nginx → loopback Rhaomi project Nginx
- Public web: Mac `/private/var/lib/rhaomi/public`을 read-only mount한 web container `/srv/rhaomi/public/current`의 Nginx static files
- Admin API: Spring Boot, same-origin `/api/admin/**`
- DB: PostgreSQL
- Source: GitHub `xxh3898/Rhaomi`

## 구현 상태

[ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md)의 topology와 release 절차는 승인됐지만 production Compose·Nginx, GHCR image, GitHub `production` environment, deploy entrypoint와 one-shot Flyway는 아직 구현되지 않았다. 이 문서는 실행 가능한 목표 계약이며 현재 운영 배포 완료를 뜻하지 않는다.

## 배포 유형

### 코드 배포

```text
feature → dev 검증
→ dev → main Release PR
→ exact main SHA GitHub-hosted 검증
→ immutable multi-architecture image + SBOM + private GHCR
→ GitHub production environment 수동 승인
→ Tailscale SSH
→ 고정·versioned deploy entrypoint
→ digest 기준 production apply
```

`main` merge와 production apply는 독립 gate다. public repository PR source를 production Mac mini runner에서 실행하지 않고 `latest`를 사용하지 않는다.

### 콘텐츠 배포

```text
Spring Boot 콘텐츠 transaction
→ 같은 transaction의 immediate·scheduled publishing event + contentRevision
→ pending/due trigger의 publishGeneration
→ single internal publisher
→ 공통 build/validate/atomic switch pipeline
```

두 경로는 [ADR-011](../09-decisions/ADR-011-transactional-outbox-static-publisher.md)의 build·검증·원자적 전환 구현을 공유한다. outbox·publisher는 아직 구현되지 않았다.

## 최초 배포 사전 조건

- [ ] 최종 도메인과 same-origin `/admin`, `/api/admin/**` route
- [ ] Cloudflare DNS·HTTPS·Tunnel과 host edge route
- [ ] project web loopback bind와 public deny rule
- [ ] Mac `/private/var/lib/rhaomi/{app,public,data/media,state,logs}` canonical directory 생성·ownership·permission
- [ ] public/media/state의 Docker Desktop bind mount smoke와 web read-only·backend/publisher 최소 write 경계
- [ ] PostgreSQL host bind source가 없는 production project-scoped Docker named volume과 exact rendered identity
- [ ] PostgreSQL container restart와 일반 Compose `down`·`up` 뒤 data persistence
- [ ] production entrypoint·runbook의 `docker compose down -v`, `docker volume prune`, named volume direct delete 금지
- [ ] canonical media `/private/var/lib/rhaomi/data/media` 영속화
- [ ] 운영 비밀값
- [ ] 관리자 2FA
- [ ] 암호화 외장 SSD·iCloud restic repository와 recovery key
- [ ] local iCloud repository snapshot/check와 Apple remote sync 완료 증거 분리
- [ ] second trusted device 또는 local cache를 authority로 쓰지 않는 clean retrieval path의 fresh retrieval·restic check·대표 restore
- [ ] local RPO와 remotely verified offsite RPO를 분리한 최초 evidence
- [ ] isolated full restore drill
- [ ] `pg_dump -Fc` backup을 새 isolated PostgreSQL named volume에 `pg_restore`하고 row/schema 검증
- [ ] Flyway migration 적용·검증
- [ ] one-shot Flyway·schema validate·expand/contract 검증
- [ ] publisher immediate/due event·overdue recovery·두 revision·lock·retry·atomic switch 검증
- [ ] HomeOps health·event·alert와 bounded restart 경계 검증
- [ ] decoder-only HEIC image와 x265 absence·SBOM 검증
- [ ] 실제 매장정보 승인
- [ ] 사진 사용 기준
- [ ] Nginx 404와 security headers
- [ ] 롤백 검증

## 코드 배포 단계

1. global deploy lock 획득
2. exact `main` SHA, image digest와 `contentRevision`·`publishGeneration`·`generatedAt` release manifest 확인
3. Mac canonical directory ownership·permission, public/media/state bind source와 access mode 확인
4. PostgreSQL named volume exact identity·mount와 보존 정책 확인
5. disk 여유와 `current`·`previous` 확인
6. 최근 정상 backup set·restore drill 상태 확인
7. migration·major update면 on-demand application-consistent backup 생성·검증
8. immutable image pull과 digest 검증
9. 관리자 write maintenance 활성화
10. one-shot Flyway migration 실행
11. 새 backend의 schema validation과 internal health 확인
12. 승인된 image/digest의 동일 publisher pipeline으로 static release 생성
13. 새 release directory의 HTML·link·SEO·asset·route smoke
14. 기존 `current`를 `previous`로 기록
15. `current` symlink 원자적 전환
16. public HTTPS·핵심 문구·asset·관리자 API smoke
17. 관리자 write maintenance 해제
18. release evidence와 HomeOps status/event 기록

검증 전에는 `current`를 바꾸지 않는다. public static site는 write maintenance 중에도 계속 제공한다.

## Flyway

- production backend 일반 기동은 migration을 실행하지 않고 schema validate만 한다.
- migration은 deploy lock과 write maintenance 안의 one-shot service만 수행한다.
- additive expand/contract를 우선한다.
- column/table 삭제·대량 변환은 별도 승인, on-demand backup과 isolated restore가 필요하다.
- destructive rollback은 검증 전 실행하지 않는다.

## 콘텐츠 배포 단계

- 코드 checkout은 마지막 승인된 main commit을 사용한다.
- publisher는 immediate pending event와 `availableAt <= now`인 scheduled notice event를 처리하고 restart 후 overdue event를 복구한다.
- content snapshot과 release manifest에 `contentRevision`, `publishGeneration`, `generatedAt`을 기록한다.
- `contentRevision`은 콘텐츠 mutation snapshot이고, mutation 없는 publish/expiry boundary, 승인된 code release와 manual rebuild/retry는 새 `publishGeneration`을 만든다.
- scheduled event마다 current notice row와 전체 snapshot을 다시 검증해 reschedule, draft·archived 전환과 window 변경의 stale event를 no-op 또는 최신 generation에 coalesce한다.
- build API와 transformer에서 published, notice 게시·만료, relation·media와 file을 이중 검증한다.
- 초안, 보관·만료 콘텐츠는 산출물에 포함하지 않는다.
- 선택된 image가 archived, missing, corrupt이거나 변환에 실패하면 전체 배포를 실패시킨다.
- 현재 공개 사이트를 유지한다.
- 동일 `publishGeneration` transient failure는 1분·5분·15분 최대 3회 retry하고 data 오류는 무한 retry하지 않는다.
- atomic switch는 `publishGeneration`을 authority로 비교해 낮거나 같은 generation이 newer `current`를 덮지 못하게 한다.
- 운영자에게 마지막 성공·실패 content revision·publish generation과 새 generation을 만드는 명시적 수동 retry를 제공한다.

## Nginx

공개 site root 개념은 web container 내부 경로다.

```text
root /srv/rhaomi/public/current;
```

Mac host source는 `/private/var/lib/rhaomi/public`이며 web container `/srv/rhaomi/public`에 read-only로 mount한다. 위 Nginx path를 Mac host `/srv/rhaomi` 생성 요구로 해석하지 않는다.

- HTML은 짧은 cache 또는 재검증
- content-hashed CSS/JS/image는 장기 immutable cache
- 404는 실제 404 상태
- same-origin `/api/admin/**`만 Spring Boot reverse proxy
- `/api/build/**`, `/internal/**`, `/actuator/**`는 public route에서 거부
- PostgreSQL, publisher, backup과 HomeOps public route 없음
- 관리자 응답에 `X-Robots-Tag: noindex, nofollow`

## release evidence·보존

- exact Git SHA, image tag·digest, SBOM과 scan 결과
- Flyway version·migration 여부와 backup-set ID
- publisher content revision, release ID와 smoke 결과
- `current`·`previous` 전후 target
- Mac canonical root·ownership/permission 확인과 public/media/state bind mapping
- PostgreSQL production project-scoped named volume exact identity, 일반 `down` persistence와 destructive volume command 부재
- 성공 release 최근 5개
- `current`·`previous` target은 개수와 무관하게 보존
- 실패 release/evidence 7일

## 배포 실패 조건

- 테스트 실패
- content/API validation 실패
- 이미지 처리 실패
- `out/` 누락
- 내부 링크 오류
- canonical에 개발 도메인
- sitemap 오류
- 핵심 URL 누락
- Nginx 전환 후 healthcheck 실패
- 디스크 여유 부족
- image tag·digest 불일치 또는 SBOM·scan 증거 누락
- 최근 정상 backup·on-demand backup 검증 실패
- publisher lock·revision 순서 오류
- Mac canonical path·permission 또는 bind mount smoke 실패
- PostgreSQL host bind PGDATA 발견, named volume identity 불일치 또는 restart/일반 `down` persistence 실패

## 수행 금지

- 활성 `current`에서 직접 파일 수정
- 운영 DB 수동 스키마 변경 후 기록 누락
- 백업 확인 없는 major upgrade
- `latest` 이미지 pull 후 즉시 운영 재시작
- feature branch를 운영 배포
- `main` merge를 production apply 승인으로 간주
- production Mac mini에서 public PR source build
- 임의 SSH shell body 실행
- production backend 일반 기동의 자동 Flyway mutation
- public `/api/build/**`, `/internal/**` 또는 actuator 노출
- 관리자 password·session·bootstrap credential을 CI log에 출력
- Mac host `/srv/rhaomi` 생성, `synthetic.conf` 또는 Docker Desktop custom File Sharing을 필수 전제로 추가
- production `docker compose down -v`, `docker volume prune` 또는 PostgreSQL named volume 직접 삭제
- PostgreSQL raw named volume을 required restic backup이나 restore authority로 사용
