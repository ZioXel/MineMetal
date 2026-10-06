package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;

/** The window's CAMetalLayer (created through SDL_Metal_CreateView). */
public final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO,
        GpuSurface.PresentMode.IMMEDIATE);

    private final MetalDevice device;
    private final BooleanSupplier isIconified;
    private final long metalView;
    private final long handle;
    private int width;
    private int height;
    private boolean acquired;

    MetalSurface(final MetalDevice device, final long windowHandle, final BooleanSupplier isIconified) {
        this.device = device;
        this.isIconified = isIconified;
        this.metalView = SDLMetal.SDL_Metal_CreateView(windowHandle);
        if (this.metalView == 0L) {
            throw new IllegalStateException("SDL_Metal_CreateView failed: " + SDLError.SDL_GetError());
        }
        long layer = SDLMetal.SDL_Metal_GetLayer(this.metalView);
        if (layer == 0L) {
            throw new IllegalStateException("SDL_Metal_GetLayer returned null");
        }
        this.handle = Native.surfaceCreate(device.ctx(), layer);
        if (this.handle == 0L) {
            throw new IllegalStateException("Failed to wrap CAMetalLayer");
        }
    }

    long handle() {
        return this.handle;
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        this.width = config.width();
        this.height = config.height();
        int mode;
        if (config.presentMode() == GpuSurface.PresentMode.FIFO) {
            mode = Native.PRESENT_FIFO;
        } else {
            // VSync off: unlimited rendering, newest frame shown once per refresh. -Dminemetal.unpaced=true = raw mode.
            mode = Boolean.getBoolean("minemetal.unpaced") ? Native.PRESENT_IMMEDIATE : Native.PRESENT_PACED;
        }
        Native.surfaceConfigure(this.handle, config.width(), config.height(), mode);
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (this.isIconified.getAsBoolean()) {
            throw new SurfaceException("Cannot acquire minimized window");
        }
        long t0 = System.nanoTime();
        boolean ok = Native.surfaceAcquire(this.handle);
        this.device.encoder().stats.acquireNs += System.nanoTime() - t0;
        if (!ok) {
            throw new SurfaceException("CAMetalLayer did not provide a drawable");
        }
        this.acquired = true;
    }

    @Override
    public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
        int copyWidth = Math.min(this.width, textureView.getWidth(0));
        int copyHeight = Math.min(this.height, textureView.getHeight(0));
        MetalProfiler.phase("present");
        ((MetalCommandEncoder) commandEncoder).presentBlit(this, textureView, copyWidth, copyHeight);
        this.acquired = false;
    }

    @Override
    public void present() {
        FrameTrace.framePresented();
        MetalProfiler.frame();
        MetalProfiler.phase("other");
        // Presentation was scheduled on the command buffer in blitFromTexture ([MTLCommandBuffer presentDrawable:]),
        // so it happens when the frame's submit completes on the GPU.
        if (this.acquired) {
            Native.surfaceDrop(this.handle);
            this.acquired = false;
        }
    }

    @Override
    public void close() {
        Native.surfaceDrop(this.handle);
        Native.release(this.handle);
        SDLMetal.SDL_Metal_DestroyView(this.metalView);
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }

    MetalDevice device() {
        return this.device;
    }
}
