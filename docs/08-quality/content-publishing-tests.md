---
title: "콘텐츠 배포 테스트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-31"
review_trigger: "관리 backend·배포 event 변경 시"
---

# 콘텐츠 배포 테스트

## Phase 1C-8f1 producer 완료

- [x] V8 `content_revision_state` 초기 0·singleton·nonnegative constraint와 transactional row allocator
- [x] V8 typed `publishing_outbox` kind/source/revision/boundary constraint와 required index
- [x] 기존 V1→V7 database의 V8 upgrade와 clean V1→V8 migration
- [x] 지원 콘텐츠 성공 mutation당 revision 정확히 1회, 동시 transaction duplicate·lost revision 없음
- [x] rollback·validation·outbox insert failure의 content/revision/event 원자적 rollback
- [x] Breed·Service·Notice·Gallery status matrix, Shop 모든 PUT, Media upload·archive·restore immediate 분류
- [x] Notice create/update의 changed publishedAt·expiresAt scheduled event와 old event 보존
- [x] published Gallery 진입·reschedule의 publishedAt scheduled event와 old event 보존
- [x] Media revision allocation failure의 DB row·temp/final file orphan 부재

## Phase 1C-8f2 claim·generation state 완료

- [x] 기존 V1→V8 database의 V9 upgrade, V8 event default `PENDING` 호환과 clean V1→V9·JPA validate
- [x] generation singleton·state/result allowlist·state별 shape·unique generation·self-FK와 claim/retry/lease index
- [x] pending/due `(availableAt, id)`·`FOR UPDATE SKIP LOCKED` single claim, generation·첫 attempt atomicity와 rollback non-consumption
- [x] Notice 게시·만료와 Gallery 게시 boundary의 current published row 최소 검증, reschedule·draft/archive·missing source generation 없는 stale no-op
- [x] active owner·generation lease renewal, expired lease same-generation recovery와 concurrent single winner·attempt 4 exhaustion
- [x] transient failure 1분·5분·15분 due retry, total attempt 4와 success/no-op/terminal typed result
- [x] 같은 content revision의 distinct generation과 lower→higher active coalesce primitive·ordering/owner guard
- [x] 새 HTTP/build endpoint, scheduler, background loop, credential·환경 변수·Compose/workflow/dependency 변경 부재

## Phase 1C-8f3 build API 완료

- [x] 관리자 session과 분리된 64자 lowercase hex Bearer token, timing-safe 비교, stateless principal·SecurityFilterChain과 production fail-fast
- [x] exact 두 GET allowlist, mutation·unknown route·admin/build 교차 권한 거부와 session 비생성
- [x] active PROCESSING generation·live lease gate와 read-only PostgreSQL REPEATABLE READ snapshot
- [x] current contentRevision, server-owned microsecond generatedAt, exact top-level·item DTO allowlist와 codeImageDigest 부재
- [x] published Breed/Service, due Gallery, active-window Notice와 canonical ordering·Markdown source 보존
- [x] Shop·Gallery relation, active distinct media manifest와 실제 canonical master size·SHA 재검증
- [x] current public relation scope media content와 Content-Type/Length/private no-store/nosniff header
- [x] unlinked·draft/archived/future-only·archived/missing/corrupt media와 invalid relation의 fixed generic 오류
- [x] build 호출 전후 content revision·outbox·generation·lease·attempt·content/media 불변
- [x] dev gateway `/api/build/**` 선행 404와 frontend token/environment 부재
- [x] concurrent mutation 중 기존 snapshot 일관성과 다음 request의 새 revision/content 확인

## Phase 1C-8f4 snapshot transformer 완료

- [x] exact `BuildSnapshotV1` unknown/missing key, backend field별 type·limit·unsafe integer, UUID·slug·Instant·HTTPS·Shop pair 검증
- [x] published Breed·Service의 10,001자 description이 Build API snapshot과 transformer를 통과하고 nullable/nonblank canonical 계약은 유지
- [x] Admin API→published Breed description의 U+00A0·U+2007·U+202F 보존, Build API exact serialization과 transformer PASS
- [x] ContentFields Java strip, Shop/Gallery Unicode-space strip, U+FEFF 보존과 Java UTF-16/code-point length family 차이 회귀
- [x] published/time eligibility, relation, before/after, alt, duplicate와 exact media manifest 재검증
- [x] Breed·Service source order 보존과 media/profile/format/width 결정 순서, 같은 입력의 byte-for-byte deterministic output
- [x] distinct media fetch-once, JPEG·PNG content type/signature/decode와 30 MiB·12,000px·60MP·single-image/APNG 제한
- [x] orientation·sRGB·metadata strip과 Gallery card·large·Hero no-upscale AVIF·WebP·JPEG 파생본
- [x] output decode·format·metadata 재검증, output-byte SHA-256 filename과 duplicate byte file dedupe
- [x] `src/generated/content.json`·`media-manifest.json`·`public/generated/media` staging contract
- [x] missing/invalid/transform/output typed error, partial temp cleanup과 existing successful target 보존
- [x] filesystem CLI success/failure safe output와 credential·UUID·path·decoder detail 비노출
- [x] Linux amd64 Hosted·Linux arm64 Mac Compose에서 Node 24·exact Sharp transformer suite 실행 계약

