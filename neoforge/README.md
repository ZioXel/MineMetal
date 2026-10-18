# MineMetal for NeoForge 1.21.1 (work in progress)

Goal: run Minecraft 1.21.1 modpacks (e.g. All the Mods 10) on Apple Metal. Every OpenGL call - from vanilla and from
every mod - is answered by MineMetal's OpenGL-on-Metal layer (MetalGL), so mods need no changes.

Build: `./gradlew build` (needs Java 25: the instance must run on Java 25).
Dev run: `./gradlew runClient`.

Plan:
1. Window: no OpenGL context, a CAMetalLayer instead; LWJGL's OpenGL function pointers point at MineMetal (FFM upcalls).
2. Vanilla 1.21.1 renders through MetalGL (reports OpenGL 4.1 core, like Apple's driver, so mods take their Mac paths).
3. Sodium, Iris, Create/Flywheel, then the rest of ATM10.

## Milestone 1 (done): Metal window + null GL driver
- `WindowMixin`: window created with `GLFW_NO_API`, no `glfwMakeContextCurrent`; `GL.createCapabilities()` is
  replaced by `MetalBootstrap.init` (CAMetalLayer on the NSWindow, LWJGL `GL.create(NeoGL.PROVIDER)`).
- `NeoGL`: LWJGL FunctionProvider made of FFM upcall stubs. Reports OpenGL 4.1 core, hands out object names,
  every shader compiles. Unimplemented functions are logged (`[MineMetal GL] unimplemented: ...`) with call counts.
- `RenderSystemMixin`: `glfwSwapBuffers` → Metal present (clears to the last `glClearColor`).
- Needs `earlyWindowControl = false` in `run/config/fml.toml` (NeoForge's early loading window is OpenGL).

## Milestone 2 (done): real rendering through Metal
- `gl/Gl.java`: GL state machine → Metal. Buffers (new MTLBuffer per glBufferData, old ones released when the GPU is
  done), textures (+ samplers from texture parameters), framebuffers (default framebuffer = backbuffer textures),
  programs (GLSL → SPIR-V → MSL at link time via the shared `GlProgramCompiler`), VAOs, draws, clears, blits.
- `gl/GlEntry.java`: the upcall targets (C signatures); `NeoGL` guards them so an exception never kills the JVM.
- shaderc + SPIRV-Cross (LWJGL 3.3.3) are jar-in-jar'd; their macOS dylibs ship as resources.
- GL CCW front faces map to MTLWindingClockwise (verified). Debug env vars: MM_FLIPWINDING, MM_NOCULL, MM_NOBLEND, MM_NODEPTH.

## Status (0.1.0-neo-experimental)

| | |
|---|---|
| Vanilla 1.21.1 | ✅ 400+ fps (Apple OpenGL: 100–130) |
| Sodium / Embeddium | ✅ 560–725 fps (Apple OpenGL: 230) |
| Iris + Complementary (HIGH) | ✅ 38 fps vs 30 on OpenGL, ~100 with MetalFX at 50 % |
| Create / Flywheel instancing | ✅ |
| Mekanism, Immersive Engineering, AE2, JEI | ✅ |
| All the Mods 10 | 🧪 boots, menus and world render; load time and fps being worked on |
| Upscaling (MetalFX / FSR / bilinear) | ✅ `config/minemetal.properties` |
| Stencil, glReadPixels (screenshots) | ❌ not yet |

Requirements: Apple Silicon, Java 25, `earlyWindowControl = false` in `config/fml.toml`.
`MM_OPENGL=1` (environment variable) falls back to Apple's OpenGL for A/B comparison.
`deploy-atm10.sh` builds the jar and swaps it into the Prism ATM10 instance.
