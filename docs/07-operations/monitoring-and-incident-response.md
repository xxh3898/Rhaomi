---
title: "모니터링·장애 대응"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-09-02"
review_trigger: "모니터링 도구·장애 등급 변경 시"
---

# 모니터링·장애 대응

## authority와 구현 상태

[ADR-013](../09-decisions/ADR-013-homeops-monitoring-recovery-boundary.md)에 따라 HomeOps만 중앙 monitoring, incident, Activity, Discord alert와 daily summary authority로 사용한다.

- Rhaomi는 privacy-safe health/status/event/metric source만 제공한다.
- 별도 availability·host metrics dashboard나 push heartbeat를 만들지 않는다.
- Prometheus·Grafana·Loki는 초기 범위에 포함하지 않는다.
- HomeOps UI와 운영 endpoint는 Tailscale 전용이다.
- D-IMP-5a는 fixed bounded status, HomeOps current deployment/backup adapter, `rhaomi-web` source label과 web/backend fixed one-restart target을 task-scoped local/CI에서 구현했다.
- HomeOps Release PR #122는 reviewed tree를 production `main@0a8ce9090c76f5ad7afba19ca896e923b96b0cbf`에 병합했고 Publish and Deploy run `33569523762`에서 application deploy와 V14 `APPLIED`를 확인했다.
- 같은 run의 Agent artifact는 `PUBLISHED`이며 exact digest는 `sha256:305c0f216bf00097ae8532b33991aed99e752669a32956b85eebfbf7351bcf4b`다. Agent live rollout은 `NOT_RUN`이다.
- monitor/mapping/Rhaomi fixed inventory provisioning, notification activation과 recovery acceptance는 계속 `NOT_RUN`이다.
- [Production readiness matrix](production-readiness.md)의 HomeOps 행은 source 구현이 확인됐으므로 `PROVISIONING_REQUIRED`다. 이는 actual monitor나 automatic recovery의 production `PASS`가 아니며 overall readiness는 `HOLD`다.

## 구현된 D-IMP-5a 경계

- `status-rhaomi.py`: fixed production inventory만 읽는 최대 4 KiB JSON. secret·content·private path·Docker Env와 raw log는 출력하지 않는다.
- `report-rhaomi-event.py`: HomeOps pinned reporter를 재사용하는 exact DTO adapter. lifecycle은 같은 event key·startedAt으로 RUNNING→SUCCESS/FAILED를 갱신한다.
- telemetry outcome: private spool acknowledgement `RETAINED`, 미설치 `NOT_CONFIGURED`, authority/local spool failure `FAILED`. deploy/backup transaction 결과와 별도다.
- `recover-rhaomi-service.py`: `restart rhaomi-web|backend`만 허용하고 shared deploy/backup lock, current identity, exactly-one restart와 pre/post health를 확인한다. restart 완료가 불확실하면 lock을 유지한다.
- Compose source의 `homeops.managed=true`는 read-only `rhaomi-web` 하나뿐이다. backend·publisher·PostgreSQL·task service는 generic control opt-in 0이다.

actual HomeOps endpoint/HMAC secret은 task validation에서 접근하지 않는다. reporter·status/recovery source와 compatibility snapshot은 production provisioning 때 fixed `/private/var/lib/rhaomi/app/bin` inventory로 설치·검증해야 한다.

## 모니터링 대상

### 공개 사이트

- HTTPS 200
- 홈 핵심 문구 존재 — current HomeOps checker 미지원, future monitoring enhancement
- 정적 asset 200
- 응답시간
- 인증서 만료
- DNS
- 404 동작

### 관리자

- Spring Boot internal 최소 health endpoint
- Docker container state·health
- PostgreSQL connection·lock·rollback 최소 지표
- production project-scoped PostgreSQL named volume identity·capacity·mount 상태
- `/private/var/lib/rhaomi/data/media` private canonical media storage 상태
- 로그인 실패 급증

CSRF 발급 endpoint를 availability probe로 호출하지 않는다. 실제 login mutation을 자동 probe로 반복하지 않는다.

### 배포

- 마지막 성공 release
- 마지막 실패 원인
- pending immediate event, due/overdue scheduled event와 가장 오래된 `availableAt`
- 마지막 처리 `contentRevision`·`publishGeneration`·`generatedAt`
- publisher lock 점유 시간
- 마지막 성공·실패 build/release
- 디스크 여유
- release 보존 개수

