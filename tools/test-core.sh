#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
dg_test_dir=$(mktemp -d)
trap 'rm -rf "$dg_test_dir"' EXIT
mapfile -t dg_sources < <(find core/src/main/java -name '*.java' -print)
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -d "$dg_test_dir" "${dg_sources[@]}" core/src/test/java/io/github/goraidebjyoti/dgchat/core/CoreChecks.java
java -ea -cp "$dg_test_dir" io.github.goraidebjyoti.dgchat.core.CoreChecks
