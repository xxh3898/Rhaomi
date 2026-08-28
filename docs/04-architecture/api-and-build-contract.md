---
title: "API·빌드 계약"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "관리 API·build 입력 변경 시"
---

# API·빌드 계약

## 공개 사이트 runtime 계약

공개 고객 브라우저는 Spring Boot API나 PostgreSQL을 호출하지 않는다. 공개 release는 Static Export 결과만 제공한다.

## 현재 관리자 인증 API

| method | path | anonymous | CSRF | 응답 |
|---|---|---:|---:|---|
| `GET` | `/api/admin/auth/csrf` | 허용 | N/A | header name, parameter name, token |
| `POST` | `/api/admin/auth/login` | 허용 | 필수 | id, email, role |
| `GET` | `/api/admin/auth/me` | 거부 | N/A | id, email, role |
| `POST` | `/api/admin/auth/logout` | 거부 | 필수 | `204 No Content` |

- 인증은 server session에 저장한다.
- login 실패는 잘못된 password, 없는 email과 inactive account를 같은 401 계약으로 처리한다.
- 인증 service 또는 repository 장애는 내부 원인을 노출하지 않는 503 `AUTH_SERVICE_UNAVAILABLE`로 처리한다.
- login password는 UTF-8 최대 72 byte이며 초과 입력은 credential 비교 전에 400 `INVALID_REQUEST`로 거부한다.
- request/response와 인증 완료 principal·저장된 `SecurityContext`에 `password_hash`를 포함하지 않는다.
- `/api/admin/**`는 위 anonymous 예외 외 인증이 기본이다.
- 아직 설계하지 않은 `/api/**`는 deny한다.
- 세 anonymous endpoint 외 non-API path와 미허용 Actuator path를 포함한 모든 request는 deny한다.

## build API — planned

후속 Issue에서 관리자 session과 분리된 namespace·credential을 설계한다.

- 예: `/api/build/**`
- API-only service credential
- published 콘텐츠와 연결된 공개용 file metadata만 read
- create/update/delete/share 금지
- 관리자 cookie/session 재사용 금지
- credential은 build container에만 주입하고 `NEXT_PUBLIC_*` 금지

현재 repository에는 build API나 build credential이 없다.

## 조회 범위 — planned

```text
shop_settings
services
breeds
gallery_items
notices
public media metadata
```

조회와 transformer는 모두 다음을 검증한다.

- `status = published`
- `notices.expires_at IS NULL OR expires_at > build_time`
- 관계 대상도 published
- 파일이 라오미펫 공개 콘텐츠에 연결됐고 공개 파생 대상임
- 정렬은 도메인 데이터 모델 기준

## content snapshot — planned

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-08-29T00:00:00Z",
  "sourceRevision": {
    "shopUpdatedAt": "...",
    "maxContentUpdatedAt": "..."
  },
  "shop": {},
  "services": [],
  "breeds": [],
  "galleryItems": [],
  "notices": []
}
```

- 모든 조회가 성공한 뒤 임시 file과 atomic rename으로 기록한다.
- 일부 collection만 과거 data로 fallback하지 않는다.
- raw persistence/API response를 component에 직접 전달하지 않는다.
- runtime schema와 published/relation/file 조건을 transformer에서 다시 검증한다.

## media 변환 — planned

```text
backend-owned original
→ authenticated build-time download
→ MIME/signature/pixel 검증
→ metadata 제거와 최적화
→ content hash
→ /generated/media/<item-id>-<hash>-<width>.<format>
```

원본 id·storage path·내부 URL을 공개 HTML에 남기지 않는다.

## build 실패 조건 — planned

- build API 연결·인증 실패
- singleton 없음
- 필수 field 누락
- duplicate slug
- draft/archived/만료 콘텐츠 포함
- 관계 대상 없음 또는 비공개
- file scope·다운로드·decode 실패
- 지원하지 않는 image 형식
- 잘못된 외부 URL
- snapshot schema mismatch
- HTML sanitize 실패
- sitemap duplicate canonical

## 호환성

계약이 바뀌면 API DTO, 도메인 데이터 모델, Flyway migration, transformer/test, 운영 data migration과 release evidence를 함께 갱신한다.
