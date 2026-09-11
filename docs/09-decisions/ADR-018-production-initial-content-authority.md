---
title: "ADR-018: Production 초기 콘텐츠 authority"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-11"
review_trigger: "초기 콘텐츠 bundle·one-shot import·첫 publication 경계 변경 시"
---

# ADR-018: Production 초기 콘텐츠 authority

- 결정일: 2026-09-11
- 상태: Accepted
- 관련 결정: [ADR-011](ADR-011-transactional-outbox-static-publisher.md), [ADR-016](ADR-016-verified-empty-first-production-activation.md), [ADR-017](ADR-017-production-initial-admin-authority.md)

## 맥락

Verified-empty activation과 최초 관리자 authority 이후에도 owner가 승인한 초기 ShopSettings·Breed·Service·Notice·Gallery·media를 application contract로 적재하는 production source가 없었다. Raw SQL이나 fixture injection은 request/value-object validation, media normalization, relation, audit, transactional outbox를 우회한다. 반대로 일반 admin API를 여러 번 호출하면 전체 초기 상태와 첫 publication event를 하나의 transaction으로 확정할 수 없다.

Issue #95의 Stage 0B 결정과 Issue #97은 owner-approved bundle을 fixed one-shot non-web task로 읽고, 기존 application validation과 canonical publisher만 재사용하는 방향을 승인했다. 이 ADR은 source capability를 고정하며 actual owner bundle 작성·운반·production 실행 승인을 포함하지 않는다.

## 결정

### Tracked bundle contract

- 구조 authority는 [`manifest-v1.schema.json`](../../contracts/initial-content/manifest-v1.schema.json)과 [`content-v1.schema.json`](../../contracts/initial-content/content-v1.schema.json)이다.
- bundle root에는 `manifest.json`, `content.json`, `media/<direct-file>`만 허용한다. Manifest는 exact schemaVersion, content/media count, byte size, SHA-256, logical key와 상대 경로를 기록한다.
- parser는 각 JSON object의 exact field set과 duplicate JSON key를 확인한다. Logical key 중복, 미추적·extra file, count/hash/size 불일치, absolute/traversal path, symlink, hard link, nested media directory를 거부한다.
- tracked schema는 구조와 wire type authority다. 문자열 길이·blank·slug·관계·시간 window·media byte semantic은 기존 request/value-object/build validator가 최종 authority이며 import가 이를 완화하지 않는다.
- actual owner content와 media는 저장소에 두지 않는다. Fixed host wrapper가 선택하는 `/private/var/lib/rhaomi/state/initial-content`만 read-only mount로 제공하며 caller path, hook, command override는 없다.

### One-shot application boundary

- `--rhaomi.production-task=initial-content`는 `WebApplicationType.NONE`, Flyway disabled, JPA `validate` 전용 context다.
- context는 필요한 entity/repository, Bean Validation, 기존 admin domain service, media ingestion/normalization, `PublicationRecorder`, transaction advisory lock만 구성한다. Controller, HTTP listener, normal bootstrap, publisher loop/executor는 생성하지 않는다.
- canonical Compose `initial-content` service는 exact production image, PostgreSQL internal network, bundle read-only mount와 canonical media read-write mount만 가진다. Port, public release/state/lock/backup/deploy-state/Docker socket mount, build credential, passkey/admin credential은 없다.

### Pristine state와 원자성

- 같은 PostgreSQL transaction에서 fixed transaction advisory lock을 먼저 획득한다.
- lock 획득 뒤 exactly one active admin을 요구하고 ShopSettings·Breed·Service·Notice·Gallery·media DB row, outbox, content revision, publish generation이 모두 zero인지 다시 확인한다.
- canonical media root는 storage가 만드는 empty `temp`·`masters` directory 외 entry를 허용하지 않는다. 기존 regular file, link, unexpected directory나 판정 실패는 partial state로 거부하며 기존 파일을 삭제하지 않는다.
- 동시 invocation과 replay에서 commit 성공은 최대 하나다. Content row, relation, audit, revision과 outbox는 모두 한 transaction에서 commit되거나 rollback된다.
- media는 기존 `MediaAdminService`의 JPEG/PNG passthrough, HEIC/HEIF normalization, size/dimension/pixel, orientation/sRGB/metadata strip, private storage를 그대로 사용한다. 실제 소비한 source byte size와 SHA-256을 manifest와 다시 대조하고 DB rollback 시 기존 transaction synchronization으로 새 master를 정리한다.

### First-publication content

