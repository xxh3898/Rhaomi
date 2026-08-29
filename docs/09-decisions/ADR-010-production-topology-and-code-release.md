---
title: "ADR-010: Production topology와 코드 릴리스"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "운영 진입 경로·배포·마이그레이션·릴리스 보존 변경 시"
---

# ADR-010: Production topology와 코드 릴리스

- 결정일: 2026-08-29
- 상태: Accepted
- 관련 결정: [ADR-001](ADR-001-nextjs-static-export.md), [ADR-008](ADR-008-runtime-independent-public-site.md), [ADR-009](ADR-009-spring-boot-backend-admin.md)

## 맥락

Rhaomi는 Mac mini에서 다른 홈서버 서비스와 함께 운영될 예정이며 공개 정적 사이트, same-origin 관리자 API, PostgreSQL과 private media를 서로 다른 신뢰 경계로 분리해야 한다. `main` merge, image 생성, 운영 반영과 Flyway migration도 하나의 암묵적 자동 단계로 묶지 않아야 한다.

이 결정은 목표 운영 계약만 확정한다. production Compose, Nginx, Cloudflare Tunnel, GHCR image, GitHub Environment와 Mac mini deploy entrypoint는 아직 구현·provisioning되지 않았다.

## 결정

### 외부 진입과 route

```text
Internet
→ Cloudflare DNS/HTTPS
→ Cloudflare Tunnel
→ 기존 host edge Nginx
→ Rhaomi project web Nginx
   ├─ /, /admin/, static assets → /srv/rhaomi/public/current
   └─ /api/admin/**             → Spring Boot backend
```

- 공유기 port forwarding을 사용하지 않는다.
- `cloudflared`가 외부로 연결하며 Rhaomi project web은 host loopback에만 bind한다.
- 고객 공개 화면과 `/admin/`은 같은 origin을 사용한다.
- `/admin/`은 검색 제외 대상일 뿐 인증 경계가 아니다. Spring session·CSRF와 출시 전 2FA·rate limit이 업무 경계다.
- PostgreSQL, backend direct port, publisher, backup과 HomeOps는 public exposure가 없다.
- Tailscale은 SSH, HomeOps UI와 운영 장애 대응에만 사용한다.
- public Nginx는 `/api/build/**`, `/internal/**`와 `/actuator/**`를 거부한다. HomeOps의 최소 health 조회는 내부 경로를 사용한다.

### production 디렉터리

```text
/srv/rhaomi/
├── app/
├── public/
│   ├── releases/<release-id>/
│   ├── current -> releases/...
│   └── previous -> releases/...
├── data/
│   ├── postgres/
│   └── media/
├── state/
│   ├── publisher/
│   └── locks/
└── logs/
```

- public web에는 `/srv/rhaomi/public`만 read-only로 mount한다.
- private DB·media를 web에 mount하지 않는다.
- backend만 canonical media를 read-write한다.
- PostgreSQL과 media는 container lifecycle과 분리된 host bind mount를 사용한다.
- publisher 상태와 전역 lock은 `state`에 두고 release 산출물과 구분한다.

### 코드 릴리스

```text
feature → dev
→ dev → main Release PR
→ exact main SHA GitHub-hosted 검증
→ immutable production image build
→ private GHCR
→ GitHub production environment 수동 승인
→ Tailscale SSH
→ Mac mini 고정·versioned deploy entrypoint
```

- public repository의 PR 코드를 production Mac mini runner에서 실행하지 않는다.
- `main` merge는 검증 가능한 release 후보를 만들 뿐 운영 반영을 자동 승인하지 않는다.
- production job은 보호된 environment 승인 뒤에만 environment secret에 접근한다.
- 임의 SSH command body 대신 exact SHA·digest와 제한된 매개변수만 받는 고정 entrypoint를 사용한다.
- image에는 commit SHA tag를 붙이고 실제 배포는 digest로 고정한다. `latest`는 금지한다.
- release evidence에 exact SHA, image digest, SBOM, dependency/image scan, migration과 smoke 결과를 기록한다.

### 배포 순서

