#!/usr/bin/env bash
# Stage 0: push every Iris-patched shader through GLSL -> SPIR-V (glslang) -> MSL (SPIRV-Cross) -> Metal compiler.
# Usage: tools/stage0/translate.sh            (reads run/patched_shaders, writes .reference/stage0/)
set -uo pipefail
cd "$(dirname "$0")/../.."
export PATH="/opt/homebrew/bin:$PATH"
IN=run/patched_shaders
OUT=.reference/stage0
rm -rf "$OUT"; mkdir -p "$OUT/spv" "$OUT/msl" "$OUT/err"
[ -d "$IN" ] || { echo "No $IN - run the game with Iris + a shader pack first"; exit 1; }
xcrun clang -fobjc-arc -O1 -framework Metal -framework Foundation -o "$OUT/mslcheck" tools/stage0/mslcheck.m || exit 1

total=0; glsl_ok=0; msl_ok=0
for f in "$IN"/*; do
  name=$(basename "$f")
  case "$name" in
    *.vsh|*.vert) stage=vert ;; *.fsh|*.frag) stage=frag ;; *.gsh|*.geom) stage=geom ;;
    *.csh|*.comp) stage=comp ;; *.tcs) stage=tesc ;; *.tes) stage=tese ;; *) continue ;;
  esac
  total=$((total+1))
  # -R: relaxed Vulkan rules (allows loose 'uniform float x;' like OpenGL), auto-map bindings/locations.
  if glslangValidator -V -R --target-env vulkan1.2 --auto-map-bindings --auto-map-locations -S "$stage" \
       -o "$OUT/spv/$name.spv" "$f" > "$OUT/err/$name.glslang.txt" 2>&1; then
    glsl_ok=$((glsl_ok+1))
    if spirv-cross --msl --msl-version 20400 --msl-texture-buffer-native "$OUT/spv/$name.spv" --output "$OUT/msl/$name.metal" \
         > "$OUT/err/$name.spvc.txt" 2>&1; then
      msl_ok=$((msl_ok+1))
      rm -f "$OUT/err/$name.spvc.txt"
    fi
    rm -f "$OUT/err/$name.glslang.txt"
  fi
done
echo "== Metal compile of generated MSL"
"$OUT/mslcheck" "$OUT"/msl/*.metal > "$OUT/metal-compile.txt" 2>&1
metal_ok=$(grep -c '^OK' "$OUT/metal-compile.txt")
{
  echo "shaders:            $total"
  echo "GLSL -> SPIR-V ok:  $glsl_ok"
  echo "SPIR-V -> MSL ok:   $msl_ok"
  echo "MSL compiles ok:    $metal_ok"
} | tee "$OUT/report.txt"
echo "details: $OUT/err/*.txt and $OUT/metal-compile.txt"