## Phase 1C-8f5 publisher control loop 완료

- [x] exact mode argument 전용 `WebApplicationType.NONE` publisher root와 normal backend loop/thread 부재
- [x] existing state service 기반 pending/due·retry·expired lease claim과 generation 없는 stale no-op executor 0회
- [x] first accepted `claimedAt` 기준 fixed 30초, exact boundary 포함·직후 제외와 highest-generation target
- [x] burst generation lower→highest coalesce와 recovered lower generation ordering 유지
- [x] debounce·executor lease heartbeat, lost lease·state mutation false의 success/no-op completion 금지
- [x] empty `FileChannel.tryLock` advisory file, shared lock contender executor concurrency 1과 unavailable transient 처리
- [x] typed success/no-public-change/transient/terminal mapping과 raw exception·path 없는 safe internal failure
- [x] lease-loss·shutdown cancellation에서 interrupt를 무시하는 actual async executor의 physical 종료 전 lock 재획득 불가, 종료 뒤 재획득 가능
- [x] shutdown timeout 뒤 non-daemon control worker·lock 유지와 executor의 늦은 success/no-op completion 금지
- [x] idle/debounce/executor shutdown, bounded lifecycle join과 shutdown 뒤 새 claim 부재
- [x] PostgreSQL 18.6 burst·stale·same-generation retry·expired lease recovery·shared lock integration
- [x] production Build API/transformer/Next/release adapter 없는 transient placeholder와 default Compose publisher service 부재

## Phase 1C-8f6 Build API adapter·staging orchestration 완료

- [x] URL/credential/generation request 전 검증, redirect·cookie 금지와 body까지 bounded timeout
- [x] exact Bearer와 generation query, raw snapshot strict parse, requested/parsed generation exact 일치
- [x] 401/403 terminal, 409 generation, timeout/connection/429/5xx transient와 malformed 2xx terminal 분류
- [x] manifest 밖 media network 전 거부와 UUID별 rejected/in-flight/result HTTP fetch 최대 1회
- [x] media 200·exact Content-Type·Content-Length·actual body length와 404 terminal·503 transient 분류
- [x] synthetic HTTP JPEG/PNG → existing Sharp transformer → content/media manifest·AVIF/WebP/JPEG hash staging
- [x] corrupt media deterministic failure, partial temp 부재와 existing target 보존
- [x] CLI fixed argv, environment-only credential, safe JSON/exit family와 token·URL/path·UUID·stack 비노출
- [x] frontend/gateway credential environment/filesystem 0, public `/api/build/**` 404 유지
- [x] Java `PublicationBuildExecutor` transient placeholder와 publication state·Flyway V1~V9 불변
- [x] Linux amd64 Hosted와 Linux arm64 Mac Compose의 Node 24/Sharp orchestration suite 실행 계약

아래 항목은 각 줄의 전체 범위를 기준으로 표시한다. producer, claim/generation, build API, transformer, HTTP adapter·isolated staging과 publisher control plane은 완료했다. Next orchestration·release/public 결과는 미완료다.

## 기본 게시

- [ ] 공개 eligibility에 영향 없는 gallery draft 수정 → `contentRevision`만 증가, publishing event/build 없음, 공개 변화 없음
- [ ] 갤러리 published → 카드·필터·이미지 반영
- [ ] status·publishedAt·expiresAt에 영향 없는 notice draft 수정 → `contentRevision`만 증가, publishing event/build 없음, 공개 변화 없음
- [ ] 공지 published → 홈·상세·sitemap 반영
- [ ] shop 설정 변경 → Hero·JSON-LD 동기화
- [ ] 서비스 가격 변경 → 서비스 섹션 동기화

## 수정

- [ ] 공개 사진 alt 수정
- [ ] 견종 변경 시 기존·신규 필터 정확
- [ ] 공지 제목 수정 시 title 정확
- [ ] 공지 slug는 의도 없이 변경되지 않음
- [ ] 외부 링크 수정
- [ ] 빈 선택 링크 버튼 자동 제거

## 보관

- [ ] gallery archived → 홈에서 제거
- [ ] notice archived → 목록·상세·sitemap 제거
- [ ] breed archived → 새 선택 불가
- [ ] service archived → 새 선택 불가
- [ ] 참조 콘텐츠의 무결성 처리

## 만료

- [ ] 미래 publishedAt 공지 생성 뒤 추가 admin mutation 없이 boundary 이후 due event로 공개
- [ ] 미래 expiresAt 공지는 만료 전 표시
- [ ] expiresAt 도달 뒤 추가 admin mutation 없이 due event로 새 build에서 제외
- [ ] timezone Asia/Seoul 확인
- [ ] pinned라도 만료 시 제외

