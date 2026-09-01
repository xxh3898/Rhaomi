---
title: "배포"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-01"
review_trigger: "호스트·파이프라인 변경 시"
---

# 배포

## 대상

- Host: Mac mini
- Runtime: Docker Compose
- Public ingress: Cloudflare Tunnel → 기존 host edge Nginx → loopback Rhaomi project Nginx. 외부 origin은 HTTPS이며 project Nginx redirect는 relative `Location`, backend forwarded origin은 client 입력이 아닌 고정 `https:443`을 사용한다.
- Public web: Mac `/private/var/lib/rhaomi/public`을 read-only mount한 web container `/srv/rhaomi/public/current`의 Nginx static files
- Admin API: Spring Boot, same-origin `/api/admin/**`
- DB: PostgreSQL
- Source: GitHub `xxh3898/Rhaomi`

## 구현 상태

[ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md)의 topology와 release 절차에 따라 canonical image·Compose·project Nginx, `.github/workflows/production-release.yml`, fixed deploy entrypoint와 non-web one-shot Flyway/schema task를 구현했다. D-IMP-4는 같은 operation lock을 쓰는 fixed backup entrypoint, strict complete set과 exact-release eligibility bridge를 추가했고 deploy가 writer mutation 전에 evidence와 backup artifact를 full-read하도록 연결했다. 이 source와 task-scoped local/Hosted evidence는 private GHCR package/visibility, GitHub `production` Environment·reviewer·secret, Tailscale identity, actual host entrypoint/path·volume·backup repository·schedule·Secret·FQDN을 provision한 것이 아니며 workflow dispatch·deploy·production migration·backup을 수행하지 않았다.

[Production readiness matrix](production-readiness.md)는 이 승인 계약, local/CI evidence, production provisioning, 외부 콘텐츠 승인과 physical-device acceptance를 분리한다. Phase 1D contract 완료만으로 아래 production 항목을 통과 처리하지 않는다.

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

두 경로는 [ADR-011](../09-decisions/ADR-011-transactional-outbox-static-publisher.md)의 build·검증·원자적 전환 구현을 공유한다. transactional outbox, generation state, dedicated polling/debounce/lock control loop, Build API→transformer staging, Next Static Export, private release manifest·`BigInt` stale guard·`previous/current` atomic switch·post-switch smoke·rollback·retention과 실제 Java executor binding을 구현했다. production Compose는 같은 image의 exact non-web publisher argv와 public/state/lock/media 및 isolated build-workspace mount target을 고정하고 나머지 image source는 read-only로 유지한다. actual Secret·Mac canonical path·approved digest provisioning과 public HTTPS acceptance는 아직 수행하지 않았다.

## D-IMP-2·D-IMP-3·D-IMP-4 source validation

- canonical base는 `/private/var/lib/rhaomi` bind source와 project-scoped PostgreSQL named volume을 유지한다.
- validation overlay만 task temp root와 labeled one-shot service를 사용한다.
- native architecture에서 web-only loopback, network adjacency, mount RO/RW, static/admin/deny route, internal Build API auth, migration·schema one-shot·malformed mode, writer 정지 중 public serving과 일반 Compose `down`→`up` sentinel persistence를 검증한다.
- fake Docker task harness가 wrong registry·malformed/duplicate input의 mutation 전 거부, lock contention, digest/revision 검증, migration/schema·backend health·publisher start·runtime backend/publisher image mismatch 실패 뒤 writer auto-resume 0과 quiescence, secret redaction을 검증한다.
- backup control harness는 deploy/backup lock contention, writer physical exit 전 dump 금지, complete 승격 전 writer 재기동 금지, capture/restart failure와 lock hold를 검증한다.
- actual task validator는 상태 A를 backup한 뒤 source DB/media를 B로 바꾸고 fresh named volume·media root에 A를 `pg_restore`/복사해 schema·audit/relation·media decode·static publication·restart/down-up persistence를 확인한다.
- task container/network는 정리하지만 task PostgreSQL volume과 image는 삭제하지 않는다.
- 위 evidence는 아래 최초 배포 사전 조건의 actual Mac·production 항목을 완료 처리하지 않는다.

## 최초 배포 사전 조건

