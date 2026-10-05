# Stage 0 – shader packs on Metal: feasibility

Date: 2026-10-05 · Minecraft 26.3 · Iris 1.11.7+mc26.3 · Sodium 0.9.3-alpha.1+mc26.3 · BSL v10.1.8

## Verdict
Feasible. Shader code translates almost perfectly; the work is in the *runtime* (Iris' OpenGL layer).

## Shader translation test (BSL, Iris-patched sources)
| Step | Result |
|---|---|
| Patched shaders dumped by Iris | 198 (99 vertex + 99 fragment, no geometry/compute) |
| GLSL → SPIR-V (glslang, `-R` relaxed Vulkan rules) | 198 / 198 |
| SPIR-V → MSL (SPIRV-Cross, MSL 2.4) | 198 / 198 |
| MSL → Metal library (system compiler) | 197 / 198 |

Only failure: a local variable named `new` (C++ keyword) in `deferred.fsh`; fix = strip debug names / rename
reserved words before MSL generation. All 198 use loose uniforms → packed into `gl_DefaultUniformBlock`
(we fill it from reflection offsets). ~74 distinct uniforms, MRT outputs widely used.

## Code analysis
- **Sodium** (required by Iris): renders via renderpearl; has VK_MULTIDRAW / VK_INDIRECT draw paths chosen by
  `instanceof VulkanDevice`. On Metal it would fall back to its GL path → one mixin to pick VK_MULTIDRAW.
- **Iris, pack side** (`shaderpack/`, `parsing/`, options GUI, ~86+ files): no OpenGL, reusable as is.
- **Iris, render side** (`gl/` 46 files, `targets/`, `pipeline/`, `uniforms/`, 24 mixins): ~120 distinct GL
  calls; hooks Mojang's GL backend (GlRenderPass, GlRenderPipeline, GlCommandEncoder, GlProgram) to swap
  programs per draw. Needs reimplementation on our backend, where we own the hook points.
- **Features with no renderpearl equivalent**: compute shaders, SSBOs, image load/store (not used by BSL;
  used by some packs) → own native Metal compute path later.
- **Reverse-Z**: Iris undoes Minecraft's reversed depth for packs → same needed on Metal.
- **License**: Iris LGPL-3.0 (fork must stay LGPL if distributed); Sodium PolyForm Shield (we only add a mixin).
