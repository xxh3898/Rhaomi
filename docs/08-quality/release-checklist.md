---
title: "출시 체크리스트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-09-01"
review_trigger: "출시 기준 변경 시"
---

# 출시 체크리스트

## Phase 1C local publication acceptance 증거

- [x] synthetic local bootstrap·same-origin Admin HTTP·multipart upload·full PUT dataset
- [x] draft revision-only, public update/current·previous, Gallery archive removal
- [x] Notice future publish·expiry, overdue recovery, stale reschedule·fixed 30초 coalesce full release
- [x] Build API V2→responsive transformer→Next→immutable release→atomic switch 연결
- [x] AVIF/WebP/JPEG·no-upscale·SHA filename·alt·HEIC normalization·private marker 비노출
- [x] backend·PostgreSQL 중단 후 read-only Nginx home/notice/media/robots/sitemap 200·internal/unknown 404
- [x] static SEO·safe Markdown·현재 public surface 접근성 semantics
- [x] tmpfs/temp root·internal network·single-command cleanup·task container/network 0

위 항목은 synthetic local/CI gate다. 아래 product·content·physical device·production provisioning·deploy 항목은 별도 증거 없이 PASS로 바꾸지 않는다.

## Phase 1D contract freeze 증거

- [x] [Production readiness matrix](../07-operations/production-readiness.md)의 계약·local/CI·구현·provisioning·외부 승인·physical acceptance vocabulary
- [x] ADR-010 topology/code release, ADR-011 publisher, ADR-013 HomeOps, ADR-014 decoder-only runtime contract 정렬
- [x] ADR-012 초기 Mac mini local-only backup amendment와 single-host disaster accepted risk
- [x] 초기 사용자 소유 domain 전략, authenticator private key/RP-side record/recovery-code secret을 분리한 WebAuthn/passkey 2차 인증 target, 실제 매장 운영자 콘텐츠·사진 승인 authority

위 체크는 문서 계약이 승인됐다는 뜻만 가진다. production 구현·provisioning·deploy 항목을 통과시키지 않으며 overall production readiness는 계속 `HOLD`다.

## 제품·콘텐츠

- [ ] 최종 Hero 문구 승인
- [ ] 실제 Hero 이미지
- [ ] 실제 OG 이미지
- [ ] 은총쌤 소개 승인
- [ ] 초기 갤러리
- [ ] 서비스와 가격 문구
- [ ] 예약 전 안내 정책
- [ ] 주소·전화·영업시간·휴무·주차
- [ ] 외부 링크
- [ ] 빈 채널 버튼 미노출
- [ ] 사진 사용 권한

## 관리 backend

