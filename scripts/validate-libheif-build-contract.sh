#!/bin/sh

set -eu

source_root=${1:-}
build_root=${2:-}
install_root=${3:-}
evidence_file=${4:-}

if [ ! -f "$source_root/CMakeLists.txt" ] ||
  [ ! -f "$build_root/CMakeCache.txt" ] ||
  [ ! -d "$install_root" ] ||
  [ -z "$evidence_file" ]; then
  echo "libheif build contract 입력이 올바르지 않습니다." >&2
  exit 1
fi

expected_plugins=$(printf '%s\n' \
  AOM_DECODER \
  AOM_ENCODER \
  DAV1D \
  FFMPEG_DECODER \
  JPEG_DECODER \
  JPEG_ENCODER \
  KVAZAAR \
  LIBDE265 \
  OPENJPH_ENCODER \
  OpenH264_DECODER \
  OpenJPEG_DECODER \
  OpenJPEG_ENCODER \
  RAV1E \
  SvtEnc \
  UVG266 \
  VVDEC \
  VVENC \
  X264 \
  X265 | LC_ALL=C sort)
actual_plugins=$(
  sed -n 's/^[[:space:]]*plugin_option(\([^[:space:])]*\).*/\1/p' \
    "$source_root/CMakeLists.txt" | LC_ALL=C sort
)

if [ "$actual_plugins" != "$expected_plugins" ]; then
  echo "libheif codec plugin option inventory가 승인된 allowlist와 다릅니다." >&2
  printf 'expected:\n%s\nactual:\n%s\n' "$expected_plugins" "$actual_plugins" >&2
  exit 1
fi

expected_direct_options=$(printf '%s\n' \
  BUILD_DEVELOPMENT_TOOLS \
  BUILD_DOCUMENTATION \
  BUILD_FRAMEWORK \
  BUILD_SHARED_LIBS \
  BUILD_TESTING \
  ENABLE_COVERAGE \
  ENABLE_MULTITHREADING_SUPPORT \
  ENABLE_PARALLEL_TILE_DECODING \
  ENABLE_PLUGIN_LOADING \
  WITH_EXAMPLES \
  WITH_EXAMPLE_HEIF_THUMB \
  WITH_EXAMPLE_HEIF_VIEW \
  WITH_FUZZERS \
  WITH_GDK_PIXBUF \
  WITH_HEADER_COMPRESSION \
  WITH_LIBSHARPYUV \
  WITH_LIBSHARPYUV_INTERNAL \
  WITH_REDUCED_VISIBILITY \
  WITH_UNCOMPRESSED_CODEC \
  WITH_WEBCODECS | LC_ALL=C sort)
actual_direct_options=$(
  sed -n 's/^[[:space:]]*option(\([^[:space:])]*\).*/\1/p' \
    "$source_root/CMakeLists.txt" |
    grep -v '\${' |
    LC_ALL=C sort
)

if [ "$actual_direct_options" != "$expected_direct_options" ]; then
  echo "libheif root option inventory가 승인된 allowlist와 다릅니다." >&2
  printf 'expected:\n%s\nactual:\n%s\n' \
    "$expected_direct_options" "$actual_direct_options" >&2
  exit 1
fi

assert_cache() {
  option_name=$1
  expected_value=$2
  if ! grep -Eq "^${option_name}:(BOOL|UNINITIALIZED)=${expected_value}$" \
    "$build_root/CMakeCache.txt"; then
    echo "CMake cache 계약 불일치: ${option_name}=${expected_value}" >&2
    exit 1
  fi
}

assert_cache WITH_LIBDE265 ON
assert_cache WITH_LIBDE265_PLUGIN OFF

for codec in \
  X265 KVAZAAR UVG266 VVDEC VVENC X264 OpenH264_DECODER DAV1D \
  AOM_DECODER AOM_ENCODER SvtEnc RAV1E JPEG_DECODER JPEG_ENCODER \
  OpenJPEG_ENCODER OpenJPEG_DECODER FFMPEG_DECODER OPENJPH_ENCODER; do
  assert_cache "WITH_${codec}" OFF
  assert_cache "WITH_${codec}_PLUGIN" OFF
done

# v1.23.1에서는 주석 처리된 upstream 후보도 명시적인 OFF 입력으로 고정한다.
for dormant in OpenH264_ENCODER OPENJPH_DECODER; do
  assert_cache "WITH_${dormant}" OFF
  assert_cache "WITH_${dormant}_PLUGIN" OFF
done

for disabled in \
  ENABLE_PLUGIN_LOADING WITH_UNCOMPRESSED_CODEC WITH_WEBCODECS \
  WITH_LIBSHARPYUV WITH_LIBSHARPYUV_INTERNAL WITH_EXAMPLES \
  WITH_EXAMPLE_HEIF_THUMB WITH_EXAMPLE_HEIF_VIEW WITH_GDK_PIXBUF \
  BUILD_DEVELOPMENT_TOOLS WITH_HEADER_COMPRESSION BUILD_DOCUMENTATION \
  BUILD_TESTING ENABLE_COVERAGE WITH_FUZZERS; do
  assert_cache "$disabled" OFF
done

for enabled in \
  BUILD_SHARED_LIBS WITH_REDUCED_VISIBILITY ENABLE_MULTITHREADING_SUPPORT \
  ENABLE_PARALLEL_TILE_DECODING; do
  assert_cache "$enabled" ON
done

libheif_path=$(find "$install_root" -type f -name 'libheif.so.1.*' -print | head -n 1)
if [ -z "$libheif_path" ]; then
  echo "설치된 libheif ABI library를 찾을 수 없습니다." >&2
  exit 1
fi

linkage=$(ldd "$libheif_path")
printf '%s\n' "$linkage" | grep -q 'libde265\.so\.0'
if printf '%s\n' "$linkage" | grep -Eiq \
  'libx265|libx264|libaom|libdav1d|libkvazaar|libvvdec|libvvenc|libjxl|libjpeg|libavcodec'; then
  echo "승인되지 않은 native codec linkage가 감지됐습니다." >&2
  printf '%s\n' "$linkage" >&2
  exit 1
fi

plugin_count=$(find "$install_root" -type f \
  \( -name '*plugin*.so*' -o -path '*/lib/libheif/*.so*' \) -print | wc -l | tr -d ' ')
if [ "$plugin_count" -ne 0 ]; then
  echo "libheif plugin file이 감지됐습니다." >&2
  exit 1
fi

mkdir -p "$(dirname "$evidence_file")"
{
  printf 'contract=libheif-decoder-only-v1\n'
  printf 'libheifTag=v1.23.1\n'
  printf 'libheifCommit=2c4bbb54c2738d4a5efbbe3e5fa1d5d76bb88eb0\n'
  printf 'libheifArchiveSha256=9fdb7410222a9fd12387f4332e3f93cf428c976ac16f1379fcd7f6415ebe03c0\n'
  printf 'libde265Version=1.0.16-r0\n'
  printf 'enabledCodec=LIBDE265\n'
  printf 'pluginFiles=0\n'
  printf 'optionInventory=verified\n'
  printf 'nativeLinkage=libde265-only\n'
} >"$evidence_file"
