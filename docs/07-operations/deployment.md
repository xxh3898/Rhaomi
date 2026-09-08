---
title: "배포"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-02"
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

[ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md)의 topology와 release 절차에 따라 canonical image·Compose·project Nginx, `.github/workflows/production-release.yml`, fixed deploy entrypoint와 non-web one-shot Flyway/schema task를 구현했다. D-IMP-4는 같은 operation lock을 쓰는 fixed backup entrypoint, strict complete set과 exact-release eligibility bridge를 추가했다. [ADR-016](../09-decisions/ADR-016-verified-empty-first-production-activation.md)은 verified-empty evidence, public ingress 없는 one-time bootstrap, 첫 backup·isolated restore acceptance와 `STEADY_STATE` 전환 source를 추가했다. D-IMP-5a는 deploy/backup lifecycle의 HomeOps current exact payload adapter와 bounded status/recovery target을 추가했고, HomeOps D-IMP-5b는 incident decision·V14 mapping/audit·durable 30분 cooldown을 구현했다. HomeOps application과 V14는 별도 HomeOps release에서 production에 배포됐지만, 이 Rhaomi source와 task-scoped local/Hosted evidence는 private GHCR package/visibility, GitHub `production` Environment·reviewer·secret, Tailscale identity, actual Rhaomi host entrypoint/path·volume·backup repository·HomeOps monitor/control·schedule·Secret·FQDN을 provision한 것이 아니다. Rhaomi workflow dispatch·release·deploy·production migration·backup·restore·mapping·Agent rollout·restart는 수행하지 않았다.

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

## D-IMP-2·D-IMP-3·D-IMP-4·D-IMP-5·first-activation source validation

- canonical base는 `/private/var/lib/rhaomi` bind source와 project-scoped PostgreSQL named volume을 유지한다.
- validation overlay만 task temp root와 labeled one-shot service를 사용한다.
- native architecture에서 web-only loopback, network adjacency, mount RO/RW, static/admin/deny route, internal Build API auth, migration·schema one-shot·malformed mode, read-only backup verifier의 repository/deploy-state write 실패·media/network/credential 부재, writer 정지 중 public serving과 일반 Compose `down`→`up` sentinel persistence를 검증한다.
- fake Docker task harness가 wrong registry·malformed/duplicate input의 mutation 전 거부, lock contention, digest/revision, missing/malformed/stale eligibility와 target verifier failure에서 writer stop 0·repository mutation 0, migration/schema·backend health·publisher start·runtime backend/publisher image mismatch 실패 뒤 writer auto-resume 0과 quiescence, secret redaction을 검증한다.
- backup control harness는 deploy/backup lock contention, writer physical exit 전 dump 금지, complete 승격 전 writer 재기동 금지, capture/restart failure와 lock hold를 검증한다.
- actual task validator는 상태 A를 backup한 뒤 source DB/media를 B로 바꾸고 fresh named volume·media root에 A를 `pg_restore`/복사해 schema·audit/relation·media decode·static publication·restart/down-up persistence를 확인한다.
- first-activation harness는 truly-empty predecessor만 허용하고 current/previous/deploy marker·eligibility·complete set·production container/volume·media/public authority와 unknown/contradictory state를 mutation 전에 거부한다. exact release의 비공개 bootstrap 실패는 `UNINITIALIZED`로 되돌리지 않고, 첫 complete backup의 read-only full-read·no-port isolated PostgreSQL/media/Flyway V10/API/static acceptance 뒤에만 `STEADY_STATE`를 기록한다.
- HomeOps task harness는 deploy RUNNING→SUCCESS/FAILED의 같은 lifecycle identity, spool acknowledgement와 local telemetry failure 분리, secret/private path 0을 확인한다. operation lock·writer fail-close는 event reporter 장애 때문에 바뀌지 않는다.
- production Compose의 HomeOps generic control label은 `rhaomi-web` 하나이고 backend/publisher/PostgreSQL/task service opt-in은 0이다. Fixed recovery target source는 web/backend allowlist를 제공하지만 automatic recovery mapping은 public HTTPS expected HTTP status 3회 실패 → `rhaomi-web` 하나만 승인됐고 backend는 unmapped/default-none이다. Keyword/body/content matcher는 current trigger에서 제외한다.
- task container/network는 정리하지만 task PostgreSQL volume과 image는 삭제하지 않는다.
- 위 evidence는 아래 최초 배포 사전 조건의 actual Mac·production 항목을 완료 처리하지 않는다.

## HomeOps production activation preflight

[Tracked preflight](../../ops/production/homeops-activation-preflight.json)는 historical D-IMP-5b source evidence와 current production release evidence를 분리한다. Live 재검증된 production compatibility authority는 HomeOps `main@0a8ce9090c76f5ad7afba19ca896e923b96b0cbf`다. Publish and Deploy run `33569523762`의 application deploy와 V14 `APPLIED`, Agent artifact `PUBLISHED` exact digest를 기록하되 Agent rollout은 `NOT_RUN`으로 분리한다.

