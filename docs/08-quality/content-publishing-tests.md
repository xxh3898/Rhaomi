---
title: "콘텐츠 배포 테스트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "관리 backend·배포 event 변경 시"
---

# 콘텐츠 배포 테스트

## 기본 게시

- [ ] 공개 eligibility에 영향 없는 gallery draft 수정 → outbox/build 없음, 공개 변화 없음
- [ ] 갤러리 published → 카드·필터·이미지 반영
- [ ] 공개 eligibility에 영향 없는 notice draft 수정 → outbox/build 없음, 공개 변화 없음
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

- [ ] 미래 expires_at 공지 표시
- [ ] 만료 시각 후 새 build에서 제외
- [ ] timezone Asia/Seoul 확인
- [ ] pinned라도 만료 시 제외

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

- [ ] 콘텐츠 변경과 publishing outbox가 같은 PostgreSQL transaction에서 commit/rollback
- [ ] monotonic revision과 오래된 build switch 거부
- [ ] Spring Boot/build API 중단 → build 실패, current 유지
- [ ] PostgreSQL 중단 → build 실패, current 유지
- [ ] invalid content → build 실패, current 유지
- [ ] image decoder 실패 → build 실패, current 유지
- [ ] disk full simulation → current 유지
- [ ] build service credential 오류·public `/api/build/**` → 요청 거부
- [ ] build API create/update/delete/share 모두 거부
- [ ] 첫 변경 뒤 30초 debounce와 global filesystem lock
- [ ] concurrent revisions → 최신 revision으로 coalesce하고 직렬 실행
- [ ] 동일 revision transient failure → 1분·5분·15분 최대 3회
- [ ] validation/data failure → 무한 retry 없이 실패 상태
- [ ] build 중 새 변경 → 최신 revision 우선 후속 build
- [ ] publisher public network·Docker socket 없음

## 롤백

- [ ] previous release 존재
- [ ] symlink 전환
- [ ] 공개 스모크
- [ ] publisher가 문제 revision을 무한 재배포하지 않게 조치
