package dev.minemetal.client.gltranslate;

import dev.minemetal.neo.gl.Gl;
import dev.minemetal.neo.gl.NeoGL;
import dev.minemetal.neo.metal.NeoNative;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.file.Path;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime of the "GL translation" mode: Minecraft's own OpenGL backend runs unchanged, but LWJGL's GL function table
 * points at MineMetal's OpenGL-on-Metal layer (the one the NeoForge 1.21.1 port uses, {@code dev.minemetal.neo.gl}),
 * windows get a CAMetalLayer instead of an OpenGL context, and swapping buffers presents through Metal.
 * <p>
 * Render thread only.
 */
public final class MetalGl {
    private static final Logger LOG = LoggerFactory.getLogger("MineMetal");

    /** True while {@link MetalGlBackend} owns the GL function table (SDL_GL_* calls in GlDevice/GlSurface are redirected). */
    private static volatile boolean active;
    private static long ctx;
    private static long frames;

    private static final class Surface {
        long view, handle;
        int width = -1, height = -1, mode = -1;
        boolean fifo = true;
    }

    private static final Long2ObjectOpenHashMap<Surface> SURFACES = new Long2ObjectOpenHashMap<>();

    private MetalGl() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Loads libminemetal + the shader tools, creates the Metal context and swaps LWJGL's GL provider. */
    static void activate() throws Exception {
        if (ctx == 0L) {
            NeoNative.load();
            ctx = NeoNative.createContext();
            loadShaderTools();
            Gl.init(ctx);
        }
        GL.destroy();
        GL.create(NeoGL.PROVIDER);
        active = true;
        LOG.info("[MineMetal] GL translation mode: OpenGL calls now go to MineMetal's OpenGL-on-Metal layer");
    }

    /** Gives LWJGL its normal OpenGL back (vanilla backends tried after us expect OpenGL.framework). */
    static void deactivate() {
        if (!active) return;
        active = false;
        try {
            GL.destroy();
            GL.create();
        } catch (Throwable t) {
            LOG.error("[MineMetal] could not restore the OpenGL function provider", t);
        }
    }

    /**
     * shaderc: Minecraft 26.3 ships LWJGL 3.4 with libshaderc (incl. vulkan_rules_relaxed); the shared compiler binds it
     * through FFM by path. SPIRV-Cross is loaded by Minecraft itself (its GL backend decompiles SPIR-V with it).
     */
    private static void loadShaderTools() {
        String path = org.lwjgl.util.shaderc.Shaderc.getLibrary().getPath();
        if (path == null) throw new IllegalStateException("LWJGL's shaderc library has no file path");
        dev.minemetal.neo.gl.compile.Shaderc.load(Path.of(path));
    }

    // ---- surfaces (one CAMetalLayer per SDL window) -----------------------------------------------------------------

    private static Surface surface(long window) {
        Surface s = SURFACES.get(window);
        if (s != null) return s;
        s = new Surface();
        s.view = SDLMetal.SDL_Metal_CreateView(window);
        if (s.view == 0L) throw new IllegalStateException("SDL_Metal_CreateView failed: " + SDLError.SDL_GetError());
        long layer = SDLMetal.SDL_Metal_GetLayer(s.view);
        if (layer == 0L) throw new IllegalStateException("SDL_Metal_GetLayer returned null");
        s.handle = NeoNative.surfaceCreate(ctx, layer);
        if (s.handle == 0L) throw new IllegalStateException("Could not wrap the CAMetalLayer");
        SURFACES.put(window, s);
        return s;
    }

    /** GlSurface.configure: size of the swapchain and FIFO (VSync) or immediate presentation. */
    public static void configure(long window, int width, int height, boolean fifo) {
        Surface s = surface(window);
        int w = Math.max(width, 1), h = Math.max(height, 1);
        int mode = fifo ? NeoNative.PRESENT_FIFO : switch (System.getProperty("minemetal.pacing", "smooth")) {
            case "paced" -> NeoNative.PRESENT_PACED;
            case "raw" -> NeoNative.PRESENT_IMMEDIATE;
            default -> NeoNative.PRESENT_SMOOTH;
        };
        s.fifo = fifo;
        if (w != s.width || h != s.height || mode != s.mode) {
            s.width = w;
            s.height = h;
            s.mode = mode;
            NeoNative.surfaceConfigure(s.handle, w, h, mode);
        }
        // GL's default framebuffer (what GlCommandEncoder.presentTexture blits into) = these backbuffer textures
        Gl.ensureBackbuffer(w, h);
    }

    /** GlSurface.present (SDL_GL_SwapWindow). */
    public static void present(long window) {
        Surface s = SURFACES.get(window);
        if (s == null) return;
        Gl.present(s.handle, s.fifo);
        NeoGL.endFrame(++frames);
    }

    public static void closeSurface(long window) {
        Surface s = SURFACES.remove(window);
        if (s == null) return;
        if (s.handle != 0L) NeoNative.release(s.handle);
        if (s.view != 0L) SDLMetal.SDL_Metal_DestroyView(s.view);
    }
}
