package dev.minemetal.client.metal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for libminemetal.dylib (see native/minemetal.m).
 * <p>
 * Pointers are passed as {@code long} (arm64: pointers and 64-bit integers share the same registers),
 * which lets the rest of the backend use LWJGL's MemoryStack/MemoryUtil addresses directly.
 */
public final class Native {
    private static final String RESOURCE = "/natives/macos/libminemetal.dylib";
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MemoryLayout P = JAVA_LONG; // pointer
    private static SymbolLookup lib;
    private static boolean loaded;

    private Native() {
    }

    public static synchronized void load() throws IOException {
        if (loaded) {
            return;
        }
        Path file;
        String override = System.getProperty("minemetal.nativeLibrary");
        if (override != null) {
            file = Path.of(override);
        } else {
            try (InputStream in = Native.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IOException("Missing " + RESOURCE + " in the MineMetal jar (was the native part built on macOS?)");
                }
                file = Files.createTempFile("libminemetal", ".dylib");
                file.toFile().deleteOnExit();
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        lib = SymbolLookup.libraryLookup(file, Arena.global());
        bind();
        loaded = true;
    }

    private static MethodHandle h(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd);
    }

    /** Short, non-blocking calls on the hot path: skip the thread-state transition. */
    private static MethodHandle hc(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd, Linker.Option.critical(false));
    }

    private static FunctionDescriptor v(MemoryLayout... args) {
        return FunctionDescriptor.ofVoid(args);
    }

    private static FunctionDescriptor f(MemoryLayout ret, MemoryLayout... args) {
        return FunctionDescriptor.of(ret, args);
    }

    private static MethodHandle ENUM, CREATE_CONTEXT, DESTROY_CONTEXT, CONTEXT_INFO, RELEASE, SET_LABEL, SUBMIT, CURRENT_SUBMIT_INDEX,
        WAIT, COMPLETED_INDEX, WAIT_IDLE, BUFFER_CREATE, BUFFER_CONTENTS, TEXTURE_CREATE, TEXTURE_VIEW, SAMPLER_CREATE, COPY_BUFFER,
        COPY_BUFFER_TO_TEXTURE, COPY_TEXTURE_TO_BUFFER, COPY_TEXTURE_TO_TEXTURE, CLEAR_COLOR_TEXTURE, CLEAR_DEPTH_TEXTURE, CLEAR_REGION,
        PASS_BEGIN, PASS_END, PASS_PUSH_DEBUG, PASS_POP_DEBUG, PASS_SET_SCISSOR, PASS_SET_PIPELINE, PASS_SET_VERTEX_BUFFER, PASS_SET_BUFFER,
        PASS_SET_BYTES, PASS_SET_TEXTURE, PASS_SET_SAMPLER, PASS_SET_TEXEL_BUFFER, PASS_SET_INDEX_BUFFER, PASS_DRAW, PASS_DRAW_INDEXED,
        PASS_MULTI_DRAW, PASS_MULTI_DRAW_INDEXED, PASS_DRAW_INDIRECT, PASS_DRAW_INDEXED_INDIRECT, PIPELINE_CREATE, SURFACE_CREATE,
        SURFACE_CONFIGURE, SURFACE_ACQUIRE, SURFACE_BLIT, SURFACE_DROP, SET_FAST_MATH, SET_COALESCE, SET_NEXT_PASS_DONT_CARE, SET_GPU_DEBUG, SET_DEPTH_FLIP, PROF_ENABLE, PROF_SET_TAG, PROF_READ, SURFACE_STATS, PASS_SET_VIEWPORT, GENERATE_MIPMAPS, SAMPLER_CREATE_EX,
        COMPUTE_PIPELINE_CREATE, COMPUTE_MAX_THREADS, COMPUTE_SET_PIPELINE, COMPUTE_SET_BUFFER, COMPUTE_SET_BYTES, COMPUTE_SET_TEXTURE, COMPUTE_SET_SAMPLER, COMPUTE_DISPATCH, COMPUTE_DISPATCH_INDIRECT, MEMORY_BARRIER, PASS_MARK_STORAGE, FILL_BUFFER, TEXTURE_ZERO, COPY_TEXTURE_ALL, UPSCALE, UPSCALE_CAPTURE_DEPTH, UPSCALE_TEMPORAL;

    private static void bind() {
        ENUM = h("mm_enum", f(JAVA_LONG, P, P));
        CREATE_CONTEXT = h("mm_create_context", f(P, P, JAVA_INT));
        DESTROY_CONTEXT = h("mm_destroy_context", v(P));
        CONTEXT_INFO = h("mm_context_info", v(P, P, JAVA_INT, P));
        RELEASE = h("mm_release", v(P));
        SET_LABEL = h("mm_set_label", v(P, P));
        SUBMIT = h("mm_submit", f(JAVA_LONG, P));
        CURRENT_SUBMIT_INDEX = hc("mm_current_submit_index", f(JAVA_LONG, P));
        WAIT = h("mm_wait", f(JAVA_INT, P, JAVA_LONG, JAVA_LONG));
        COMPLETED_INDEX = h("mm_completed_index", f(JAVA_LONG, P));
        WAIT_IDLE = h("mm_wait_idle", v(P));
        BUFFER_CREATE = h("mm_buffer_create", f(P, P, JAVA_LONG));
        BUFFER_CONTENTS = hc("mm_buffer_contents", f(P, P));
        TEXTURE_CREATE = h("mm_texture_create", f(P, P, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        TEXTURE_VIEW = h("mm_texture_view", f(P, P, JAVA_INT, JAVA_INT));
        SAMPLER_CREATE = h("mm_sampler_create", f(P, P, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT));
        COPY_BUFFER = h("mm_copy_buffer", v(P, P, JAVA_LONG, P, JAVA_LONG, JAVA_LONG));
        COPY_BUFFER_TO_TEXTURE = h("mm_copy_buffer_to_texture",
            v(P, P, JAVA_LONG, JAVA_LONG, JAVA_LONG, P, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        COPY_TEXTURE_TO_BUFFER = h("mm_copy_texture_to_buffer",
            v(P, P, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, P, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        COPY_TEXTURE_TO_TEXTURE = h("mm_copy_texture_to_texture",
            v(P, P, P, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        CLEAR_COLOR_TEXTURE = h("mm_clear_color_texture", v(P, P, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        CLEAR_DEPTH_TEXTURE = h("mm_clear_depth_texture", v(P, P, JAVA_DOUBLE));
        CLEAR_REGION = h("mm_clear_region", v(P, P, P, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_DOUBLE,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        PASS_BEGIN = h("mm_pass_begin", f(JAVA_INT, P, P, JAVA_INT, JAVA_INT, P, P, JAVA_DOUBLE, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, P));
        PASS_END = h("mm_pass_end", v(P));
        PASS_PUSH_DEBUG = hc("mm_pass_push_debug", v(P, P));
        PASS_POP_DEBUG = hc("mm_pass_pop_debug", v(P));
        PASS_SET_SCISSOR = hc("mm_pass_set_scissor", v(P, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        PASS_SET_PIPELINE = hc("mm_pass_set_pipeline", f(JAVA_INT, P, P));
        PASS_SET_VERTEX_BUFFER = hc("mm_pass_set_vertex_buffer", v(P, P, JAVA_LONG, JAVA_INT));
        PASS_SET_BUFFER = hc("mm_pass_set_buffer", v(P, JAVA_INT, P, JAVA_LONG, JAVA_INT));
        PASS_SET_BYTES = hc("mm_pass_set_bytes", v(P, JAVA_INT, P, JAVA_LONG, JAVA_INT));
        PASS_SET_TEXTURE = hc("mm_pass_set_texture", v(P, JAVA_INT, P, JAVA_INT));
        PASS_SET_SAMPLER = hc("mm_pass_set_sampler", v(P, JAVA_INT, P, JAVA_INT));
        PASS_SET_TEXEL_BUFFER = hc("mm_pass_set_texel_buffer", v(P, JAVA_INT, P, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT));
        PASS_SET_INDEX_BUFFER = hc("mm_pass_set_index_buffer", v(P, P, JAVA_INT));
        PASS_DRAW = hc("mm_pass_draw", v(P, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        PASS_DRAW_INDEXED = hc("mm_pass_draw_indexed", v(P, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        PASS_MULTI_DRAW = hc("mm_pass_multi_draw", v(P, P, JAVA_INT, JAVA_INT, JAVA_INT));
        PASS_MULTI_DRAW_INDEXED = hc("mm_pass_multi_draw_indexed", v(P, P, JAVA_INT, JAVA_INT, JAVA_INT));
        PASS_DRAW_INDIRECT = hc("mm_pass_draw_indirect", v(P, P, JAVA_LONG, JAVA_INT));
        PASS_DRAW_INDEXED_INDIRECT = hc("mm_pass_draw_indexed_indirect", v(P, P, JAVA_LONG, JAVA_INT));
        PIPELINE_CREATE = h("mm_pipeline_create",
            f(P, P, P, P, P, P, P, P, JAVA_INT, P, JAVA_INT, P, JAVA_INT, P, P, JAVA_INT, P, JAVA_INT));
        SURFACE_CREATE = h("mm_surface_create", f(P, P, P));
        SURFACE_CONFIGURE = h("mm_surface_configure", v(P, JAVA_INT, JAVA_INT, JAVA_INT));
        SURFACE_ACQUIRE = h("mm_surface_acquire", f(JAVA_INT, P));
        SURFACE_BLIT = h("mm_surface_blit", v(P, P, P, JAVA_INT, JAVA_INT));
        SURFACE_DROP = h("mm_surface_drop", v(P));
        SET_FAST_MATH = h("mm_set_fast_math", v(JAVA_INT));
        SET_COALESCE = h("mm_set_coalesce", v(JAVA_INT));
        SET_NEXT_PASS_DONT_CARE = hc("mm_set_next_pass_dont_care", v(P, JAVA_INT));
        SET_GPU_DEBUG = h("mm_set_gpu_debug", v(P, JAVA_INT));
        SET_DEPTH_FLIP = hc("mm_set_depth_flip", v(P, JAVA_INT));
        PROF_ENABLE = h("mm_prof_enable", f(JAVA_INT, P, JAVA_INT));
        PROF_SET_TAG = hc("mm_prof_set_tag", v(P, JAVA_INT));
        PROF_READ = h("mm_prof_read", v(P, P));
        SURFACE_STATS = h("mm_surface_stats", v(P, P));
        PASS_SET_VIEWPORT = hc("mm_pass_set_viewport", v(P, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
        GENERATE_MIPMAPS = h("mm_generate_mipmaps", v(P, P));
        COMPUTE_PIPELINE_CREATE = h("mm_compute_pipeline_create", f(P, P, P, P, P, P, JAVA_INT));
        COMPUTE_MAX_THREADS = hc("mm_compute_max_threads", f(JAVA_INT, P));
        COMPUTE_SET_PIPELINE = hc("mm_compute_set_pipeline", v(P, P));
        COMPUTE_SET_BUFFER = hc("mm_compute_set_buffer", v(P, P, JAVA_LONG, JAVA_INT));
        COMPUTE_SET_BYTES = hc("mm_compute_set_bytes", v(P, P, JAVA_LONG, JAVA_INT));
        COMPUTE_SET_TEXTURE = hc("mm_compute_set_texture", v(P, P, JAVA_INT));
        COMPUTE_SET_SAMPLER = hc("mm_compute_set_sampler", v(P, P, JAVA_INT));
        COMPUTE_DISPATCH = hc("mm_compute_dispatch", v(P, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        COMPUTE_DISPATCH_INDIRECT = hc("mm_compute_dispatch_indirect", v(P, P, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));
        MEMORY_BARRIER = hc("mm_memory_barrier", v(P));
        PASS_MARK_STORAGE = hc("mm_pass_mark_storage", v(P));
        FILL_BUFFER = h("mm_fill_buffer", v(P, P, JAVA_LONG, JAVA_LONG, JAVA_INT));
        TEXTURE_ZERO = h("mm_texture_zero", v(P, P, JAVA_INT, JAVA_INT));
        COPY_TEXTURE_ALL = h("mm_copy_texture_all", v(P, P, P));
        UPSCALE = h("mm_upscale", f(JAVA_INT, P, P, P, JAVA_INT, JAVA_FLOAT));
        UPSCALE_CAPTURE_DEPTH = h("mm_upscale_capture_depth", v(P, P));
        UPSCALE_TEMPORAL = h("mm_upscale_temporal", f(JAVA_INT, P, P, P, P, JAVA_INT, JAVA_FLOAT));
        SAMPLER_CREATE_EX = h("mm_sampler_create_ex",
            f(P, P, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_LONG));
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    // --- context -----------------------------------------------------------------------------------------------------

    public static long enumValue(long kind, long name) {
        try { return (long) ENUM.invokeExact(kind, name); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long createContext(long err, int errLen) {
        try { return (long) CREATE_CONTEXT.invokeExact(err, errLen); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void destroyContext(long ctx) {
        try { DESTROY_CONTEXT.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void contextInfo(long ctx, long name, int nameLen, long out) {
        try { CONTEXT_INFO.invokeExact(ctx, name, nameLen, out); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void release(long obj) {
        if (obj == 0L) {
            return;
        }
        try { RELEASE.invokeExact(obj); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void setLabel(long obj, long label) {
        try { SET_LABEL.invokeExact(obj, label); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long submit(long ctx) {
        try { return (long) SUBMIT.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long currentSubmitIndex(long ctx) {
        try { return (long) CURRENT_SUBMIT_INDEX.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static boolean await(long ctx, long index, long timeoutNs) {
        try { return (int) WAIT.invokeExact(ctx, index, timeoutNs) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    public static long completedIndex(long ctx) {
        try { return (long) COMPLETED_INDEX.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void waitIdle(long ctx) {
        try { WAIT_IDLE.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    // --- resources ---------------------------------------------------------------------------------------------------

    public static long bufferCreate(long ctx, long size) {
        try { return (long) BUFFER_CREATE.invokeExact(ctx, size); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long bufferContents(long buffer) {
        try { return (long) BUFFER_CONTENTS.invokeExact(buffer); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long textureCreate(long ctx, long pixelFormat, int width, int height, int layers, int mips, int usage) {
        try {
            return (long) TEXTURE_CREATE.invokeExact(ctx, pixelFormat, width, height, layers, mips, usage);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long textureView(long texture, int baseMip, int mips) {
        try { return (long) TEXTURE_VIEW.invokeExact(texture, baseMip, mips); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long samplerCreate(long ctx, boolean repeatU, boolean repeatV, boolean minLinear, boolean magLinear, boolean mipLinear,
                                     int maxAnisotropy, float lodMax) {
        try {
            return (long) SAMPLER_CREATE.invokeExact(ctx, b(repeatU), b(repeatV), b(minLinear), b(magLinear), b(mipLinear), maxAnisotropy, lodMax);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --- copies / clears ---------------------------------------------------------------------------------------------

    public static void copyBuffer(long ctx, long src, long srcOffset, long dst, long dstOffset, long size) {
        try { COPY_BUFFER.invokeExact(ctx, src, srcOffset, dst, dstOffset, size); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void copyBufferToTexture(long ctx, long buffer, long offset, long bytesPerRow, long bytesPerImage, long texture,
                                           int slice, int mip, int x, int y, int w, int h) {
        try {
            COPY_BUFFER_TO_TEXTURE.invokeExact(ctx, buffer, offset, bytesPerRow, bytesPerImage, texture, slice, mip, x, y, w, h);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void copyTextureToBuffer(long ctx, long texture, int slice, int mip, int x, int y, int w, int h, long buffer, long offset,
                                           long bytesPerRow, long bytesPerImage) {
        try {
            COPY_TEXTURE_TO_BUFFER.invokeExact(ctx, texture, slice, mip, x, y, w, h, buffer, offset, bytesPerRow, bytesPerImage);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void copyTextureToTexture(long ctx, long src, long dst, int mip, int srcX, int srcY, int dstX, int dstY, int w, int h) {
        try {
            COPY_TEXTURE_TO_TEXTURE.invokeExact(ctx, src, dst, mip, srcX, srcY, dstX, dstY, w, h);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void clearColorTexture(long ctx, long texture, float r, float g, float b, float a) {
        try { CLEAR_COLOR_TEXTURE.invokeExact(ctx, texture, r, g, b, a); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void clearDepthTexture(long ctx, long texture, double depth) {
        try { CLEAR_DEPTH_TEXTURE.invokeExact(ctx, texture, depth); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void clearRegion(long ctx, long color, long depth, int mip, float r, float g, float b, float a, double clearDepth,
                                   int x, int y, int w, int h) {
        try {
            CLEAR_REGION.invokeExact(ctx, color, depth, mip, r, g, b, a, clearDepth, x, y, w, h);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --- render pass -------------------------------------------------------------------------------------------------

    public static boolean passBegin(long ctx, long colorViews, int count, int clearMask, long clearColors, long depthView,
                                    double clearDepth, int x, int y, int w, int h, long label) {
        try {
            return (int) PASS_BEGIN.invokeExact(ctx, colorViews, count, clearMask, clearColors, depthView, clearDepth, x, y, w, h, label) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passEnd(long ctx) {
        try { PASS_END.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passPushDebug(long ctx, long label) {
        try { PASS_PUSH_DEBUG.invokeExact(ctx, label); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passPopDebug(long ctx) {
        try { PASS_POP_DEBUG.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetScissor(long ctx, int x, int y, int w, int h) {
        try { PASS_SET_SCISSOR.invokeExact(ctx, x, y, w, h); } catch (Throwable t) { throw rethrow(t); }
    }

    public static boolean passSetPipeline(long ctx, long pipeline) {
        try { return (int) PASS_SET_PIPELINE.invokeExact(ctx, pipeline) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetVertexBuffer(long ctx, long buffer, long offset, int index) {
        try { PASS_SET_VERTEX_BUFFER.invokeExact(ctx, buffer, offset, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetBuffer(long ctx, int stages, long buffer, long offset, int index) {
        try { PASS_SET_BUFFER.invokeExact(ctx, stages, buffer, offset, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetBytes(long ctx, int stages, long data, long length, int index) {
        try { PASS_SET_BYTES.invokeExact(ctx, stages, data, length, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetTexture(long ctx, int stages, long texture, int index) {
        try { PASS_SET_TEXTURE.invokeExact(ctx, stages, texture, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetSampler(long ctx, int stages, long sampler, int index) {
        try { PASS_SET_SAMPLER.invokeExact(ctx, stages, sampler, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetTexelBuffer(long ctx, int stages, long buffer, long offset, long length, long pixelFormat, int bytesPerTexel,
                                          int index) {
        try {
            PASS_SET_TEXEL_BUFFER.invokeExact(ctx, stages, buffer, offset, length, pixelFormat, bytesPerTexel, index);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passSetIndexBuffer(long ctx, long buffer, boolean is32) {
        try { PASS_SET_INDEX_BUFFER.invokeExact(ctx, buffer, b(is32)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passDraw(long ctx, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        try { PASS_DRAW.invokeExact(ctx, vertexCount, instanceCount, firstVertex, firstInstance); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passDrawIndexed(long ctx, int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        try {
            PASS_DRAW_INDEXED.invokeExact(ctx, indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passMultiDraw(long ctx, long params, int instanceCount, int firstInstance, int drawCount) {
        try { PASS_MULTI_DRAW.invokeExact(ctx, params, instanceCount, firstInstance, drawCount); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passMultiDrawIndexed(long ctx, long params, int instanceCount, int firstInstance, int drawCount) {
        try {
            PASS_MULTI_DRAW_INDEXED.invokeExact(ctx, params, instanceCount, firstInstance, drawCount);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passDrawIndirect(long ctx, long buffer, long offset, int drawCount) {
        try { PASS_DRAW_INDIRECT.invokeExact(ctx, buffer, offset, drawCount); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passDrawIndexedIndirect(long ctx, long buffer, long offset, int drawCount) {
        try { PASS_DRAW_INDEXED_INDIRECT.invokeExact(ctx, buffer, offset, drawCount); } catch (Throwable t) { throw rethrow(t); }
    }

    // --- pipelines ---------------------------------------------------------------------------------------------------

    public static long pipelineCreate(long ctx, long name, long vs, long vsEntry, long fs, long fsEntry, long layouts, int layoutCount,
                                      long attribs, int attribCount, long targets, int targetCount, long raster, long bias,
                                      boolean eagerDepthVariant, long err, int errLen) {
        try {
            return (long) PIPELINE_CREATE.invokeExact(ctx, name, vs, vsEntry, fs, fsEntry, layouts, layoutCount, attribs, attribCount, targets,
                targetCount, raster, bias, b(eagerDepthVariant), err, errLen);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --- surface -----------------------------------------------------------------------------------------------------

    public static long surfaceCreate(long ctx, long layer) {
        try { return (long) SURFACE_CREATE.invokeExact(ctx, layer); } catch (Throwable t) { throw rethrow(t); }
    }

    public static final int PRESENT_FIFO = 0;
    public static final int PRESENT_PACED = 1;
    public static final int PRESENT_IMMEDIATE = 2;

    public static void surfaceConfigure(long surface, int width, int height, int presentMode) {
        try { SURFACE_CONFIGURE.invokeExact(surface, width, height, presentMode); } catch (Throwable t) { throw rethrow(t); }
    }

    public static boolean surfaceAcquire(long surface) {
        try { return (int) SURFACE_ACQUIRE.invokeExact(surface) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    public static void surfaceBlit(long ctx, long surface, long srcView, int copyWidth, int copyHeight) {
        try { SURFACE_BLIT.invokeExact(ctx, surface, srcView, copyWidth, copyHeight); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void surfaceDrop(long surface) {
        try { SURFACE_DROP.invokeExact(surface); } catch (Throwable t) { throw rethrow(t); }
    }

    /** Color attachments (bit mask) the next pass begin does not need to load (they will be fully overwritten). */
    public static void setNextPassDontCare(long ctx, int mask) {
        try { SET_NEXT_PASS_DONT_CARE.invokeExact(ctx, mask); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void setCoalesce(boolean enabled) {
        try { SET_COALESCE.invokeExact(b(enabled)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void setFastMath(boolean enabled) {
        try { SET_FAST_MATH.invokeExact(b(enabled)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void setGpuDebug(long ctx, boolean enabled) {
        try { SET_GPU_DEBUG.invokeExact(ctx, b(enabled)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void setDepthFlip(long ctx, boolean flip) {
        try { SET_DEPTH_FLIP.invokeExact(ctx, b(flip)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static int profEnable(long ctx, boolean forceSplit) {
        try { return (int) PROF_ENABLE.invokeExact(ctx, forceSplit ? 1 : 0); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void profSetTag(long ctx, int tag) {
        try { PROF_SET_TAG.invokeExact(ctx, tag); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void profRead(long ctx, long out) {
        try { PROF_READ.invokeExact(ctx, out); } catch (Throwable t) { throw rethrow(t); }
    }

    // --- stage 4: compute / storage ---

    /** MSL kernel → compute pipeline (0 on failure, message in err). */
    public static long computePipelineCreate(long ctx, long name, long src, long entry, long err, int errLen) {
        try { return (long) COMPUTE_PIPELINE_CREATE.invokeExact(ctx, name, src, entry, err, errLen); } catch (Throwable t) { throw rethrow(t); }
    }

    public static int computeMaxThreads(long pso) {
        try { return (int) COMPUTE_MAX_THREADS.invokeExact(pso); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void computeSetPipeline(long ctx, long pso) {
        try { COMPUTE_SET_PIPELINE.invokeExact(ctx, pso); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void computeSetBuffer(long ctx, long buffer, long offset, int index) {
        try { COMPUTE_SET_BUFFER.invokeExact(ctx, buffer, offset, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void computeSetBytes(long ctx, long data, long length, int index) {
        try { COMPUTE_SET_BYTES.invokeExact(ctx, data, length, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void computeSetTexture(long ctx, long texture, int index) {
        try { COMPUTE_SET_TEXTURE.invokeExact(ctx, texture, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void computeSetSampler(long ctx, long sampler, int index) {
        try { COMPUTE_SET_SAMPLER.invokeExact(ctx, sampler, index); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void computeDispatch(long ctx, int gx, int gy, int gz, int tx, int ty, int tz) {
        try { COMPUTE_DISPATCH.invokeExact(ctx, gx, gy, gz, tx, ty, tz); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void computeDispatchIndirect(long ctx, long buffer, long offset, int tx, int ty, int tz) {
        try { COMPUTE_DISPATCH_INDIRECT.invokeExact(ctx, buffer, offset, tx, ty, tz); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void memoryBarrier(long ctx) {
        try { MEMORY_BARRIER.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passMarkStorage(long ctx) {
        try { PASS_MARK_STORAGE.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void fillBuffer(long ctx, long buffer, long offset, long size, int value) {
        try { FILL_BUFFER.invokeExact(ctx, buffer, offset, size, value); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void textureZero(long ctx, long texture, int level, int bytesPerPixel) {
        try { TEXTURE_ZERO.invokeExact(ctx, texture, level, bytesPerPixel); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void copyTextureAll(long ctx, long src, long dst) {
        try { COPY_TEXTURE_ALL.invokeExact(ctx, src, dst); } catch (Throwable t) { throw rethrow(t); }
    }

    /** Scales src up into dst (mode 1 bilinear, 2 FSR 1, 3 MetalFX spatial). Returns the mode actually used, or -1. */
    public static int upscale(long ctx, long src, long dst, int mode, float sharpness) {
        try { return (int) UPSCALE.invokeExact(ctx, src, dst, mode, sharpness); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void upscaleCaptureDepth(long ctx, long depth) {
        try { UPSCALE_CAPTURE_DEPTH.invokeExact(ctx, depth); } catch (Throwable t) { throw rethrow(t); }
    }

    /** MetalFX Temporal; params = 44 floats (see TemporalState). Returns 4, or -1 if it could not run (caller falls back). */
    public static int upscaleTemporal(long ctx, long color, long dst, long params, int reset, float sharpness) {
        try { return (int) UPSCALE_TEMPORAL.invokeExact(ctx, color, dst, params, reset, sharpness); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void surfaceStats(long surface, long out) {
        try { SURFACE_STATS.invokeExact(surface, out); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void passSetViewport(long ctx, double x, double y, double w, double h, double znear, double zfar) {
        try { PASS_SET_VIEWPORT.invokeExact(ctx, x, y, w, h, znear, zfar); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void generateMipmaps(long ctx, long texture) {
        try { GENERATE_MIPMAPS.invokeExact(ctx, texture); } catch (Throwable t) { throw rethrow(t); }
    }

    /** addr: 0 repeat, 1 clamp-to-edge, 2 mirrored repeat, 3 clamp-to-border; mipMode: 0 none, 1 nearest, 2 linear; compare: MTLCompareFunction or -1. */
    public static long samplerCreateEx(long ctx, int addrU, int addrV, boolean minLinear, boolean magLinear, int mipMode, int maxAnisotropy,
                                       float lodMin, float lodMax, long compareFunc) {
        try {
            return (long) SAMPLER_CREATE_EX.invokeExact(ctx, addrU, addrV, b(minLinear), b(magLinear), mipMode, maxAnisotropy, lodMin, lodMax, compareFunc);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static int b(boolean v) {
        return v ? 1 : 0;
    }
}
