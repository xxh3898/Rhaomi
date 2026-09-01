#!/bin/sh

set -eu
umask 077

media_root=/var/lib/rhaomi/media-permissions

permission_fail() {
  printf '%s\n' BACKUP_MEDIA_PERMISSION_INVALID >&2
  exit 1
}

canonical_id() {
  case "$1" in
    "" | *[!0-9]* | 0[0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}

assert_regular_tree() {
  [ -d "$media_root" ] && [ ! -L "$media_root" ] || permission_fail
  [ -z "$(find "$media_root" -xdev ! -type d ! -type f -print -quit)" ] ||
    permission_fail
}

apply_tree_state() {
  owner_id=$1
  group_id=$2
  directory_mode=$3
  file_mode=$4
  find "$media_root" -xdev -type d -exec chown "$owner_id:$group_id" {} + ||
    permission_fail
  find "$media_root" -xdev -type f -exec chown "$owner_id:$group_id" {} + ||
    permission_fail
  find "$media_root" -xdev -type d -exec chmod "$directory_mode" {} + ||
    permission_fail
  find "$media_root" -xdev -type f -exec chmod "$file_mode" {} + ||
    permission_fail
}

assert_entry_state() {
  entry_type=$1
  expected_owner=$2
  expected_group=$3
  expected_mode=$4
  find "$media_root" -xdev -type "$entry_type" -exec sh -eu -c '
    expected_owner=$1
    expected_group=$2
    expected_mode=$3
    shift 3
    for candidate do
      [ "$(stat -c %u "$candidate")" = "$expected_owner" ] || exit 1
      [ "$(stat -c %g "$candidate")" = "$expected_group" ] || exit 1
      [ "$(stat -c %a "$candidate")" = "$expected_mode" ] || exit 1
    done
  ' sh "$expected_owner" "$expected_group" "$expected_mode" {} + || permission_fail
}

assert_tree_state() {
  expected_owner=$1
  expected_group=$2
  expected_directory_mode=$3
  expected_file_mode=$4
  assert_regular_tree
  assert_entry_state d "$expected_owner" "$expected_group" "$expected_directory_mode"
  assert_entry_state f "$expected_owner" "$expected_group" "$expected_file_mode"
}

[ "$#" -eq 3 ] || permission_fail
[ "$(id -u)" = 0 ] || permission_fail
action=$1
host_uid=$2
host_gid=$3
canonical_id "$host_uid" || permission_fail
canonical_id "$host_gid" || permission_fail
assert_regular_tree

case "$action" in
  runtime)
    apply_tree_state 0 "$host_gid" 0750 0640
    assert_tree_state 0 "$host_gid" 750 640
    ;;
  capture)
    apply_tree_state "$host_uid" "$host_gid" 0700 0600
    assert_tree_state "$host_uid" "$host_gid" 700 600
    ;;
  assert-runtime)
    assert_tree_state 0 "$host_gid" 750 640
    ;;
  assert-capture)
    assert_tree_state "$host_uid" "$host_gid" 700 600
    ;;
  *) permission_fail ;;
esac
