# MineMetal

A Fabric mod that runs **Minecraft Java 26.3 on Apple Metal**: a native Metal backend for Minecraft's renderer,
instead of Apple's deprecated OpenGL layer.

On an M2 MacBook Air it renders **120–200+ fps where OpenGL manages 50–60**, and shader packs run through the
companion fork [MineMetal-Iris](https://github.com/ZioXel/MineMetal-Iris) (BSL works, shadows coming).

> ⚠️ Experimental, personal project. Apple Silicon + macOS 14 or newer only. Expect rough edges.

## What's in it

- **Metal backend:** Minecraft's own rendering layer (renderpearl) implemented on Metal through a small Objective-C
  library called from Java (FFM). Shaders are translated GLSL → SPIR-V → Metal Shading Language at load time.
- **Frame pacing:** VSync off renders without limit and shows the newest frame on every display refresh (no tearing,
  no blocking).
- **Sodium:** works, using Sodium's multidraw terrain path.
- **GL emulation for Iris:** enough of OpenGL (textures, framebuffers, GLSL programs, loose uniforms, draws) is
  emulated on Metal for Iris to run shader packs. Iris' world shaders draw inside Minecraft's Metal render passes.

## Status

| | |
|---|---|
| Vanilla on Metal | ✅ works, Metal validation clean |
| Sodium | ✅ |
| Shader packs (Iris fork): post-processing | ✅ |
| Shader packs: world (terrain, entities, particles, sky) | ✅ |
| Shader packs: shadows | 🚧 next |
| Compute shaders / image load-store / SSBOs | ⏳ later |

## Build & run

Needs a JDK 25 (`brew install openjdk@25`) and Xcode command line tools.

```sh
./mm.sh build        # builds the mod and the native Metal library
./mm.sh run          # runs Minecraft from the dev environment
./mm.sh kit          # downloads Sodium, Iris and BSL for testing
./mm.sh iris         # builds MineMetal + ../MineMetal-Iris, runs with shader packs on Metal
```

Useful flags (`-PmmArgs="..."`): `-PmetalHud` (Apple's performance HUD), `-PmetalValidation` (Metal API validation),
`-Dminemetal.disable=true` (use OpenGL instead).

## License

CC0-1.0. Minecraft is a trademark of Mojang/Microsoft; this project is not affiliated with them, Apple or the Iris team.