- [x] Java production image·Spring Boot·Gradle Wrapper 잠금 버전
- [ ] PostgreSQL 연결
- [ ] Flyway migration과 JPA schema validation
- [ ] server session·CSRF·fixation 방어
- [ ] 관리자 API field allowlist와 anonymous deny
- [ ] build API credential·read-only policy
- [ ] 관리자 WebAuthn/passkey 2차 인증, authenticator private key server 비수집, RP-side credential record, registration revoke/remove, recovery-code secret·rotation과 password-only production 차단
- [ ] 상태 validation
- [ ] archive 운영
- [ ] `/admin/` media list·authenticated preview·single upload·archive/restore와 401/403 non-retry 계약
- [ ] `/admin/` shop settings 404 미초기화·26-key full PUT·canonical response와 audit field 제외
- [ ] Hero·미용사·OG active-only private media picker, image-alt pair와 archived/missing relation clear·replace
- [ ] `/admin/` breed/service strict list·draft create·immutable slug·full PUT·server order 보존·post-mutation canonical GET와 archive/restore
- [ ] 서비스 published description·priceText 보조 검증과 404/409/422 frontend-owned 오류 문구
- [ ] breed/service pending 중복 방지·stale GET 차단·401/403 mutation non-retry와 canonical refresh ready 뒤 enabled trigger keyboard focus 복귀
- [ ] `/admin/` gallery strict list·draft create·full PUT·server order 보존·post-mutation canonical GET와 published/archive/restore
- [ ] gallery breed/service/media catalog 독립 복구, draft/archived 모든 상태 관계와 published 관계·필수값 검증
- [ ] gallery active/archived single media picker, cover 재사용·before/after 상호 배제, private Blob revoke와 keyboard focus lifecycle
- [ ] `/admin/` notice strict list·draft create·immutable slug·source Markdown·pinned·full PUT·server order 보존·post-mutation canonical GET와 published/archive/restore
- [ ] notice published body·publishedAt와 상태 공통 expiry window, unchanged microsecond 보존, 404/409/422 frontend-owned 오류와 401/403 non-retry
- [ ] 공개 영향 transaction과 같은 PostgreSQL transaction의 immediate publishing event
- [ ] future notice publishedAt·expiresAt의 `availableAt` durable scheduled event와 overdue recovery
- [ ] scheduled event current-row/snapshot 재검증과 reschedule·draft/archive·window-change stale no-op/coalesce
- [ ] `contentRevision`·`publishGeneration` 분리와 draft-only 변경 trigger 분류
- [ ] single publisher 30초 debounce·global lock·highest generation coalescing
- [ ] build API read-only와 public `/api/build/**` deny
- [ ] published·notice expiry·relation·media/file의 API/transformer 이중 검증
- [ ] publisher 동일 generation 1분·5분·15분 최대 3회 retry, 승인된 manual rebuild/retry의 새 generation과 data 오류 중단
- [ ] V2 snapshot/release manifest의 canonical decimal string `contentRevision`·`publishGeneration`·`generatedAt`과 `BigInt` generation 기준 stale switch 거부
- [ ] generated V2→Next Static Export→strict final-tree validator→private manifest→immutable release의 exact production image 실행
- [ ] `previous/current` atomic symlink, post-switch home·notice·media·404 smoke·rollback과 current/previous 보호 retention
- [ ] actual publisher child process tree의 physical exit와 global filesystem lock lifetime 일치
- [ ] 실제 iPhone Safari HEIC 선택·upload·preview·archive/restore
- [ ] 실제 iPhone Safari·VoiceOver 견종·서비스 form·status·sortOrder·긴 slug reflow
- [ ] 실제 iPhone Safari·VoiceOver 갤러리 form·relation picker·datetime-local·긴 alt/summary reflow
- [ ] 실제 iPhone Safari·VoiceOver 공지 form·pinned·datetime-local·긴 Markdown·게시/보관/복구 reflow

## 공개 사이트

- [ ] static export
- [ ] raw HTML disabled Markdown·dangerous URL·remote image policy와 public artifact secret/private path 부재
- [ ] generated responsive AVIF·WebP·JPEG hash asset과 missing/orphan file 부재
- [ ] 공개 고객 site의 backend runtime 요청 없음
- [ ] 모바일 반응형
- [ ] 데스크톱
- [ ] 견종 필터
- [ ] lightbox
- [ ] 서비스
- [ ] 공지 상세
- [ ] 404
- [ ] sticky CTA
- [ ] 전화 링크
- [ ] 지도 링크

## 접근성

- [ ] keyboard
- [ ] focus visible
- [ ] dialog focus
- [ ] headings
- [ ] alt
- [ ] contrast
- [ ] 320px reflow
- [ ] 200% text
- [ ] reduced motion
- [ ] VoiceOver 표본

## SEO

- [ ] title/description
- [ ] canonical
- [ ] OG
- [ ] LocalBusiness JSON-LD
- [ ] robots
- [ ] sitemap
- [ ] 실제 404
- [ ] NAP
- [ ] Google Search Console
- [ ] 네이버 서치어드바이저

## 성능

- [ ] 이미지 파생본
- [ ] EXIF 제거
- [x] HEIC image-level 검증
- [x] decoder-only libheif `v1.23.1` exact commit과 libde265 decode gate
- [x] production CMake의 libde265-only fail-closed codec allowlist와 encoder·plugin·experimental path OFF
- [x] production image x265 package·library·link·plugin 부재
- [x] source·license notice·SBOM·image scan gate
- [x] Linux amd64와 Mac mini Linux arm64 actual HEIC fixture gate
- [ ] asset budgets
- [ ] Lighthouse
- [ ] 저속 모바일 표본
- [ ] layout shift
- [ ] third-party SDK 없음

