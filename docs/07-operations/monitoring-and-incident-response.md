---
title: "모니터링·장애 대응"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "모니터링 도구·장애 등급 변경 시"
---

# 모니터링·장애 대응

## authority와 구현 상태

[ADR-013](../09-decisions/ADR-013-homeops-monitoring-recovery-boundary.md)에 따라 HomeOps만 중앙 monitoring, incident, Activity, Discord alert와 daily summary authority로 사용한다.

- Rhaomi는 privacy-safe health/status/event/metric source만 제공한다.
- 별도 availability·host metrics dashboard나 push heartbeat를 만들지 않는다.
- Prometheus·Grafana·Loki는 초기 범위에 포함하지 않는다.
- HomeOps UI와 운영 endpoint는 Tailscale 전용이다.
- Rhaomi용 HomeOps monitor·alert·restart 설정은 아직 구현되지 않았다.

## 모니터링 대상

### 공개 사이트

- HTTPS 200
- 홈 핵심 문구 존재
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
- 외장 SSD snapshot/check 결과
- local iCloud Drive repository snapshot/check와 integrity 결과
- Apple remote sync가 별도 검증된 마지막 backup-set ID·시각과 offsite RPO
- remote sync 증거가 없을 때 offsite `UNKNOWN` 또는 실패 상태. local 성공으로 `PASS` 대체 금지
- 마지막 isolated full restore 일시·RPO/RTO
- 최초 production fresh retrieval·restic check·대표 restore evidence 상태

### host

- Mac mini CPU, memory와 load
- disk·inode·project volume capacity
- `/private/var/lib/rhaomi` bind source와 Docker-managed PostgreSQL named volume capacity를 분리한 상태
- Docker daemon과 container health
- TLS certificate 만료

## 초기 임계값

| 신호 | 조건 | 결과 |
|---|---|---|
| public HTTPS·핵심 문구 | 3회 연속 실패 | 즉시 alert |
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

임계값 변경은 HomeOps와 이 문서를 함께 갱신하고 actual baseline 근거를 남긴다.

## 자동 복구 경계

명시적으로 opt-in한 stateless `rhaomi-web`, `rhaomi-backend` container 각각의 단일 restart만 허용한다.

필수 조건:

- 정의된 연속 health 실패
- deploy lock과 backup lock 없음
- 같은 service restart 후 30분 cooldown
- trigger, 전후 health와 결과 audit

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