- [ ] 사용자 소유 기존 도메인의 exact temporary FQDN provisioning과 same-origin `/admin`, `/api/admin/**` route
- [ ] Cloudflare DNS·HTTPS·Tunnel과 host edge route
- [ ] project web loopback bind와 public deny rule
- [ ] Mac `/private/var/lib/rhaomi/{app,public,data/media,state,logs}`와 `/private/var/lib/rhaomi/state/publisher/build-workspace` canonical directory 생성·ownership·permission
- [ ] public/media/state/build-workspace의 Docker Desktop bind mount smoke와 web read-only·backend/publisher 최소 write 경계; publisher image source의 workspace 외 write 거부
- [ ] PostgreSQL host bind source가 없는 production project-scoped Docker named volume과 exact rendered identity
- [ ] PostgreSQL container restart와 일반 Compose `down`·`up` 뒤 data persistence
- [ ] production entrypoint·runbook의 `docker compose down -v`, `docker volume prune`, named volume direct delete 금지
- [ ] canonical media `/private/var/lib/rhaomi/data/media` 영속화
- [ ] 운영 비밀값
- [ ] private GHCR package 생성·visibility·pull 권한과 exact-SHA tag/digest evidence
- [ ] GitHub `production` Environment, required reviewer·self-review policy·main deployment policy·secret/value
- [ ] Tailscale deploy identity·target host/user·SSH known-hosts와 public internet SSH 미노출
- [ ] fixed entrypoint·Compose·`production.env`·Docker credential config의 Mac installation·owner·mode
- [ ] exact release SHA에 바인딩된 D-IMP-4 backup eligibility evidence
- [ ] 관리자 password+WebAuthn/passkey 2차 인증, authenticator private key server 비수집, RP-side credential ID·public key·필요 metadata, registration revoke/remove, recovery-code secret의 password manager+별도 offline copy·rotation
- [ ] protected source와 분리된 Mac mini local backup repository/path·ownership·permission·capacity
- [ ] `pg_dump -Fc`와 canonical media를 묶은 동일 backup-set manifest·retention·check
- [ ] isolated full restore drill
- [ ] `pg_dump -Fc` backup을 새 isolated PostgreSQL named volume에 `pg_restore`하고 row/schema 검증
- [ ] Flyway migration 적용·검증
- [ ] one-shot Flyway·schema validate·expand/contract 검증
- [ ] publisher immediate/due event·overdue recovery·두 revision·lock·retry·atomic switch 검증
- [ ] HomeOps health·event·alert와 bounded restart 경계 검증
- [ ] decoder-only HEIC image와 x265 absence·SBOM 검증
- [ ] 실제 매장 운영자의 NAP·영업정보·정책·문구·링크 최종 승인
- [ ] 실제 매장 운영자의 Hero/OG/프로필/시술 사진과 반려견/고객 사진 게시 권한 승인
- [ ] Nginx 404와 security headers
- [ ] 롤백 검증

외장 SSD·iCloud 3-2-1, restic recovery key와 remotely verified offsite RPO는 초기 production 사전 조건이 아니라 future hardening이다. 미구성 상태는 `NOT_CONFIGURED / DEFERRED`이며 local backup 성공으로 offsite `PASS`를 만들지 않는다. 초기 local-only backup은 Mac mini 전체 손실에서 함께 사라질 수 있다는 accepted risk를 release evidence에 남긴다.

## D-IMP-3 코드 image apply 단계

1. exact release SHA·fixed GHCR digest·SBOM reference를 strict 검증하고 canonical host root의 directory/symlink/owner 계약을 확인
2. `/private/var/lib/rhaomi/state/locks/rhaomi-deploy.lock`을 atomic `mkdir`로 획득하고 자신의 owner token으로만 해제
3. fixed Compose·`production.env`·Docker credential config의 regular-file·owner·mode, exact release SHA에 바인딩된 4-line eligibility·JSON hash와 provisioned repository의 complete manifest/dump/media full-read를 확인
4. exact manifest digest를 pull하고 RepoDigest·OCI revision·image ID를 writer 정지 전에 확인
5. `rhaomi-web`과 PostgreSQL을 유지하고 backend·publisher를 graceful stop한 뒤 두 container의 `exited`를 확인; public static home 200 재확인
6. 같은 exact image의 `migration` one-shot을 실행하고 writer quiescence를 재확인
7. Flyway-disabled `schema-validate` one-shot을 실행하고 writer quiescence·public web을 재확인
8. backend만 exact digest로 recreate하고 internal health `UP` 후 publisher를 recreate
9. backend·publisher의 runtime image ID가 pulled image ID와 같은지 확인한 뒤에만 own lock을 해제하고 bounded·redacted success evidence와 maintenance release를 기록

input·path·backup·digest 실패는 writer 정지 전에 fail-closed를 우선한다. backup compatibility marker만 신뢰하지 않고 target image 확인 뒤 fixed evidence와 complete set을 다시 읽는다. writer maintenance가 시작된 뒤 migration·schema validation·backend health·publisher start·runtime image identity가 실패하면 backend/publisher를 다시 stop하고 physical quiescence를 확인한 뒤에만 own lock을 해제한다. 정지를 확인할 수 없으면 own lock을 남겨 다음 deploy를 차단하며 old writer를 자동 resume하지 않는다. backend health 실패 전에 publisher를 시작하지 않는다. 이 slice는 production `current` content release를 변경하지 않으며 actual public HTTPS·content switch·first-production acceptance는 D-IMP-6에서 수행한다.

## Flyway