위 완료 표시는 D-IMP-1 source와 local/Hosted validation gate에 한정된다. GHCR publish, production Compose 실제 배치와 iPhone Safari HEIC acceptance는 각각 code release·운영·물리 기기 항목이므로 계속 미완료다.

### D-IMP-2 source·task validation

- [x] external exact image만 사용하는 four-service production Compose source, `build:`·`latest` 0
- [x] web-only loopback port와 web→backend, backend↔PostgreSQL, publisher→backend/DB internal network 경계
- [x] canonical Mac public/media/state/build-workspace bind mapping과 web/media RO·backend/publisher 최소 RW, publisher image source의 workspace 외 read-only rendered contract
- [x] project-scoped PostgreSQL named volume, task 일반 Compose `down`→`up` sentinel·identity persistence
- [x] project Nginx static/admin proxy, relative `/admin` redirect, fixed backend forwarded `https:443`, build/internal/actuator/manifest deny, nested hidden file 404와 query-bearing Referrer access-log 제외
- [x] task temp overlay·one-shot V1~V9 migration/schema validation, normal backend/publisher Flyway·bootstrap 비활성
- [x] native amd64/arm64 validator와 Hosted exact-head evidence, task container/network cleanup

위 완료 표시는 repository source와 task-scoped local/Hosted 환경에만 적용된다. actual `/private/var/lib/rhaomi`·production volume·Secret·loopback/FQDN·Cloudflare/GHCR/deploy evidence 없이 아래 production 항목을 완료 처리하지 않는다.

### D-IMP-3 source·task validation

- [x] `workflow_dispatch` only와 `refs/heads/main`·exact requested 40자 SHA fail-closed gate
- [x] validation read-only, publish-only `packages: write`, deploy-only `environment: production`·environment secret 권한 분리
- [x] canonical Dockerfile required exact-head build arg, `linux/amd64`·`linux/arm64`, exact SHA tag, existing tag overwrite 거부, returned digest apply, published platform manifest·attestation와 OCI source/revision·attached SBOM·provenance·scan evidence
- [x] pinned Tailscale/SSH known-host authority와 fixed `/private/var/lib/rhaomi/app/bin/deploy-rhaomi.sh` + strict `--release-sha`·`--image`·`--sbom` argv
- [x] fixed host config/Docker credential, release-bound backup prerequisite, atomic global deploy lock와 caller override 폐기
- [x] public web 유지 + backend/publisher physical exit 후에만 one-shot Flyway V1~V9→Flyway-disabled schema validation
- [x] migration/schema/backend health/publisher start/runtime image mismatch 뒤 writer quiescence·auto-resume 0, quiescence 미확인 시 own lock 보존, runtime same-image verification·bounded redacted evidence
- [x] malformed task mode·wrong registry/digest/revision·duplicate option·lock contention·secret marker task regression
- [x] Mac mini native Linux arm64와 Hosted Linux amd64의 exact-head D-IMP-1·2·3 source/runtime acceptance

위 항목은 workflow·entrypoint·one-shot task implementation evidence다. actual private GHCR package, GitHub `production` Environment/reviewer/secret, Tailscale identity, Mac entrypoint/config/path/volume, backup set을 provision하지 않았고 production workflow dispatch·package push·deploy·migration을 수행하지 않았으므로 아래 운영 항목은 계속 미완료다.

### D-IMP-4 source·task validation

- [x] deploy와 같은 global operation lock, backend/publisher physical quiescence, public static serving 유지
- [x] `pg_dump -Fc`+private canonical media의 같은 set ID, strict manifest V1과 `.incomplete`→read-only complete atomic promotion
- [x] fixed repository config·owner/mode/sentinel, symlink·path traversal·special file와 secret artifact/log fail-closed
- [x] exact target eligibility JSON·4-line compatibility hash chain과 deploy의 writer mutation 전 full-read 재검증
- [x] scheduled/on-demand/predeploy·structural/full-read·retention dry-run/apply fixed mode와 03:30 KST schedule source
- [x] fixed backup wrapper의 `docker`·standalone `docker-compose` fail-closed binary 확인
- [x] daily 7 / weekly 4 / monthly 6, latest 3·on-demand 보호와 incomplete/latest-corrupt/<3 verified apply refusal
- [x] source A→backup→source B mutation→fresh PostgreSQL named volume/media root restore A, schema/audit/relation/media decode/static publication 검증
- [x] PostgreSQL restart·일반 Compose `down`→`up` persistence, task container/network cleanup과 volume/image deletion 0

