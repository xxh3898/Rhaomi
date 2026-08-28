---
title: "API·빌드 계약"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "CMS 필드 또는 빌드 입력 변경 시"
---

# API·빌드 계약

## 공개 사이트 런타임 계약

공개 브라우저는 Directus API를 호출하지 않는다. Directus API는 빌더와 관리자만 사용한다.

## 빌더 계정

- API-only 사용자
- App access 없음
- static token
- 공개 콘텐츠와 관련 파일에 대한 read-only 정책
- create/update/delete/share 금지
- 시스템 컬렉션 최소 read
- 토큰은 빌드 컨테이너에만 주입
- `NEXT_PUBLIC_*` 금지

## 조회 범위

빌더가 읽는 사용자 컬렉션:

```text
shop_settings
services
breeds
gallery_items
notices
```

파일 메타데이터:

```text
directus_files
```

조회 조건:

- `status = published`
- `notices.expires_at IS NULL OR expires_at > build_time`
- 관계 대상도 published
- 정렬은 데이터 모델 문서 기준

## 콘텐츠 스냅샷

개념적 형식:

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-08-28T12:00:00Z",
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

## 스냅샷 규칙

- 모든 조회가 성공한 뒤 한 파일로 기록한다.
- 임시 파일에 쓴 뒤 rename한다.
- 일부 컬렉션만 과거 데이터로 fallback하지 않는다.
- `generatedAt`은 빌드 시각이며 게시일을 대체하지 않는다.
- raw Directus 응답을 컴포넌트에 직접 전달하지 않고 내부 domain type으로 변환한다.
- 빌드 시 입력값을 runtime schema로 검증한다.

## URL 변환

Directus 파일 ID와 내부 URL을 공개 HTML에 직접 남기지 않는다.

```text
Directus file
→ build-time download
→ content hash
→ /generated/media/<item-id>-<hash>-<width>.<format>
```

## 실패 조건

아래 중 하나면 빌드를 실패시킨다.

- CMS 연결 실패
- 인증 실패
- singleton 없음
- 필수 필드 누락
- duplicate slug
- 관계 대상 없음
- 공개 이미지 다운로드·디코딩 실패
- 지원하지 않는 이미지 형식
- 잘못된 외부 URL
- snapshot schema mismatch
- HTML sanitize 실패
- sitemap에 중복 canonical 발생

## 호환성

스냅샷 구조가 바뀌면:

1. `schemaVersion` 증가 여부 검토
2. CMS 데이터 모델 문서 수정
3. 변환기와 테스트 수정
4. 기존 운영 데이터 migration 계획
5. release evidence 기록
