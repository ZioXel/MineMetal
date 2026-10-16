package dev.minemetal.neo.metal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Downcalls into libminemetal.dylib (shared with the Fabric 26.3 mod, ../native/minemetal.m). Pointers are longs. */
public final class NeoNative {
    public static final int PRESENT_FIFO = 0;
    public static final int PRESENT_PACED = 1;
    public static final int PRESENT_IMMEDIATE = 2;
    public static final int PRESENT_SMOOTH = 3;

    private static final String RESOURCE = "/natives/macos/libminemetal.dylib";
    private static final Linker LINKER = Linker.nativeLinker();
    private static SymbolLookup lib;
    private static MethodHandle CREATE_CONTEXT, LAYER_FOR_NSWINDOW, SURFACE_CREATE, SURFACE_CONFIGURE, PRESENT_CLEAR;
    private static MethodHandle ENUM, RELEASE, SUBMIT, COMPLETED_INDEX, CURRENT_SUBMIT_INDEX, SET_DEPTH_FLIP, WAIT_IDLE;
    private static MethodHandle BUFFER_CREATE, BUFFER_CONTENTS, TEXTURE_CREATE, TEXTURE_VIEW, GENERATE_MIPMAPS, SAMPLER_CREATE_EX;
    private static MethodHandle COPY_BUFFER_TO_TEXTURE, COPY_TEXTURE_TO_TEXTURE, COPY_TEXTURE_TO_BUFFER;
    private static MethodHandle PASS_BEGIN, PASS_END, PASS_SET_VIEWPORT, PASS_SET_SCISSOR, PASS_SET_PIPELINE, PASS_SET_VERTEX_BUFFER;
    private static MethodHandle PASS_SET_BYTES, PASS_SET_TEXTURE, PASS_SET_SAMPLER, PASS_SET_INDEX_BUFFER, PASS_DRAW, PASS_DRAW_INDEXED;
    private static MethodHandle PIPELINE_CREATE, SURFACE_ACQUIRE, SURFACE_BLIT, MULTI_DRAW_INDEXED, COPY_BUFFER, WAIT, PASS_SET_BUFFER, PASS_SET_TEXEL_BUFFER, UPSCALE;

    private NeoNative() {
    }

