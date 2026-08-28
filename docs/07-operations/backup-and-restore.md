---
title: "백업·복구"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "저장소·보존 정책 변경 시"
---

# 백업·복구

## 보호 대상

### 필수

- PostgreSQL logical dump
- Directus uploads 원본
- Directus schema snapshot
- Directus Flow·policy 재현 정보
- Nginx·Compose·scripts가 있는 Git commit
- 운영 환경변수의 별도 안전한 복구 사본
- 도메인·DNS·인증서 운영 정보

### 재생성 가능

- 공개 정적 파생 이미지
- Next `out/`
- node_modules
- build cache

## 3-2-1 목표

- 3개 이상의 사본
- 2종류 이상의 저장 매체
- 1개는 Mac mini와 분리된 위치

정확한 offsite 목적지는 [미확정 항목](../01-product/open-items.md)에 남아 있다.

## 권장 주기

| 대상 | 주기 | 최소 보존 |
|---|---|---|
| PostgreSQL dump | 매일 | 일간 7, 주간 4, 월간 6 |
| uploads 증분/동기화 | 매일 | 동일 |
| schema snapshot | 스키마 변경 PR | Git 이력 |
| 운영 config | 변경 시 | Git + 비밀 백업 |
| 수동 출시 전 백업 | major upgrade/migration 전 | 검증 완료까지 |

소규모 데이터 기준의 초기 정책이며 실제 용량과 백업 매체에 맞춰 조정한다.

## 백업 일관성

- DB dump 시각과 uploads snapshot 시각을 같은 manifest에 기록한다.
- 파일 업로드 중 백업이 수행될 수 있음을 고려한다.
- 중요 migration 전에는 콘텐츠 변경을 잠시 멈추고 일관된 백업을 만든다.
- checksum과 파일 개수를 기록한다.
- 백업 성공 로그만 믿지 않고 산출물 존재와 크기를 확인한다.

## 복구 순서

1. 장애 범위 확인
2. 쓰기 중지 또는 Directus maintenance
3. 복구 대상 시점 선택
4. 새 임시 PostgreSQL에 dump 복원
5. schema와 row count 검증
6. uploads 복원
7. Directus를 임시 DB에 연결
8. 핵심 컬렉션과 파일 접근 확인
9. 정적 빌드 수행
10. 공개 스모크
11. 운영 전환
12. 사건 기록

## 복구 테스트

최소 분기 1회:

- 임시 DB로 실제 restore
- `shop_settings`, `gallery_items`, `notices` 조회
- 대표 이미지 접근
- 정적 사이트 build
- 복구 시간과 문제 기록
- 운영 DB를 덮어쓰지 않음

## 삭제 사고

- 운영자 삭제는 archive이므로 먼저 status 복구
- hard delete면 Directus revisions 가능 여부를 확인
- 원본 파일 삭제까지 발생했으면 uploads backup 복구
- DB와 파일 참조 시점을 일치
- 복구 후 정적 재배포

## RPO/RTO

초기 목표:

- RPO: 최대 24시간
- RTO: 당일 수동 복구 가능한 수준

사업 영향이 커지면 실제 복구 훈련 결과를 근거로 강화한다.
