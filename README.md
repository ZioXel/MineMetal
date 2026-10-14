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

## Upscaling (MetalFX / AMD FSR 1)

Video Settings → **MineMetal** (shown while Metal is running) renders the world at a lower resolution and scales it up
to the window before the GUI is drawn, so menus, chat and the hotbar stay sharp:

| Setting | Values |
|---|---|
| Upscaler | Off (default), MetalFX Temporal, MetalFX Spatial, AMD FSR 1 (EASU + RCAS), Bilinear |
| Render Scale | 50–100 % per axis (75 % ≈ 56 % of the pixels) |
| Upscale Sharpness | 0–100 % AMD RCAS sharpening after FSR 1 or MetalFX |

**MetalFX Spatial** is the recommended choice. **MetalFX Temporal** (experimental) jitters the camera by a sub-pixel amount every frame and builds motion vectors from the scene
depth and the camera movement, so it upscales *and* anti-aliases (also usable at 100 % as pure anti-aliasing). Turn the
shader pack's own TAA off (Complementary: Temporal Filtering and Temporal Jitter OFF). Moving mobs and particles only get
camera motion, and view bobbing still makes the terrain wobble slightly with Temporal (turn View Bobbing off for the
cleanest image). Debug options: `-Dminemetal.taa.depth=reversed|standard|auto`, `-Dminemetal.taa.jitterSignX=±1` / `jitterSignY`,
`-Dminemetal.taa.motionSign=±1`, `-Dminemetal.taa.view=extract|level|late`, `-Dminemetal.taa.skyReactive=0..1`.

The settings are stored in `config/minemetal.properties`; `-Dminemetal.upscale=metalfx_temporal|metalfx|fsr1|bilinear|off` and
`-Dminemetal.renderScale=67` override them. Works with vanilla and Iris shader packs (packs simply see a smaller
screen). Sodium replaces Video Settings with its own screen; use the config file there. FSR 1 is © AMD, MIT licensed
(`tools/fsr/`).

## Install (players)

Requirements: Apple Silicon Mac, macOS 14+, Minecraft Java 26.3 with Fabric Loader 0.19.5+, Sodium 0.9.x.

1. Install Fabric for 26.3 with the [Fabric installer](https://fabricmc.net/use/installer/) and start the Fabric profile once.
2. Download `minemetal-*.jar` from [Releases](https://github.com/ZioXel/MineMetal/releases) and Sodium from Modrinth,
   put both into `~/Library/Application Support/minecraft/mods`.
3. For shader packs: also download **Iris (MineMetal)** from
   [MineMetal-Iris releases](https://github.com/ZioXel/MineMetal-Iris/releases) and use it **instead of** the official
   Iris jar.

Video Settings → Graphics API shows **Default (Metal)** while MineMetal is running. Pick OpenGL or Vulkan there
(and restart) to use those instead.

## Build & run

Needs a JDK 25 (`brew install openjdk@25`) and Xcode command line tools.

```sh
./mm.sh build        # builds the mod and the native Metal library
./mm.sh run          # runs Minecraft from the dev environment
./mm.sh kit          # downloads Sodium, Iris and BSL for testing
./mm.sh iris         # builds MineMetal + ../MineMetal-Iris, runs with shader packs on Metal
./release.sh         # release jars of both mods into dist/ (./release.sh publish -> GitHub releases)
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
