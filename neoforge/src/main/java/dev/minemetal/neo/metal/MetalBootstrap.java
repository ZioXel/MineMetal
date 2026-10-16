package dev.minemetal.neo.metal;

import com.mojang.logging.LogUtils;
import dev.minemetal.neo.gl.NeoGL;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.nio.IntBuffer;

/**
 * Replaces the OpenGL context of the game window: the window is created with GLFW_NO_API, gets a CAMetalLayer,
 * and LWJGL's GL bindings are pointed at MineMetal's function provider instead of OpenGL.framework.
 */
public final class MetalBootstrap {
    private static final Logger LOG = LogUtils.getLogger();
    /** MM_OPENGL=1: run the game on Apple's OpenGL (for A/B comparisons). */
    public static final boolean ENABLED = !"1".equals(System.getenv("MM_OPENGL"));
    private static long ctx;
    private static long surface;
    private static boolean vsync = true;
    private static int width = -1, height = -1, mode = -1;
    private static long frames;

    private MetalBootstrap() {
    }

    public static GLCapabilities init(long window) {
        try {
            int api = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CLIENT_API);
            LOG.info("[MineMetal] window client API = 0x{} (0 = NO_API, expected)", Integer.toHexString(api));
            NeoNative.load();
            ctx = NeoNative.createContext();
            loadShaderTools();
            dev.minemetal.neo.gl.Gl.init(ctx);
            long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(window);
            long layer = NeoNative.layerForNSWindow(nsWindow);
            if (layer == 0L) throw new IllegalStateException("Could not attach a CAMetalLayer to the window");
            surface = NeoNative.surfaceCreate(ctx, layer);
            if (surface == 0L) throw new IllegalStateException("Could not wrap the CAMetalLayer");
            configure(window);

            GL.destroy();
            GL.create(NeoGL.PROVIDER);
            GLCapabilities caps = GL.createCapabilities();
            LOG.info("[MineMetal] Metal window ready; GL bindings now go to MineMetal ({} GL functions looked up)",
                NeoGL.lookups());
            return caps;
        } catch (Throwable t) {
            LOG.error("[MineMetal] Metal bootstrap failed", t);
            throw t instanceof RuntimeException r ? r : new RuntimeException(t);
        }
    }

    /** shaderc + SPIRV-Cross dylibs ship as resources; LWJGL loads them by absolute path. */
    private static void loadShaderTools() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("minemetal-natives");
        dir.toFile().deleteOnExit();
        for (String lib : new String[]{"libshaderc.dylib", "libspirv-cross.dylib"}) {
            try (java.io.InputStream in = MetalBootstrap.class.getResourceAsStream("/natives/macos/" + lib)) {
                if (in == null) throw new java.io.IOException("Missing /natives/macos/" + lib + " in the MineMetal jar");
                java.nio.file.Path out = dir.resolve(lib);
                java.nio.file.Files.copy(in, out);
                out.toFile().deleteOnExit();
                if (lib.startsWith("libshaderc")) dev.minemetal.neo.gl.compile.Shaderc.load(out);
                else org.lwjgl.system.Configuration.SPVC_LIBRARY_NAME.set(out.toString());
            }
        }
    }

    public static void setVsync(boolean on) {
        vsync = on;
    }

    private static void configure(long window) {
        int w, h;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pw = stack.mallocInt(1), ph = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, pw, ph);
            w = Math.max(pw.get(0), 1);
            h = Math.max(ph.get(0), 1);
        }
        // VSync off: "smooth" by default (paced + evenly spaced frames, ProMotion-friendly, as on the 26.3 mod);
        // MM_PRESENT=paced|immediate for comparison.
        String pm = System.getenv("MM_PRESENT");
        int m = vsync ? NeoNative.PRESENT_FIFO
            : "immediate".equals(pm) ? NeoNative.PRESENT_IMMEDIATE
            : "paced".equals(pm) ? NeoNative.PRESENT_PACED : NeoNative.PRESENT_SMOOTH;
        if (w != width || h != height || m != mode) {
            width = w;
            height = h;
            mode = m;
            NeoNative.surfaceConfigure(surface, w, h, m);
        }
        dev.minemetal.neo.gl.Gl.ensureBackbuffer(w, h);
    }

    /** Replaces glfwSwapBuffers. */
    public static void present(long window) {
        dev.minemetal.neo.gl.Gl.present(surface, vsync);
        configure(window);
        frames++;
        NeoGL.endFrame(frames);
    }
}
