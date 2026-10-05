#!/usr/bin/env bash
# MineMetal dev helper.  Usage:  ./mm.sh build | run | iris | kit
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
  iris)
    # Full shader-pack-on-Metal cycle: build MineMetal, build + install the MineMetal-Iris fork, run with -PirisMetal.
    # Also undoes Minecraft's "forced OpenGL after a crash" setting so the run really uses Metal.
    ./gradlew --no-configuration-cache copyReferenceLibs build 2>&1 | tee .reference/build.log || { echo "MineMetal BUILD FAILED"; exit 1; }
    (cd ../MineMetal-Iris && bash ./metal.sh build) || { echo "Iris fork BUILD FAILED (log: .reference/iris-build.log)"; exit 1; }
    [ -f run/options.txt ] && sed -i '' 's/^preferredGraphicsBackend:"opengl"/preferredGraphicsBackend:"default"/' run/options.txt
    mkdir -p .reference/logs
    stamp=$(date +%Y%m%d-%H%M%S)
    ./gradlew --no-configuration-cache runClient -PirisMetal "${@:2}" 2>&1 | tee .reference/run.log ".reference/logs/run-$stamp.log" ;;
  kit)
    ./gradlew --no-configuration-cache fetchShaderTestKit 2>&1 | tee .reference/kit.log ;;
  *) echo "usage: ./mm.sh build|run|iris|kit"; exit 2 ;;
esac
echo "== exit code: $? (log: .reference/$cmd.log)"
