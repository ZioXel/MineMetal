package dev.minemetal.client.mixin;

import dev.minemetal.client.gltranslate.MetalGl;
import java.nio.IntBuffer;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * GL translation mode: renderpearl's GlDevice runs without a real OpenGL context. Its SDL_GL_* context calls become
 * no-ops and the context reports OpenGL 4.1 (what MineMetal's GL layer implements, like Apple's driver). Without
 * {@link MetalGl#isActive()} every call goes to SDL unchanged.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice")
public abstract class GlDeviceMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_CreateContext(J)J", remap = false))
    private long minemetal$createContext(final long window) {
        return MetalGl.isActive() ? 1L : SDLVideo.SDL_GL_CreateContext(window);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_GetAttribute(ILjava/nio/IntBuffer;)Z", remap = false))
    private boolean minemetal$getAttribute(final int attribute, final IntBuffer value) {
        if (!MetalGl.isActive()) return SDLVideo.SDL_GL_GetAttribute(attribute, value);
        // SDL_GL_CONTEXT_MAJOR_VERSION = 17, SDL_GL_CONTEXT_MINOR_VERSION = 18
        value.put(value.position(), attribute == 17 ? 4 : attribute == 18 ? 1 : 0);
        return true;
    }

    @Redirect(method = "makeCurrent", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_MakeCurrent(JJ)Z", remap = false))
    private boolean minemetal$makeCurrent(final long window, final long context) {
        return MetalGl.isActive() || SDLVideo.SDL_GL_MakeCurrent(window, context);
    }

    @Redirect(method = {"<init>", "close"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_DestroyContext(J)Z", remap = false))
    private boolean minemetal$destroyContext(final long context) {
        return MetalGl.isActive() || SDLVideo.SDL_GL_DestroyContext(context);
    }
}