## 예약 event·stale 안전성

- [x] notice transaction과 changed publishedAt·expiresAt scheduled event가 함께 commit/rollback
- [x] scheduled event에 `availableAt`, source type·ID, current revision과 expected boundary 식별값 기록
- [x] Gallery publishedAt scheduled event claim 시 current Gallery row·boundary 최소 재검증
- [x] eligible event claim·`publishGeneration` 할당·첫 attempt가 atomic하고 lease 만료 뒤 같은 generation으로 복구
- [x] publishedAt·expiresAt 변경 뒤 old event claim → current row 최소 재검증과 generation 없는 stale no-op
- [x] future notice의 draft·archived 전환 뒤 old event claim → generation 없는 stale no-op
- [x] active generation build snapshot이 event payload 대신 current row·relation·media/file을 재검증
- [x] Build API DTO 형태의 snapshot을 transformer가 independently 재검증하고 invalid stale/관계 입력을 staging 전에 거부
- [ ] 위 재검증 결과를 publisher release까지 전달 → stale 공개 없음
- [ ] publisher가 boundary 동안 down → restart 후 overdue event 반복 처리·정확한 snapshot 공개/제거
- [ ] 가까운 여러 publish/expiry boundary → 30초 debounce/coalesce 후 최종 `generatedAt` snapshot 정확
- [x] 가까운 여러 due trigger의 fixed 30초 claim·highest-generation coalesce control 결과 정확
- [x] 같은 `contentRevision`의 publish boundary와 expiry boundary claim이 서로 다른 `publishGeneration` 생성
- [ ] 주간 notice expiry audit가 event drift를 탐지하되 correctness trigger를 대체하지 않음

## 이미지 transformer

- [x] JPEG signature/decode·metadata strip·responsive derivative
- [x] PNG signature/decode·alpha JPEG flatten·responsive derivative
- [ ] WebP
- [ ] 실제 iPhone HEIC
- [x] synthetic portrait orientation
- [x] synthetic landscape
- [x] 큰 원본의 30 MiB·12,000px·60MP 제한
- [x] 손상 파일
- [x] 잘못된 MIME·signature
- [x] synthetic GPS EXIF·XMP 제거
- [x] Gallery card·large·Hero responsive AVIF·WebP·JPEG variants와 no upscale
- [x] snapshot/manifest·public path에 원본 URL·storage path 비노출

WebP source와 실제 iPhone HEIC는 canonical master transformer 입력 형식이 아니다. 실제 iPhone HEIC는 backend에서 JPEG master로 정규화된 뒤 publisher end-to-end gate에서 별도 검증한다.

## 실패 안전성

- [x] 콘텐츠 변경과 publishing outbox가 같은 PostgreSQL transaction에서 commit/rollback
- [x] Build API response의 `contentRevision`·`publishGeneration`·`generatedAt` 일치와 `codeImageDigest` 부재
- [x] transformer content/manifest의 `contentRevision`·`publishGeneration`·`generatedAt` 보존
- [ ] publisher content snapshot/release manifest의 위 세 field·승인 code image digest 일치
- [ ] 낮거나 같은 `publishGeneration`의 old build가 newer current를 덮지 못함
- [x] build service token disabled → fixed 503, production 누락·malformed → startup failure
- [x] Build API client timeout·5xx → staging adapter transient failure, public/current mutation 없음
- [ ] PostgreSQL 중단 → build 실패, current 유지
- [x] invalid snapshot → staging 실패, partial temp 부재와 기존 target 유지
- [x] image decoder/transform 실패 → staging 실패, partial temp 부재와 기존 target 유지
- [x] parent write failure → staging 실패, partial temp 부재와 기존 target 유지
- [x] build service credential 오류·admin session-only·dev/public `/api/build/**` → 요청 거부
- [x] build API create/update/delete/share와 unknown GET 모두 거부
- [x] 첫 accepted generation 뒤 exact 30초 debounce와 global filesystem advisory lock control plane
- [x] lower active generation → 실제 higher active generation coalesce primitive와 역방향·invalid target 거부
- [x] concurrent triggers → 30초 orchestration이 가장 높은 accepted `publishGeneration`을 선택하고 executor port 직렬 진입
- [x] 동일 `publishGeneration` transient failure → 1분·5분·15분 최대 3회
- [x] validation/data failure → 무한 retry 없이 terminal failure 상태
- [x] 자동 attempt retry·lease recovery는 같은 generation
- [ ] 승인된 manual rebuild/retry는 별도 event와 새 generation
- [ ] build 중 새 변경·due boundary → 최신 generation 우선 후속 build
- [x] publisher control mode의 public web port·controller·Docker socket 의존 없음

## 롤백

- [ ] previous release 존재
- [ ] symlink 전환
- [ ] 공개 스모크
- [ ] publisher가 문제 revision을 무한 재배포하지 않게 조치
