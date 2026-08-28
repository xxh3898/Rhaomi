---
title: "초기 문서 패키지 매니페스트"
status: "approved"
owner: "조치호"
reviewers: "조치호"
last_updated: "2026-08-28"
review_trigger: "패키지 재생성 시"
---

# 패키지 매니페스트

## 적용 범위

이 문서는 2026-08-28에 생성한 **초기 기준 문서 ZIP**의 파일 트리와 SHA-256 snapshot이다. Issue #1 이후 추가되는 source, runtime 설정, workflow와 구현 PR에서 동기화한 문서의 현재 checksum을 나타내지 않는다.

아래 값은 최초 수신 패키지의 provenance 확인을 위해 보존하며 일반 구현 변경마다 재계산하지 않는다. 새 문서 패키지를 다시 배포하는 경우에만 전체 package를 재검증하고 갱신한다.

- 생성일: 2026-08-28
- 대상 저장소: `xxh3898/Rhaomi`
- 매니페스트 제외 파일 수: 78
- 상대 Markdown 링크 검사: PASS
- GitHub Issue YAML 파싱: PASS

## 파일 트리

```text
.
├── .github/
│   ├── ISSUE_TEMPLATE/
│   │   ├── 01-feature.yml
│   │   ├── 02-bug.yml
│   │   ├── 03-documentation.yml
│   │   ├── 04-operations.yml
│   │   └── config.yml
│   ├── CODEOWNERS
│   └── pull_request_template.md
├── docs/
│   ├── 00-governance/
│   │   ├── branch-and-release-policy.md
│   │   ├── decision-log.md
│   │   ├── definition-of-done.md
│   │   ├── document-conventions.md
│   │   ├── glossary.md
│   │   └── references.md
│   ├── 01-product/
│   │   ├── functional-requirements.md
│   │   ├── non-functional-requirements.md
│   │   ├── open-items.md
│   │   ├── product-brief.md
│   │   ├── roadmap.md
│   │   ├── scope.md
│   │   ├── success-metrics.md
│   │   └── user-journeys.md
│   ├── 02-content/
│   │   ├── admin-content-rules.md
│   │   ├── content-ownership.md
│   │   ├── copy-deck.md
│   │   ├── information-architecture.md
│   │   ├── media-guidelines.md
│   │   └── shop-data-baseline.md
│   ├── 03-design/
│   │   ├── accessibility.md
│   │   ├── design-direction.md
│   │   ├── interactions.md
│   │   └── responsive-layout.md
│   ├── 04-architecture/
│   │   ├── api-and-build-contract.md
│   │   ├── cms-data-model.md
│   │   ├── container-architecture.md
│   │   ├── environment-and-configuration.md
│   │   ├── frontend-architecture.md
│   │   ├── image-pipeline.md
│   │   ├── repository-structure.md
│   │   ├── static-publishing-pipeline.md
│   │   └── system-context.md
│   ├── 05-seo/
│   │   ├── metadata-and-structured-data.md
│   │   ├── search-engine-registration.md
│   │   ├── seo-release-checklist.md
│   │   └── seo-strategy.md
│   ├── 06-security/
│   │   ├── access-control.md
│   │   ├── dependency-and-license-policy.md
│   │   ├── privacy-and-legal.md
│   │   ├── secrets-and-data-protection.md
│   │   └── threat-model.md
│   ├── 07-operations/
│   │   ├── backup-and-restore.md
│   │   ├── content-operations-runbook.md
│   │   ├── deployment.md
│   │   ├── maintenance.md
│   │   ├── monitoring-and-incident-response.md
│   │   └── rollback.md
│   ├── 08-quality/
│   │   ├── acceptance-criteria.md
│   │   ├── browser-device-matrix.md
│   │   ├── content-publishing-tests.md
│   │   ├── performance-budget.md
│   │   ├── release-checklist.md
│   │   └── test-strategy.md
│   ├── 09-decisions/
│   │   ├── ADR-001-nextjs-static-export.md
│   │   ├── ADR-002-directus-postgresql.md
│   │   ├── ADR-003-static-publish-on-content-change.md
│   │   ├── ADR-004-static-media-copy.md
│   │   ├── ADR-005-directus-admin-first.md
│   │   ├── ADR-006-soft-delete.md
│   │   ├── ADR-007-external-contact-only.md
│   │   └── ADR-008-runtime-independent-public-site.md
│   ├── 10-templates/
│   │   ├── adr-template.md
│   │   ├── content-change-template.md
│   │   ├── feature-spec-template.md
│   │   ├── incident-template.md
│   │   ├── release-evidence-template.md
│   │   └── test-evidence-template.md
│   └── README.md
├── AGENTS.md
└── README.md
```

