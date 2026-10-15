# MineMetal for NeoForge 1.21.1 (work in progress)

Goal: run Minecraft 1.21.1 modpacks (e.g. All the Mods 10) on Apple Metal. Every OpenGL call - from vanilla and from
every mod - is answered by MineMetal's OpenGL-on-Metal layer (MetalGL), so mods need no changes.

Build: `./gradlew build` (needs Java 25: the instance must run on Java 25).
Dev run: `./gradlew runClient`.

Plan:
1. Window: no OpenGL context, a CAMetalLayer instead; LWJGL's OpenGL function pointers point at MineMetal (FFM upcalls).
2. Vanilla 1.21.1 renders through MetalGL (reports OpenGL 4.1 core, like Apple's driver, so mods take their Mac paths).
3. Sodium, Iris, Create/Flywheel, then the rest of ATM10.