Cross-repository release 순서는 `HomeOps release → live compatibility 재검증 → Rhaomi release/provisioning`이다. 앞의 두 단계는 exact main/tree/run과 reporter·DTO·monitoring contract 대조를 통과했고, Rhaomi release/provisioning은 아직 시작하지 않았다. 향후 HomeOps authority가 바뀌거나 current runtime 결과가 불확실해지면 Rhaomi release/provisioning을 시작하지 않는다.

Activation은 V14 production state 확인 → public HTTPS expected HTTP status monitored-service의 disabled `rhaomi-web` mapping → fixed Rhaomi inventory → exact Agent rollout과 fresh capability → read-only end-to-end compatibility → explicit enable approval → controlled single drill approval → post-health/audit/Activity → observation window 순서다. Backend mapping은 만들지 않는다. Deploy/backup shared lock, current runtime identity, 정상 backup/restore eligibility와 previous Agent rollback identity 중 하나라도 불확실하면 중단한다.

Durable cooldown은 30분이고 `FAILED`·`OUTCOME_UNKNOWN` no-auto-retry를 유지한다. Mapping enable과 actual restart/drill은 각각 별도 승인 대상이며 Issue #61에서는 수행 금지다. Rhaomi release/provisioning, mapping create/enable, Agent rollout과 notification activation도 이 Issue에서 실행하지 않는다.

후속 실패 시 web mapping을 disable/default-none으로 되돌리고 V14 mapping/audit table과 attempt evidence는 보존한다. Previous exact Agent artifact rollback, HomeOps application rollback과 DB migration state를 별도로 판정하며, failed/unknown attempt를 즉시 재실행하거나 owner가 불확실한 shared lock을 삭제하지 않는다.

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
- [ ] approved job이 만든 24시간 미만의 exact release SHA-bound D-IMP-4 backup eligibility·manifest evidence
- [ ] 관리자 password+WebAuthn/passkey 2차 인증, authenticator private key server 비수집, RP-side credential ID·public key·필요 metadata, registration revoke/remove, recovery-code secret의 password manager+별도 offline copy·rotation
- [ ] exact released image의 bounded login rate limit과 generic 429·positive `Retry-After`, credential/service failure·concurrency·single-process restart-reset 제한 검증; production evidence 전 public 관리자 인증 차단
- [ ] protected source와 분리된 Mac mini local backup repository/path·ownership·permission·capacity
- [ ] `pg_dump -Fc`와 canonical media를 묶은 동일 backup-set manifest·retention·check
- [ ] isolated full restore drill
- [ ] `pg_dump -Fc` backup을 새 isolated PostgreSQL named volume에 `pg_restore`하고 row/schema 검증
- [ ] Flyway migration 적용·검증
- [ ] one-shot Flyway·schema validate·expand/contract 검증
- [ ] publisher immediate/due event·overdue recovery·두 revision·lock·retry·atomic switch 검증
- [ ] `HomeOps release → live compatibility 재검증 → Rhaomi release/provisioning` 순서와 exact candidate/authority evidence
- [ ] D-IMP-5a fixed inventory, D-IMP-5b V14와 public HTTPS expected HTTP status 3회→`rhaomi-web` disabled mapping·30분 cooldown·Agent capability·alert/control provisioning; backend mapping 없음
- [ ] 별도 승인된 mapping enable과 controlled single restart/drill, post-health/audit/Activity·observation evidence
- [ ] decoder-only HEIC image와 x265 absence·SBOM 검증
- [ ] 실제 매장 운영자의 NAP·영업정보·정책·문구·링크 최종 승인
- [ ] 실제 매장 운영자의 Hero/OG/프로필/시술 사진과 반려견/고객 사진 게시 권한 승인
- [ ] Nginx 404와 security headers
- [ ] 롤백 검증

외장 SSD·iCloud 3-2-1, restic recovery key와 remotely verified offsite RPO는 초기 production 사전 조건이 아니라 future hardening이다. 미구성 상태는 `NOT_CONFIGURED / DEFERRED`이며 local backup 성공으로 offsite `PASS`를 만들지 않는다. 초기 local-only backup은 Mac mini 전체 손실에서 함께 사라질 수 있다는 accepted risk를 release evidence에 남긴다.

## 최초 production activation 단계

이 경로는 predecessor가 전혀 없는 host에서 dispatch input `first-activation`을 명시했을 때만 사용한다. runtime 상태로 자동 선택하지 않는다.

