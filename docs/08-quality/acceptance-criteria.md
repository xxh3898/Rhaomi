---
title: "수용 기준"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
review_trigger: "제품 기능 변경 시"
---

# 수용 기준

## AC-01 첫 화면

**Given** 모바일 390px 화면에서 홈을 열었을 때  
**When** 초기 HTML이 표시되면  
**Then** 라오미펫, 용인 처인구 애견미용 맥락, 대표 문구, 예약 문의·전화 CTA를 확인할 수 있다.

## AC-02 조건부 문의 채널

**Given** 네이버톡톡 URL이 비어 있을 때  
**When** 고객이 홈을 열면  
**Then** 네이버톡톡 버튼이 렌더링되지 않는다.

**Given** 유효 URL이 저장되고 배포되었을 때  
**Then** 버튼이 표시되고 정확한 URL로 이동한다.

## AC-03 견종 필터

**Given** 비숑과 푸들 공개 사진이 있을 때  
**When** 고객이 `비숑 프리제` 필터를 선택하면  
**Then** 해당 견종 사진만 표시되고 선택 상태가 접근성 API에 전달된다.

## AC-04 필터 목록

**Given** 활성 견종이지만 공개 사진이 없을 때  
**When** 홈을 생성하면  
**Then** 해당 견종은 홈 필터에 표시되지 않는다.

## AC-05 갤러리 상세

**Given** 키보드 사용자가 갤러리 카드를 열었을 때  
**Then** 포커스가 dialog로 이동하고 Escape로 닫히며 원래 카드로 복귀한다.

## AC-06 공지 CRUD

**Given** 운영자가 공지를 draft로 저장했을 때  
**Then** 공개 사이트에 보이지 않는다.

**When** published로 변경하고 배포가 성공하면  
**Then** 홈과 상세 URL, sitemap에 나타난다.

**When** archived로 변경하면  
**Then** 다음 배포 후 모두에서 제외된다.

## AC-07 갤러리 CRUD

**Given** 운영자가 사진, 견종, 서비스, alt를 입력하고 게시할 때  
**Then** 공개 파생 이미지가 생성되고 해당 견종 필터에 표시된다.

**Given** 지원하지 않거나 손상된 이미지일 때  
**Then** 새 공개 배포는 실패하고 기존 사이트가 유지된다.

## AC-08 정적 독립성

**Given** 공개 사이트가 정상 배포된 후 Spring Boot와 PostgreSQL을 중지했을 때
**Then** 고객은 홈, 공지, 이미지, CTA를 계속 사용할 수 있다.

## AC-09 SEO

**Given** 운영 release가 생성되었을 때  
**Then** 홈은 고유 title, description, canonical, OG, LocalBusiness JSON-LD를 포함한다.

## AC-10 접근성

**Given** `prefers-reduced-motion: reduce`일 때  
**Then** 비필수 reveal, parallax, scale animation이 제거된다.

## AC-11 오류

**Given** 존재하지 않는 URL 요청  
**Then** 맞춤 안내와 홈 링크를 제공하고 HTTP 404를 반환한다.

## AC-12 배포 안전성

**Given** 새 release의 검증이 실패할 때  
**Then** `current`가 변경되지 않는다.

**Given** 전환 직후 스모크가 실패할 때  
**Then** `previous`로 복귀할 수 있다.

## AC-13 관리자 매장정보 편집

**Given** 인증된 운영자가 row가 없는 `/admin/` 매장정보 화면을 열었을 때

**Then** 장애 문구 대신 실제 값이 없는 빈 full form과 명시적 주차 선택을 확인할 수 있다.

**When** 필수값을 입력하고 저장하면

**Then** mutable field 26개와 nullable `null`만 포함한 PUT 한 번을 보내고 server canonical response를 form에 반영한다.

**Given** 기존 Hero·미용사·OG relation이 active일 때

**When** 운영자가 media picker를 사용하면

**Then** 현재 slot의 relation 바로 아래에 picker 하나만 표시하고 active private media를 선택·해제하며 같은 media를 여러 slot에 재사용할 수 있다.

**Given** keyboard 사용자가 Hero·미용사·OG의 `미디어 선택` trigger를 활성화했을 때

**Then** focus가 중간 form control을 거치지 않고 해당 inline picker의 첫 control로 이동하고, 닫기·선택 완료 후 원래 slot trigger로 복귀한다.

**Given** 기존 relation이 archived이거나 목록에서 찾을 수 없을 때

**Then** 관계를 숨기지 않고 제거 또는 active media 교체가 필요함을 표시하며 invalid 관계를 그대로 정상 저장하지 않는다.