- Bundle은 ShopSettings와 최소 한 개 이상의 media, published Breed, published Service, published Notice, published Gallery를 포함해야 한다.
- Notice/Gallery `publishedAt`은 import 시각보다 미래일 수 없고 Notice는 이미 만료된 상태일 수 없다. UTC Instant는 최대 microsecond precision으로 제한한다.
- Gallery relation과 Shop media/alt pair는 logical key를 UUID로 치환한 뒤 기존 domain/build validation으로 다시 검증한다.
- Audit actor는 import 실행 전 확인한 유일한 active admin이다. ID나 audit field를 bundle이 주입하는 경로는 없다.

### Publication authority

- 개별 import service 호출은 public-impact event를 각각 만들지 않는다. 모든 canonical row와 관계가 완성된 뒤 `PublicationRecorder`로 content revision을 한 번 증가시키고 immediate `PENDING` outbox event 하나를 기록한다.
- Future Notice expiry가 있으면 같은 transaction과 같은 최초 `contentRevision`에 기존 scheduled expiry event를 추가할 수 있다. 이 event는 첫 immediate build를 대체하지 않는다.
- `publishGeneration`은 import task가 할당하지 않는다. 기존 publisher가 pending event를 claim할 때 monotonic generation을 할당한다.
- 첫 static release는 기존 exact opt-in publisher의 Build Snapshot V2 → transformer → candidate validation → atomic current switch → post-switch smoke → DB completion만 사용한다. Import task는 transformer, release directory, current/previous를 직접 호출하거나 수정하지 않는다.
- DB commit 뒤 publisher가 실패하면 content와 durable pending event를 보존한다. 자동 re-import, generation reset, empty release, success/noop 오기록을 금지하며 기존 authorized retry/rebuild만 별도 승인으로 사용한다.

### Host lifecycle

- fixed host authority는 `/private/var/lib/rhaomi/app/bin/import-initial-content-rhaomi.sh`이며 argument는 0개다.
- wrapper는 owner/mode, valid `STEADY_STATE`, backend/publisher의 동일 exact GHCR digest와 OCI revision을 mutation 전에 검증한다.
- deploy/backup/initial-admin과 같은 `rhaomi-deploy.lock` 안에서 writer를 stop하고 physical `exited`를 확인한 뒤 task를 실행한다.
- task 성공·실패 뒤 동일 image backend health와 publisher running을 복구한 뒤에만 자기 lock을 해제한다. Quiescence나 recovery가 불확실하면 false success를 금지하고 lock을 보존한다.
- 성공 evidence에는 manifest SHA-256, count, content revision, `PENDING`만 기록한다. 콘텐츠 문자열, media bytes, credential, internal URL, private host path는 기록하지 않는다.

## 이유

- Application request/value-object/media/build validator를 재사용해 production만의 별도 data 의미를 만들지 않는다.
- transaction advisory lock, pristine re-check, one transaction으로 replay와 partial bootstrap을 fail-closed한다.
- First content commit과 public release를 분리해 content durability와 publisher failure semantics를 보존한다.
- Fixed path·service·mount·shared lifecycle lock으로 caller가 production trust boundary를 넓히지 못하게 한다.

## 거부한 대안

### Raw SQL 또는 repository fixture insert

Validation, relation, media normalization, audit와 publication recorder를 우회하므로 거부한다.

### Admin HTTP API 순차 호출

여러 transaction 사이 partial visibility와 여러 public event를 만들며, 전체 bundle atomicity를 보장하지 못해 거부한다.

### Import task의 직접 build/release 설치

Build API, transformer validation, candidate smoke, stale-generation re-check, atomic switch와 publisher completion authority를 우회하므로 거부한다.

### Bundle path 또는 hook argument

Arbitrary filesystem/command trust boundary를 추가하고 fixed production invocation을 깨므로 거부한다.

## 결과와 남은 gate

- Source gate는 structural parser, PostgreSQL/Flyway integration, concurrency/replay/rollback/media cleanup, Build Snapshot V2, Compose/fixed-wrapper regression으로 검증한다.
- 기존 publisher/transformer/release E2E는 동일 pending event와 Build Snapshot V2 contract의 downstream authority다. Source/CI PASS를 actual owner content나 첫 production release PASS로 표시하지 않는다.
- #96·#97 dev integration, dev→main Source Release, main→dev tree-neutral back-sync, exact-main CI/security 재검증 전에 Issue #95 Stage A를 실행하지 않는다.
- Actual bundle transport와 approval evidence, production invocation, first static release, passkey/recovery acceptance, ingress activation은 모두 별도 owner gate다.

## 비수행 경계

이 ADR의 source 구현은 actual owner bundle 작성·commit·upload, PR merge, Source Release, production workflow dispatch, GHCR publish, GitHub Environment/Secret/Variable, `/private/var/lib/rhaomi`, production Docker/DB/media/public/backup/migration, Cloudflare/HomeOps를 변경하지 않는다.