## SHA-256

| 파일 | SHA-256 |
|---|---|
| `.github/CODEOWNERS` | `9a4ef561acd25993db0015eef8cf304be239dec5c6b94cf580d7a546db3c06ea` |
| `.github/ISSUE_TEMPLATE/01-feature.yml` | `ddbc596328f8855624e904a8b928543423653570af404ca9f38ad344bd701146` |
| `.github/ISSUE_TEMPLATE/02-bug.yml` | `d186ec6ab9c2f6b14503fc815c00cfb94b7c9a6014088a6972c3b3ebe975355a` |
| `.github/ISSUE_TEMPLATE/03-documentation.yml` | `a62d1a6336e0deb7ff6363f0f95af07ed1a12caa6347888ceca94559d4f3e478` |
| `.github/ISSUE_TEMPLATE/04-operations.yml` | `df8fd95361886610c0e7eb7fc85b82f4730e2d7417036c4ab354e01c37d03472` |
| `.github/ISSUE_TEMPLATE/config.yml` | `8b09e7df09fc7534371988062769ee427af2954a02c04c763e8a8005be0f2be4` |
| `.github/pull_request_template.md` | `4ae9694f3bb83812cb4139bde5a7a680986133227db28b9a8bc29f931ac888aa` |
| `AGENTS.md` | `36dc91a7b63d7450186f00eeadc91418152fd6fc212885a03ef6087d55beb5d6` |
| `README.md` | `e2dc92cc0c8d0bfb665eeed7ac71ab7f467c6a33dabd8a76d8585a27655682fb` |
| `docs/00-governance/branch-and-release-policy.md` | `e1616ac1abaf3022658163c022f201cd4928b29c5f8630b3cfb044b03dc812cf` |
| `docs/00-governance/decision-log.md` | `8f6db2b2bfd91ec4736fbb17a8ad0668cd36670cfa951e69b92c3521a413137c` |
| `docs/00-governance/definition-of-done.md` | `6b207ad4e15cd16bed25ddbd30d1ec5f14930eb57b2d8999914b36574395f8a2` |
| `docs/00-governance/document-conventions.md` | `d2960671a1e879a93e0e7df917dd254247fa83169d4260eaf79580facbe026d6` |
| `docs/00-governance/glossary.md` | `dfabb6ab71f89d2f1e2f1b32d60fe6e8acf75b35569aa061ef4d641cdb4250cc` |
| `docs/00-governance/references.md` | `ea8456a3ee51700cae9dee8b60ceb74efd8f95af5106c2275af91354cd3c9a5f` |
| `docs/01-product/functional-requirements.md` | `4570f8a77c2d9719b88d3702b84a8f029a1c5d4d5708858b39aab6967cb4488e` |
| `docs/01-product/non-functional-requirements.md` | `fa6dd926cdb6d3e8fe5a60c335b3cd5884ffa225532bcdf8a66b366e23f31069` |
| `docs/01-product/open-items.md` | `d13983a21932f5b978de9772aecad554f772d49ce565ff6576ee35e7d89a898b` |
| `docs/01-product/product-brief.md` | `f7234b7371f43f435bcde169e35c1a7ec4d8cee1f67551af95e08f4125796405` |
| `docs/01-product/roadmap.md` | `e4f4c7e184996f0c986653acfb8c23518701c8493b2825679c8b64c939afa738` |
| `docs/01-product/scope.md` | `8701aeb418648fcbc74ea65ff20567c38f91bfc89f78fd5280c3365709d768d4` |
| `docs/01-product/success-metrics.md` | `a27f459df5f96e6312dfe30473528069744c7ffd1d68ed5e30b5c1d6e36a5dff` |
| `docs/01-product/user-journeys.md` | `93cdaab859b7252cef73b3284a1ed7c44d505e3a04cff51ef52430bf8d8b3a73` |
| `docs/02-content/admin-content-rules.md` | `554127c7cda0a9385424b278798655609de2a53be7df8eeca61d98800d10bf45` |
| `docs/02-content/content-ownership.md` | `685c07f810835a4a89829d877f01f38cf02d5445478f58ddc37b204fe7e5b30c` |
| `docs/02-content/copy-deck.md` | `316e82b58faf597b91be60e199e8ec900978a4ce94a35be4ca8ffeb111636204` |
| `docs/02-content/information-architecture.md` | `883848495190213430e8358b7905d11571cabf05c4c62f4997700927c15c8638` |
| `docs/02-content/media-guidelines.md` | `2c347e70f4c3369e1335913eb301ab367d2c38e1c802eb6ef2247605039ac6bd` |
| `docs/02-content/shop-data-baseline.md` | `162bce5d4ab43788674d27f4dc93776b7ee179b8b04989ad9af6d3a19cfeec71` |
| `docs/03-design/accessibility.md` | `40369a5b822385443fe6382d8220c14135645527223e863ddbe4e1044c07d549` |
| `docs/03-design/design-direction.md` | `3e92b872e2fb9b09ac8dca9e048d409850a532a8d0fa9945522d8469c3f75cbb` |
| `docs/03-design/interactions.md` | `4e63b870317211619ac07a474c9259688c8a08faa5b1f1d4f122f29f4295a3f6` |
| `docs/03-design/responsive-layout.md` | `6f6c6b5386e2d71a3d8e24cffb966c322171709bfb6fd7a6fa35862cd0b8858d` |
| `docs/04-architecture/api-and-build-contract.md` | `453fd3399abe6f6b478206690d0b357fd87c933ec786c18723296de20c2a86ba` |
| `docs/04-architecture/cms-data-model.md` | `5966edb0dd0f80b6e2a09de344abd7121fb4f7ebac9cc16860aa4574ce062e30` |
| `docs/04-architecture/container-architecture.md` | `8b22b4003f90888e558fa4522e62f3d0fe782a20cf95197a5d9566083e42ab00` |
| `docs/04-architecture/environment-and-configuration.md` | `5f374f0228980b83b8394c81062f1c4eb19629b81e98e01447394cec441c316d` |
| `docs/04-architecture/frontend-architecture.md` | `b1e11e0e18fbc6ae6f1aaeec21d1dbfdb704bf502aa13273f5c04d9615b3c63b` |
| `docs/04-architecture/image-pipeline.md` | `6dd96d07301bb431e8639ed1f3a24d0e5a1e3a5364721d7200210c35047bd987` |
| `docs/04-architecture/repository-structure.md` | `71feef71d0aa57b06294bbcee4d643ca63716c7b0cd8c3ba637c4a5be18819d4` |
| `docs/04-architecture/static-publishing-pipeline.md` | `f2ee0a9d59c38291e7769409e8382e5f3069f21b3454ce0db81c9287f6bbcefa` |
| `docs/04-architecture/system-context.md` | `0c3fad2d31584852ec9393c84eda64897ebd26344e42eef298a08536f08fb193` |
| `docs/05-seo/metadata-and-structured-data.md` | `20c5f630e93575992ec3685f7e40bcf6e8e7f016bcaefb73f45dd530c4427baf` |
| `docs/05-seo/search-engine-registration.md` | `354c1d19a0a0de191ccb2c93ddc7856765de865f5f5289db5f238dfed0962e9d` |
| `docs/05-seo/seo-release-checklist.md` | `4f7ed4e7f90b733aca8c585fd489c406234ee432111948f8b8c96595fb4b9ace` |
| `docs/05-seo/seo-strategy.md` | `6ecb0b234ccf27409bc43a8da014fed8ef8acdd512575203105a2c38e514ab1e` |
| `docs/06-security/access-control.md` | `1e58cf890e99799853f0856cac29e55f42577d1bea6e2e942cc1669c5f54fab1` |
| `docs/06-security/dependency-and-license-policy.md` | `7aaecb1ac8d63bcd0e6a24d7e09dfe1a55d1db3bd84e4e2c909714ccb8cd20ad` |
| `docs/06-security/privacy-and-legal.md` | `4efdec1f90cb1a72b5e195a064782cde2ce8e06ae91900b1f85118498689e251` |
| `docs/06-security/secrets-and-data-protection.md` | `fc3a4bd9861ce605f5906d1deaaa1f3c8cf762f971d89a34a3cb28ac6fbe5fd4` |
| `docs/06-security/threat-model.md` | `deb7e577562ee184d3a7ad1516ddb4172e29974712db34865a8bdad60eb0f96c` |
| `docs/07-operations/backup-and-restore.md` | `f806357621d0f2d3420a244d57a87e59305265d1e14f7ee63aa07228f3897a86` |
| `docs/07-operations/content-operations-runbook.md` | `3d2ae7d2567f49448a2c2cd1558cdfcb5e212855a6dd102b7a45965e14321f10` |
| `docs/07-operations/deployment.md` | `2ca0fbf04a5a7af8f02e1ce4ad142d03b499712b0d261ed029ab578ea2980527` |
| `docs/07-operations/maintenance.md` | `3ec328c8bb12cfa4410e58ab56ebd06241061c1c769e883bd72e688b7469ff2a` |
| `docs/07-operations/monitoring-and-incident-response.md` | `ce5a9fd51ece691fcc9dc40aae021489d1c4b2dc1e6f4fad5993455a6d90b0b4` |
| `docs/07-operations/rollback.md` | `0bb84f48f726d8a7c7dc66b713f718a2c5f1b14251637f1f19c3970ee4285823` |
| `docs/08-quality/acceptance-criteria.md` | `95006c2b2e6a625c1559ad793a90b0d5dd0def976226d55ebd20220dcca744e3` |
| `docs/08-quality/browser-device-matrix.md` | `24280acee1047ef2489bbd448b8012320c6d3a8cdb6c259270a83d768ed6fa47` |
| `docs/08-quality/content-publishing-tests.md` | `cfcb64d875ac843776ed67da7f0692875dff6e9c58d62e6e5b003967ecaebe8c` |
| `docs/08-quality/performance-budget.md` | `4cd582667e7dd86854df58976aafda517c6fdb39eef728cb842790d5273dfa4e` |
| `docs/08-quality/release-checklist.md` | `175bd9f6e6e2bb3c267a0265aac2a799d9dd6e99c4edcad1245cd63bdd06fcd4` |
| `docs/08-quality/test-strategy.md` | `d31283a690fe6e0f714eed1854168c6a5b67ec8c03a7d9ce0db93ecb92266dfa` |
| `docs/09-decisions/ADR-001-nextjs-static-export.md` | `c8481afa0cf12596b383e17433536b41501d1401d1675c2883f0dd26a6bda858` |
| `docs/09-decisions/ADR-002-directus-postgresql.md` | `2b199e8d6342f6e51fc16e4320a246eb4ff33a189f854911c6ebf9344e33a477` |
| `docs/09-decisions/ADR-003-static-publish-on-content-change.md` | `4301fafdb65459f95851887b4673377c54feaa1e3b11ff744a4026a9a55c2ad7` |
| `docs/09-decisions/ADR-004-static-media-copy.md` | `1e4a6b622eeb61aa3f23f61fa5cb63235d2e0f0fbd0fc979281a9d8ea8c0db4d` |
| `docs/09-decisions/ADR-005-directus-admin-first.md` | `df1fa468ad0b4f961ae45cb17e1ec3aea5db87456d1bdb9dce6cbab5747ed652` |
| `docs/09-decisions/ADR-006-soft-delete.md` | `576046dda88b7733438b3522e71c0bc1b793c509b027c5aa70b96baa8e688262` |
| `docs/09-decisions/ADR-007-external-contact-only.md` | `19e559a03f5fb6db6cbefa672ebf70c2f2011fd882c612d59e163d33150173c1` |
| `docs/09-decisions/ADR-008-runtime-independent-public-site.md` | `f33860255adfa999e258043629b8d15017da167431219207802778ac95e303f5` |
| `docs/10-templates/adr-template.md` | `942658a088fb39bb6b0d69b72c20907a59bb1a7a4c294ebef292e9968b0ed80d` |
| `docs/10-templates/content-change-template.md` | `6f92b10ce2f12fc9255eaa4bed8892f6f5b1093a4bb8759766c92320596dc408` |
| `docs/10-templates/feature-spec-template.md` | `8c5f1e2b7e43c7f00aec38f9b41dc6d37963d2a645e4df301383cbec71605e5a` |
| `docs/10-templates/incident-template.md` | `3618ce04a6ee117747c13ac011689bc85d4e544646738ef8db6a39207c718f3f` |
| `docs/10-templates/release-evidence-template.md` | `b1129c96aff9eac4cf2ca372f939cc33bfaaf4ca40e867094a4a4d951690d173` |
| `docs/10-templates/test-evidence-template.md` | `a1e72bbe0a39b72c335f150896b72574d5bf18f77f9c9ae3d98b90b9837dc7c8` |
| `docs/README.md` | `d3aa182a52024121f815807b1e6a49f4baff4fb94d70843ca447cc6c38f4236d` |
