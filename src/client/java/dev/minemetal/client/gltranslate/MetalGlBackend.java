package dev.minemetal.client.gltranslate;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import dev.minemetal.client.metal.MetalBackend;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metal through OpenGL translation: Minecraft's own OpenGL backend (renderpearl's GlDevice, unmodified) drives
 * MineMetal's OpenGL-on-Metal layer instead of Apple's OpenGL driver. The same approach as the NeoForge 1.21.1 port;
 * selected with {@code -Dminemetal.backend=gl} so it can be compared with the native Metal backend on the same build.
 * <p>
 * GlDevice/GlSurface talk to SDL for the GL context and buffer swaps; those calls are redirected by
 * {@code GlDeviceMixin} / {@code GlSurfaceMixin} while {@link MetalGl#isActive()}.
 */
public final class MetalGlBackend extends GlBackend {
    private static final Logger LOG = LoggerFactory.getLogger("MineMetal");
    private boolean loaded;
    private @Nullable BackendCreationException loadFailure;

    @Override
    public String getName() {
        return "Metal (GL translation)";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        if (this.loaded) return;
        if (this.loadFailure != null) throw this.loadFailure;
        if (!MetalBackend.isSupportedPlatform()) {
            this.loadFailure = new BackendCreationException("Metal requires macOS on Apple Silicon", BackendCreationException.Reason.PLATFORM_ERROR);
            throw this.loadFailure;
        }
        try {
            MetalGl.activate();
            this.loaded = true;
        } catch (Throwable t) {
            LOG.error("[MineMetal] GL translation mode failed to start", t);
            MetalGl.deactivate();
            this.loadFailure = new BackendCreationException("MineMetal GL translation: " + t, BackendCreationException.Reason.OTHER);
            throw this.loadFailure;
        }
    }

    @Override
    public void unloadLibrary() {
        if (!this.loaded) return;
        MetalGl.deactivate();
        this.loaded = false;
    }

    /** Same window as the native Metal backend: no OpenGL context, a CAMetalLayer is attached per surface. */
    @Override
    public long createWindow(final @Nullable String title, final int width, final int height, final long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
    }

    /** GlDevice reports GL errors as runtime exceptions; turn them into a clean fallback to the next backend. */
    @Override
    public GpuDevice createDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
        try {
            return super.createDevice(debugOptions);
        } catch (RuntimeException e) {
            LOG.error("[MineMetal] GL translation device creation failed", e);
            throw new BackendCreationException("MineMetal GL translation: " + e, BackendCreationException.Reason.OTHER);
        }
    }
}