- production backend 일반 기동은 migration을 실행하지 않고 JPA schema validate만 한다.
- migration은 deploy lock과 write maintenance 안의 one-shot service만 수행한다.
- exact `--rhaomi.production-task=migrate`는 Flyway를 활성하고 적용 후 JPA validate를 수행하며, `schema-validate`는 Flyway를 비활성하고 JPA validate만 수행한다. unknown·duplicate task mode는 기동 전 거부한다.
- additive expand/contract를 우선한다.
- column/table 삭제·대량 변환은 별도 승인, on-demand backup과 isolated restore가 필요하다.
- destructive rollback은 검증 전 실행하지 않는다.

## 콘텐츠 배포 단계

- 코드 checkout은 마지막 승인된 main commit을 사용한다.
- publisher는 immediate pending event와 `availableAt <= now`인 scheduled notice event를 처리하고 restart 후 overdue event를 복구한다.
- generated content와 private release manifest에 canonical decimal string `contentRevision`, `publishGeneration`과 microsecond `generatedAt`을 기록하고 exact code SHA·image tag/digest·Flyway·SBOM reference·site tree digest를 결합한다.
- `contentRevision`은 콘텐츠 mutation snapshot이고, mutation 없는 publish/expiry boundary, 승인된 code release와 manual rebuild/retry는 새 `publishGeneration`을 만든다.
- scheduled event마다 current notice row와 전체 snapshot을 다시 검증해 reschedule, draft·archived 전환과 window 변경의 stale event를 no-op 또는 최신 generation에 coalesce한다.
- build API와 transformer에서 published, notice 게시·만료, relation·media와 file을 이중 검증한다.
- 초안, 보관·만료 콘텐츠는 산출물에 포함하지 않는다.
- 선택된 image가 archived, missing, corrupt이거나 변환에 실패하면 전체 배포를 실패시킨다.
- 현재 공개 사이트를 유지한다.
- 동일 `publishGeneration` transient failure는 1분·5분·15분 최대 3회 retry하고 data 오류는 무한 retry하지 않는다.
- atomic switch는 `publishGeneration`을 authority로 비교해 낮거나 같은 generation이 newer `current`를 덮지 못하게 한다.
- candidate는 release root와 같은 filesystem에서 완성·검증한 뒤 immutable package로 설치한다. manifest는 public `site/` root 밖에 두고 `current`·`previous`는 exact release `site/` symlink만 가리킨다.
- switch 직전 current manifest를 다시 읽어 stale 여부를 재확인하고, loopback post-switch serving smoke 실패 시 같은 lock scope에서 이전 symlink 상태를 복구한다.
- 성공 release는 기본 5개를 보존하되 current·previous target은 개수와 무관하게 보호한다.
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

- exact Git SHA, image tag·published OCI index digest와 index-bound `SBOM_REFERENCE`
- workflow run ID, amd64/arm64 manifest·attestation identity, attached SPDX SBOM·SLSA provenance hash와 attached-SBOM scan 결과
- 별도 local validation image의 pre-publish SBOM·scan은 `auxiliary` scope로 명시
- Flyway version·migration 여부와 backup-set ID
- publisher content revision, release ID와 smoke 결과
- `current`·`previous` 전후 target
- Mac canonical root·ownership/permission 확인과 public/media/state/build-workspace bind mapping 및 publisher image source의 workspace 외 read-only 증거
- PostgreSQL production project-scoped named volume exact identity, 일반 `down` persistence와 destructive volume command 부재
- local-only backup repository identity, backup-set ID·manifest/check와 isolated restore evidence
- external/offsite backup은 미구성이면 `NOT_CONFIGURED / DEFERRED`; local 성공을 offsite 성공으로 표현하지 않음
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
- requested exact SHA tag가 이미 존재해 immutable publish가 덮어쓰기를 필요로 함
- GitHub `production` Environment·required reviewer·main policy·Tailscale/SSH authority가 actual provisioning되지 않음
- fixed host config·Docker credential·backup eligibility·global lock 검증 실패
- backend/publisher 정지 확인 전 migration 시도, one-shot migration/schema 또는 backend health/publisher start 실패
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
- 이미 존재하는 exact SHA image tag 덮어쓰기
- `main` merge를 production apply 승인으로 간주
- production Mac mini에서 public PR source build
- 임의 SSH shell body 실행
- caller-supplied production env-file·Docker config·Compose override를 deploy authority로 사용
- migration/schema 실패 후 old backend/publisher 자동 resume
- production backend 일반 기동의 자동 Flyway mutation
- public `/api/build/**`, `/internal/**` 또는 actuator 노출
- 관리자 password·session·bootstrap credential을 CI log에 출력
- Mac host `/srv/rhaomi` 생성, `synthetic.conf` 또는 Docker Desktop custom File Sharing을 필수 전제로 추가
- production `docker compose down -v`, `docker volume prune` 또는 PostgreSQL named volume 직접 삭제
- PostgreSQL raw named volume을 required restic backup이나 restore authority로 사용