### 백업

- 마지막 local backup 성공 시각과 local RPO
- backup-set ID, dump·media size와 file count
- protected source와 분리된 Mac mini local repository identity·capacity·manifest/check 결과
- 마지막 isolated full restore 일시·RPO/RTO
- single-host backup risk 수용 상태
- future external SSD/iCloud hardening은 구성 전 `NOT_CONFIGURED / DEFERRED`; local 성공으로 offsite `PASS` 대체 금지

### host

- Mac mini CPU, memory와 load
- disk·inode·project volume capacity
- `/private/var/lib/rhaomi` bind source와 Docker-managed PostgreSQL named volume capacity를 분리한 상태
- Docker daemon과 container health
- TLS certificate 만료

## 초기 임계값

| 신호 | 조건 | 결과 |
|---|---|---|
| public HTTPS expected HTTP status | 3회 연속 실패 | 즉시 alert |
| keyword/body/content probe | 별도 HomeOps monitoring 구현 필요 | current automatic recovery trigger 제외 |
| container health | 2회 연속 unhealthy | 즉시 alert |
| local backup | 마지막 성공 36시간 초과 | critical |
| offsite backup | future hardening 미구성 | `NOT_CONFIGURED / DEFERRED`, 초기 critical trigger 아님 |
| publisher lock | 15분 초과 | critical |
| pending/due outbox | immediate pending 또는 `availableAt <= now` overdue 10분 초과 | warning |
| build/release | 실패 | 즉시 alert |
| disk | 80% / 90% | warning / critical |
| memory | 85%가 10분 지속 | warning |
| CPU | 90%가 10분 지속 | warning |
| TLS 만료 | 30 / 14 / 7일 | 단계별 alert |

임계값 변경은 HomeOps와 이 문서를 함께 갱신하고 actual baseline 근거를 남긴다.

## 자동 복구 경계

architecture상 local fixed action은 `rhaomi-web`, `backend` 각각의 단일 restart뿐이다. 승인된 automatic recovery mapping은 public HTTPS response status가 monitored-service의 `expectedStatus`와 다른 상태로 3회 연속 판정된 incident → `rhaomi-web` 하나다. `backend`는 unmapped/default-none이고 automatic recovery가 비활성이다. D-IMP-5a local target allowlist에 backend가 남아 있다는 사실은 mapping 또는 activation authority가 아니다.

Current HomeOps `MonitoredServiceRequest`는 GET/HEAD와 `expectedStatus`를 제공하고 `HttpServiceChecker`는 response body를 버린 뒤 status equality만 판정한다. Keyword/body/content probe는 별도 future monitoring 구현 뒤 재검토하며 current automatic recovery trigger가 아니다.

필수 조건:

- 정의된 연속 health 실패
- deploy lock과 backup lock 없음
- 같은 service restart 후 30분 cooldown
- trigger, 전후 health와 결과 audit

HomeOps D-IMP-5b application과 V14 schema는 production에 배포됐고 incident winner, exact mapping row lock과 durable 30분 cooldown contract를 유지한다. `FAILED`와 `OUTCOME_UNKNOWN`은 자동 재실행 금지다. Production mapping row와 Agent capability는 아직 없고 fixed target의 존재를 automatic recovery 활성화로 해석하지 않는다.

## production activation preflight

[Activation preflight](../../ops/production/homeops-activation-preflight.json)는 historical source evidence와 live production release evidence를 분리한다. [Compatibility snapshot](../../ops/production/homeops-compatibility.json)은 reporter·DTO·monitoring contract를 다시 계산한 current HomeOps `main@0a8ce9090c76f5ad7afba19ca896e923b96b0cbf`를 authority로 고정한다.

Cross-repository 순서는 `HomeOps release → live compatibility 재검증 → Rhaomi release/provisioning`이다. HomeOps release와 live compatibility는 run `33569523762` 기준으로 완료했고 Rhaomi release/provisioning은 `NOT_RUN`이다. 이후 disabled web mapping → fixed inventory → Agent rollout/fresh capability → read-only end-to-end 확인 → 별도 mapping enable 승인 → 별도 controlled restart/drill 승인 → post-health/audit/Activity → observation window 순으로 진행한다. 각 단계 실패 시 다음 mutation을 시작하지 않는다.

