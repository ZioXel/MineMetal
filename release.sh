#!/usr/bin/env bash
# Builds release jars of MineMetal and MineMetal-Iris and publishes them as GitHub releases (needs the gh CLI, logged in).
#   ./release.sh            -> builds into dist/ only
#   ./release.sh publish    -> builds and creates the GitHub releases (tags from the mod versions)
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
MM_VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)
mkdir -p dist && rm -f dist/*.jar

echo "== MineMetal $MM_VERSION"
./gradlew --no-configuration-cache clean build
cp "build/libs/minemetal-$MM_VERSION.jar" dist/

echo "== MineMetal-Iris"
(cd ../MineMetal-Iris && ./gradlew :fabric:clean :fabric:build -x test -Pbuild.release)
IRIS_JAR=$( (ls ../MineMetal-Iris/fabric/build/libs/iris-fabric-*.jar ../MineMetal-Iris/build/libs/iris-fabric-*.jar 2>/dev/null || true) | grep -v -- -sources | grep -v -- -snapshot | head -1 || true)
[ -n "$IRIS_JAR" ] || { echo "Iris release jar not found"; exit 1; }
cp "$IRIS_JAR" dist/
IRIS_VERSION=$(basename "$IRIS_JAR" .jar | sed 's/^iris-fabric-//')
ls -l dist/

[ "${1:-}" = "publish" ] || { echo "Built. Run './release.sh publish' to create the GitHub releases."; exit 0; }

REQS="Requirements: Apple Silicon Mac, macOS 14+, Minecraft 26.3, Fabric Loader 0.19.5+, Sodium 0.9.x."
if gh release view "v$MM_VERSION" --repo ZioXel/MineMetal >/dev/null 2>&1; then echo "MineMetal v$MM_VERSION already released, skipping"; else
gh release create "v$MM_VERSION" "dist/minemetal-$MM_VERSION.jar" --repo ZioXel/MineMetal --prerelease \
  --title "MineMetal $MM_VERSION (experimental)" \
  --notes "Native Apple Metal renderer for Minecraft Java 26.3.

$REQS

Install: put the jar into your .minecraft/mods folder (Fabric profile). For shader packs also install Iris (MineMetal) from https://github.com/ZioXel/MineMetal-Iris/releases instead of the official Iris.
Video Settings -> Graphics API shows \"Default (Metal)\" while MineMetal runs; pick OpenGL or Vulkan there (and restart) to switch back.

New in this version:
- Shader packs: \"Shadows Every 2nd Frame\" option (MineMetal page in Sodium's settings / Video Settings). The shadow map is usually the most expensive part of a pack; it is now reused every other frame, aligned to the current camera. Needs Iris (MineMetal) 1.11.6-minemetal.2.
- Smoother frame pacing with VSync off: frames are spaced evenly when the frame rate is below the refresh rate (ProMotion-friendly, less judder).
- Shader cache: compiled shader-pack shaders are kept in .minecraft/minemetal-cache, so the second launch has far fewer stutters.
- Upscaling (since 0.1.2): MetalFX Spatial (recommended), AMD FSR 1, MetalFX Temporal (experimental), Render Scale 50-100 %.
Example (M2 Pro, fullscreen 3600x2260): Complementary Reimagined (Medium) with MetalFX Spatial at 50 % runs at about 80 fps.
If the game does not start, remove the jar."
fi
if gh release view "v$IRIS_VERSION" --repo ZioXel/MineMetal-Iris >/dev/null 2>&1; then echo "Iris v$IRIS_VERSION already released, skipping"; else
(cd ../MineMetal-Iris && gh release create "v$IRIS_VERSION" "../MineMetal/dist/$(basename "$IRIS_JAR")" --repo ZioXel/MineMetal-Iris \
  --target metal --prerelease --title "Iris (MineMetal) $IRIS_VERSION (experimental)" \
  --notes "Iris fork that runs shader packs on Apple Metal together with MineMetal (https://github.com/ZioXel/MineMetal/releases). On OpenGL it behaves like official Iris.

New in minemetal.2: support for MineMetal's \"Shadows Every 2nd Frame\" option (requires MineMetal 0.1.3+).

$REQS

Install: remove the official Iris jar from your mods folder and put this one in, together with MineMetal and Sodium.
Licensed LGPL-3.0 like upstream Iris; source on the metal branch.")
fi
echo "== published"
