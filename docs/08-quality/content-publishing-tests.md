---
title: "콘텐츠 배포 테스트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-30"
review_trigger: "관리 backend·배포 event 변경 시"
---

# 콘텐츠 배포 테스트

## Phase 1C-8f1 producer 완료

- [x] V8 `content_revision_state` 초기 0·singleton·nonnegative constraint와 transactional row allocator
- [x] V8 typed `publishing_outbox` kind/source/revision/boundary constraint와 required index
- [x] 기존 V1→V7 database의 V8 upgrade와 clean V1→V8 migration
- [x] 지원 콘텐츠 성공 mutation당 revision 정확히 1회, 동시 transaction duplicate·lost revision 없음
- [x] rollback·validation·outbox insert failure의 content/revision/event 원자적 rollback
- [x] Breed·Service·Notice·Gallery status matrix, Shop 모든 PUT, Media upload·archive·restore immediate 분류
- [x] Notice create/update의 changed publishedAt·expiresAt scheduled event와 old event 보존
- [x] published Gallery 진입·reschedule의 publishedAt scheduled event와 old event 보존
- [x] Media revision allocation failure의 DB row·temp/final file orphan 부재

아래 항목은 각 줄의 전체 범위를 기준으로 표시한다. producer-only transaction·typed event 항목은 완료했고 실제 공개 결과·consumer/build 범위를 포함한 항목은 미완료다.

## 기본 게시

- [ ] 공개 eligibility에 영향 없는 gallery draft 수정 → `contentRevision`만 증가, publishing event/build 없음, 공개 변화 없음
- [ ] 갤러리 published → 카드·필터·이미지 반영
- [ ] status·publishedAt·expiresAt에 영향 없는 notice draft 수정 → `contentRevision`만 증가, publishing event/build 없음, 공개 변화 없음
- [ ] 공지 published → 홈·상세·sitemap 반영
- [ ] shop 설정 변경 → Hero·JSON-LD 동기화
- [ ] 서비스 가격 변경 → 서비스 섹션 동기화

## 수정

- [ ] 공개 사진 alt 수정
- [ ] 견종 변경 시 기존·신규 필터 정확
- [ ] 공지 제목 수정 시 title 정확
- [ ] 공지 slug는 의도 없이 변경되지 않음
- [ ] 외부 링크 수정
- [ ] 빈 선택 링크 버튼 자동 제거

## 보관

- [ ] gallery archived → 홈에서 제거
- [ ] notice archived → 목록·상세·sitemap 제거
- [ ] breed archived → 새 선택 불가
- [ ] service archived → 새 선택 불가
- [ ] 참조 콘텐츠의 무결성 처리

## 만료

- [ ] 미래 publishedAt 공지 생성 뒤 추가 admin mutation 없이 boundary 이후 due event로 공개
- [ ] 미래 expiresAt 공지는 만료 전 표시
- [ ] expiresAt 도달 뒤 추가 admin mutation 없이 due event로 새 build에서 제외
- [ ] timezone Asia/Seoul 확인
- [ ] pinned라도 만료 시 제외

## 예약 event·stale 안전성

- [x] notice transaction과 changed publishedAt·expiresAt scheduled event가 함께 commit/rollback
- [x] scheduled event에 `availableAt`, source type·ID, current revision과 expected boundary 식별값 기록
- [ ] Gallery publishedAt scheduled event 처리 시 current Gallery row·boundary 재검증
- [ ] eligible event claim·`publishGeneration` 할당·첫 attempt가 atomic하고 claim crash 뒤 같은 generation으로 복구
- [ ] publishedAt·expiresAt 변경 뒤 old event 처리 → current row 재검증, stale 공개 없음
- [ ] future notice의 draft·archived 전환 뒤 old event 처리 → no-op 또는 최신 generation coalesce, 공개 없음
- [ ] publisher가 boundary 동안 down → restart 후 overdue event claim·정확한 snapshot 공개/제거
- [ ] 가까운 여러 publish/expiry boundary → 30초 debounce/coalesce 후 최종 `generatedAt` snapshot 정확
- [ ] 같은 `contentRevision`의 publish boundary와 expiry boundary가 서로 다른 `publishGeneration` 생성
- [ ] 주간 notice expiry audit가 event drift를 탐지하되 correctness trigger를 대체하지 않음

## 이미지

- [ ] JPEG
- [ ] PNG
- [ ] WebP
- [ ] 실제 iPhone HEIC
- [ ] portrait orientation
- [ ] landscape
- [ ] 큰 원본
- [ ] 손상 파일
- [ ] 잘못된 MIME
- [ ] GPS EXIF 제거
- [ ] responsive variants
- [ ] 원본 URL 비노출

## 실패 안전성

- [x] 콘텐츠 변경과 publishing outbox가 같은 PostgreSQL transaction에서 commit/rollback
- [ ] snapshot/release manifest의 `contentRevision`·`publishGeneration`·`generatedAt` 일치
- [ ] 낮거나 같은 `publishGeneration`의 old build가 newer current를 덮지 못함
- [ ] Spring Boot/build API 중단 → build 실패, current 유지
- [ ] PostgreSQL 중단 → build 실패, current 유지
- [ ] invalid content → build 실패, current 유지
- [ ] image decoder 실패 → build 실패, current 유지
- [ ] disk full simulation → current 유지
- [ ] build service credential 오류·public `/api/build/**` → 요청 거부
- [ ] build API create/update/delete/share 모두 거부
- [ ] 첫 변경 뒤 30초 debounce와 global filesystem lock
- [ ] concurrent triggers → 가장 높은 accepted `publishGeneration`으로 coalesce하고 직렬 실행
- [ ] 동일 `publishGeneration` transient failure → 1분·5분·15분 최대 3회
- [ ] validation/data failure → 무한 retry 없이 실패 상태
- [ ] 자동 attempt retry는 같은 generation, 승인된 manual rebuild/retry는 새 generation
- [ ] build 중 새 변경·due boundary → 최신 generation 우선 후속 build
- [ ] publisher public network·Docker socket 없음

## 롤백

- [ ] previous release 존재
- [ ] symlink 전환
- [ ] 공개 스모크
- [ ] publisher가 문제 revision을 무한 재배포하지 않게 조치
