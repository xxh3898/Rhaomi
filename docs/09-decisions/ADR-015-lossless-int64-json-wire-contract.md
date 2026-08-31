---
title: "ADR-015: 손실 없는 int64 JSON wire 계약"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-31"
review_trigger: "revision·generation domain 또는 build/generated artifact schema 변경 시"
---

# ADR-015: 손실 없는 int64 JSON wire 계약

- 결정일: 2026-08-31
- 상태: Accepted
- 관련 결정: [ADR-011](ADR-011-transactional-outbox-static-publisher.md)

## 맥락

PostgreSQL의 `content_revision`·`publish_generation`은 `BIGINT`이고 Java publication domain은 양의 값을 `long`으로 처리한다. 반면 ECMAScript JSON `number`는 `Number.MAX_SAFE_INTEGER`를 초과하는 정수를 정확히 보존할 수 없다. 기존 Build Snapshot V1의 numeric field는 `9007199254740993`부터 Build API의 유효한 응답과 Node parser·transformer의 acceptance set을 다르게 만들고, generation equality·stale ordering·생성 산출물에 precision loss를 일으킬 수 있었다.

현재 production release consumer와 persisted V1 public artifact는 없으므로 compatibility reader나 dual writer 없이 wire와 생성 산출물을 V2로 전환한다.

## 결정

### database와 Java domain

- PostgreSQL `BIGINT` column·constraint와 Java internal `long` domain을 유지한다.
- `contentRevision`은 `0..9223372036854775807`, `publishGeneration`은 `1..9223372036854775807`이다.
- endpoint query `publishGeneration=<positive-long decimal>`과 Spring request binding·service method의 `long` 계약을 유지한다.
- 이번 결정으로 Flyway migration, column/type/constraint 변경 또는 production data migration을 만들지 않는다.

### Build Snapshot V2 wire

HTTP DTO boundary의 `contentRevision`·`publishGeneration`은 `Long.toString(...)`으로 만든 canonical decimal `String`이다.

```json
{
  "schemaVersion": 2,
  "contentRevision": "9007199254740993",
  "publishGeneration": "9007199254740993"
}
```

- `contentRevision`: `0` 또는 `[1-9][0-9]*`, 값 범위 `0..9223372036854775807`
- `publishGeneration`: `[1-9][0-9]*`, 값 범위 `1..9223372036854775807`
- 빈 문자열, leading zero, sign, 공백, 소수·지수 표현, 상한 초과와 JSON `number`를 거부한다.
- V1 numeric wire shape는 폐기하며 V2 parser는 V1이나 numeric field를 수용하지 않는다.

### Node와 생성 산출물

- `BuildSnapshotV2`는 두 값을 canonical decimal `string`으로 유지한다.
- `BigInt`는 범위 검증, 요청 generation equality와 stale-generation ordering에만 사용한다.
- revision/generation을 `Number`, unary `+`, `parseInt` 등으로 변환하지 않는다.
- `GeneratedContentV2`, `PublicMediaManifestV2`, `PublicationStagingResult`와 machine JSON CLI도 같은 문자열을 byte-for-byte 보존한다.
- `content.json`과 `media-manifest.json`은 모두 `schemaVersion: 2`와 string revision/generation을 기록한다.
- media `byteSize`, width·height·profile width처럼 별도로 범위가 제한된 숫자는 기존 JSON number 계약을 유지한다.

### publication state 불변식

Build API와 transformer staging 성공만으로는 public publication `SUCCESS` 또는 `NO_PUBLIC_CHANGE`가 아니다. actual `PublicationBuildExecutor`는 V2 string을 그대로 보존한 Next Static Export, final-tree·private manifest 검증, `BigInt` stale guard, immutable install, `current/previous` switch와 post-switch serving smoke까지 성공한 경우에만 `SUCCESS`를 반환한다. 같거나 낮은 current generation으로 switch가 필요 없을 때만 `NO_PUBLIC_CHANGE`다. production image·secret·path provisioning은 이 local/CI executor foundation과 별도 gate다.

## 검토한 대안

### JavaScript safe integer로 domain 축소

PostgreSQL·Java의 현재 단조 증가 domain과 이미 승인된 positive-long query 계약을 축소하고 DB validation·migration 판단까지 요구하므로 거부한다.

### alternate binary int64 protocol

precision은 해결하지만 현재 JSON Build API·정적 산출물과 별도의 protocol, tooling, operational dependency를 추가한다. canonical decimal string이면 기존 JSON 경계에서 손실 없이 충분히 검증할 수 있어 거부한다.

## 결과

### 장점

- PostgreSQL/Java의 전체 non-negative·positive `long` domain을 JSON과 Node에서 정확히 보존한다.
- generation equality와 향후 stale ordering이 floating-point precision에 의존하지 않는다.
- HTTP, transformer, staging CLI와 generated artifact가 같은 representation을 사용한다.

### 비용·위험

- V1 wire/generated artifact와 호환되지 않는 schema change다.
- consumer는 숫자 연산 전에 명시적으로 `BigInt` validation을 수행해야 한다.
- schemaVersion과 field type을 동시에 검증하지 않는 consumer는 fail-open할 수 있으므로 exact V2 parser를 필수로 한다.

## 검증 계약

- 실제 PostgreSQL 18.6·Flyway V1~V9에서 `9007199254740993`과 `9223372036854775807` active generation의 Build API HTTP 200 문자열 보존
- raw HTTP → Node parser → transformer staging → `content.json`·`media-manifest.json`·CLI의 exact 문자열 보존
- private release manifest와 Java executor machine result의 exact 문자열 보존, `BigInt` equal/lower stale switch 거부와 `Long.MAX_VALUE` current 전환
- `contentRevision=0`, safe integer 경계와 최대 `long` 성공
- zero generation, malformed/leading-zero/overflow string과 JSON numeric field 거부

## 재검토 조건

- revision/generation domain이 signed 64-bit 범위를 벗어남
- JSON 대신 protobuf 등 별도 lossless wire protocol을 채택함
- production consumer migration 또는 backward-compatible artifact reader가 필요해짐
