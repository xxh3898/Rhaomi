---
title: "ADR-003: 콘텐츠 변경 시 정적 재빌드"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
review_trigger: "콘텐츠 반영 방식 변경 시"
---

# ADR-003: 콘텐츠 변경 시 정적 재빌드

- 결정일: 2026-08-28
- 상태: Accepted
- 구체화: [ADR-011](ADR-011-transactional-outbox-static-publisher.md)

## 맥락

관리 backend를 사용하면서도 공지·사진·서비스가 검색 가능한 정적 HTML에 포함되어야 한다. 운영자는 저장 후 개발자의 수동 코드 변경 없이 공개 반영할 수 있어야 한다.

## 결정

공개 결과에 영향을 주는 콘텐츠 변경과 publishing outbox를 같은 PostgreSQL transaction에 기록한다. 새로 설정·변경된 게시·만료 경계를 가진 Notice와 published Gallery 게시 경계는 저장 transaction에서 durable scheduled publishing event도 함께 기록한다. single internal publisher는 immediate pending event와 due scheduled event를 처리해 현재 승인된 production code image/digest로 정적 사이트를 다시 생성한다.

현재 Phase 1C-8f1은 Flyway V8 transactional `contentRevision`과 immediate·Notice/Gallery scheduled outbox producer, 8f2는 claim/lease·`publishGeneration` state, 8f3은 active generation 기반 internal read-only build snapshot·public-scope media API, 8f4는 strict snapshot transformer·responsive derivative·atomic staging까지 구현했다. polling/debounce publisher, build API HTTP client, Next render와 release switch는 후속 Issue다.

```text
Spring Boot content transaction
→ same-transaction immediate·scheduled publishing event
→ single internal publisher
→ snapshot
→ image variants
→ static export
→ validation
→ atomic switch
```

## 안전장치

- build API와 publisher는 외부 공개하지 않음
- 관리자 session과 분리된 read-only service credential
- Notice `publishedAt`·`expiresAt`, Gallery `publishedAt`의 durable `availableAt` event와 restart 후 overdue 처리
- scheduled event 처리 시 current Notice·Gallery row·snapshot 재검증과 stale no-op/coalesce
- 콘텐츠 mutation `contentRevision`과 public trigger `publishGeneration` 분리
- 30초 debounce
- global build lock
- 임시 release에서 build
- 검증 성공 전 current 미변경
- 실패 시 기존 release 유지
- 낮은 `publishGeneration`은 더 높은 current generation을 전환하지 못함
- build 중 새 mutation·시간 경계는 후속 generation
- code/content deploy가 같은 release 스크립트 사용

## 결과

### 장점

- 관리 콘텐츠가 HTML과 sitemap에 포함
- 운영자는 저장만으로 반영 요청
- 공개 사이트 런타임 독립
- 모든 배포를 검증 가능

### 비용

- 저장과 공개 반영 사이 지연
- outbox와 publisher 운영
- future boundary scheduling과 두 revision 운영
- 연속 저장 시 build 자원 사용
- 운영자는 관리 API 저장 성공과 공개 성공을 구분해야 함

## 거부한 대안

### 브라우저 runtime fetch

즉시 반영은 쉽지만 공개 사이트를 backend에 결합하고 핵심 콘텐츠가 초기 HTML에 없게 된다.

### 정기 cron build만

구현은 단순하지만 공지와 긴급 변경의 반영 시점을 예측하기 어렵다.

### 개발자 수동 build

운영자 자율 관리 목표에 맞지 않는다.

## 재검토 조건

- build 시간이 운영자 요구를 충족하지 못함
- 콘텐츠 변경 빈도가 급증
- 실시간성이 제품 핵심이 됨
- 콘텐츠 preview/approval workflow가 필요
