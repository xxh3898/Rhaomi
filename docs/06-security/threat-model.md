---
title: "위협 모델"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-09-01"
review_trigger: "외부 노출·관리 기능·인증 변경 시"
---

# 위협 모델

## 보호 자산

- 관리자 계정과 password hash
- 관리자 session과 CSRF token
- WebAuthn RP-side credential record와 recovery-code secret
- PostgreSQL 데이터
- transactional content revision, publish generation과 publishing outbox state
- private canonical 시술사진과 metadata
- internal build service credential, full release adapter와 향후 production publisher 주입 경계
- 초기 Mac mini local backup repository·manifest와 future encrypted restic recovery key
- 공개 도메인과 배포 권한
- GitHub 저장소와 Actions 권한, exact-SHA image digest·SBOM/provenance release evidence
- Mac fixed deploy config·Docker credential, global deploy lock와 release-bound backup eligibility evidence

## 공격 표면

- Spring Boot 관리자 login과 `/api/admin/**`
- 향후 WebAuthn registration·authentication·registration revoke/remove와 recovery-code 복구
- session cookie와 CSRF token 전달
- local/test 관리자 bootstrap
- Actuator health
- local Nginx same-origin `/api/**` proxy와 production `/api/admin/**` edge
- local Compose env file과 frontend dependency/runtime mount 경계
- PostgreSQL 연결
- 파일 upload·native image decoder, 내부 publication recorder·claim state service·build API와 향후 publisher
- GitHub Actions·production environment·Tailscale deploy entrypoint
- HomeOps health/status/event와 bounded restart
- backup 파일과 운영자 휴대전화

## 주요 위협과 통제

