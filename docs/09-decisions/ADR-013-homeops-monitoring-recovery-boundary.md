---
title: "ADR-013: HomeOps 통합 관제·알림·자동 복구 경계"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "관제 authority·임계값·자동 복구·로그 보존 변경 시"
---

# ADR-013: HomeOps 통합 관제·알림·자동 복구 경계

- 결정일: 2026-08-29
- 상태: Accepted
- 관련 결정: [ADR-008](ADR-008-runtime-independent-public-site.md), [ADR-010](ADR-010-production-topology-and-code-release.md), [ADR-012](ADR-012-application-consistent-backup-restore.md)

## 맥락

Mac mini에서 프로젝트별 dashboard·heartbeat·alert stack을 중복 운영하면 같은 장애가 여러 채널에서 다르게 판정되고 host 자원을 소비한다. Rhaomi는 필요한 privacy-safe 상태만 제공하고 incident, Activity, Discord 알림과 일일 요약의 단일 authority를 기존 HomeOps로 유지해야 한다.

HomeOps와 Rhaomi가 같은 host에 있으므로 전원, 회선 또는 Docker 전체 장애 때 알림을 보내지 못할 수 있다. 초기 서비스는 이 blind spot을 명시적으로 수용한다.

## 결정

### 단일 관제 authority

- HomeOps만 중앙 monitoring, incident, Activity, Discord alert와 daily summary authority로 사용한다.
- Rhaomi 전용 availability dashboard, host metrics dashboard와 push heartbeat를 추가하지 않는다.
- 초기에는 Prometheus, Grafana와 Loki를 도입하지 않는다.
- HomeOps UI와 운영 endpoint는 Tailscale 전용이다.

### Rhaomi가 제공할 privacy-safe contract

- public HTTPS와 핵심 문구 probe 대상
- Spring Boot internal minimal health
- Docker container healthcheck
- secret 없는 bounded status script
- deployment, publisher, backup과 restore drill typed event
- disk·PostgreSQL·publisher에 필요한 최소 metric source

CSRF 발급 endpoint를 availability probe로 호출하지 않는다. health/status response에는 콘텐츠 body, email, path, credential, token과 session 식별자를 포함하지 않는다.

### HomeOps 통합 대상

- public HTTPS·핵심 문구 synthetic check
- internal application health
- Docker container state·health
- Mac mini CPU, memory와 load
- disk, inode와 volume capacity
- PostgreSQL connection, lock과 rollback 등 최소 운영 지표
- publisher immediate pending·due/overdue scheduled event, lock, 마지막 `contentRevision`·`publishGeneration`·`generatedAt`과 성공·실패 release
- local backup RPO, local iCloud repository integrity, remotely verified offsite RPO와 fresh retrieval·restore drill 상태
- incident·Activity·Discord 알림과 daily summary

### 초기 임계값

| 신호 | 조건 | 결과 |
|---|---|---|
| public HTTPS·keyword | 3회 연속 실패 | 즉시 alert |
| container health | 2회 연속 unhealthy | 즉시 alert |
| local backup | 마지막 성공 36시간 초과 | critical |
| offsite backup | 최초 remote 증거 없음 / 마지막 remotely verified success 36시간 초과 | `UNKNOWN`+critical alert / critical |
| publisher lock | 15분 초과 | critical |
| pending/due outbox | immediate pending 또는 `availableAt <= now` overdue 10분 초과 | warning |
| build/release | 실패 | 즉시 alert |
| disk | 80% / 90% | warning / critical |
| memory | 85%가 10분 지속 | warning |
| CPU | 90%가 10분 지속 | warning |
| TLS 만료 | 30 / 14 / 7일 | 단계별 alert |

### 자동 복구

허용 범위는 명시적으로 opt-in한 stateless `rhaomi-web`, `rhaomi-backend` container 각각의 단일 restart뿐이다.

다음 조건을 모두 만족해야 한다.

- 정의된 연속 health 실패
- deploy lock과 backup lock 없음
- 같은 service restart 후 30분 cooldown
- trigger, 전후 health와 결과 audit 기록

다음 작업은 자동 복구에서 금지한다.

- Compose `down`·`up`
- PostgreSQL restart
- volume·filesystem data mutation
- migration·restore·backup 삭제
- `cloudflared` 또는 HomeOps 자체 제어
- allowlist 밖 service의 임의 start·stop

### 로그

- Docker `local` driver 또는 동등한 bounded rotation을 사용한다.
- service당 총 약 100 MiB를 초기 한도로 둔다.
- request body, Cookie, Authorization, CSRF, password와 session ID를 기록하지 않는다.
- 기본 access log에서 query string을 제외한다.
- release·backup ID와 상태 code·결과 중심으로 기록한다.
- 일반 로그는 14일 보존하고 incident hold로 표시된 증거는 자동 정리에서 제외한다.

### 수용하는 잔여 위험

HomeOps가 Mac mini와 같은 host에 있어 전원·회선·Docker 전체 장애는 관제와 알림을 동시에 중단할 수 있다. 초기 서비스에서는 이 same-host blind spot을 수용하며 외부 heartbeat를 추가하지 않는다.

## 이유

- 단일 authority가 incident와 alert의 중복·상충을 줄인다.
- 프로젝트별 contract만 두어 관제 UI와 알림 credential을 Rhaomi에 중복 저장하지 않는다.
- 자동 복구를 stateless 단일 restart로 제한해 data·migration·backup 손상을 피한다.

## 결과

### 장점

- 홈서버 전체 신호를 한 incident 흐름으로 연계할 수 있다.
- Rhaomi runtime은 최소 health/event surface만 제공한다.
- restart 조건과 금지 범위를 audit할 수 있다.

### 비용·위험

- HomeOps 장애가 중앙 관제 공백으로 이어진다.
- 같은 host 전체 장애는 외부에서 감지하지 못한다.
- 임계값은 실제 운영 baseline에 따라 조정이 필요할 수 있다.

## 거부한 대안

### 프로젝트별 Prometheus·Grafana·Loki

초기 규모에 비해 운영·storage·alert 중복 비용이 커서 거부한다.

### public CSRF endpoint probe

availability와 무관한 token 발급을 반복하고 보안 endpoint를 synthetic traffic에 사용하므로 거부한다.

### 광범위한 자동 재시작

DB·migration·volume과 공통 infra에 대한 자동 mutation은 장애를 확대할 수 있어 거부한다.

## 실행 계획

- [ ] Rhaomi minimal health/status/event schema 구현
- [ ] HomeOps public/internal/container/host/DB/publisher/backup monitor 등록
- [ ] 임계값과 Discord·Activity routing 검증
- [ ] opt-in stateless single-restart allowlist와 lock/cooldown/audit 구현
- [ ] bounded log rotation과 secret redaction 검증
- [ ] same-host outage 수동 확인 runbook 작성

## 재검토 조건

- 외부 SLA 또는 무인 장애 감지가 요구됨
- same-host blind spot이 실제 복구 목표를 침해함
- 서비스 규모가 별도 metric store를 정당화함
- 자동 복구가 반복 실패하거나 data service 제어 요구가 생김
