---
title: "ADR-011: Transactional outbox와 정적 publisher"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-30"
review_trigger: "공개 콘텐츠 trigger·build API·publisher·정적 전환 방식 변경 시"
---

# ADR-011: Transactional outbox와 정적 publisher

- 결정일: 2026-08-29
- 상태: Accepted
- 관련 결정: [ADR-003](ADR-003-static-publish-on-content-change.md), [ADR-004](ADR-004-static-media-copy.md), [ADR-008](ADR-008-runtime-independent-public-site.md), [ADR-010](ADR-010-production-topology-and-code-release.md)

## 맥락

관리 API의 저장 성공과 정적 공개 반영 성공은 서로 다른 transaction이다. DB commit 뒤 best-effort hook만 호출하면 event를 잃을 수 있고, deploy hook과 builder를 별도 상시 서비스로 나누면 현재 규모에 비해 lock·retry·상태 경계가 늘어난다.

공개 build는 draft, 만료 공지, 보관된 relation과 private file을 누출하지 않아야 하고 실패한 build가 현재 정상 사이트를 덮어쓰지 않아야 한다. Notice는 미래 `publishedAt`과 `expiresAt`, Gallery는 미래 `publishedAt`을 허용하므로 저장 transaction 외에도 시간 경계 자체가 durable public build trigger여야 한다. 이 결정은 [ADR-003](ADR-003-static-publish-on-content-change.md)의 재빌드 방향을 구체화하며 이를 대체하지 않는다.

## 결정

### durable trigger

```text
관리 API transaction
→ 같은 PostgreSQL transaction에서 immediate·scheduled publishing event 기록
→ commit
→ internal publisher가 pending 또는 due event 처리
```

- `shop_settings`, Notice, Breed/Service, Gallery와 Media 가운데 공개 결과 또는 eligibility에 영향을 주는 변경만 immediate outbox를 기록한다. 지원되는 성공 mutation은 public trigger 유무와 별개로 `contentRevision`을 증가시킨다.
- 공개 eligibility에 영향을 주지 않는 draft-only 수정과 Media upload는 불필요한 immediate build를 만들지 않도록 application에서 분류한다.
- 공개 상태 진입·이탈, published relation 변경, 만료·게시 window 변경과 공개 선택 media 변경은 항상 trigger 대상이다.
- outbox와 콘텐츠 변경은 같은 PostgreSQL transaction에서 commit되거나 함께 rollback된다.
- Notice create/update transaction이 non-null `publishedAt` 또는 `expiresAt`을 새로 설정·변경하면 status·현재 시각과 무관하게 같은 transaction에 해당 boundary의 durable scheduled publishing event를 기록한다.
- Gallery가 published로 진입하거나 published 상태에서 non-null `publishedAt`을 변경하면 같은 transaction에 durable `GALLERY_PUBLISHED_AT_DUE` event를 기록한다.
- scheduled event에는 최소 typed event kind, `availableAt`, source type·ID, event를 만든 content revision과 expected boundary 값을 기록한다.
- publisher는 즉시 실행 가능한 pending event와 `availableAt <= now`인 scheduled event를 처리한다. boundary 동안 publisher가 중단돼도 restart 뒤 overdue event를 다시 claim한다.
- scheduled event를 물리 삭제해야 correctness가 성립하는 계약으로 만들지 않는다. 처리 완료·stale no-op 상태를 내구적으로 기록할 수 있다.
- 관리 API 저장 성공, publisher 처리 중, 공개 성공·실패 상태를 구분한다.

#### 현재 구현 경계 — Phase 1C-8f1·8f2·8f3·8f4