- monitored-service exact row identity와 runtime/Agent rollback identity는 private production evidence로만 확인한다.
- backend mapping은 생성하지 않는다.
- deploy/backup shared lock, current image/config, 정상 backup/restore eligibility 중 하나라도 불확실하면 enable하지 않는다.
- notification/Discord activation은 recovery acceptance와 별도 승인이다.
- Issue #61에서는 Rhaomi release/provisioning, mapping create/enable, Agent rollout, notification activation, restart/drill을 수행하지 않는다.
- 후속 실패 rollback은 web mapping disable/default-none, no-auto-retry, V14 audit 보존과 previous exact Agent artifact 복귀다. Application rollback과 DB migration 상태를 분리하고 불확실 shared lock은 임의 삭제하지 않는다.

자동 복구에서 금지:

- Compose `down`·`up`
- PostgreSQL restart
- volume·filesystem data mutation
- `docker compose down -v`, `docker volume prune`, named volume direct delete
- migration·restore·backup 삭제
- `cloudflared`·HomeOps 자체 제어
- allowlist 밖 service의 임의 start·stop

## 로그 계약

- Docker `local` driver 또는 동등한 bounded rotation으로 service당 약 100 MiB를 초기 한도로 둔다.
- request body, Cookie, Authorization, CSRF, password와 session ID를 기록하지 않는다.
- 기본 access log에서 query string을 제외한다.
- release·backup ID와 결과 중심으로 기록한다.
- 일반 로그는 14일 보존하고 incident hold는 자동 정리에서 제외한다.

## 관제 사각지대

HomeOps가 Mac mini와 같은 host에 있어 전원·회선·Docker 전체 장애는 관제와 alert를 함께 중단할 수 있다. 초기 서비스에서는 이 same-host blind spot을 명시적으로 수용하며 외부 heartbeat를 추가하지 않는다.

초기 backup도 같은 Mac mini에 있으므로 host/storage 전체 손실은 production data와 local backup을 함께 잃을 수 있다. HomeOps는 이 상태를 숨기지 않고 external/offsite hardening이 구성될 때까지 `NOT_CONFIGURED / DEFERRED`로 표시한다.

## 장애 등급

| 등급 | 예 | 대응 |
|---|---|---|
| SEV-1 | 공개 사이트 전체 불가, 도메인 탈취, 관리자 침해 | 즉시 격리·롤백·자격 증명 폐기 |
| SEV-2 | 문의 링크 오류, 잘못된 영업정보, 콘텐츠 게시 불가 | 당일 수정 |
| SEV-3 | 일부 이미지 오류, 비핵심 UI 결함 | 계획된 수정 |
| SEV-4 | 문구·정렬 개선 | backlog |

## 초기 대응

1. 현재 공개 site가 정상인지 확인
2. 마지막 변경이 코드인지 콘텐츠인지 구분
3. release ID와 commit 기록
4. 새 배포 중지
5. 침해 의심 시 관리자 세션·토큰 차단
6. 기존 정상 release가 있으면 rollback
7. DB/파일 손상이 의심되면 쓰기 중지
8. 증거 보존
9. 사건 문서 작성

자동 restart가 수행됐더라도 원인 확인·evidence와 cooldown audit를 생략하지 않는다.

## 콘텐츠 오정보

영업시간, 휴무, 전화번호가 잘못된 경우:

1. 후속 `/admin`에서 수정
2. 재배포 결과 확인
3. 네이버지도·카카오맵·블로그도 확인
4. 캐시된 공유 미리보기와 검색 결과는 즉시 바뀌지 않을 수 있음을 기록
5. 중요한 경우 공지 또는 SNS로 정정

## 침해 의심

- 운영자 계정 비활성화
- 모든 관리자 session 폐기
- build/publisher service credential rotation
- GitHub production·Tailscale deploy credential 확인
- restic key 노출 가능성이 있으면 backup repository와 recovery 절차 확인
- GitHub runner와 credential 확인
- Nginx·Spring Boot access log 보존
- 파일·콘텐츠 변경 이력 확인
- clean release 재배포
- 재발 방지 조치

## 종료 조건

- 고객 영향 제거
- 원인과 변경 범위 확인
- 복구 검증
- 남은 위험 기록
- 후속 Issue
- 문서·런북 갱신