1. global deploy lock 획득
2. exact `main` SHA와 release manifest 확인
3. disk 여유와 `current`·`previous` 확인
4. 최근 정상 backup 확인
5. migration·major update면 on-demand backup 생성·검증
6. image pull과 digest 검증
7. 관리자 write maintenance 활성화
8. one-shot Flyway migration 실행
9. 새 backend의 schema validation과 internal health 확인
10. [ADR-011](ADR-011-transactional-outbox-static-publisher.md)의 동일 publisher pipeline으로 static release 생성
11. 새 release directory smoke
12. 기존 `current`를 `previous`로 기록
13. `current` symlink 원자적 전환
14. public HTTPS smoke
15. 관리자 write maintenance 해제
16. release evidence와 HomeOps 상태 기록

검증 전에는 `current`를 바꾸지 않는다. 전환 후 smoke가 실패하면 `previous`로 즉시 복귀하고 maintenance 해제 여부를 명시적으로 판단한다. public static site는 maintenance 중에도 계속 제공한다.

### Flyway와 schema 호환성

- production backend 일반 기동은 schema를 자동 변경하지 않고 validate만 한다.
- Flyway migration은 deploy lock과 maintenance 안의 one-shot service만 수행한다.
- additive expand/contract를 우선하고 새 code와 직전 code가 전환 구간에서 공존 가능한 schema를 유지한다.
- column/table 삭제, 대량 변환과 비가역 migration은 별도 승인, on-demand backup과 isolated restore 검증이 필요하다.
- 검증되지 않은 destructive rollback을 실행하지 않는다.

### release 보존

- 성공 release 최근 5개를 보존한다.
- `current`와 `previous`가 가리키는 release는 보존 수와 무관하게 정리하지 않는다.
- 실패 release와 evidence는 7일 보존한다.
- build cache와 정적 derivative는 재생성 가능 대상으로 분리한다.

## 이유

- 공개 사이트를 backend·DB 장애와 분리하면서 같은 origin 관리자 API를 유지한다.
- merge와 production apply를 분리해 검증·수동 승인·rollback 근거를 확보한다.
- digest와 고정 entrypoint는 mutable tag와 임의 원격 shell의 범위를 줄인다.
- one-shot migration과 expand/contract는 code rollback을 schema 변경과 분리한다.

## 결과

### 장점

- public/internal/data network와 filesystem 경계가 명확하다.
- 현재·직전 정적 release를 원자적으로 전환·복구할 수 있다.
- 배포 근거를 exact SHA·digest·manifest로 재현할 수 있다.

### 비용·위험

- GitHub environment, GHCR, Tailscale deploy identity와 제한된 entrypoint를 별도로 구현해야 한다.
- Mac mini, host edge Nginx와 Cloudflare Tunnel은 공통 장애 지점이다.
- schema가 비호환이면 image rollback만으로 복구할 수 없다.

## 거부한 대안

### `main` merge 즉시 Mac mini 자동 배포

운영 승인과 Secret 접근이 merge에 암묵적으로 결합되고 장애 시 중단 지점이 부족해 거부한다.

### production host에서 PR source build

검토되지 않은 코드를 운영 host에서 실행하고 build toolchain을 운영면에 추가하므로 거부한다.

### mutable `latest` image

실행 중인 code와 rollback 대상을 exact하게 증명할 수 없어 거부한다.

## 실행 계획

- [ ] production Compose와 project Nginx 구현
- [ ] GHCR immutable multi-architecture image와 SBOM pipeline 구현
- [ ] GitHub production environment·required reviewer·branch policy 설정
- [ ] Tailscale 전용 고정 deploy entrypoint 구현
- [ ] one-shot Flyway와 write maintenance 구현
- [ ] release·rollback evidence를 격리 환경에서 검증

## 재검토 조건

- 운영 host 또는 ingress가 Mac mini·Cloudflare Tunnel에서 변경됨
- 무중단 다중 instance가 필요해짐
- release 빈도·복구 목표가 현재 수동 승인 모델을 초과함
- destructive schema 변경이 승인됨