- Flyway V8은 `(1, 0)` singleton `content_revision_state`와 immediate·Notice/Gallery scheduled kind를 제한한 `publishing_outbox`를 만든다.
- application recorder는 기존 content transaction을 필수로 요구하며 row increment와 필요한 event insert를 최종 domain persistence 뒤 한 번 수행한다.
- content·revision·event 중 하나라도 실패하면 모두 rollback되고, Media는 transaction completion cleanup으로 이동한 final master도 제거한다.
- Flyway V9은 `(1, 0)` transactional `publish_generation_state` singleton과 outbox `PENDING | PROCESSING | RETRY_WAIT | SUCCEEDED | NOOP | FAILED | COALESCED`, unique generation, owner·lease·attempt·fixed result·coalesced target을 추가한다.
- internal Java state service는 `(availableAt, id)`·`FOR UPDATE SKIP LOCKED`로 fresh pending/due row를 claim하고 generation 할당·첫 attempt를 같은 transaction에 기록한다. rollback은 generation을 소비하지 않는다.
- scheduled claim은 current Notice·Gallery의 published 상태와 expected boundary만 최소 확인한다. stale이면 generation 없이 `NOOP / STALE_TRIGGER`이며 relation·media·file·`generatedAt` eligibility는 current build API와 독립 transformer가 다시 검증한다.
- active owner·generation·lease guard, same-generation expired lease recovery와 1분·5분·15분 retry, 총 attempt 4회, typed terminal result와 lower→higher active coalesce primitive를 구현했다.
- Phase 1C-8f3은 별도 stateless service credential로 active generation의 read-only `REPEATABLE READ` snapshot과 public-scope canonical media 조회를 구현했다. exact DTO에는 producer/outbox row, claim owner·lease·event ID를 노출하지 않는다.
- Phase 1C-8f4는 exact snapshot·relation·eligibility·media manifest를 재검증하고 responsive public derivative와 deterministic content/media manifest를 새 atomic staging target에 만드는 transport-independent transformer를 구현했다.
- actual polling loop·build API HTTP client·30초 debounce orchestration·Next render와 build/release 처리는 없다. prune 정책도 후속 범위다.

### revision과 public ordering

- `contentRevision`은 지원되는 콘텐츠 domain의 성공한 mutation마다 같은 transaction에서 증가하는 snapshot revision이다. 공개 eligibility에 영향 없는 draft-only mutation도 revision은 증가하지만 immediate public trigger는 만들지 않는다. 시간 경계만 도달하면 콘텐츠 row가 바뀌지 않으므로 증가하지 않는다.
- `publishGeneration`은 실제 public build trigger가 처리 가능 상태로 승격될 때 PostgreSQL에서 할당하는 단조 증가 sequence다.
- eligible event claim, `publishGeneration` 할당과 첫 attempt 상태 기록은 하나의 PostgreSQL transaction으로 처리한다. crash로 claim lease가 만료되면 같은 generation의 attempt를 복구하고 임의의 더 낮은 generation으로 재생성하지 않는다.
- immediate 콘텐츠 mutation, due `publishedAt`, due `expiresAt`, 승인된 code release와 운영자가 승인한 manual rebuild/retry는 각각 `publishGeneration` ordering에 포함한다.
- transient failure의 자동 attempt retry는 같은 `publishGeneration`을 유지한다. 운영자가 새로 승인한 rebuild/retry는 새 immediate event와 새 generation을 만든다.
- 같은 `contentRevision`에서도 게시·만료 경계와 승인된 rebuild에 따라 서로 다른 `publishGeneration`과 public release를 만들 수 있다.
- 가까운 여러 trigger를 debounce/coalesce하면 가장 높은 accepted `publishGeneration`을 target으로 사용하고 중간 generation은 no-op 또는 coalesced 결과로 기록한다.

### 단일 internal publisher

초기 규모에서는 deploy hook과 builder를 분리하지 않고 internal publisher 하나가 다음을 담당한다.

