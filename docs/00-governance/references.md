---
title: "공식 참고자료"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-29"
review_trigger: "기술·정책 문서 변경 또는 업그레이드 시"
---

# 공식 참고자료

접근일: 2026-08-29

## Next.js

- Static Exports  
  https://nextjs.org/docs/app/guides/static-exports
- `generateStaticParams`  
  https://nextjs.org/docs/app/api-reference/functions/generate-static-params
- Metadata and Open Graph images  
  https://nextjs.org/docs/app/getting-started/metadata-and-og-images
- `robots.txt` metadata file  
  https://nextjs.org/docs/app/api-reference/file-conventions/metadata/robots
- `sitemap.xml` metadata file  
  https://nextjs.org/docs/app/api-reference/file-conventions/metadata/sitemap

## Spring Boot·Security·Gradle·PostgreSQL

- Spring Boot project와 stable version
  https://spring.io/projects/spring-boot/
- Spring Boot reference
  https://docs.spring.io/spring-boot/
- Spring Boot system requirements
  https://docs.spring.io/spring-boot/system-requirements.html
- Spring Security CSRF
  https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
- Spring Security session management
  https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html
- Gradle releases
  https://gradle.org/releases/
- Gradle Java compatibility
  https://docs.gradle.org/current/userguide/compatibility.html
- PostgreSQL documentation
  https://www.postgresql.org/docs/
- PostgreSQL 18 `pg_dump`
  https://www.postgresql.org/docs/18/app-pgdump.html

## Production ingress·release

- Cloudflare Tunnel
  https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/
- Cloudflare Tunnel firewall model
  https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/tunnel-with-firewall/
- GitHub Actions deployments and environments
  https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments
- GitHub deployment environment 관리
  https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
- Tailscale SSH
  https://tailscale.com/kb/1193/tailscale-ssh

## macOS·Docker storage

- Apple Platform Security — signed system volume
  https://support.apple.com/guide/security/signed-system-volume-security-secd698747c9/web
- Apple Platform Security — APFS system/Data volume 역할
  https://support.apple.com/guide/security/role-of-apple-file-system-seca6147599e/web
- Docker Desktop settings — Mac file sharing 기본 경로
  https://docs.docker.com/desktop/settings-and-maintenance/settings/#file-sharing
- Docker volumes와 lifecycle
  https://docs.docker.com/engine/storage/volumes/
- `docker compose down`과 `--volumes`
  https://docs.docker.com/reference/cli/docker/compose/down/

## Backup·restore

- restic backup
  https://restic.readthedocs.io/en/stable/040_backup.html
- restic repository copy·check
  https://restic.readthedocs.io/en/stable/045_working_with_repos.html
- restic restore
  https://restic.readthedocs.io/en/stable/050_restore.html
- restic retention·forget·prune
  https://restic.readthedocs.io/en/stable/060_forget.html
- restic password 자동화 주의사항
  https://restic.readthedocs.io/en/stable/faq.html#how-can-i-specify-encryption-passwords-automatically

## HEIC production runtime

- libheif source와 build options
  https://github.com/strukturag/libheif
- libheif `v1.23.1` release
  https://github.com/strukturag/libheif/releases/tag/v1.23.1
- libheif `v1.23.1` CMake options
  https://github.com/strukturag/libheif/blob/v1.23.1/CMakeLists.txt
- libheif license
  https://github.com/strukturag/libheif/blob/v1.23.1/COPYING
- libde265 source와 license
  https://github.com/strukturag/libde265

## Directus — superseded 결정 조사 기록

아래 링크는 ADR-002·005와 ADR-009의 전환 근거를 보존하기 위한 역사적 참고자료다. 현재 runtime dependency가 아니다.

- Overview and Data Studio  
  https://directus.com/docs/getting-started/overview
- Self-hosted project and Docker  
  https://directus.com/docs/getting-started/create-a-project
- Access control  
  https://directus.com/docs/guides/auth/access-control
- Security best practices  
  https://directus.com/docs/guides/security/best-practices
- Flows  
  https://directus.com/docs/guides/automate/flows
- Triggering static site builds  
  https://directus.com/docs/tutorials/workflows/trigger-netlify-site-builds-with-directus-automate
- Accessing files  
  https://directus.com/docs/guides/files/access
- Image transformations  
  https://directus.com/docs/guides/files/transform
- Schema promotion  
  https://directus.com/docs/tutorials/migration/promoting-changes-between-environments-in-directus
- Licensing overview  
  https://directus.com/docs/licensing/overview

## Google Search

- JavaScript SEO basics  
  https://developers.google.com/search/docs/crawling-indexing/javascript/javascript-seo-basics
- LocalBusiness structured data  
  https://developers.google.com/search/docs/appearance/structured-data/local-business
- Sitemap overview  
  https://developers.google.com/search/docs/crawling-indexing/sitemaps/overview
- Core Web Vitals  
  https://developers.google.com/search/docs/appearance/core-web-vitals
- Page experience  
  https://developers.google.com/search/docs/appearance/page-experience

## 네이버 서치어드바이저

- 검색엔진 최적화 기본  
  https://searchadvisor.naver.com/guide/seo-basic-intro
- 웹사이트 생성 시 권장사항  
  https://searchadvisor.naver.com/guide/seo-basic-create
- robots.txt 설정  
  https://searchadvisor.naver.com/guide/seo-basic-robots

## 접근성

- WCAG 2.2 Quick Reference  
  https://www.w3.org/WAI/WCAG22/quickref/
- WAI Easy Checks  
  https://www.w3.org/WAI/test-evaluate/preliminary/

## 사용 원칙

- 공식 문서는 구현 시점에 다시 확인한다.
- 버전별 동작 차이가 있으면 현재 프로젝트의 잠금 버전을 기준으로 별도 ADR을 작성한다.
- 외부 서비스의 정책, 라이선스, 검색 노출은 코드만으로 보장하지 않는다.
