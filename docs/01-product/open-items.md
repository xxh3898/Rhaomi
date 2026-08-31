---
title: "미확정 항목"
status: "draft"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-31"
review_trigger: "항목 확정 시"
---

# 미확정 항목

구현 시작을 막지는 않지만 운영 출시 전에는 아래 항목을 확정해야 한다.

## 출시 차단

| 항목 | 현재 상태 | 책임자 | 완료 조건 |
|---|---|---|---|
| 초기 public FQDN provisioning | 출시 차단 | 조치호 | 사용자 소유 기존 도메인의 exact temporary FQDN을 외부 입력으로 확정하고 Cloudflare·Nginx·`PUBLIC_SITE_URL`·canonical/OG/sitemap/robots·HTTPS smoke 동기화 |
| Hero·OG 실제 이미지 | 없음 | 은총쌤 | 사용 권한이 확인된 실제 사진 확보 |
| 시술사진 사용 동의 기준 | 확인 필요 | 은총쌤 | 반려견 사진 게시에 대한 운영 기준 확정 |
| 정확한 예약·취소 정책 | 미정 | 은총쌤 | 고객에게 공개할 최종 문구 승인 |
| 임시 소개·대표 문구 | 임시 | 은총쌤 | 최종 브랜드 문구 승인 |
| 네이버톡톡 URL | 미정 | 은총쌤 | 실제 링크 확인 또는 미사용 확정 |
| 카카오톡/채널 URL | 미정 | 은총쌤 | 실제 링크 확인 또는 미사용 확정 |
| NAP 최종 확인 | 확인 필요 | 은총쌤 | 지도·전화·간판 정보와 일치 확인 |
| 영업시간·정기휴무·주차 | 확인 필요 | 은총쌤 | 공개할 현재값과 지도 서비스 정보의 일치 확인 |
| Mac production filesystem provisioning | 출시 차단 | 조치호 | `/private/var/lib/rhaomi` canonical directory ownership·permission, public/media/state bind와 PostgreSQL named-volume persistence 검증 |
| 초기 local-only backup provisioning | 출시 차단 | 조치호 | protected source와 분리된 Mac mini local repository/path, 동일 DB/media backup-set, retention/check와 isolated `pg_restore`·media restore 증거 |
| 관리자 WebAuthn/passkey 2차 인증 | 미구현 | 조치호·은총쌤 | 운영 계정 passkey 등록·인증, recovery code의 password manager+별도 offline copy와 password-only production 차단 검증 |
| 실제 iPhone HEIC 업로드 | 미검증 | 조치호·은총쌤 | 후속 `/admin` UI에서 iPhone Safari 원본 선택·업로드·방향·색상 확인 |
| 실제 iPhone Safari·VoiceOver | 미검증 | 조치호·은총쌤 | actual public HTTPS와 `/admin`의 320px·focus·form·VoiceOver 표본 acceptance |

## 구현 중 확정 가능

- 최종 컬러 토큰
- 로고 유무와 워드마크
- 서비스별 최종 가격 문구
- 갤러리 초기 견종 목록
- 은총쌤 프로필 사진과 소개
- 갤러리 카드 수와 홈 노출 개수
- 공지 상세를 모달과 정적 페이지 중 어느 방식으로 동시에 제공할지
- 분석 도구 도입 여부

## 초기 production 이후 deferred hardening

- 사촌 소유 전용 도메인 구매와 canonical/public domain migration. 초기에는 사용자 소유 기존 도메인을 임시 public domain으로 사용하며 DB migration이나 content schema 변경 없이 교체한다.
- 외장 SSD encrypted restic repository
- iCloud Drive separate encrypted restic repository와 remotely verified offsite RPO
- external repository recovery key. 도입 시 macOS Keychain 또는 root-owned `0600` secret source와 password manager+별도 offline copy를 사용한다.

위 항목은 현재 초기 production blocker가 아니다. 구성되지 않은 external/offsite backup을 `PASS`로 표시하지 않고 `NOT_CONFIGURED / DEFERRED`로 유지한다.

## 구현 계약과 운영값 구분