**Given** Hero 또는 미용사 이미지를 선택했을 때

**Then** 300 code-point 이하의 공개 이미지 대체텍스트를 요구하고 OG에는 alt input을 만들지 않는다.

## AC-14 관리자 견종·서비스 콘텐츠 편집

**Given** 인증된 운영자가 `/admin/` 관리 홈을 열었을 때

**Then** 매장정보·갤러리·미디어·견종·서비스·공지가 모두 사용 가능하다.

**When** 운영자가 새 견종 또는 서비스를 만들면

**Then** 이름과 직접 입력한 lowercase kebab slug, 선택 text, 빈 값이면 null인 sortOrder를 POST 한 번으로 보내고 strict하게 검증한 `draft` canonical response를 item state로 반영한 뒤 GET list의 server ordering을 적용한다.

**When** 운영자가 기존 견종 또는 서비스를 편집하면

**Then** slug는 읽기 전용이고 status와 mutable field 전체를 PUT 한 번으로 보내며 성공 response를 item state로 반영한 뒤 GET list가 반환한 `sortOrder`, `name`, `id` 순서를 그대로 적용한다.

**Given** POST 또는 PUT은 성공했지만 후속 GET list가 실패했을 때

**Then** mutation 실패로 표시하거나 자동 재전송하지 않고 저장 완료와 목록 순서 refresh 실패를 구분하며 explicit refresh로 복구할 수 있다.

**Given** 서비스의 description 또는 priceText가 비어 있을 때

**When** published를 선택해 저장하면

**Then** client가 게시 필수값을 안내하고 backend `PUBLISH_VALIDATION_FAILED`를 최종 authority로 유지한다.

**Given** keyboard 사용자가 create/edit panel을 열었을 때

**Then** focus가 첫 이름 input으로 이동하고 취소 또는 성공 뒤 원래 trigger나 item 수정 action으로 복귀한다.

**Given** 항목이 archived일 때

**Then** 삭제된 것으로 표현하지 않고 draft 또는 published로 복구할 수 있으며 영구 삭제 action은 제공하지 않는다.

## AC-15 관리자 갤러리 콘텐츠·관계 편집

**Given** 인증된 운영자가 `/admin/` 관리 홈을 열었을 때

**Then** 갤러리와 공지를 포함한 여섯 관리 영역이 사용 가능하고 같은 page state에서 관리 홈으로 복귀할 수 있다.

**When** 운영자가 갤러리 초안을 생성하거나 기존 항목을 수정하면

**Then** 생성은 status 없이 POST 한 번, 수정은 status와 nullable key를 포함한 mutable field 전체를 PUT 한 번 보내고 성공 response 적용 뒤 GET list의 server ordering을 그대로 사용한다.

**Given** draft 또는 archived 항목을 편집할 때

**Then** 존재하는 모든 상태의 견종·서비스와 active·archived media를 상태와 함께 선택할 수 있고 cover는 before/after와 재사용할 수 있지만 before와 after는 같을 수 없다.

**When** published 상태로 저장할 때

**Then** 게시된 견종·서비스, active cover와 선택한 before/after, 사실 기반 alt text와 publishedAt을 요구하고 backend의 관계·게시 검증을 최종 authority로 유지한다.

**Given** 저장은 성공했지만 후속 canonical GET이 실패했을 때

**Then** 저장 실패로 표시하거나 mutation을 자동 재전송하지 않고 warning과 explicit refresh를 제공하며 목록이 ready가 된 뒤 enabled 원 trigger로 focus를 복귀한다.

**Given** keyboard 사용자가 cover·before·after picker를 열었을 때

**Then** 해당 relation 바로 아래의 picker 하나로 focus가 이동하고 닫기·선택 뒤 원 trigger로 복귀하며 private Blob preview object URL은 교체·unmount에서 폐기된다.

## AC-16 관리자 공지 콘텐츠·게시기간 편집

**Given** 인증된 운영자가 `/admin/` 공지 화면을 열었을 때

**Then** 모든 상태·미래·만료 공지를 backend가 반환한 고정·게시·수정 시각·id 순서 그대로 표시하고 Markdown 전체 본문을 목록 card에서 HTML로 렌더링하지 않는다.

**When** 운영자가 새 공지를 만들면

**Then** status 없는 title·slug·nullable summary·nullable bodyMarkdown·pinned·publishedAt·expiresAt을 POST 한 번 보내고 strict하게 검증한 draft response를 적용한 뒤 canonical GET을 수행한다.

