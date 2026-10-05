#!/usr/bin/env bash
# MineMetal dev helper.  Usage:  ./mm.sh build   |   ./mm.sh run
# Output is also written to .reference/<cmd>.log so Claude can read it directly.
set -o pipefail
cd "$(dirname "$0")"
mkdir -p .reference
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
cmd="${1:-build}"
case "$cmd" in
  build)
    ./gradlew --no-configuration-cache copyReferenceLibs build "${@:2}" 2>&1 | tee .reference/build.log ;;
  run)
    mkdir -p .reference/logs
    stamp=$(date +%Y%m%d-%H%M%S)
    ./gradlew --no-configuration-cache runClient "${@:2}" 2>&1 | tee .reference/run.log ".reference/logs/run-$stamp.log" ;;
  kit)
    ./gradlew --no-configuration-cache fetchShaderTestKit 2>&1 | tee .reference/kit.log ;;
  *) echo "usage: ./mm.sh build|run|kit"; exit 2 ;;
esac
echo "== exit code: $? (log: .reference/$cmd.log)"
