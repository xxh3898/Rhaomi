---
title: "출시 체크리스트"
status: "approved"
owner: "조치호"
reviewers: "은총쌤"
last_updated: "2026-08-29"
review_trigger: "출시 기준 변경 시"
---

# 출시 체크리스트

## 제품·콘텐츠

- [ ] 최종 Hero 문구 승인
- [ ] 실제 Hero 이미지
- [ ] 실제 OG 이미지
- [ ] 은총쌤 소개 승인
- [ ] 초기 갤러리
- [ ] 서비스와 가격 문구
- [ ] 예약 전 안내 정책
- [ ] 주소·전화·영업시간·휴무·주차
- [ ] 외부 링크
- [ ] 빈 채널 버튼 미노출
- [ ] 사진 사용 권한

## 관리 backend

- [ ] Java image·Spring Boot·Gradle Wrapper 잠금 버전
- [ ] PostgreSQL 연결
- [ ] Flyway migration과 JPA schema validation
- [ ] server session·CSRF·fixation 방어
- [ ] 관리자 API field allowlist와 anonymous deny
- [ ] build API credential·read-only policy
- [ ] 관리자 2FA
- [ ] 상태 validation
- [ ] archive 운영
- [ ] domain event/outbox와 deploy hook
- [ ] 실제 iPhone CRUD

## 공개 사이트

- [ ] static export
- [ ] 공개 고객 site의 backend runtime 요청 없음
- [ ] 모바일 반응형
- [ ] 데스크톱
- [ ] 견종 필터
- [ ] lightbox
- [ ] 서비스
- [ ] 공지 상세
- [ ] 404
- [ ] sticky CTA
- [ ] 전화 링크
- [ ] 지도 링크

## 접근성

- [ ] keyboard
- [ ] focus visible
- [ ] dialog focus
- [ ] headings
- [ ] alt
- [ ] contrast
- [ ] 320px reflow
- [ ] 200% text
- [ ] reduced motion
- [ ] VoiceOver 표본

## SEO

- [ ] title/description
- [ ] canonical
- [ ] OG
- [ ] LocalBusiness JSON-LD
- [ ] robots
- [ ] sitemap
- [ ] 실제 404
- [ ] NAP
- [ ] Google Search Console
- [ ] 네이버 서치어드바이저

## 성능

- [ ] 이미지 파생본
- [ ] EXIF 제거
- [ ] HEIC 검증
- [ ] asset budgets
- [ ] Lighthouse
- [ ] 저속 모바일 표본
- [ ] layout shift
- [ ] third-party SDK 없음

## 보안·운영

- [ ] HTTPS
- [ ] same-origin `/admin`, `/api` route
- [ ] DB 외부 비공개
- [ ] deploy hook 내부·인증
- [ ] secrets scan
- [ ] production session `Secure`, TLS와 관리자 2FA 확인
- [ ] DB backup
- [ ] 원본 image storage backup
- [ ] offsite backup
- [ ] restore test
- [ ] rollback
- [ ] monitoring
- [ ] disk alert
- [ ] certificate alert

## 승인

- [ ] 은총쌤 콘텐츠 승인
- [ ] 조치호 기술 승인
- [ ] release evidence
- [ ] 남은 위험 명시
- [ ] production deploy 승인