**When** 기존 공지를 게시·보관·복구하거나 고정·게시·만료 기간을 바꾸면

**Then** immutable slug와 audit을 제외한 mutable field 전체를 PUT 한 번 보내며 published에는 nonblank body와 publishedAt을, expiresAt에는 상태와 무관하게 더 이른 publishedAt을 요구한다.

**Given** 게시·만료 입력이 정규화 후 같은 시각일 때는 저장을 막고 정확히 1µs 차이는 허용하며, 이미 지난 유효 window와 미래 publishedAt에는 현재 시각 기반 추가 제한을 두지 않는다.

**Given** 사용자가 backend의 microsecond 시각을 form에서 바꾸지 않았을 때

**Then** full PUT은 원래 Instant를 보존한다. 저장 성공 뒤 canonical GET이 실패해도 mutation 실패로 표시하거나 자동 재전송하지 않고 explicit refresh를 제공하며, ready가 된 enabled 원 trigger로 focus를 복귀한다.

## AC-17 transactional publication producer

**Given** 지원되는 Breed·Service·Notice·ShopSettings·Gallery·Media mutation이 성공했을 때

**Then** 같은 PostgreSQL transaction에서 `contentRevision`이 정확히 한 번 증가하고 공개 영향 분류에 맞는 immediate event만 같은 revision으로 기록된다.

**Given** Notice 게시·만료 boundary가 새로 설정·변경되거나 published Gallery의 게시 boundary가 생성·변경됐을 때

**Then** `availableAt = expectedBoundaryAt`인 typed scheduled event가 같은 revision으로 기록되며 이전 scheduled event를 삭제하지 않는다.

**Given** validation·repository·outbox insert 또는 media revision allocation이 실패했을 때

**Then** content row·revision·event가 함께 rollback되고 media temp·final orphan이 남지 않는다.

**Given** 동시 mutation 또는 rollback 뒤 다음 mutation이 실행될 때

**Then** duplicate·lost revision이 없고 rollback된 transaction이 revision을 영구 소비하지 않는다.

## AC-18 publication claim·generation state

**Given** immediate pending 또는 due scheduled event가 있을 때

**When** internal state service가 다음 event를 claim하면

**Then** PostgreSQL row lock으로 한 consumer만 선택되고 `publishGeneration` 증가·첫 attempt·active owner lease가 같은 transaction에 기록된다. transaction rollback이면 event는 `PENDING`이고 generation counter도 증가하지 않는다.

**Given** scheduled Notice·Gallery의 current row가 없거나 draft·archived 또는 expected boundary와 다를 때

**When** due claim을 시도하면

**Then** generation 없이 `NOOP / STALE_TRIGGER`로 종료한다. 이 판정은 source 상태·경계의 최소 검증이며 relation·media·file을 포함한 전체 공개 eligibility는 build API와 후속 transformer가 다시 검증한다.

**Given** active processing lease가 만료되거나 transient attempt가 실패했을 때

**When** recovery 또는 due retry를 claim하면

**Then** 기존 generation을 유지하고 attempt를 증가시키며 1분·5분·15분 간격과 총 4회 제한 뒤 성공·no-op·terminal failure 중 하나로 고정된 결과를 기록한다.

**Given** 같은 owner가 lower·higher active generation을 보유할 때

**When** lower를 higher에 coalesce하면

**Then** 실제 존재하는 higher `PROCESSING` generation만 target이 되고 higher→lower, terminal source, 잘못된 owner·generation은 거부된다. 실제 30초 debounce와 build orchestration은 실행되지 않는다.

## AC-19 internal read-only build API

**Given** valid 64자 lowercase hex build token과 active `PROCESSING` generation·live lease가 있을 때

**When** publisher 후보가 `GET /api/build/snapshot`을 호출하면

**Then** 관리자 session과 분리된 stateless principal로 인증하고 하나의 read-only PostgreSQL `REPEATABLE READ` transaction에서 server-owned microsecond `generatedAt`, current `contentRevision`, exact DTO allowlist와 published/time/relation/media/file 조건을 검증한다.

**Given** admin session만 있거나 build token이 missing·wrong·malformed·disabled이고, 또는 build token으로 admin API를 호출할 때

**Then** 각 namespace는 서로 권한을 부여하지 않고 fixed 401 또는 disabled build service의 503으로 fail-closed한다. production token 누락·형식 오류는 startup failure다.

**Given** unknown·terminal·retry-wait·expired lease generation 또는 invalid explicit relation/file이 있을 때

**Then** active target이 아니면 409, snapshot data가 invalid면 silent omission 없는 422로 종료하고 SQL·path·token·hash·내부 detail이나 partial snapshot을 반환하지 않는다.

