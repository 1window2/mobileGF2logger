#!/bin/sh
# Builds and runs the zdtun host regression tests with the host C compiler (Linux or macOS).
# Usage: app/src/test/cpp/run-zdtun-host-test.sh   (CC overrides the compiler)
set -eu

here=$(cd "$(dirname "$0")" && pwd)
zdtun="$here/../../main/cpp/third_party/zdtun"
out=$(mktemp "${TMPDIR:-/tmp}/zdtun-host-test.XXXXXX")
trap 'rm -f "$out"' EXIT

${CC:-cc} -std=gnu11 -D_GNU_SOURCE -DNO_DEBUG \
    -Wall -Wextra -Wno-unused-function -Wno-unused-variable -Wno-unused-parameter -Wno-sign-compare \
    -I"$zdtun" -idirafter "$here/host-shim" \
    "$here/zdtun_host_test.c" -o "$out"
"$out"
