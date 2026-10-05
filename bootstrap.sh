#!/usr/bin/env bash
# MineMetal bootstrap — run once from Terminal:  bash ~/Documents/Projects/MineMetal/bootstrap.sh
# Checks the toolchain, builds the mod, decompiles Minecraft (Loom genSources) and copies the
# Blaze3D rendering sources + core shaders into .reference/ so they can be studied.
set -uo pipefail
cd "$(dirname "$0")"
mkdir -p .reference
exec > >(tee .reference/bootstrap.log) 2>&1
export PATH="$HOME/.homebrew/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"

echo "== system";  sw_vers; uname -m; sysctl -n machdep.cpu.brand_string
echo "== xcode";   xcode-select -p || echo "MISSING: run  xcode-select --install"
xcrun --find clang; xcrun --sdk macosx --show-sdk-version; xcrun --find metal 2>&1 | head -1
echo "== tools";   for t in brew git cmake glslangValidator spirv-cross; do printf '%s: ' $t; command -v $t || echo none; done

echo "== java"
JH=$(/usr/libexec/java_home -v 25 2>/dev/null || true)
if [ -z "$JH" ] && command -v brew >/dev/null; then
  for f in openjdk@25 openjdk; do
    p="$(brew --prefix $f 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
    if [ -x "$p/bin/java" ] && "$p/bin/java" -version 2>&1 | grep -q 'version "25'; then JH=$p; break; fi
  done
fi
if [ -z "$JH" ]; then
  echo "MISSING: JDK 25.  Install:  brew install openjdk@25   (or Temurin 25 from adoptium.net), then re-run."
  /usr/libexec/java_home -V 2>&1; exit 1
fi
export JAVA_HOME="$JH"; "$JAVA_HOME/bin/java" -version
echo "JAVA_HOME=$JAVA_HOME" > .reference/java_home

echo "== gradle build + genSources (first run downloads Minecraft, takes a few minutes)"
./gradlew build || { echo "BUILD FAILED"; exit 1; }
./gradlew --no-configuration-cache genSources || { echo "GENSOURCES FAILED"; exit 1; }

echo "== extract reference sources"
SRCJAR=""; BINJAR=""
while IFS= read -r j; do
  if unzip -Z1 "$j" 2>/dev/null | grep '^com/mojang/blaze3d/.*\.java$' >/dev/null; then SRCJAR="$j"; fi
  if unzip -Z1 "$j" 2>/dev/null | grep '^assets/minecraft/shaders/core/' >/dev/null; then BINJAR="$j"; fi
done < <(find .gradle "$HOME/.gradle/caches/fabric-loom" -name '*.jar' -path '*minecraft*' 2>/dev/null)
echo "sources jar: $SRCJAR"; echo "client jar:  $BINJAR"
rm -rf .reference/src .reference/shaders
mkdir -p .reference/src .reference/shaders
[ -n "$SRCJAR" ] && { unzip -Z1 "$SRCJAR" > .reference/all-sources.txt
  unzip -qo "$SRCJAR" 'com/mojang/blaze3d/*' 'net/minecraft/client/renderer/*' 'net/minecraft/client/Minecraft.java' 'net/minecraft/client/Options.java' 'net/minecraft/client/main/*' -d .reference/src; }
[ -n "$BINJAR" ] && unzip -qo "$BINJAR" 'assets/minecraft/shaders/*' -d .reference/shaders
find .reference/src -name '*.java' | wc -l
echo "== DONE — tell Claude the bootstrap finished."
