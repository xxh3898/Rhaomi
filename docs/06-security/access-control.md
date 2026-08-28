---
title: "접근제어"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-28"
review_trigger: "역할·권한 변경 시"
---

# 접근제어

Directus 11+ 계열의 역할·정책 모델을 기준으로 하되, 실제 잠금 버전의 공식 문서를 확인한다.

## 역할

### System Administrator

대상: 조치호

- Directus Admin access
- 데이터 모델
- Policies/Roles
- Flows
- Settings
- 사용자 관리
- 영구 삭제
- 스키마 migration
- license 설정

일상 콘텐츠 편집에는 사용하지 않는다.

### Content Owner

대상: 은총쌤

- Data Studio App access
- `shop_settings` read/update
- `services` create/read/update
- `breeds` create/read/update
- `gallery_items` create/read/update
- `notices` create/read/update
- 허용 폴더의 파일 create/read
- system collection, flow, role, user, extension, license 접근 금지
- 영구 delete 금지
- share 금지

### Site Builder

대상: API-only service account

- App access 없음
- static token
- 공개 빌드에 필요한 collection read
- 관련 file read
- create/update/delete/share 금지
- published 필터 또는 빌드 변환기에서 엄격한 필터
- 토큰 주기적 rotation

### Public

- 사용자 collection 권한 없음
- file read 없음
- 공개 사이트가 Directus를 호출하지 않으므로 불필요

## 필드 제한

운영자가 수정하면 안 되는 예:

- system id
- audit timestamps
- schema version
- 내부 배포 상태
- 권한/role
- file storage path
- license
- build token

## 검증

- 기본 생성 상태 `draft`
- publish 필수값 validation
- URL allowlist
- status presets
- 운영자에게 임의 HTML·스크립트 필드 노출 금지
- system collection read도 최소화

## 2FA

- System Administrator 필수
- Content Owner 필수
- 복구코드는 계정과 다른 안전한 장소에 보관
- 운영자 휴대전화 변경 시 복구 절차 사전 확인

## 계정 수명주기

- 공유 계정 금지
- 퇴사·운영자 변경 시 즉시 비활성화
- 비밀번호 재사용 금지
- 토큰은 사람 계정 대신 API-only 계정에 발급
- 장기간 미사용 계정 검토
- 보안 사고 의심 시 세션·토큰 폐기