1. fixed host inventory·owner/mode와 empty backup repository sentinel을 확인하고 deploy/backup과 같은 global operation lock을 획득한다.
2. current·previous·deploy marker·eligibility·complete set·production container/volume·canonical media/public predecessor가 모두 부재인지 검증한다.
3. target SHA와 absence matrix를 담은 owner-only verified-empty evidence와 `FIRST_ACTIVATION_BOOTSTRAPPING` state를 mutation 전에 원자 기록한다.
4. exact digest/revision을 확인하고 `rhaomi-web` 없이 PostgreSQL→one-shot Flyway V1~V10→schema validation→backend health→publisher running/same-image를 확인한다.
5. `RECOVERY_ACCEPTANCE_REQUIRED`를 기록하고 fixed backup entrypoint의 `first-activation` mode로 writer quiescence·DB/media capture·writer recovery·complete full-read를 수행한다.
6. 별도 no-port recovery Compose에서 read-only verifier, tmpfs PostgreSQL `pg_restore`, isolated media restore, Flyway V10/JPA, empty source core rows와 synthetic row/media/API/static publication을 검증한다.
7. recovery project의 physical 종료 뒤 exact backup-set/hash evidence와 `STEADY_STATE`를 원자 기록한다.

어느 단계든 실패·중단·상태 불확실이면 public/admin/content activation과 steady-state marker를 금지한다. partial state를 empty로 되돌리거나 자동 retry하지 않으며, `STEADY_STATE` 이후 이 경로는 영구 거부한다. 실제 public FQDN, WebAuthn RP/account/passkey/recovery code, 콘텐츠, Cloudflare와 HomeOps mapping은 이 단계의 성공만으로 활성화하지 않는다.

## D-IMP-3 코드 image apply 단계

1. GitHub `production` Environment 승인 뒤 fixed SSH argv로 exact release SHA-bound `predeploy` backup을 실행하고 complete set·eligibility 발급 성공을 확인한다. 실패하면 deploy entrypoint를 호출하지 않는다.
2. deploy entrypoint가 exact release SHA·fixed GHCR digest·SBOM reference를 strict 검증하고 `/private/var/lib/rhaomi/state/locks/rhaomi-deploy.lock`을 atomic `mkdir`로 획득한다.
3. fixed Compose·`production.env`·Docker credential config의 regular-file·owner·mode, 4-line compatibility target/hash, eligibility evidence SHA, provisioned repository sentinel/canonical root를 host-side envelope로 검증한다. 이 단계는 image pull 전이다.
4. exact manifest digest를 pull하고 RepoDigest·OCI revision·image ID를 writer 정지 전에 확인한다.
5. 같은 exact target image의 fixed `backup-verifier`가 read-only repository/deploy-state mount에서 evidence exact shape, complete manifest/dump/media full-read와 `createdAt`·manifest `verifiedAt`의 strict `<24h` freshness를 확인한다.
6. `rhaomi-web`과 PostgreSQL을 유지하고 backend·publisher를 graceful stop한 뒤 두 container의 `exited`를 확인하며 public static home 200을 재확인한다.
7. 같은 exact image의 `migration` one-shot을 실행하고 writer quiescence를 재확인한다.
8. Flyway-disabled `schema-validate` one-shot을 실행하고 writer quiescence·public web을 재확인한다.
9. backend만 exact digest로 recreate하고 internal health `UP` 후 publisher를 recreate한다.
10. backend·publisher의 runtime image ID가 pulled image ID와 같은지 확인한 뒤에만 own lock을 해제하고 bounded·redacted success evidence와 maintenance release를 기록한다.

fixed deploy entrypoint는 host/config 검증 뒤 같은 `eventKey`·`startedAt`으로 HomeOps deployment `RUNNING`을 기록하고 transaction 종료에 `SUCCESS` 또는 stable failure code의 `FAILED`를 기록한다. HomeOps reporter가 private spool에 보존하지 못하면 `homeOpsTelemetry=failed`로 분리하지만 deployment transaction 자체를 credential/network 오류로 바꾸지 않는다. reporter·HMAC endpoint와 secret은 caller input이나 Rhaomi environment가 아니다.

input·path·backup envelope·digest·target verifier 실패는 writer 정지 전에 fail-closed한다. compatibility marker만 신뢰하지 않으며 host envelope는 pull 전에, target-image full-read는 pull 뒤에 수행한다. verifier root/repository/deploy-state는 read-only이고 media·network·credential이 없으므로 target image code가 recovery authority를 mutate할 수 없다. writer maintenance가 시작된 뒤 migration·schema validation·backend health·publisher start·runtime image identity가 실패하면 backend/publisher를 다시 stop하고 physical quiescence를 확인한 뒤에만 own lock을 해제한다. 정지를 확인할 수 없으면 own lock을 남겨 다음 deploy를 차단하며 old writer를 자동 resume하지 않는다. backend health 실패 전에 publisher를 시작하지 않는다. 이 slice는 production `current` content release를 변경하지 않으며 actual public HTTPS·content switch·first-production acceptance는 D-IMP-6에서 수행한다.

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
