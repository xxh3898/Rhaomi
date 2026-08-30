---
title: "위협 모델"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-30"
review_trigger: "외부 노출·관리 기능·인증 변경 시"
---

# 위협 모델

## 보호 자산

- 관리자 계정과 password hash
- 관리자 session과 CSRF token
- PostgreSQL 데이터
- transactional content revision과 publishing outbox
- 향후 원본 시술사진
- 향후 internal build/publisher service credential
- encrypted restic repository와 recovery key
- 공개 도메인과 배포 권한
- GitHub 저장소와 Actions 권한

## 공격 표면

- Spring Boot 관리자 login과 `/api/admin/**`
- session cookie와 CSRF token 전달
- local/test 관리자 bootstrap
- Actuator health
- local Nginx same-origin `/api/**` proxy와 production `/api/admin/**` edge
- PostgreSQL 연결
- 파일 upload·native image decoder, 내부 publication recorder와 향후 build API·publisher
- GitHub Actions·production environment·Tailscale deploy entrypoint
- HomeOps health/status/event와 bounded restart
- backup 파일과 운영자 휴대전화

## 주요 위협과 통제

| 위협 | 영향 | 통제 |
|---|---|---|
| 관리자 credential 탈취 | 콘텐츠 변조, 내부 데이터 접근 | BCrypt, 일반화된 로그인 실패, 2FA 배포 게이트, session 폐기 |
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
| 갤러리 archived media의 게시 오용 | 보관 원본이 public 후보로 오인됨 | picker 상태 text, published client 안내, backend의 active media 강제, 후속 public snapshot 재검증 |
| gateway route 혼합 | API 오류를 HTML 성공으로 오인, 보호 경계 우회 | `/api/**` 전용 backend location, upstream 실패 non-200, frontend fallback 분리 |
| CORS·cookie rewrite 완화 | 의도하지 않은 origin의 session 사용 | same-origin relative URL, CORS header와 cookie Domain rewrite 금지 |
| DB 포트 노출 | 데이터 탈취 | host port 금지, 개발 전용 내부 network |
| 공급망 취약점 | 코드 실행 | exact version, Wrapper/lockfile, scanner, 별도 upgrade 검증 |
| backend 장애 | 관리자 작업 중단 | 공개 Static Export와 runtime 분리 |
| 콘텐츠 삭제 | 영업 자산 손실 | 후속 CRUD에서 archive, migration·backup gate |
| revision 중복·event 유실 | 최신 snapshot 식별 실패, 공개 반영 누락 | singleton row lock 기반 allocator, content·revision·typed outbox same-transaction commit/rollback, sequence·best-effort hook 금지 |
| outbox 위조·내부 상태 노출 | draft trigger 오분류, 내부 콘텐츠 식별자 노출 | kind/source/boundary DB allowlist, domain 내부 `MANDATORY` recorder, 관리자 response·public/build endpoint 비노출 |
| stale scheduled event 오용 | 재예약·보관 콘텐츠의 잘못된 공개 | old event 삭제에 의존하지 않고 후속 consumer가 current Notice·Gallery row·expected boundary·전체 snapshot 재검증 |
| self-hosted runner 악용 | Mac mini 장악 | 전용 runner scope, untrusted PR 실행 금지, 최소 권한 |
| publisher credential 탈취 | private snapshot·media 노출 | internal network, read-only endpoint, admin session 분리, public Nginx deny |
| backup key 탈취·분실 | 민감 원본 노출 또는 복구 불가 | 별도 encrypted repository, 제한된 password source, password manager+offline recovery key |
| PostgreSQL volume 오삭제 | 전체 운영 DB 손실 | project-scoped named volume, 일반 `down` 보존, production `down -v`·prune·direct delete 금지, logical backup·isolated `pg_restore` |
| 자동 복구 오작동 | 장애 확대·data mutation | stateless web/backend 단일 restart allowlist, deploy/backup lock, 30분 cooldown, audit |
| native codec 공급망 | image 처리 RCE·license 위반 | pinned source commit, decoder-only, x265 absence, SBOM·scan·actual fixture |

## 출시 차단

- 관리자 2FA 없음
- TLS 없이 production session cookie 사용
- production에서 `Secure=false`
- PostgreSQL 외부 노출
- 실제 secret·password·실사용 email 커밋
- CSRF disable 또는 state-changing anonymous endpoint
- default production 관리자 자동 생성
- 미설계 `/api/**` anonymous 허용
- backup 없음
- public build/internal/actuator route 노출
- publication outbox·revision table의 HTTP response 또는 외부 network 노출
- one-shot Flyway·manual digest deploy·restore drill 부재
- Mac `/private/var/lib/rhaomi` ownership·bind smoke, PostgreSQL named-volume restart/일반 `down` persistence와 isolated `pg_restore` 증거 부재
- HomeOps 자동 복구가 DB·volume·migration·backup을 변경할 수 있음

현재 local backend·gateway와 `/admin/` 인증 셸·미디어·매장정보·견종·서비스·갤러리·공지 관리 UI는 운영 배포 대상이 아니므로 2FA·TLS·운영 account provisioning을 구현하지 않는다. noindex와 client session 확인도 보안 통제로 간주하지 않으며, 실제 iPhone Safari HEIC upload·shop/견종/서비스/갤러리/공지 form·VoiceOver 증거가 없는 상태를 운영 준비 완료로 표현하지 않는다.

## 출시 후 개선

- 관리자 IP/Access 정책
- 공유 session store가 필요한 규모인지 측정
- 외부 heartbeat가 필요한지 same-host blind spot 근거로 재검토
- 중앙 log와 login rate limit
- 정기 보안 scan
