# 합성 HEIC/HEIF test fixture

이 directory의 fixture는 실제 인물·고객·매장 사진을 사용하지 않고
`scripts/generate-synthetic-media-fixtures.mjs`로 생성한다.

- raster: 64×48 기하 도형 PNG
- metadata: 합성 orientation, EXIF/GPS, XMP/device marker
- color profile: `saucecontrol/Compact-ICC-Profiles`의 `DisplayP3-v4.icc`
  - source commit: `bdd84663061bc4ae95ca70decff54f581e27f702`
  - SHA-256: `cb51de38e482ee974c0c76b9689e16aad04bad16e226fed2f30c842d15ff3a3d`
  - license: CC0-1.0
- encoder: `libheif-tools=1.23.0-r0`의 `heif-enc`
- multi-image: 서로 다른 두 합성 raster를 top-level image 두 개로 encoding
- sequence brand: multi-image fixture의 HEIF `ftyp` major brand를 sequence용 `msf1`로
  바꿔 container 단계의 fail-closed 판정을 검증

재생성 예시:

```bash
docker run --rm \
  -v "$PWD:/workspace" \
  -w /workspace \
  node:24.20.0-alpine3.23@sha256:0388af2af070cd4736a1567cfed02469ba117848845b4165d87a333edb53d2ca \
  sh -c 'apk add --no-cache libheif-tools=1.23.0-r0 && node scripts/generate-synthetic-media-fixtures.mjs'
```

fixture에는 개인식별정보가 없으며, HEIC 정규화 후 방향·sRGB·metadata 제거와
multi-image/sequence 거부를 검증하는 용도로만 사용한다.