1. immediate pending event와 due scheduled event poll·claim, generation·attempt 기록
2. 첫 변경 뒤 30초 debounce
3. Mac `/private/var/lib/rhaomi/state/locks`를 bind한 publisher container `/var/lib/rhaomi/locks`의 global filesystem lock
4. 대기 trigger를 가장 높은 `publishGeneration`으로 coalesce
5. 일관된 read-only 콘텐츠·media snapshot 획득
6. responsive derivative 생성
7. 현재 승인된 production `main` image/digest로 Next Static Export
8. HTML, internal link, SEO, asset, route 검증
9. 새 release 설치와 `current` atomic switch
10. attempt/result, `contentRevision`, `publishGeneration`, `generatedAt`, release ID 기록

- publisher는 public network와 Docker socket을 사용하지 않는다.
- publisher의 filesystem 접근은 필요한 read-only media와 release/state/lock write 경로로 제한한다.
- release host source는 `/private/var/lib/rhaomi/public`이고 publisher container target은 `/srv/rhaomi/public`이다. `releases`, `current`, `previous` 조작은 container target에서 수행되지만 Mac host authority는 `/private/var/lib/rhaomi/public`이다.
- publisher state host source는 `/private/var/lib/rhaomi/state/publisher`, lock source는 `/private/var/lib/rhaomi/state/locks`다. 둘을 각각 publisher container `/var/lib/rhaomi/publisher`, `/var/lib/rhaomi/locks`로 read-write mount한다.
- code release와 content release는 같은 build·validate·switch 구현을 사용한다.
- content release는 임의 branch가 아니라 현재 승인된 production `main` image/digest만 사용한다.

### build API

- 관리자 session과 분리된 256-bit lowercase hex service credential을 exact Bearer header로 받고 timing-safe 비교한다. raw token·header·hash를 log/error/response에 넣지 않는다.
- 전용 SecurityFilterChain은 stateless이고 session·request cache를 만들지 않는다. 정확히 snapshot·media content GET만 허용하며 create/update/delete/share와 unknown route는 거부한다.
- endpoint는 internal network에만 두고 dev/public Nginx가 `/api/build/**`를 일반 API proxy보다 먼저 거부한다. browser·`NEXT_PUBLIC_*`에 credential을 두지 않는다.
- production token 누락·형식 오류는 startup failure이고 non-production 미설정은 build API만 503 fail-closed다.
- snapshot은 active `PROCESSING` generation·live lease를 확인하고 하나의 read-only PostgreSQL `REPEATABLE READ` transaction에서 server-owned microsecond `generatedAt`, current `contentRevision`과 exact public DTO를 만든다.
- response는 `schemaVersion`, 일관된 `contentRevision`, target `publishGeneration`, `generatedAt`, Shop·Service·Breed·Gallery·Notice와 distinct media manifest를 포함하며 `codeImageDigest`는 포함하지 않는다.
- build API는 published status, notice `published_at`·`expires_at`, relation target status, media active status, 허용 file과 실제 byte를 재검증한다. transformer도 API response와 source/output file을 다시 검증한다.
- 명시적으로 선택된 media가 archived, missing 또는 corrupt면 silent omission하지 않고 전체 build를 실패시킨다.
- media content는 current Shop 또는 공개 가능한 Gallery relation scope만 허용하고 actual size·SHA를 검증해 private no-store로 반환한다.
- raw storage path/hash, DB credential, 관리자 session, audit와 private metadata를 노출하지 않는다. 호출은 publication/content state를 변경하지 않는다.

### retry·순서·실패