- Flyway V4·V7과 매장정보 관리자 API는 text·영업정보·외부 링크와 nullable Hero·프로필·OG private media relation을 담을 구조와 검증을 제공한다.
- 실제 라오미펫 상호·주소·전화·영업시간·소개·외부 URL은 migration, source constant, 기본 fixture로 seed하지 않는다. NAP·영업정보·예약/취소 정책·문구·서비스/가격·외부 링크·사진과 반려견/고객 사진 게시 권한의 최종 authority는 실제 매장 운영자다.
- Flyway V5 private media upload/master, Flyway V6 갤러리 relation과 Flyway V7 매장정보 Hero·프로필·OG media FK는 구현됐다.
- internal build snapshot/media API와 공개 responsive 파생본, Hero·Gallery·OG Static Export binding은 구현됐다. 실제 운영 콘텐츠·사진 seed, 최종 crop/focal-point·시각 디자인과 public release provisioning은 아직 없다.
- 실제 사진 게시 동의, 실제 iPhone Safari UI 검증과 운영 private storage·backup이 완료될 때까지 사진 공개는 출시 차단 상태로 남긴다.
- [ADR-012](../09-decisions/ADR-012-application-consistent-backup-restore.md)의 2026-08-31 개정에 따라 초기 production은 Mac mini 내부 local-only application-consistent backup을 사용한다. `pg_dump -Fc`와 canonical media를 같은 backup-set ID로 묶고 protected source와 분리된 local repository/path에서 retention·check·isolated restore를 검증한다.
- 초기 local-only backup은 logical deletion·corruption·rollback 복구에는 도움을 주지만 Mac mini host/storage 전체 손실 시 production data와 함께 손실될 수 있다. 이 single-host risk는 초기 production에서 수용한다.
- 외장 SSD·iCloud 3-2-1과 offsite RPO는 future hardening이다. 구현 전에는 offsite 상태를 `NOT_CONFIGURED / DEFERRED`로 두고 local backup 성공을 offsite `PASS`로 표시하지 않는다.
- Mac host filesystem authority는 `/private/var/lib/rhaomi`이고 PostgreSQL primary PGDATA는 production project-scoped Docker named volume이다. exact ownership·permission·rendered volume identity, bind/persistence smoke와 logical backup→isolated `pg_restore` 증거는 provisioning 전까지 출시 차단이다.
- `/srv/rhaomi`는 Linux web/publisher container target에만 허용한다. Mac host `synthetic.conf`·Docker Desktop custom File Sharing, PostgreSQL host PGDATA bind와 raw-volume restic backup은 production 계약이 아니다.
- [ADR-010](../09-decisions/ADR-010-production-topology-and-code-release.md)~[ADR-015](../09-decisions/ADR-015-lossless-int64-json-wire-contract.md)는 production 운영·lossless wire 계약을 확정했고 publisher control loop와 Build API→transformer→Next→immutable release/atomic switch에 이어 synthetic same-origin Admin HTTP·scheduled boundary·read-only Nginx 런타임 독립 acceptance까지 구현했다. [Production readiness matrix](../07-operations/production-readiness.md)가 이를 production provisioning·외부 승인·물리 acceptance와 분리한다. production Compose·GitHub Environment·publisher service/secret/path provisioning, local backup, HomeOps, passkey와 decoder-only image는 아직 구현되지 않았다.
- Phase 1C-8f8 sample은 tmpfs/temp root에서만 생성되고 migration·production profile·default fixture로 seed되지 않는다. local PASS를 실제 NAP·예약 정책·고객/반려견 사진 승인이나 production 공개 PASS로 해석하지 않는다.
- 이번 Issue에서는 실제 이미지·갤러리 seed, 운영 `shop_settings` provisioning과 production migration을 실행하지 않는다. 실제 값·게시 권한 확인과 별도 운영 승인을 거친 후속 작업으로 남긴다.

## 확인 전 실행 보류

다음 작업은 정보가 확정되기 전 운영 환경에서 수행하지 않는다.

- 검색엔진 소유권 인증
- 운영 도메인 canonical 배포
- 실제 관리자 초대
- 실제 사진 공개
- 개인정보 분석 스크립트 설치
- 운영 데이터 삭제