위 완료 표시는 source와 task-scoped local/Hosted evidence다. actual Mac repository path·capacity·scheduler, production DB/media backup, production restore/RPO·RTO는 계속 아래 운영 항목에서 미완료다.

## 보안·운영

- [ ] HTTPS
- [ ] Cloudflare Tunnel → host edge Nginx → loopback project Nginx
- [ ] same-origin `/admin`, `/api/admin/**` route
- [ ] public `/api/build/**`, `/internal/**`, `/actuator/**` deny
- [ ] DB 외부 비공개
- [ ] publisher public network·Docker socket 부재
- [ ] secrets scan
- [ ] production session `Secure`, TLS와 관리자 WebAuthn/passkey 2차 인증·RP/private-key 경계 확인
- [ ] exact main SHA·immutable image·digest와 `latest` 부재
- [ ] GitHub production environment 수동 승인과 고정 Tailscale deploy entrypoint
- [ ] write maintenance·one-shot Flyway·schema validate·expand/contract
- [ ] 실제 Mac `/private/var/lib/rhaomi/{app,public,data/media,state,logs}` 생성·ownership·permission
- [ ] Mac public/media/state/build-workspace source의 Docker Desktop bind smoke와 web RO·backend/publisher 최소 write mode, publisher image source의 workspace 외 write 거부
- [ ] `/srv/rhaomi/public`이 web/publisher Linux container target일 뿐 Mac host source가 아님
- [ ] PostgreSQL host PGDATA bind 부재, production project-scoped named volume exact identity·mount
- [ ] PostgreSQL container restart와 일반 Compose `down`·`up` 뒤 DB persistence
- [ ] production `docker compose down -v`, `docker volume prune`, named volume direct delete 금지
- [ ] DB `pg_dump -Fc`와 canonical media의 동일 backup-set manifest
- [ ] raw PostgreSQL volume required restic input 부재와 새 isolated named volume `pg_restore` 검증
- [ ] protected source와 분리된 Mac mini local backup repository/path·ownership·permission·capacity
- [ ] 동일 `pg_dump -Fc`·canonical media backup-set manifest와 local RPO evidence
- [ ] daily 7 / weekly 4 / monthly 6와 post-prune check
- [ ] quarterly isolated full restore, local RPO 24h·RTO 8h evidence
- [ ] initial local-only backup의 single-host disaster accepted risk가 release evidence·HomeOps에 명시됨
- [ ] `current`·`previous` atomic switch·rollback과 성공 release 5개 보존
- [ ] HomeOps 단일 관제·incident·Activity·Discord authority
- [ ] public/internal/container/host/DB/publisher/backup monitor와 임계값
- [ ] stateless single restart의 lock·30분 cooldown·audit와 금지 범위
- [ ] same-host blind spot 수용 기록
- [ ] service당 약 100 MiB bounded log와 일반 14일·incident hold

## 승인

- [ ] 은총쌤 콘텐츠 승인
- [ ] 조치호 기술 승인
- [ ] release evidence
- [ ] 남은 위험 명시
- [ ] production deploy 승인

## Future hardening — 초기 production blocker 아님

- [ ] 외장 SSD encrypted restic repository와 `/Volumes/<provisioned-volume>/...` exact identity
- [ ] iCloud Drive separate encrypted restic repository
- [ ] local iCloud integrity와 Apple remote sync 증거 분리, remotely verified offsite RPO
- [ ] second trusted device 또는 clean retrieval path fresh retrieval·restic check·대표 restore
- [ ] repository recovery key의 password manager+별도 offline copy

위 항목은 미구성 시 `NOT_CONFIGURED / DEFERRED`다. `[ ]` 상태를 초기 production 실패로 해석하지 않으며 local backup 성공으로 offsite `PASS`를 만들지 않는다.
