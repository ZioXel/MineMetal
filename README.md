# MineMetal

A Fabric mod that runs **Minecraft Java 26.3 on Apple Metal**: a native Metal backend for Minecraft's renderer,
instead of Apple's deprecated OpenGL layer.

On an M2 MacBook Air it renders **120–200+ fps where OpenGL manages 50–60**, and shader packs run through the
companion fork [MineMetal-Iris](https://github.com/ZioXel/MineMetal-Iris), including shadows, compute shaders and
Complementary's colored lighting, which Apple's OpenGL driver cannot do at all.

> ⚠️ Experimental, personal project. Apple Silicon + macOS 14 or newer only. Expect rough edges.

## What's in it

- **Metal backend:** Minecraft's own rendering layer (renderpearl) implemented on Metal through a small Objective-C
  library called from Java (FFM). Shaders are translated GLSL → SPIR-V → Metal Shading Language at load time.
- **Frame pacing:** VSync off renders without limit and shows the newest frame on every display refresh (no tearing,
  no blocking).
- **Sodium:** works, using Sodium's multidraw terrain path.
- **GL emulation for Iris:** enough of OpenGL 4.3 is emulated on Metal for Iris to run shader packs: textures
  (1D/2D/3D), framebuffers, GLSL programs with loose uniforms, per-buffer blending, **compute shaders, image
  load/store (including atomics) and storage buffers**. Iris' world and shadow shaders draw inside Minecraft's Metal
  render passes.
- **Profiler:** `-Dminemetal.gpuProfile=true` logs GPU and CPU time per frame phase (shadow, world, composite, …).

## Status

| | |
|---|---|
| Vanilla on Metal | ✅ works, Metal validation clean |
| Sodium | ✅ |
| Shader packs (Iris fork): post-processing | ✅ |
| Shader packs: world (terrain, entities, particles, sky, hand) | ✅ |
| Shader packs: shadows | ✅ |
| Compute shaders / image load-store / SSBOs | ✅ |
| Fullscreen performance with heavy packs | 🚧 being optimised |

### Shader packs tested (M2 MacBook Air, 1708×960 window)

| Pack | Result |
|---|---|
| BSL 10.1.8 | ✅ with shadows, ~50–60 fps |
| Complementary Reimagined r5.9.3 | ✅ ~45–60 fps |
| Complementary Unbound r5.9.3 | ✅ up to the top profile, incl. colored lighting (voxel light propagation) |
| MakeUp Ultra Fast 9.5g | ✅ |
| Sildur's Vibrant 2.02 | ✅ |

Heavy packs are GPU-bound at full Retina resolution (BSL ~25 fps fullscreen); that is the next thing being worked on.

## Build & run

Needs a JDK 25 (`brew install openjdk@25`) and Xcode command line tools.

```sh
./mm.sh build        # builds the mod and the native Metal library
./mm.sh run          # runs Minecraft from the dev environment
./mm.sh kit          # downloads Sodium, Iris and BSL for testing
./mm.sh iris         # builds MineMetal + ../MineMetal-Iris, runs with shader packs on Metal
```

Useful flags: `-PmetalHud` (Apple's performance HUD), `-PmetalValidation` (Metal API validation). JVM flags go in
`-PmmArgs="..."`:

| Flag | |
|---|---|
| `-Dminemetal.disable=true` | use OpenGL instead |
| `-Dminemetal.gpuProfile=true` | per-phase GPU/CPU frame times in the log |
| `-Dminemetal.glSelfTest=true` | checks the GL emulation (draw + compute/SSBO/image atomics) at startup |
| `-Dminemetal.noCoalesce=true` | one Metal render encoder per pass (debugging) |
| `-Dminemetal.reportMacOS=true` | report `MC_OS_MAC` to shader packs again (see MineMetal-Iris docs) |

## License

CC0-1.0. Minecraft is a trademark of Mojang/Microsoft; this project is not affiliated with them, Apple or the Iris team.