    public static synchronized void load() throws IOException {
        if (lib != null) return;
        Path file;
        String override = System.getProperty("minemetal.nativeLibrary");
        if (override != null) {
            file = Path.of(override);
        } else {
            try (InputStream in = NeoNative.class.getResourceAsStream(RESOURCE)) {
                if (in == null) throw new IOException("Missing " + RESOURCE + " (was the native part built on macOS?)");
                file = Files.createTempFile("libminemetal", ".dylib");
                file.toFile().deleteOnExit();
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        lib = SymbolLookup.libraryLookup(file, Arena.global());
        CREATE_CONTEXT = h("mm_create_context", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
        LAYER_FOR_NSWINDOW = h("mm_layer_for_nswindow", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        SURFACE_CREATE = h("mm_surface_create", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
        SURFACE_CONFIGURE = h("mm_surface_configure", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));
        PRESENT_CLEAR = h("mm_neo_present_clear",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        final var P = JAVA_LONG;
        final var I = JAVA_INT;
        final var J = JAVA_LONG;
        final var F = JAVA_FLOAT;
        final var D = JAVA_DOUBLE;
        ENUM = h("mm_enum", FunctionDescriptor.of(J, P, P));
        RELEASE = h("mm_release", FunctionDescriptor.ofVoid(P));
        SUBMIT = h("mm_submit", FunctionDescriptor.of(J, P));
        COMPLETED_INDEX = hc("mm_completed_index", FunctionDescriptor.of(J, P));
        CURRENT_SUBMIT_INDEX = hc("mm_current_submit_index", FunctionDescriptor.of(J, P));
        SET_DEPTH_FLIP = h("mm_set_depth_flip", FunctionDescriptor.ofVoid(P, I));
        WAIT_IDLE = h("mm_wait_idle", FunctionDescriptor.ofVoid(P));
        BUFFER_CREATE = h("mm_buffer_create", FunctionDescriptor.of(P, P, J));
        BUFFER_CONTENTS = hc("mm_buffer_contents", FunctionDescriptor.of(P, P));
        TEXTURE_CREATE = h("mm_texture_create", FunctionDescriptor.of(P, P, J, I, I, I, I, I));
        TEXTURE_VIEW = h("mm_texture_view", FunctionDescriptor.of(P, P, I, I));
        GENERATE_MIPMAPS = h("mm_generate_mipmaps", FunctionDescriptor.ofVoid(P, P));
        SAMPLER_CREATE_EX = h("mm_sampler_create_ex", FunctionDescriptor.of(P, P, I, I, I, I, I, I, F, F, J));
        COPY_BUFFER_TO_TEXTURE = h("mm_copy_buffer_to_texture", FunctionDescriptor.ofVoid(P, P, J, J, J, P, I, I, I, I, I, I));
        COPY_TEXTURE_TO_TEXTURE = h("mm_copy_texture_to_texture", FunctionDescriptor.ofVoid(P, P, P, I, I, I, I, I, I, I));
        COPY_TEXTURE_TO_BUFFER = h("mm_copy_texture_to_buffer", FunctionDescriptor.ofVoid(P, P, I, I, I, I, I, I, P, J, J, J));
        PASS_BEGIN = hc("mm_pass_begin", FunctionDescriptor.of(I, P, P, I, I, P, P, D, I, I, I, I, P));
        PASS_END = hc("mm_pass_end", FunctionDescriptor.ofVoid(P));
        PASS_SET_VIEWPORT = hc("mm_pass_set_viewport", FunctionDescriptor.ofVoid(P, D, D, D, D, D, D));
        PASS_SET_SCISSOR = hc("mm_pass_set_scissor", FunctionDescriptor.ofVoid(P, I, I, I, I));
        PASS_SET_PIPELINE = h("mm_pass_set_pipeline", FunctionDescriptor.of(I, P, P));
        PASS_SET_VERTEX_BUFFER = hc("mm_pass_set_vertex_buffer", FunctionDescriptor.ofVoid(P, P, J, I));
        PASS_SET_BYTES = hc("mm_pass_set_bytes", FunctionDescriptor.ofVoid(P, I, P, J, I));
        PASS_SET_TEXTURE = hc("mm_pass_set_texture", FunctionDescriptor.ofVoid(P, I, P, I));
        PASS_SET_SAMPLER = hc("mm_pass_set_sampler", FunctionDescriptor.ofVoid(P, I, P, I));
        PASS_SET_INDEX_BUFFER = hc("mm_pass_set_index_buffer", FunctionDescriptor.ofVoid(P, P, I));
        PASS_DRAW = hc("mm_pass_draw", FunctionDescriptor.ofVoid(P, I, I, I, I));
        PASS_DRAW_INDEXED = hc("mm_pass_draw_indexed", FunctionDescriptor.ofVoid(P, I, I, I, I, I));
        PIPELINE_CREATE = h("mm_pipeline_create", FunctionDescriptor.of(P, P, P, P, P, P, P, P, I, P, I, P, I, P, P, I, P, I));
        MULTI_DRAW_INDEXED = hc("mm_pass_multi_draw_indexed", FunctionDescriptor.ofVoid(P, P, I, I, I));
        PASS_SET_BUFFER = hc("mm_pass_set_buffer", FunctionDescriptor.ofVoid(P, I, P, J, I));
        PASS_SET_TEXEL_BUFFER = hc("mm_pass_set_texel_buffer", FunctionDescriptor.ofVoid(P, I, P, J, J, J, I, I));
        UPSCALE = h("mm_upscale", FunctionDescriptor.of(I, P, P, P, I, F));
        COPY_BUFFER = h("mm_copy_buffer", FunctionDescriptor.ofVoid(P, P, J, P, J, J));
        WAIT = h("mm_wait", FunctionDescriptor.of(I, P, J, J));
        SURFACE_ACQUIRE = h("mm_surface_acquire", FunctionDescriptor.of(I, P));
        SURFACE_BLIT = h("mm_surface_blit", FunctionDescriptor.ofVoid(P, P, P, I, I));
    }

    /** Short, non-blocking calls on the per-draw path: skip the Java thread-state transition. */
    private static MethodHandle hc(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd, Linker.Option.critical(false));
    }

    private static MethodHandle h(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd);
    }

    private static RuntimeException rethrow(Throwable t) {
        return t instanceof RuntimeException r ? r : new RuntimeException(t);
    }

    public static long createContext() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment err = a.allocate(512);
            long ctx = (long) CREATE_CONTEXT.invokeExact(err.address(), 512);
            if (ctx == 0L) throw new IllegalStateException("Metal context creation failed: " + err.getString(0));
            return ctx;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long layerForNSWindow(long nsWindow) {
        try { return (long) LAYER_FOR_NSWINDOW.invokeExact(nsWindow); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long surfaceCreate(long ctx, long layer) {
        try { return (long) SURFACE_CREATE.invokeExact(ctx, layer); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void surfaceConfigure(long surface, int width, int height, int mode) {
        try { SURFACE_CONFIGURE.invokeExact(surface, width, height, mode); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void presentClear(long ctx, long surface, float r, float g, float b, float a) {
        try { PRESENT_CLEAR.invokeExact(ctx, surface, r, g, b, a); } catch (Throwable t) { throw rethrow(t); }
    }

    // ---- generic GL backend ----------------------------------------------------------------------------------------

    public static long enumValue(String kind, String name) {
        try (Arena a = Arena.ofConfined()) {
            return (long) ENUM.invokeExact(a.allocateFrom(kind).address(), a.allocateFrom(name).address());
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static void release(long obj) {
        try { RELEASE.invokeExact(obj); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long submit(long ctx) {
        try { return (long) SUBMIT.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long completedIndex(long ctx) {
        try { return (long) COMPLETED_INDEX.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long currentSubmitIndex(long ctx) {
        try { return (long) CURRENT_SUBMIT_INDEX.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void setDepthFlip(long ctx, boolean flip) {
        try { SET_DEPTH_FLIP.invokeExact(ctx, flip ? 1 : 0); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void waitIdle(long ctx) {
        try { WAIT_IDLE.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long bufferCreate(long ctx, long size) {
        try { return (long) BUFFER_CREATE.invokeExact(ctx, size); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long bufferContents(long buf) {
        try { return (long) BUFFER_CONTENTS.invokeExact(buf); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long textureCreate(long ctx, long pixelFormat, int w, int h, int layers, int mips, int usage) {
        try { return (long) TEXTURE_CREATE.invokeExact(ctx, pixelFormat, w, h, layers, mips, usage); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long textureView(long tex, int baseMip, int mips) {
        try { return (long) TEXTURE_VIEW.invokeExact(tex, baseMip, mips); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void generateMipmaps(long ctx, long tex) {
        try { GENERATE_MIPMAPS.invokeExact(ctx, tex); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long samplerCreate(long ctx, int addrU, int addrV, int minLinear, int magLinear, int mipMode, int aniso,
                                     float lodMin, float lodMax, long compare) {
        try {
            return (long) SAMPLER_CREATE_EX.invokeExact(ctx, addrU, addrV, minLinear, magLinear, mipMode, aniso, lodMin, lodMax, compare);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static void copyBufferToTexture(long ctx, long buf, long offset, long bytesPerRow, long bytesPerImage, long tex,
                                           int slice, int mip, int x, int y, int w, int h) {
        try {
            COPY_BUFFER_TO_TEXTURE.invokeExact(ctx, buf, offset, bytesPerRow, bytesPerImage, tex, slice, mip, x, y, w, h);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static void copyTextureToTexture(long ctx, long src, long dst, int mip, int sx, int sy, int dx, int dy, int w, int h) {
        try { COPY_TEXTURE_TO_TEXTURE.invokeExact(ctx, src, dst, mip, sx, sy, dx, dy, w, h); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void copyTextureToBuffer(long ctx, long tex, int slice, int mip, int x, int y, int w, int h, long buf, long offset,
                                           long bytesPerRow, long bytesPerImage) {
        try {
            COPY_TEXTURE_TO_BUFFER.invokeExact(ctx, tex, slice, mip, x, y, w, h, buf, offset, bytesPerRow, bytesPerImage);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static boolean passBegin(long ctx, long colorViews, int count, int clearMask, long clearColors, long depthView,
                                    double clearDepth, int ax, int ay, int aw, int ah) {
        try {
            return (int) PASS_BEGIN.invokeExact(ctx, colorViews, count, clearMask, clearColors, depthView, clearDepth, ax, ay, aw, ah, 0L) != 0;
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passEnd(long ctx) {
        try { PASS_END.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetViewport(long ctx, double x, double y, double w, double h) {
        try { PASS_SET_VIEWPORT.invokeExact(ctx, x, y, w, h, 0.0, 1.0); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetScissor(long ctx, int x, int y, int w, int h) {
        try { PASS_SET_SCISSOR.invokeExact(ctx, x, y, w, h); } catch (Throwable t) { throw rethrow(t); }
    }

    public static boolean passSetPipeline(long ctx, long pipeline) {
        try { return (int) PASS_SET_PIPELINE.invokeExact(ctx, pipeline) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetVertexBuffer(long ctx, long buf, long offset, int index) {
        try { PASS_SET_VERTEX_BUFFER.invokeExact(ctx, buf, offset, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetBytes(long ctx, int stages, long data, long length, int index) {
        try { PASS_SET_BYTES.invokeExact(ctx, stages, data, length, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetTexture(long ctx, int stages, long tex, int index) {
        try { PASS_SET_TEXTURE.invokeExact(ctx, stages, tex, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetSampler(long ctx, int stages, long sampler, int index) {
        try { PASS_SET_SAMPLER.invokeExact(ctx, stages, sampler, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetIndexBuffer(long ctx, long buf, boolean is32) {
        try { PASS_SET_INDEX_BUFFER.invokeExact(ctx, buf, is32 ? 1 : 0); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passDraw(long ctx, int vertexCount, int instances, int firstVertex, int firstInstance) {
        try { PASS_DRAW.invokeExact(ctx, vertexCount, instances, firstVertex, firstInstance); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passDrawIndexed(long ctx, int indexCount, int instances, int firstIndex, int baseVertex, int firstInstance) {
        try { PASS_DRAW_INDEXED.invokeExact(ctx, indexCount, instances, firstIndex, baseVertex, firstInstance); } catch (Throwable t) { throw rethrow(t); }
    }

    /** Returns the pipeline, or 0 with the message in {@code err}. All pointer args are native addresses. */
    public static long pipelineCreate(long ctx, long name, long vs, long vsEntry, long fs, long fsEntry, long layouts, int layoutCount,
                                      long attribs, int attribCount, long targets, int targetCount, long raster, long bias,
                                      int eagerDepth, long err, int errLen) {
        try {
            return (long) PIPELINE_CREATE.invokeExact(ctx, name, vs, vsEntry, fs, fsEntry, layouts, layoutCount, attribs, attribCount,
                targets, targetCount, raster, bias, eagerDepth, err, errLen);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static boolean surfaceAcquire(long surface) {
        try { return (int) SURFACE_ACQUIRE.invokeExact(surface) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    public static void surfaceBlit(long ctx, long surface, long srcView, int w, int h) {
        try { SURFACE_BLIT.invokeExact(ctx, surface, srcView, w, h); } catch (Throwable t) { throw rethrow(t); }
    }

    /** params: drawCount triples (firstIndex, indexCount, vertexOffset). */
    public static void passMultiDrawIndexed(long ctx, long params, int instances, int firstInstance, int drawCount) {
        try { MULTI_DRAW_INDEXED.invokeExact(ctx, params, instances, firstInstance, drawCount); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void copyBuffer(long ctx, long src, long srcOffset, long dst, long dstOffset, long size) {
        try { COPY_BUFFER.invokeExact(ctx, src, srcOffset, dst, dstOffset, size); } catch (Throwable t) { throw rethrow(t); }
    }

    /** Waits until submit {@code index} completed; returns true if it did within the timeout. */
    public static boolean waitFor(long ctx, long index, long timeoutNs) {
        try { return (int) WAIT.invokeExact(ctx, index, timeoutNs) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetBuffer(long ctx, int stages, long buf, long offset, int index) {
        try { PASS_SET_BUFFER.invokeExact(ctx, stages, buf, offset, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetTexelBuffer(long ctx, int stages, long buf, long offset, long length, long pixelFormat, int bytesPerTexel, int index) {
        try {
            PASS_SET_TEXEL_BUFFER.invokeExact(ctx, stages, buf, offset, length, pixelFormat, bytesPerTexel, index);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** Scales src into dst (1 bilinear, 2 FSR 1, 3 MetalFX Spatial); returns the mode actually used. */
    public static int upscale(long ctx, long src, long dst, int mode, float sharpness) {
        try { return (int) UPSCALE.invokeExact(ctx, src, dst, mode, sharpness); } catch (Throwable t) { throw rethrow(t); }
    }
}