- 동일 `publishGeneration`의 일시적 실패는 1분, 5분, 15분 간격으로 최대 3회 retry한다.
- validation·data 오류는 무한 retry하지 않고 운영자가 확인할 실패 상태로 종료한다.
- scheduled event 처리 직전 current Notice·Gallery row와 전체 build snapshot을 다시 읽어 status, current boundary, relation, media/file과 `generatedAt` eligibility를 재검증한다.
- reschedule, draft·archived 전환 또는 window 변경으로 event가 stale이면 공개 snapshot을 만들지 않고 no-op 처리하거나 최신 pending generation에 합친다. old event가 가진 과거 기대값은 공개 데이터 authority가 아니다.
- 더 높은 `publishGeneration`이 도착하면 최신 snapshot을 우선하며 낮은 generation의 build가 최신 release를 덮지 못한다.
- release manifest와 content snapshot에는 최소 `contentRevision`, `publishGeneration`, `generatedAt`을 기록한다. `current` atomic switch의 stale protection authority는 `publishGeneration`이다.
- build·validation 실패 시 `current`를 변경하지 않고 기존 공개 사이트를 유지한다.
- 후속 `/admin/`은 마지막 성공·실패, 대상 content revision·publish generation과 명시적 수동 retry를 제공한다.
- state-changing 관리 요청과 code deploy 자체는 publisher가 자동 재전송하지 않는다.

## 이유

- transactional outbox는 콘텐츠 commit과 publish 요청 기록의 유실 구간을 없애고, durable scheduled event는 mutation이 없는 Notice 게시·만료와 Gallery 게시 시각의 trigger 유실을 막는다.
- 단일 publisher는 작은 운영 규모에서 debounce, lock, build와 결과 상태를 한 ownership으로 유지한다.
- build API와 transformer의 이중 검증은 permission drift와 snapshot 오류가 공개 산출물로 전파되는 것을 줄인다.
- 원자적 전환은 실패를 현재 공개 사이트와 격리한다.

## 결과

### 장점

- 저장과 공개 반영의 상태를 정확히 구분하고 재처리할 수 있다.
- 변경 burst를 합치고 build를 직렬화한다.
- 공개 사이트의 runtime backend 독립을 유지한다.

### 비용·위험

- outbox schema, due-event claim 복구, 두 revision과 publisher 상태 관리가 필요하다.
- 30초 debounce와 build 시간만큼 공개 반영이 지연된다.
- publisher가 단일 처리 지점이지만 실패 시 기존 사이트는 계속 제공된다.

## 거부한 대안

### transaction commit 뒤 best-effort HTTP hook

commit과 hook 사이 장애에서 publish 요청을 잃을 수 있어 거부한다.

### deploy hook·queue·builder 상시 서비스 분리

현재 규모에서는 인증·network·재시도·관제 경계만 늘어나므로 거부한다.

### 공개 브라우저 runtime fetch

backend 장애를 공개 사이트로 전파하고 정적 HTML·SEO 계약을 약화하므로 거부한다.

## 실행 계획

- [x] Flyway V8 transactional `contentRevision`, immediate event 분류와 Notice·Gallery `availableAt` scheduled outbox producer 구현
- [x] pending/due claim·lease 복구, `publishGeneration`과 attempt/result DB/state-machine foundation 구현
- [x] internal read-only build API와 stateless service credential, active generation gate, REPEATABLE READ snapshot·public-scope media 구현
- [ ] single publisher의 반복 poll, 30초 debounce, global lock, build orchestration과 stale snapshot 방지 구현
- [x] build API의 current published/relation/media/file·canonical master 재검증 구현
- [x] snapshot·media transformer의 response schema·source/output file 이중 검증, responsive derivative·atomic staging 구현
- [ ] publisher의 build API HTTP client와 transformer orchestration 구현
- [ ] release manifest 3개 필드와 `publishGeneration` 기준 code/content 공통 build·validate·atomic switch 검증
- [ ] 실제 Mac mini의 public/state/lock bind source ownership·permission과 publisher container mount·atomic symlink smoke 검증
- [ ] future Notice publish·expiry와 Gallery publish, reschedule/archive stale event, publisher downtime과 close-boundary coalesce 통합 테스트
- [ ] `/admin/` publish status와 수동 retry UI 구현

## 재검토 조건

- 콘텐츠 변경량이나 build 시간이 단일 publisher 처리량을 초과함
- preview·승인 workflow가 별도 queue를 요구함
- multi-host publisher와 분산 lock이 필요해짐
- static build 대신 다른 공개 delivery 방식이 승인됨