**Given** Shop 또는 현재 공개 가능한 Gallery relation에 속한 active media가 있을 때

**When** 같은 active generation으로 media content를 요청하면

**Then** actual size·SHA가 검증된 canonical master와 exact private header를 반환한다. unlinked·draft/archived/future-only·missing·archived media는 존재 여부를 구분하지 않는 404다.

**Given** snapshot 또는 media GET이 성공·실패했을 때

**Then** `contentRevision`, outbox row, `publishGeneration`, lease, attempt와 content/media state는 변하지 않는다. POST·PUT·PATCH·DELETE와 unknown build route, dev/public gateway `/api/build/**`는 모두 거부한다.

## AC-20 build snapshot transformer·responsive derivative

**Given** exact `BuildSnapshotV1`과 manifest에 일치하는 JPEG·PNG canonical media가 `MediaContentProvider`로 제공될 때

**When** 독립 transformer가 새 staging target을 만들면

**Then** unknown/missing field·schema·semantic·게시 시각·relation·media manifest를 다시 검증하고 distinct media를 한 번씩만 읽으며, source보다 업스케일하지 않은 Gallery card `360/640/960`, Gallery large `768/1200/1600`, Hero `768/1280/1920` AVIF·WebP·JPEG 파생본을 생성한다.

**Then** orientation·sRGB와 metadata 제거를 적용하고 output byte SHA-256 filename, 결정적 `content.json`·`media-manifest.json`과 `public/generated/media`를 기록한다. 같은 입력은 byte-for-byte 같은 산출물과 순서를 만든다.

**Given** snapshot drift, missing media, MIME/signature mismatch, corrupt·APNG·oversized image, transform 또는 filesystem 실패가 있을 때

**Then** fixed typed error로 전체 transform을 실패시키고 partial temp를 제거하며 이미 존재하는 성공 target을 교체하지 않는다. 오류에는 snapshot data·media UUID·path·decoder detail을 노출하지 않는다.

**Given** Phase 1C-8f4 transformer가 성공했을 때

**Then** build API HTTP client, Markdown/HTML·SEO·Next render, release manifest와 `current` atomic switch가 완료된 것으로 간주하지 않는다. publisher polling/debounce/lock control plane은 AC-21의 별도 계약으로 검증한다.

## AC-21 publisher control loop

**Given** normal backend가 publisher mode argument 없이 시작될 때

**Then** 기존 HTTP API만 기동하고 publisher loop·lifecycle bean이나 background claim thread를 만들지 않는다.

**Given** exact `--rhaomi.publisher.mode=control-loop`이 선택될 때

**Then** publisher는 `WebApplicationType.NONE` 전용 root로 기동하고 controller·web server·admin bootstrap을 구성하지 않으며 stable non-secret owner와 bounded poll/lease/renew/shutdown 설정만 사용한다.

**Given** 첫 실제 `PROCESSING` generation을 `T0`에 accepted하고 `T0 + 30s`까지 fresh·retry·recovered generation이 도착할 때

**When** fixed debounce window가 닫히면

**Then** exact boundary trigger를 포함하고 이후 trigger는 제외하며, lower active claim을 실제 highest live generation으로 coalesce하고 highest만 executor target으로 사용한다. generation 없는 stale no-op은 executor를 호출하지 않는다.

**Given** debounce 또는 executor가 lease renewal interval보다 오래 걸릴 때

**Then** lease를 expiry 전에 반복 갱신하고 completion 직전 ownership을 다시 확인한다. renewal·coalesce·result mutation이 false이거나 lease를 잃으면 success/no-op을 기록하지 않는다.

**Given** global filesystem lock이 다른 publisher에 잡혀 있거나 executor가 safe internal failure를 반환할 때

**Then** executor 동시 진입 없이 active target을 existing transient failure state로 전환하고 raw exception·credential·path를 durable result나 lock file에 남기지 않는다.

**Given** executor가 `SUCCESS | NO_PUBLIC_CHANGE | TRANSIENT_FAILURE | TERMINAL_FAILURE`를 반환할 때

**Then** control loop만 기존 state service의 success·no-op·transient·terminal transition에 각각 매핑한다. 현재 placeholder는 transient failure로 fail-closed하며 public release를 만들지 않는다.

**Given** idle, debounce 또는 executor 중 shutdown이 요청될 때

**Then** 새 claim을 시작하지 않고 bounded하게 worker/task를 중단하며 미완료 target의 terminal 결과나 ownership을 위조하지 않는다.
