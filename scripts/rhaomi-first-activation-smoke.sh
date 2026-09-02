#!/bin/sh

set -eu

[ "$#" -eq 2 ] || {
  printf '%s\n' FIRST_ACTIVATION_SMOKE_INPUT_INVALID >&2
  exit 64
}

base_url=$1
marker=$2
case "$base_url" in
  http://first-activation-static:8080) ;;
  *)
    printf '%s\n' FIRST_ACTIVATION_SMOKE_INPUT_INVALID >&2
    exit 64
    ;;
esac
printf '%s' "$marker" | grep -Eq '^first-activation-[0-9a-f]{40}$' || {
  printf '%s\n' FIRST_ACTIVATION_SMOKE_INPUT_INVALID >&2
  exit 64
}

index=$(wget -qO- "$base_url/") || {
  printf '%s\n' FIRST_ACTIVATION_STATIC_UNAVAILABLE >&2
  exit 1
}
printf '%s' "$index" | grep -Fq "$marker" || {
  printf '%s\n' FIRST_ACTIVATION_STATIC_INVALID >&2
  exit 1
}

health=$(wget -qO- http://backend:8080/actuator/health) || {
  printf '%s\n' FIRST_ACTIVATION_API_UNAVAILABLE >&2
  exit 1
}
printf '%s' "$health" | grep -Fq '"status":"UP"' || {
  printf '%s\n' FIRST_ACTIVATION_API_INVALID >&2
  exit 1
}

media_path=$(printf '%s' "$index" |
  grep -Eo '/generated/media/[A-Za-z0-9._/-]+' | sed -n '1p')
case "$media_path" in
  /generated/media/*) ;;
  *)
    printf '%s\n' FIRST_ACTIVATION_MEDIA_INVALID >&2
    exit 1
    ;;
esac
wget -qO- "$base_url$media_path" >/dev/null || {
  printf '%s\n' FIRST_ACTIVATION_MEDIA_INVALID >&2
  exit 1
}
test -f "/srv/rhaomi/public/current$media_path"

test -L /srv/rhaomi/public/current
test -f /srv/rhaomi/public/current/index.html
grep -Fq "$marker" /srv/rhaomi/public/current/index.html

printf '%s\n' '{"contract":"rhaomi-first-activation-smoke-v1","status":"success"}'
