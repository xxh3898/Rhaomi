# Production runtime source·license notice

이 문서는 D-IMP-1 production image의 tracked 공급망 notice다. exact machine inventory는 `production-image-components.json`, 각 build의 실제 transitive inventory는 generated CycloneDX SBOM을 authority로 사용한다.

| component | exact identity | license | source·배포 의무 상태 |
|---|---|---|---|
| libheif | `v1.23.1`, commit `2c4bbb54c2738d4a5efbbe3e5fa1d5d76bb88eb0`, archive SHA-256 `9fdb7410222a9fd12387f4332e3f93cf428c976ac16f1379fcd7f6415ebe03c0` | LGPL-3.0-or-later | source·license·재연결 의무를 release gate에서 보존한다. source `COPYING`을 image evidence에 포함한다. |
| libde265 | Alpine `1.0.16-r0`, upstream `v1.0.16` | LGPL-3.0-or-later | Alpine package source와 license, 재연결 의무를 release gate에서 보존한다. |
| NightMonkeys `imageio-heif` | `1.1.0`, commit `7c81dee4e2a077ad97f3835ff4d7cca4ac3f28da` | MIT | license notice를 보존한다. |
| Eclipse Temurin JRE | `25.0.4_7-alpine-3.23` exact image digest | GPL-2.0-with-classpath-exception | base image source·notice를 release inventory에 보존한다. |
| Node.js | `24.20.0-alpine3.23` exact image digest | MIT | Node와 transitive runtime notice를 generated SBOM에서 보존한다. |

x265와 HEIC encoder를 제거해도 libheif·libde265 등 남은 component의 license 의무가 사라지지 않는다. 이 구현은 source·license evidence를 생성하지만 GHCR publish와 production 배포 의무 이행을 완료한 것으로 표시하지 않는다. 실제 배포 전 D-IMP-3 release evidence에서 exact image SBOM, source offer·notice와 재연결 가능성의 충족 상태를 다시 확인한다.
