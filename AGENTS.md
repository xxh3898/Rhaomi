---
title: "Codex 및 AI 에이전트 작업 지침"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "기술·브랜치·운영 방식 변경 시"
---

# AGENTS.md

이 저장소에서 작업하는 모든 AI 에이전트와 자동화 도구는 아래 기준을 따른다.

## 1. 언어와 산출물

- Issue, PR, 코드 주석, 문서, 커밋 본문은 한국어로 작성한다.
- 파일명과 코드 식별자는 일반적인 영문 개발 관례를 사용한다.
- 추정값을 확정값처럼 기록하지 않는다.
- 미확정 정보는 `TBD`, `확인 필요`, `출시 차단` 중 하나로 명시한다.

## 2. 아키텍처 불변 조건

- 공개 프론트엔드는 Next.js App Router + TypeScript다.
- `next.config`에 `output: 'export'`를 사용한다.
- SSR, Server Actions, Next.js API Routes, 런타임 동적 렌더링을 추가하지 않는다.
- 브라우저에서 PostgreSQL로 직접 연결하지 않는다.
- 공개 사이트는 런타임에 Spring Boot API를 호출하지 않는다.
- 콘텐츠 데이터와 이미지는 후속 build API에서 빌드 시점에 동기화해 정적 산출물에 포함한다.
- 예약·결제·회원가입·문의 폼을 구현하지 않는다.
- 관리 backend는 Java 21 + Spring Boot + PostgreSQL이며 DB schema source of truth는 Flyway다.
- 관리자 인증은 Spring Security 서버 세션과 CSRF 보호를 사용하고 JWT를 기본안으로 추가하지 않는다.
- 관리자 UI는 후속 Issue에서 same-origin `/admin`으로 구현한다.
- 이미지 원본 storage는 후속 Issue에서 backend 소유로 설계하고, 공개 사이트에는 메타데이터를 제거한 최적화 파생본만 배포한다.

위 조건을 바꿔야 한다면 구현 전에 ADR을 먼저 추가하거나 기존 ADR을 개정한다.

## 3. 보안 기준

- 비밀값, 토큰, 비밀번호, 실사용 이메일을 저장소에 커밋하지 않는다.
- `.env.example`에는 키 이름과 설명만 둔다.
- 미설계 `/api/**` endpoint는 기본 거부하고 login/csrf/최소 health만 anonymous로 허용한다.
- 사이트 빌더용 namespace와 credential은 관리자 session과 분리하고 읽기 전용 최소 권한을 사용한다.
- 관리자 session cookie는 HttpOnly·SameSite를 명시하고 production에서 Secure를 강제한다.
- state-changing 관리 API에서 CSRF 보호를 비활성화하지 않는다.
- 운영자에게 영구 삭제 권한을 기본 제공하지 않는다. 화면상 삭제는 `archived` 전환으로 처리한다.
- 관리자 계정은 2단계 인증을 배포 게이트로 둔다.

## 4. 변경 동기화

다음 변경은 반드시 관련 문서를 함께 수정한다.

| 코드 변경 | 같이 수정할 문서 |
|---|---|
| 페이지 또는 섹션 추가 | 정보 구조, 기능 요구사항, 수용 기준 |
| PostgreSQL table·field 또는 API 변경 | 도메인 데이터 모델, 접근제어, Flyway migration, API 계약 |
| 배포 방식 변경 | 배포, 롤백, ADR |
| SEO 메타데이터·URL 변경 | SEO 전략, 사이트맵, 검색엔진 체크리스트 |
| 이미지 처리 변경 | 이미지 파이프라인, 성능 예산, 개인정보 |
| 관리자 권한 변경 | 접근제어, 위협 모델 |
| 외부 링크 변경 | 기준 매장정보, 콘텐츠 인벤토리 |

## 5. 구현 품질

- 모바일 320px부터 레이아웃을 검증한다.
- 키보드만으로 모든 공개 기능을 사용할 수 있어야 한다.
- `prefers-reduced-motion`에서 비필수 애니메이션을 제거한다.
- 필터, 모달, 아코디언에는 명시적인 접근성 상태와 레이블을 제공한다.
- 이미지에는 실제 내용과 일치하는 대체 텍스트를 제공한다.
- Lighthouse 점수만으로 품질을 판정하지 않고 실제 기기와 키보드 테스트를 포함한다.
- 출력 디렉터리와 링크·메타데이터·구조화 데이터를 배포 전에 자동 검증한다.

## 6. Git 작업 규칙

- 기능 단위 Issue 하나에 구현 PR 하나를 기본으로 한다.
- 기본 통합 브랜치는 `dev`, 운영 브랜치는 `main`을 권장한다.
- feature PR은 `dev`를 대상으로 한다.
- 검증된 릴리스 묶음만 `dev`에서 `main`으로 승격한다.
- 강제 푸시, 운영 배포, 데이터 마이그레이션, 영구 삭제는 명시적인 승인 없이 수행하지 않는다.
- PR에는 변경 이유, 계약 변화, 테스트 증거, 롤백 방법을 기록한다.

## 7. 완료 보고 형식

```text
결과: READY | HOLD | DECISION_REQUIRED

변경:
- ...

검증:
- ...

문서 동기화:
- ...

남은 위험:
- ...

수행하지 않은 작업:
- merge / deploy / production migration 등
```
