---
title: "출시 체크리스트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
review_trigger: "출시 기준 변경 시"
---

# 출시 체크리스트

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

- [ ] Java image·Spring Boot·Gradle Wrapper 잠금 버전
- [ ] PostgreSQL 연결
- [ ] Flyway migration과 JPA schema validation
- [ ] server session·CSRF·fixation 방어
- [ ] 관리자 API field allowlist와 anonymous deny
- [ ] build API credential·read-only policy
- [ ] 관리자 2FA
- [ ] 상태 validation
- [ ] archive 운영
- [ ] `/admin/` media list·authenticated preview·single upload·archive/restore와 401/403 non-retry 계약
- [ ] `/admin/` shop settings 404 미초기화·26-key full PUT·canonical response와 audit field 제외
- [ ] Hero·미용사·OG active-only private media picker, image-alt pair와 archived/missing relation clear·replace
- [ ] `/admin/` breed/service strict list·draft create·immutable slug·full PUT·server order 보존·post-mutation canonical GET와 archive/restore
- [ ] 서비스 published description·priceText 보조 검증과 404/409/422 frontend-owned 오류 문구
- [ ] breed/service pending 중복 방지·stale GET 차단·401/403 mutation non-retry와 canonical refresh ready 뒤 enabled trigger keyboard focus 복귀
- [ ] 공개 영향 transaction과 같은 PostgreSQL transaction의 immediate publishing event
- [ ] future notice publishedAt·expiresAt의 `availableAt` durable scheduled event와 overdue recovery
- [ ] scheduled event current-row/snapshot 재검증과 reschedule·draft/archive·window-change stale no-op/coalesce
- [ ] `contentRevision`·`publishGeneration` 분리와 draft-only 변경 trigger 분류
- [ ] single publisher 30초 debounce·global lock·highest generation coalescing
- [ ] build API read-only와 public `/api/build/**` deny
- [ ] published·notice expiry·relation·media/file의 API/transformer 이중 검증
- [ ] publisher 동일 generation 1분·5분·15분 최대 3회 retry, 승인된 manual rebuild/retry의 새 generation과 data 오류 중단
- [ ] snapshot/release manifest의 `contentRevision`·`publishGeneration`·`generatedAt`과 generation 기준 stale switch 거부
- [ ] 실제 iPhone Safari HEIC 선택·upload·preview·archive/restore
- [ ] 실제 iPhone Safari·VoiceOver 견종·서비스 form·status·sortOrder·긴 slug reflow

## 공개 사이트

- [ ] static export
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
- [ ] HEIC 검증
- [ ] decoder-only libheif `v1.23.1` exact commit과 libde265 decode
- [ ] production CMake의 libde265-only fail-closed codec allowlist와 encoder·plugin·experimental path OFF
- [ ] production image x265 package·library·link·plugin 부재
- [ ] source·license notice·SBOM·image scan
- [ ] Linux amd64와 Mac mini Linux arm64 actual HEIC fixture
- [ ] asset budgets
- [ ] Lighthouse
- [ ] 저속 모바일 표본
- [ ] layout shift
- [ ] third-party SDK 없음

## 보안·운영

- [ ] HTTPS
- [ ] Cloudflare Tunnel → host edge Nginx → loopback project Nginx
- [ ] same-origin `/admin`, `/api/admin/**` route
- [ ] public `/api/build/**`, `/internal/**`, `/actuator/**` deny
- [ ] DB 외부 비공개
- [ ] publisher public network·Docker socket 부재
- [ ] secrets scan
- [ ] production session `Secure`, TLS와 관리자 2FA 확인
- [ ] exact main SHA·immutable image·digest와 `latest` 부재
- [ ] GitHub production environment 수동 승인과 고정 Tailscale deploy entrypoint
- [ ] write maintenance·one-shot Flyway·schema validate·expand/contract
- [ ] 실제 Mac `/private/var/lib/rhaomi/{app,public,data/media,state,logs}` 생성·ownership·permission
- [ ] Mac public/media/state source의 Docker Desktop bind smoke와 web RO·backend/publisher 최소 write mode
- [ ] `/srv/rhaomi/public`이 web/publisher Linux container target일 뿐 Mac host source가 아님
- [ ] PostgreSQL host PGDATA bind 부재, production project-scoped named volume exact identity·mount
- [ ] PostgreSQL container restart와 일반 Compose `down`·`up` 뒤 DB persistence
- [ ] production `docker compose down -v`, `docker volume prune`, named volume direct delete 금지
- [ ] DB `pg_dump -Fc`와 canonical media의 동일 backup-set manifest
- [ ] raw PostgreSQL volume required restic input 부재와 새 isolated named volume `pg_restore` 검증
- [ ] 외장 SSD·iCloud의 별도 encrypted restic repository
- [ ] 외장 SSD `/Volumes/<provisioned-volume>/...` exact repository path·volume identity·ownership
- [ ] local iCloud Drive repository snapshot/check와 Apple remote sync 완료 증거 분리
- [ ] remote sync 미증명 backup set의 offsite RPO `PASS` 금지와 local/offsite RPO 별도 표시
- [ ] 최초 production gate의 second trusted device 또는 clean retrieval path fresh retrieval·restic check·대표 restore
- [ ] daily 7 / weekly 4 / monthly 6와 post-prune check
- [ ] quarterly isolated full restore, local/offsite RPO 24h 분리·RTO 8h evidence
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