| 위협 | 영향 | 통제 |
|---|---|---|
| 관리자 credential 탈취 | 콘텐츠 변조, 내부 데이터 접근 | BCrypt, 일반화된 로그인 실패, WebAuthn/passkey 배포 게이트, RP-side credential record 최소 접근, recovery code 보호, session 폐기 |
| passkey private key의 server 수집·기록 | authenticator trust 경계 붕괴, key 유출 | private key는 authenticator/device authority, server input·DB·환경변수·log·backup·release evidence에서 금지 |
| WebAuthn registration 위조·record 변조 | 공격자 authenticator 등록, 관리자 사칭 | credential ID·public key·account binding·sign-counter metadata integrity, registration 승인, 분실·폐기·의심 시 credential record revoke/remove |
| recovery code 노출·재사용 | second factor 우회 | recovery code만 secret inventory로 관리하고 사용·노출·재발급·운영자 변경 시 기존 code 무효화와 새 set rotation |
| session 탈취 | 관리자 권한 사용 | HttpOnly, SameSite, production Secure/TLS fail-fast, log 마스킹 |
| CSRF | 관리자 의도와 다른 변경 | Spring Security CSRF 유지, token 없는 state change 거부 |
| session fixation | 로그인 전 session 탈취 연계 | 로그인 성공 시 session id 교체 |
| 비활성 계정 로그인 | 해지 계정 재사용 | `active` 확인과 동일한 401 실패 |
| bootstrap 오용 | default 관리자 생성 | 기본 비활성, 완전한 env 요구, production profile 차단 |
| API fail-open | 미설계 endpoint 노출 | login/csrf/health만 anonymous, API·Actuator·non-API 모두 명시 전 `denyAll` |
| password hash 노출 | offline cracking | entity 직접 반환 금지, DTO allowlist, 인증 완료 credential erase, session principal·log·body 검사 |
| 인증 service 장애 오분류 | 장애 은폐, 진단 지연 | credential 401 allowlist, service/repository 장애 generic 503 |
| BCrypt 입력 경계 불일치 | 예외 기반 5xx, bootstrap 기동 실패 | login·bootstrap 공통 UTF-8 72-byte validation을 encoder 전에 적용 |
| client credential/token 잔존 | 공유 기기·XSS에서 관리자 정보 재사용 | login identity 검증 직후 credential 제거, React 반영 뒤 fresh CSRF 준비, CSRF memory-only, browser storage·URL·log 저장 금지 |
| post-login CSRF 준비 실패 오분류 | 생성된 session 방치, 불필요한 credential 재입력 | unavailable 분리, 재시도 시 `/me` 확인 후 fresh CSRF만 재획득, mutation-ready 전환 보류 |
| 관리자 mutation 자동 재전송 | upload 중복·의도하지 않은 상태 변경 | 403에서 CSRF 폐기, network/5xx/malformed 포함 자동 retry 금지, 다음 명시적 사용자 action만 1회 전송 |
| private media URL 오노출 | 보관 원본의 공개 cache·session 우회 | authenticated Blob GET, JPEG/PNG 검증, object URL lifecycle revoke, public media route·storage metadata 부재 |
| 매장정보 mass assignment·partial update | server audit 변조, 기존 field 유실 | exact 26-key full request builder, audit/unknown key 제외, strict response shape, backend DTO allowlist |
| 견종·서비스 response drift·mass assignment | internal field 노출, 잘못된 canonical state | 도메인별 exact response validator, UUID·enum·slug·Instant 검증, update slug·audit 제외, backend DTO allowlist |
| 갤러리 response drift·relation 오판 | storage metadata 노출, invalid 관계 게시 | exact response validator, scalar UUID만 허용, full PUT의 id·actor·audit 제외, backend relation·publish 최종 검증 |
| 공지 response drift·게시기간 오판 | 내부 field 노출, 잘못된 공지 게시·만료 | exact response validator, immutable slug·audit request 제외, microsecond window 비교, backend publish/window 최종 검증 |
| 콘텐츠 mutation 중복·stale GET 경쟁 | 중복 row, 최신 성공 상태 유실 | pending ref, refresh/mutation 상호 잠금, request sequence 무효화, 자동 retry·auto-save 부재 |
| stale media relation 은폐 | archived/missing 원본의 공개 후보 오인 | active-only 새 선택, 기존 UUID 가시화, clear/replace 안내, backend relation 재검증 |
| 갤러리 archived media의 게시 오용 | 보관 원본이 public 후보로 오인됨 | picker 상태 text, published client 안내, backend mutation과 build snapshot의 active media 재검증 |
| gateway route 혼합 | API 오류를 HTML 성공으로 오인, 보호 경계 우회 | `/api/build/**` 선행 404, 일반 `/api/**` 전용 backend location, upstream 실패 non-200, frontend fallback 분리 |
| CORS·cookie rewrite 완화 | 의도하지 않은 origin의 session 사용 | same-origin relative URL, CORS header와 cookie Domain rewrite 금지 |
| DB 포트 노출 | 데이터 탈취 | host port 금지, 개발 전용 내부 network |
| 공급망 취약점 | 코드 실행 | exact version, Wrapper/lockfile, scanner, 별도 upgrade 검증 |
| backend 장애 | 관리자 작업 중단 | 공개 Static Export와 runtime 분리 |
| 콘텐츠 삭제 | 영업 자산 손실 | 후속 CRUD에서 archive, migration·backup gate |
| revision 중복·event 유실 | 최신 snapshot 식별 실패, 공개 반영 누락 | singleton row lock 기반 allocator, content·revision·typed outbox same-transaction commit/rollback, sequence·best-effort hook 금지 |
| outbox 위조·내부 상태 노출 | draft trigger 오분류, 내부 콘텐츠 식별자 노출 | kind/source/boundary DB allowlist, domain 내부 `MANDATORY` recorder, 관리자와 build DTO에서 outbox row·claim field 비노출 |
| concurrent outbox double claim·generation gap | 동일 trigger 중복 build, ordering 불일치 | `FOR UPDATE SKIP LOCKED`, transactional singleton row `UPDATE ... RETURNING`, claim·generation·첫 attempt same-transaction rollback |
| claim 탈취·stale owner 완료 | active build 결과 오염, attempt 상태 손상 | owner·generation·`PROCESSING`·active lease guard, 만료 lease만 같은 generation으로 recovery, 최대 attempt 4회 |
| publication 결과 detail 노출 | SQL·path·credential·내부 예외 노출 | fixed result code DB allowlist, arbitrary result text column·HTTP status endpoint 부재 |
| stale scheduled event 오용 | 재예약·보관 콘텐츠의 잘못된 공개 | claim 시 current Notice·Gallery published 상태·expected boundary 최소 검증과 generation 없는 no-op, current build API와 후속 transformer의 전체 snapshot·relation·media/file 재검증 |
| generation coalesce 역전 | 낮은 trigger가 더 새로운 공개 결과를 덮음 | source보다 큰 실제 `PROCESSING` target self-reference, same-owner active claim guard, terminal source·higher→lower 거부 |
| publisher의 HTTP process 암묵 실행 | admin process 장애·권한과 build lifecycle 혼합 | exact mode argument 전용 root, normal component scan 밖 구성, `WebApplicationType.NONE`, controller·web server 부재 |
| debounce·executor 중 lease 상실 | stale owner가 성공 결과를 기록하거나 새 owner와 중복 build | renewal interval을 lease 절반 이하로 제한, debounce·async executor heartbeat, completion 직전 renewal과 false mutation fail-closed |
| concurrent publisher executor 진입 | 같은 generation·release filesystem 동시 변경 | executor 전 container-side `FileChannel.tryLock`, callable 진입·종료 별도 acknowledgment, interrupt 무시·shutdown timeout 중 lock 유지, actual body 종료 뒤에만 handle release |
| publisher 오류 detail·lock file 노출 | credential·DB·filesystem 정보 유출 | fixed result code와 safe internal category, raw throwable/path 미기록, 빈 advisory lock file |
| self-hosted runner 악용 | Mac mini 장악 | 전용 runner scope, untrusted PR 실행 금지, 최소 권한 |
| build credential 탈취 | private snapshot·canonical media 노출 | 256-bit token·timing-safe 비교, stateless GET allowlist, admin session 분리, active generation·public relation scope, public Nginx deny, token 비기록 |
| frontend filesystem credential 노출 | browser build·dependency lifecycle을 통한 build token·DB/admin credential 탈취 | frontend repository-root bind 금지, source/config allowlist mount, `.env*`·backend·local secret 제외, frontend env/file·token digest Compose smoke |
| production web credential·private filesystem 노출 | public Nginx 침해가 DB/build token·media/state로 확장하거나 hidden release file·query-bearing Referrer가 노출 | web environment credential 0, public bind RO만 mount, PostgreSQL network 미공유, admin-only proxy와 build/internal/actuator/manifest 404, server-level nested hidden path 404, Referrer access-log 제외 |
| external origin downgrade·internal authority 노출 | `/admin` redirect에 internal HTTP·8080·loopback port가 노출되거나 backend가 secure public request를 HTTP로 판단 | `absolute_redirect off`의 relative `Location`, config 고정 `X-Forwarded-Proto: https`·`X-Forwarded-Port: 443`, client-supplied forwarded scheme/port 비신뢰, runtime external-Host regression |
| production topology drift | backend/DB/publisher host 노출 또는 과도한 lateral movement | external-image canonical Compose, web-only loopback edge, 세 service internal network adjacency와 rendered/runtime contract 검사 |
| build credential 오구성 | internal endpoint의 fail-open 또는 production 미보호 기동 | backend production 누락·형식 오류 startup failure, Node adapter의 request 전 exact URL/64자 lowercase hex 검증, browser/public env 주입 금지 |
| Build API redirect·SSRF·credential 유출 | token이 다른 origin으로 전달되거나 internal topology 노출 | root absolute http/https origin allowlist, userinfo/query/fragment/path 거부, redirect manual·cookie omit, credential argv/query/path/log/generated output 금지 |
| media 중복·protocol drift | 같은 private master 반복 유출, memory/resource 낭비, 잘못된 byte transform | strict manifest membership, UUID별 rejected/in-flight/result memoization, exact MIME·Content-Length·body length와 bounded body read |
| build snapshot 혼합·내부 field 노출 | 서로 다른 revision 조합 또는 private metadata 유출 | read-only REPEATABLE READ, 단일 microsecond generatedAt, exact DTO allowlist, current revision과 relation/media/file 재검증 |
| int64 JSON precision loss | generation equality·stale ordering 충돌, 잘못된 artifact 식별 | V2 canonical decimal string, `Long.toString` DTO, Node string 보존과 range/equality/ordering 한정 `BigInt`, JSON number·malformed·overflow 거부 |
| build API state mutation | lease·attempt·콘텐츠 ordering 오염 | GET-only chain, 모든 mutation deny, read-only transaction, 전후 publication state integration test |
| snapshot parser drift·relation 우회 | draft·만료·missing relation 또는 private field의 공개 산출물 유입 | exact key·schema·semantic·generatedAt eligibility·relation·media manifest를 transport-independent transformer에서 재검증, unknown/missing field fail-closed |
| canonical media 위조·metadata 노출 | 손상 image 처리, GPS·기기 정보 공개 | provider content type과 JPEG·PNG signature/decode/size/pixel/single-image 재검증, orientation·sRGB·metadata strip, output decode·format·metadata 검사 |
| partial·nondeterministic staging 공개 | 혼합 revision, cache 불일치, 이전 성공 결과 훼손 | deterministic order와 output-byte SHA-256 filename, temp sibling 완성 뒤 rename, failure cleanup, existing target fail-closed 보존 |
| staging 성공의 publication 성공 오판 | Next/release 검증 없이 outbox 완료·불완전 사이트 공개 | staging-only result를 DB completion과 분리하고 full executor의 export·validator·manifest·switch·post-switch serving smoke 뒤에만 `SUCCESS` 허용 |
| Notice Markdown XSS·remote image 우회 | script 실행·private/remote resource 공개 | raw HTML 비활성, escaping·link protocol allowlist, Markdown image의 alt-only 처리와 exported HTML dangerous URL 검증 |
| release tree·manifest 변조 | stale downgrade·path escape·임의 파일 공개 | exact manifest shape·site tree digest, regular-file-only traversal, symlink/special file 거부와 `BigInt` switch-time stale 재검증 |
| publisher child process 잔존 | global lock 해제 뒤 두 release executor 동시 side effect | Node root·관찰 descendant physical exit 확인, 정상 root exit 뒤 orphan도 transient 강제 종료, Java body 종료 전 lock 유지 |
| non-main·stale SHA production 실행 | 승인하지 않은 code의 image publish·배포 | `workflow_dispatch` only, `refs/heads/main`·`github.sha`·requested 40자 SHA exact gate를 모든 release job에서 재검증 |
| workflow 권한·secret 경계 확대 | PR 코드가 package를 쓰거나 production identity 탈취 | validation `contents: read`, publish job만 `packages: write`, deploy job만 `environment: production`·environment secret, PR Validate package write 0 |
| exact SHA tag 덮어쓰기·mutable apply | 같은 승인 식별자가 다른 byte를 가리킴 | existing tag publish 거부, multi-arch manifest returned digest apply, requested digest·RepoDigest·OCI revision·runtime image ID 검증, `latest` 금지 |
| arbitrary remote shell·config injection | GitHub input·runner env가 production host command·Compose authority로 확대 | fixed SSH executable·세 scalar argv, strict registry/SHA/digest allowlist, fixed root·Compose·env·Docker config, inherited Compose/Docker override 폐기 |
| concurrent deploy·writer 활성 중 migration | DB write와 schema 변경 경쟁, 서로 다른 image 재기동 | Mac `mkdir` global lock, public web·DB만 유지, backend/publisher 정지·physical exited 확인 후 one-shot migration/schema validation |
| migration/schema 실패 후 자동 resume | 호환성 미확인 old writer가 migrated DB에 write | critical path non-zero, backend/publisher maintenance hold, backend health 전 publisher 시작 금지, false-success evidence 금지 |
| backup prerequisite 우회 | 복구 authority 없는 migration | fixed `0600` eligibility file의 exact 4-line allowlist·release SHA·evidence hash·owner 검증, D-IMP-4 미구성 시 actual deploy fail-closed |
| local-only backup 동시 손실 | host/storage 전체 장애에서 production data와 backup 동시 손실 | 초기 accepted risk 명시, local manifest/check·isolated restore; external/offsite는 future hardening |
| future backup key 탈취·분실 | 민감 원본 노출 또는 복구 불가 | external hardening 도입 시 별도 encrypted repository, 제한된 password source, password manager+offline recovery key |
| PostgreSQL volume 오삭제 | 전체 운영 DB 손실 | project-scoped named volume, 일반 `down` 보존, production `down -v`·prune·direct delete 금지, logical backup·isolated `pg_restore` |
| 자동 복구 오작동 | 장애 확대·data mutation | stateless web/backend 단일 restart allowlist, deploy/backup lock, 30분 cooldown, audit |
| native codec 공급망 | image 처리 RCE·license 위반 | pinned source commit, decoder-only, x265 absence, SBOM·scan·actual fixture |

