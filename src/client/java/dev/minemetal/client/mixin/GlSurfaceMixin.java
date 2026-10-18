package dev.minemetal.client.mixin;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.opengl.GlSurface;
import dev.minemetal.client.gltranslate.MetalGl;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GL translation mode: the window's surface is a CAMetalLayer. configure() sizes the Metal swapchain and MineMetal's
 * default framebuffer, present() replaces SDL_GL_SwapWindow.
 */
@Mixin(GlSurface.class)
public abstract class GlSurfaceMixin {
    @Shadow @Final private long windowHandle;

    @Redirect(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetSwapInterval(I)Z", remap = false))
    private boolean minemetal$swapInterval(final int interval) {
        return MetalGl.isActive() || SDLVideo.SDL_GL_SetSwapInterval(interval);
    }

    @Inject(method = "configure", at = @At("TAIL"))
    private void minemetal$configure(final GpuSurface.Configuration config, final CallbackInfo ci) {
        if (MetalGl.isActive()) {
            MetalGl.configure(this.windowHandle, config.width(), config.height(), config.presentMode() == GpuSurface.PresentMode.FIFO);
        }
    }

    @Redirect(method = "present", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SwapWindow(J)Z", remap = false))
    private boolean minemetal$present(final long window) {
        if (!MetalGl.isActive()) return SDLVideo.SDL_GL_SwapWindow(window);
        MetalGl.present(window);
        return true;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void minemetal$close(final CallbackInfo ci) {
        if (MetalGl.isActive()) MetalGl.closeSurface(this.windowHandle);
    }
}
