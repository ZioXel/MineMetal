package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point Minecraft sees: an extra {@link GpuBackend} next to OpenGL and Vulkan. */
public final class MetalBackend implements GpuBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal");
    private @Nullable BackendCreationException loadFailure;
    private boolean loaded;

    public static boolean isSupportedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"));
    }

    @Override
    public String getName() {
        return "Metal";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        if (this.loaded) {
            return;
        }
        if (this.loadFailure != null) {
            throw this.loadFailure;
        }
        if (!isSupportedPlatform()) {
            this.loadFailure = new BackendCreationException("Metal backend requires macOS on Apple Silicon", BackendCreationException.Reason.PLATFORM_ERROR);
            throw this.loadFailure;
        }
        try {
            Native.load();
        } catch (Throwable t) {
            LOGGER.error("MineMetal: failed to load native library", t);
            this.loadFailure = new BackendCreationException("Failed to load libminemetal: " + t, BackendCreationException.Reason.OTHER);
            throw this.loadFailure;
        }
        this.loaded = true;
    }

    @Override
    public void unloadLibrary() {
        // The dylib stays mapped for the lifetime of the process (Arena.global()).
    }

    @Override
    public long createWindow(final @Nullable String title, final int width, final int height, final long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
    }

    @Override
    public GpuDevice createDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
        return new FrontendGpuDevice(new MetalDevice(debugOptions));
    }
}