## 출시 차단

- 관리자 WebAuthn/passkey 2차 인증 없음 또는 password-only production 허용
- passkey private key를 server가 수집·저장·로그하거나 RP-side credential record와 recovery-code secret을 같은 rotation 대상으로 취급
- WebAuthn registration revoke/remove와 recovery-code 무효화·rotation 절차 부재
- TLS 없이 production session cookie 사용
- production에서 `Secure=false`
- PostgreSQL 외부 노출
- 실제 secret·password·실사용 email 커밋
- CSRF disable 또는 state-changing anonymous endpoint
- default production 관리자 자동 생성
- 미설계 `/api/**` anonymous 허용
- backup 없음
- public build/internal/actuator route 노출
- publication outbox·revision/generation state의 HTTP response 또는 외부 network 노출
- normal backend process의 publisher loop/thread와 publisher process의 controller·web port 노출
- protected exact-SHA/digest release·fixed entrypoint·one-shot Flyway/schema validation·restore drill의 actual production acceptance 부재
- Mac `/private/var/lib/rhaomi` ownership·bind smoke, PostgreSQL named-volume restart/일반 `down` persistence와 isolated `pg_restore` 증거 부재
- HomeOps 자동 복구가 DB·volume·migration·backup을 변경할 수 있음

현재 local backend·gateway와 `/admin/` 인증 셸·미디어·매장정보·견종·서비스·갤러리·공지 관리 UI는 운영 배포 대상이 아니므로 WebAuthn/passkey·TLS·운영 account provisioning을 구현하지 않는다. noindex와 client session 확인도 보안 통제로 간주하지 않으며, 실제 iPhone Safari HEIC upload·shop/견종/서비스/갤러리/공지 form·VoiceOver 증거가 없는 상태를 운영 준비 완료로 표현하지 않는다.

## 출시 후 개선

- 관리자 IP/Access 정책
- 공유 session store가 필요한 규모인지 측정
- 외부 heartbeat가 필요한지 same-host blind spot 근거로 재검토
- 중앙 log와 login rate limit
- 정기 보안 scan
