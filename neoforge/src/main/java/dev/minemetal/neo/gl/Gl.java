package dev.minemetal.neo.gl;

import com.mojang.logging.LogUtils;
import dev.minemetal.neo.gl.compile.GlProgramCompiler;
import dev.minemetal.neo.gl.compile.ShaderCompileException;
import dev.minemetal.neo.metal.NeoNative;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenGL 3.2-core-ish state machine on top of the Metal backend (milestone 2: what vanilla 1.21.1 uses).
 * <p>
 * Every draw opens a render pass on the current draw framebuffer (the native side keeps the encoder open while the
 * attachments do not change), builds/fetches a pipeline for the current program + vertex layout + fixed-function
 * state, binds uniforms (snapshot per draw), textures and buffers, and draws. Shaders are translated GLSL → SPIR-V →
 * MSL at link time; the Y flip in the vertex stage keeps GL's bottom-up row order in every texture.
 */
public final class Gl {
    private static final Logger LOG = LogUtils.getLogger();
    /** Verified on hardware: GL front faces (CCW) map to MTLWindingClockwise after the Y flip. MM_FLIPWINDING=1 inverts. */
    private static final boolean FLIP_WINDING = "1".equals(System.getenv("MM_FLIPWINDING"));
    // debug switches (environment variables pass through Gradle to the game)
    private static final boolean NO_CULL = "1".equals(System.getenv("MM_NOCULL"));
    private static final boolean NO_BLEND = "1".equals(System.getenv("MM_NOBLEND"));
    private static final boolean NO_DEPTH = "1".equals(System.getenv("MM_NODEPTH"));

    static long ctx;

    // pixel formats (MTLPixelFormat values, resolved at init)
    static long PF_RGBA8, PF_BGRA8, PF_R8, PF_RG8, PF_RGBA16F, PF_RGBA32F, PF_R16F, PF_RG16F, PF_R32F, PF_RG32F, PF_RG11B10F,
        PF_RGB10A2, PF_RGBA16, PF_R32UI, PF_D32;
    private static final Map<String, Long> VERTEX_FORMATS = new HashMap<>();

    // ---- objects ---------------------------------------------------------------------------------------------------

    static final class Buf {
        long mtl, size, contents, cap;
    }

    /**
     * Recycles MTLBuffers by power-of-two size class: a buffer handed back is reused once the GPU has finished the
     * command buffer that was recording when it was released. Creating MTLBuffers is the expensive part of glBufferData.
     */
    static final class BufferPool {
        private final Map<Long, ArrayDeque<Long>> free = new HashMap<>();
        private final ArrayDeque<long[]> pending = new ArrayDeque<>(); // {submitIndex, buffer, capacity}
        private long freeBytes;
        private static final long MAX_FREE_BYTES = 512L << 20;
        long created, reused;

        static long sizeClass(long size) {
            long s = Math.max(size, 256);
            return s > (64L << 20) ? (s + 4095) & ~4095L : Long.highestOneBit(s - 1) << 1;
        }

        long obtain(long cap) {
            ArrayDeque<Long> q = free.get(cap);
            if (q != null && !q.isEmpty()) {
                freeBytes -= cap;
                reused++;
                return q.pollLast();
            }
            created++;
            return NeoNative.bufferCreate(ctx, cap);
        }

        void release(long buf, long cap) {
            if (buf != 0L) pending.add(new long[]{NeoNative.currentSubmitIndex(ctx), buf, cap});
        }

        void collect(long completed) {
            while (!pending.isEmpty() && pending.peekFirst()[0] <= completed) {
                long[] e = pending.pollFirst();
                if (freeBytes + e[2] > MAX_FREE_BYTES) {
                    NeoNative.release(e[1]);
                } else {
                    free.computeIfAbsent(e[2], k -> new ArrayDeque<>()).add(e[1]);
                    freeBytes += e[2];
                }
            }
        }
    }

    static final BufferPool POOL = new BufferPool();
    /** Bumped on every state change that affects pipeline selection (see Program.memo*). */
    static int stateVersion;

    static long memoHits, memoMisses, hashHits, frames;

    private static long mix(long h, long v) {
        h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        return h;
    }

    /**
     * Allocation-free pipeline identity: vertex layout (per input: slot, stride, offset, type, size, flags) plus all
     * fixed-function state and attachment formats. Also fills the vertex slot plan (SLOT_BUFFERS / SLOT_STRIDES).
     */
    private static long stateHash(Program p, Vao va, int mode, int colorCount) {
        long h = mode;
        int slots = 0;
        java.util.Arrays.fill(SLOT_STRIDES, 0);
        for (int loc : p.inputLocs) {
            Attrib a = loc >= 0 && loc < 16 ? va.attribs[loc] : null;
            if (a == null || !a.enabled || BUFFERS.get(a.buffer) == null) {
                h = mix(h, -1);
                continue;
            }
            int stride = a.stride > 0 ? a.stride : a.size * typeBytes(a.type);
            int slot = -1;
            for (int s = 0; s < slots; s++) if (SLOT_BUFFERS[s] == a.buffer && SLOT_STRIDES[s] == stride) slot = s;
            if (slot < 0 && slots < 8) {
                slot = slots++;
                SLOT_BUFFERS[slot] = a.buffer;
                SLOT_STRIDES[slot] = stride;
            }
            h = mix(h, slot | (long) stride << 8 | a.offset << 24);
            h = mix(h, a.type | (long) a.size << 16 | (a.normalized ? 1L << 20 : 0) | (a.integer ? 1L << 21 : 0));
        }
        h = mix(h, blend ? blendSrcRgb | (long) blendDstRgb << 16 | (long) blendSrcA << 32 | (long) blendDstA << 48 : 7);
        h = mix(h, blend ? blendOpRgb | (long) blendOpA << 16 : 7);
        h = mix(h, colorMask | (depthTest ? (long) depthFunc << 8 | (depthMask ? 1L << 24 : 0) : 0) | (cullFace ? (long) cullMode << 32 : 0)
            | (long) frontFace << 48);
        h = mix(h, polygonOffset ? Float.floatToIntBits(offsetFactor) | (long) Float.floatToIntBits(offsetUnits) << 32 : 3);
        for (int i = 0; i < colorCount; i++) h = mix(h, A_FORMATS[i]);
        h = mix(h, A_DEPTH[0] != 0L ? 1 : 2);
        return h;
    }

    static void dirty() {
        stateVersion++;
    }

    static final class Tex {
        long mtl, pf;
        int w, h, mips = 1, bpp = 4;
        boolean depth;
        int minFilter = 0x2702, magFilter = 0x2601, wrapS = 0x2901, wrapT = 0x2901, maxLevel = 1000, compareMode, compareFunc = 0x203;
        float aniso = 1f;
        long sampler;
        final Int2ObjectOpenHashMap<Long> views = new Int2ObjectOpenHashMap<>();
    }

    static final class Fbo {
        final int[] color = new int[8];
        final int[] colorLevel = new int[8];
        int depth;
    }

    static final class Shader {
        int type;
        String src = "";
    }

    static final class Uniform {
        final String name;
        GlProgramCompiler.BlockMember vs, fs;
        int samplerIndex = -1;

        Uniform(String name) {
            this.name = name;
        }
    }

    static final class Program {
        final List<Integer> shaders = new ArrayList<>();
        final Map<String, Integer> attribBindings = new HashMap<>();
        GlProgramCompiler.Result result;
        String error;
        final List<Uniform> uniforms = new ArrayList<>();
        final Map<String, Integer> locations = new HashMap<>();
        ByteBuffer vsBlock, fsBlock;
        int[] samplerUnits = new int[0];
        final Map<String, Long> pipelines = new HashMap<>();
        final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap pipelinesByHash = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        // last pipeline used, valid while nothing that feeds the pipeline key changed
        int[] inputLocs = new int[0];
        int memoVersion = -1, memoVao = -1, memoMode = -1, memoFbo = -1, memoSlots;
        long memoPso;
        final int[] memoSlotBuffers = new int[8], memoSlotStrides = new int[8];
        boolean failedLogged;
    }

    static final class Attrib {
        boolean enabled, normalized, integer;
        int buffer, size = 4, type = 0x1406, stride;
        long offset;
    }

    static final class Vao {
        final Attrib[] attribs = new Attrib[16];
        int ebo;

        Vao() {
            for (int i = 0; i < 16; i++) attribs[i] = new Attrib();
        }
    }

    private static final Int2ObjectOpenHashMap<Buf> BUFFERS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<Tex> TEXTURES = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<Fbo> FBOS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<Tex> RENDERBUFFERS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<Shader> SHADERS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<Program> PROGRAMS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<Vao> VAOS = new Int2ObjectOpenHashMap<>();
    private static int nextName = 1;

    // ---- state -----------------------------------------------------------------------------------------------------

    static int activeUnit;
    static final int[] units = new int[32];
    static int arrayBuffer, currentProgram, vao, drawFbo, readFbo, renderbuffer;
    static final Map<Integer, Integer> otherBuffers = new HashMap<>();
    static final int[] viewport = new int[4];
    static final int[] scissor = new int[4];
    static boolean blend, depthTest, cullFace, scissorTest, polygonOffset;
    static int blendSrcRgb = 1, blendDstRgb = 0, blendSrcA = 1, blendDstA = 0, blendOpRgb = 0x8006, blendOpA = 0x8006;
    static int depthFunc = 0x201, cullMode = 0x405, frontFace = 0x901, colorMask = 15;
    static boolean depthMask = true;
    static float offsetFactor, offsetUnits;
    static final float[] clearColor = new float[4];
    static double clearDepth = 1.0;
    static int unpackRowLength, unpackSkipRows, unpackSkipPixels, unpackAlignment = 4;

    // default framebuffer
    static long backColor, backDepth;
    static int backW, backH;

    // misc
    private static long dummyTexture, zeroBuffer, defaultSampler;
    private static final Map<Long, Long> SAMPLERS = new HashMap<>();
    private static final ArrayDeque<long[]> RETIRED = new ArrayDeque<>();
    private static final Set<String> WARNED = new HashSet<>();
    static long draws, frameDraws;

    private Gl() {
    }

    private static void warnOnce(String key, String msg) {
        if (WARNED.add(key)) LOG.warn("[MineMetal GL] {}", msg);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    public static void init(long context) {
        ctx = context;
        NeoNative.setDepthFlip(ctx, false);
        PF_RGBA8 = NeoNative.enumValue("pixel", "RGBA8_UNORM");
        PF_BGRA8 = NeoNative.enumValue("pixel", "BGRA8_UNORM");
        PF_R8 = NeoNative.enumValue("pixel", "R8_UNORM");
        PF_RG8 = NeoNative.enumValue("pixel", "RG8_UNORM");
        PF_RGBA16F = NeoNative.enumValue("pixel", "RGBA16_FLOAT");
        PF_RGBA32F = NeoNative.enumValue("pixel", "RGBA32_FLOAT");
        PF_R16F = NeoNative.enumValue("pixel", "R16_FLOAT");
        PF_RG16F = NeoNative.enumValue("pixel", "RG16_FLOAT");
        PF_R32F = NeoNative.enumValue("pixel", "R32_FLOAT");
        PF_RG32F = NeoNative.enumValue("pixel", "RG32_FLOAT");
        PF_RG11B10F = NeoNative.enumValue("pixel", "RG11B10_FLOAT");
        PF_RGB10A2 = NeoNative.enumValue("pixel", "RGB10A2_UNORM");
        PF_RGBA16 = NeoNative.enumValue("pixel", "RGBA16_UNORM");
        PF_R32UI = NeoNative.enumValue("pixel", "R32_UINT");
        PF_D32 = NeoNative.enumValue("pixel", "D32_FLOAT");

        dummyTexture = NeoNative.textureCreate(ctx, PF_RGBA8, 1, 1, 1, 1, USAGE);
        long staging = NeoNative.bufferCreate(ctx, 16);
        MemoryUtil.memPutInt(NeoNative.bufferContents(staging), -1);
        NeoNative.copyBufferToTexture(ctx, staging, 0, 4, 4, dummyTexture, 0, 0, 0, 0, 1, 1);
        retire(staging);
        zeroBuffer = NeoNative.bufferCreate(ctx, 16);
        long z = NeoNative.bufferContents(zeroBuffer);
        MemoryUtil.memPutFloat(z, 0f);
        MemoryUtil.memPutFloat(z + 4, 0f);
        MemoryUtil.memPutFloat(z + 8, 0f);
        MemoryUtil.memPutFloat(z + 12, 1f);
        defaultSampler = NeoNative.samplerCreate(ctx, 1, 1, 0, 0, 0, 1, 0f, 1000f, -1);
        VAOS.put(0, new Vao());
        FBOS.put(0, new Fbo());
    }

    private static final int USAGE = 1 | 2 | 4 | 8; // copy dst/src, sampled, render target

    static void retire(long obj) {
        if (obj != 0L) RETIRED.add(new long[]{NeoNative.currentSubmitIndex(ctx), obj});
    }

    /** Default framebuffer = backbuffer textures, presented by {@link #present}. */
    public static void ensureBackbuffer(int w, int h) {
        if (w == backW && h == backH && backColor != 0L) return;
        retire(backColor);
        retire(backDepth);
        backW = w;
        backH = h;
        backColor = NeoNative.textureCreate(ctx, PF_RGBA8, w, h, 1, 1, USAGE);
        backDepth = NeoNative.textureCreate(ctx, PF_D32, w, h, 1, 1, USAGE);
    }

    public static void present(long surface, boolean blocking) {
        if (blocking) NeoNative.surfaceAcquire(surface);
        NeoNative.surfaceBlit(ctx, surface, backColor, backW, backH);
        NeoNative.submit(ctx);
        long done = NeoNative.completedIndex(ctx);
        while (!RETIRED.isEmpty() && RETIRED.peekFirst()[0] <= done) NeoNative.release(RETIRED.pollFirst()[1]);
        POOL.collect(done);
        if (++frames % 1200 == 0) {
            LOG.info("[MineMetal GL] frame {}: {} draws/frame, pipeline memo {}% / hash {}% hits, buffers created {} / reused {}", frames,
                frameDraws, memoHits * 100 / Math.max(1, memoHits + memoMisses), hashHits * 100 / Math.max(1, memoMisses), POOL.created, POOL.reused);
            memoHits = memoMisses = hashHits = 0;
        }
        frameDraws = 0;
    }

    static int gen() {
        return nextName++;
    }

    // ---- buffers ---------------------------------------------------------------------------------------------------

    static int boundBuffer(int target) {
        return switch (target) {
            case 0x8892 -> arrayBuffer;
            case 0x8893 -> VAOS.get(vao).ebo;
            default -> otherBuffers.getOrDefault(target, 0);
        };
    }

    static void bindBuffer(int target, int buffer) {
        switch (target) {
            case 0x8892 -> arrayBuffer = buffer;
            case 0x8893 -> VAOS.get(vao).ebo = buffer;
            default -> otherBuffers.put(target, buffer);
        }
        if (buffer != 0 && !BUFFERS.containsKey(buffer)) {
            BUFFERS.put(buffer, new Buf());
            dirty();
        }
    }

    static Buf buffer(int target) {
        int id = boundBuffer(target);
        return id == 0 ? null : BUFFERS.computeIfAbsent(id, k -> new Buf());
    }

    static void bufferData(int target, long size, long data) {
        Buf b = buffer(target);
        if (b == null) return;
        b.size = Math.max(size, 0);
        long cap = BufferPool.sizeClass(b.size);
        POOL.release(b.mtl, b.cap);
        b.mtl = POOL.obtain(cap);
        b.cap = cap;
        b.contents = NeoNative.bufferContents(b.mtl);
        if (data != 0L && size > 0) MemoryUtil.memCopy(data, b.contents, size);
    }

    static void bufferSubData(int target, long offset, long size, long data) {
        Buf b = buffer(target);
        if (b == null || b.contents == 0L || data == 0L || offset + size > b.size) return;
        MemoryUtil.memCopy(data, b.contents + offset, size);
    }

    static long mapBuffer(int target, long offset) {
        Buf b = buffer(target);
        if (b == null) return 0L;
        if (b.mtl == 0L) bufferData(target, Math.max(b.size, 16), 0L);
        return b.contents + offset;
    }

    static long bufferSize(int target) {
        Buf b = buffer(target);
        return b == null ? 0 : b.size;
    }

    static void deleteBuffer(int id) {
        Buf b = BUFFERS.remove(id);
        if (b != null) POOL.release(b.mtl, b.cap);
        if (arrayBuffer == id) arrayBuffer = 0;
    }

    // ---- textures --------------------------------------------------------------------------------------------------

    static Tex boundTexture() {
        int id = units[activeUnit];
        return id == 0 ? null : TEXTURES.computeIfAbsent(id, k -> new Tex());
    }

    static void bindTexture(int target, int id) {
        units[activeUnit] = id;
        if (id != 0) TEXTURES.computeIfAbsent(id, k -> new Tex());
    }

    static void deleteTexture(int id) {
        Tex t = TEXTURES.remove(id);
        if (t != null) freeTexture(t);
        for (int i = 0; i < units.length; i++) if (units[i] == id) units[i] = 0;
    }

    private static void freeTexture(Tex t) {
        retire(t.mtl);
        for (long v : t.views.values()) retire(v);
        t.views.clear();
        t.mtl = 0L;
    }

    /** internal format → {pixel format, bytes per pixel, depth?1:0} */
    static long[] internalFormat(int ifmt) {
        return switch (ifmt) {
            case 0x1903, 0x8229 -> new long[]{PF_R8, 1, 0};                      // RED, R8
            case 0x8227, 0x822B -> new long[]{PF_RG8, 2, 0};                     // RG, RG8
            case 0x881A -> new long[]{PF_RGBA16F, 8, 0};
            case 0x8814 -> new long[]{PF_RGBA32F, 16, 0};
            case 0x822D -> new long[]{PF_R16F, 2, 0};
            case 0x822F -> new long[]{PF_RG16F, 4, 0};
            case 0x822E -> new long[]{PF_R32F, 4, 0};
            case 0x8230 -> new long[]{PF_RG32F, 8, 0};
            case 0x8C3A -> new long[]{PF_RG11B10F, 4, 0};
            case 0x8059 -> new long[]{PF_RGB10A2, 4, 0};
            case 0x805B -> new long[]{PF_RGBA16, 8, 0};
            case 0x8236 -> new long[]{PF_R32UI, 4, 0};
            case 0x1902, 0x81A5, 0x81A6, 0x81A7, 0x8CAC, 0x84F9, 0x88F0, 0x8CAD -> new long[]{PF_D32, 4, 1};
            default -> new long[]{PF_RGBA8, 4, 0};                                // RGBA, RGBA8, RGB, RGB8, SRGB...
        };
    }

    static void allocate(Tex t, int ifmt, int w, int h, int mips) {
        dirty();
        long[] f = internalFormat(ifmt);
        freeTexture(t);
        t.pf = f[0];
        t.bpp = (int) f[1];
        t.depth = f[2] != 0;
        t.w = Math.max(w, 1);
        t.h = Math.max(h, 1);
        int full = 32 - Integer.numberOfLeadingZeros(Math.max(t.w, t.h));
        t.mips = Math.max(1, Math.min(mips, full));
        t.mtl = NeoNative.textureCreate(ctx, t.pf, t.w, t.h, 1, t.mips, USAGE);
        t.sampler = 0L;
    }

    static void texImage2D(int target, int level, int ifmt, int w, int h, int format, int type, long pixels) {
        if (target == 0x8064) return; // GL_PROXY_TEXTURE_2D
        Tex t = boundTexture();
        if (t == null) return;
        if (level == 0) {
            int mips = t.maxLevel < 1000 ? t.maxLevel + 1 : 1;
            if (t.mtl == 0L || t.w != w || t.h != h || t.pf != internalFormat(ifmt)[0] || t.mips < mips) allocate(t, ifmt, w, h, mips);
        } else if (t.mtl == 0L || level >= t.mips) {
            allocate(t, ifmt, Math.max(w << level, 1), Math.max(h << level, 1), level + 1);
        }
        if (pixels != 0L) upload(t, level, 0, 0, w, h, format, type, pixels);
        if (texLogs++ < 0) {
            LOG.info("[MineMetal GL] texImage2D tex={} level={} ifmt=0x{} {}x{} fmt=0x{} type=0x{} data={} -> mips={} pf={}", units[activeUnit],
                level, Integer.toHexString(ifmt), w, h, Integer.toHexString(format), Integer.toHexString(type), pixels != 0L, t.mips, t.pf);
        }
    }

    private static int texLogs;

    static void texSubImage2D(int target, int level, int x, int y, int w, int h, int format, int type, long pixels) {
        Tex t = boundTexture();
        if (t == null || t.mtl == 0L || pixels == 0L || level >= t.mips) return;
        upload(t, level, x, y, w, h, format, type, pixels);
    }

    private static int components(int format) {
        return switch (format) {
            case 0x1903, 0x1902, 0x8D94, 0x1901 -> 1; // RED, DEPTH, RED_INTEGER, STENCIL_INDEX
            case 0x8227, 0x8228 -> 2;                 // RG, RG_INTEGER
            case 0x1907, 0x80E0, 0x8D98 -> 3;         // RGB, BGR, RGB_INTEGER
            default -> 4;
        };
    }

    private static int typeSize(int type) {
        return switch (type) {
            case 0x1400, 0x1401 -> 1;
            case 0x1402, 0x1403, 0x140B -> 2;
            case 0x8035, 0x8367, 0x8368, 0x8C3B, 0x84FA -> -4; // packed: 4 bytes per pixel
            default -> 4;
        };
    }

    private static void upload(Tex t, int level, int x, int y, int w, int h, int format, int type, long pixels) {
        if (w <= 0 || h <= 0) return;
        int ts = typeSize(type);
        int comps = components(format);
        int srcBpp = ts < 0 ? -ts : ts * comps;
        int rowPixels = unpackRowLength > 0 ? unpackRowLength : w;
        int align = Math.max(unpackAlignment, 1);
        long srcRow = ((long) rowPixels * srcBpp + align - 1) / align * align;
        long src = pixels + unpackSkipRows * srcRow + (long) unpackSkipPixels * srcBpp;
        int dstBpp = t.bpp;
        long dstRow = (long) w * dstBpp;
        long stagingCap = BufferPool.sizeClass(dstRow * h);
        long staging = POOL.obtain(stagingCap);
        long dst = NeoNative.bufferContents(staging);
        boolean expandRgb = comps == 3 && ts == 1 && dstBpp == 4;
        boolean swapBgra = format == 0x80E1 && ts == 1 && dstBpp == 4;
        for (int row = 0; row < h; row++) {
            long s = src + row * srcRow;
            long d = dst + row * dstRow;
            if (expandRgb) {
                for (int i = 0; i < w; i++) {
                    MemoryUtil.memPutByte(d + i * 4L, MemoryUtil.memGetByte(s + i * 3L));
                    MemoryUtil.memPutByte(d + i * 4L + 1, MemoryUtil.memGetByte(s + i * 3L + 1));
                    MemoryUtil.memPutByte(d + i * 4L + 2, MemoryUtil.memGetByte(s + i * 3L + 2));
                    MemoryUtil.memPutByte(d + i * 4L + 3, (byte) -1);
                }
            } else if (swapBgra) {
                for (int i = 0; i < w; i++) {
                    int v = MemoryUtil.memGetInt(s + i * 4L);
                    MemoryUtil.memPutInt(d + i * 4L, (v & 0xFF00FF00) | ((v & 0xFF) << 16) | ((v >>> 16) & 0xFF));
                }
            } else {
                MemoryUtil.memCopy(s, d, Math.min(dstRow, (long) w * srcBpp));
            }
        }
        NeoNative.copyBufferToTexture(ctx, staging, 0, dstRow, dstRow * h, t.mtl, 0, level, x, y, w, h);
        POOL.release(staging, stagingCap);
    }

    static void texParameter(int target, int pname, int value) {
        Tex t = boundTexture();
        if (t == null) return;
        switch (pname) {
            case 0x2801 -> t.minFilter = value;
            case 0x2800 -> t.magFilter = value;
            case 0x2802 -> t.wrapS = value;
            case 0x2803 -> t.wrapT = value;
            case 0x813D -> t.maxLevel = value;
            case 0x884C -> t.compareMode = value;
            case 0x884D -> t.compareFunc = value;
            default -> {
                return;
            }
        }
        t.sampler = 0L;
    }

    static void texParameterf(int target, int pname, float value) {
        Tex t = boundTexture();
        if (t == null) return;
        if (pname == 0x84FE) {
            t.aniso = value;
            t.sampler = 0L;
        } else {
            texParameter(target, pname, (int) value);
        }
    }

    static int texLevelParameter(int level, int pname) {
        Tex t = boundTexture();
        if (t == null || t.mtl == 0L) return 0;
        return switch (pname) {
            case 0x1000 -> Math.max(t.w >> level, 1);
            case 0x1001 -> Math.max(t.h >> level, 1);
            default -> 0;
        };
    }

    static void generateMipmap() {
        Tex t = boundTexture();
        if (t != null && t.mtl != 0L && t.mips > 1) NeoNative.generateMipmaps(ctx, t.mtl);
    }

    private static int addr(int wrap) {
        return switch (wrap) {
            case 0x2901 -> 0;
            case 0x8370 -> 2;
            case 0x812D -> 3;
            default -> 1;
        };
    }

    static long sampler(Tex t) {
        if (t.sampler != 0L) return t.sampler;
        int min = t.minFilter;
        boolean minLinear = min == 0x2601 || min == 0x2701 || min == 0x2703;
        int mip = t.mips <= 1 ? 0 : (min == 0x2700 || min == 0x2701) ? 1 : (min == 0x2702 || min == 0x2703) ? 2 : 0;
        int aniso = Math.max(1, Math.min(16, (int) t.aniso));
        long compare = t.compareMode == 0x884E ? t.compareFunc - 0x200 : -1;
        long key = addr(t.wrapS) | (long) addr(t.wrapT) << 2 | (minLinear ? 1L : 0) << 4 | (t.magFilter == 0x2601 ? 1L : 0) << 5
            | (long) mip << 6 | (long) aniso << 8 | (long) Math.min(t.maxLevel, 1000) << 16 | (compare + 1) << 32;
        Long s = SAMPLERS.get(key);
        if (s == null) {
            s = NeoNative.samplerCreate(ctx, addr(t.wrapS), addr(t.wrapT), minLinear ? 1 : 0, t.magFilter == 0x2601 ? 1 : 0, mip, aniso,
                0f, Math.min(t.maxLevel, 1000), compare);
            SAMPLERS.put(key, s);
        }
        t.sampler = s;
        return s;
    }

    static long view(Tex t, int level) {
        if (level == 0 || t.mips <= 1) return t.mtl;
        Long v = t.views.get(level);
        if (v == null) {
            v = NeoNative.textureView(t.mtl, level, 1);
            t.views.put(level, v);
        }
        return v;
    }

    // ---- framebuffers ----------------------------------------------------------------------------------------------

    static void bindFramebuffer(int target, int id) {
        dirty();
        if (id != 0) FBOS.computeIfAbsent(id, k -> new Fbo());
        if (target == 0x8D40 || target == 0x8CA9) drawFbo = id;
        if (target == 0x8D40 || target == 0x8CA8) readFbo = id;
    }

    static Fbo fbo(int target) {
        int id = target == 0x8CA8 ? readFbo : drawFbo;
        return FBOS.get(id);
    }

    static void framebufferTexture(int target, int attachment, int texture, int level) {
        dirty();
        Fbo f = fbo(target);
        if (f == null) return;
        if (attachment == 0x8D00 || attachment == 0x821A) { // DEPTH, DEPTH_STENCIL
            f.depth = texture;
        } else if (attachment >= 0x8CE0 && attachment < 0x8CE8) {
            f.color[attachment - 0x8CE0] = texture;
            f.colorLevel[attachment - 0x8CE0] = level;
        }
    }

    static void framebufferRenderbuffer(int target, int attachment, int rb) {
        Tex t = RENDERBUFFERS.get(rb);
        if (t == null) return;
        int id = -rb; // renderbuffers live in the texture table under negative names
        TEXTURES.put(id, t);
        framebufferTexture(target, attachment, rb == 0 ? 0 : id, 0);
    }

    static void renderbufferStorage(int ifmt, int w, int h) {
        if (renderbuffer == 0) return;
        Tex t = RENDERBUFFERS.computeIfAbsent(renderbuffer, k -> new Tex());
        allocate(t, ifmt, w, h, 1);
    }

    static void bindRenderbuffer(int id) {
        renderbuffer = id;
    }

    static void deleteFramebuffer(int id) {
        FBOS.remove(id);
        if (drawFbo == id) drawFbo = 0;
        if (readFbo == id) readFbo = 0;
    }

    /** Attachments of a framebuffer: color views [0..count), depth view; returns count or -1 if unusable. */
    private static int attachments(int fboId, long[] colors, long[] formats, long[] depth) {
        if (fboId == 0) {
            java.util.Arrays.fill(colors, 0L);
            java.util.Arrays.fill(formats, 0L);
            colors[0] = backColor;
            formats[0] = PF_RGBA8;
            depth[0] = backDepth;
            return backColor == 0L ? -1 : 1;
        }
        Fbo f = FBOS.get(fboId);
        if (f == null) return -1;
        int count = 0;
        for (int i = 0; i < 8; i++) {
            Tex t = f.color[i] != 0 ? TEXTURES.get(f.color[i]) : null;
            if (t != null && t.mtl != 0L && !t.depth) {
                colors[i] = view(t, f.colorLevel[i]);
                formats[i] = t.pf;
                count = i + 1;
            } else {
                colors[i] = 0L;
                formats[i] = 0L;
            }
        }
        Tex d = f.depth != 0 ? TEXTURES.get(f.depth) : null;
        depth[0] = d != null && d.mtl != 0L && d.depth ? d.mtl : 0L;
        return count == 0 && depth[0] == 0L ? -1 : count;
    }

    private static final long[] A_COLORS = new long[8], A_FORMATS = new long[8], A_DEPTH = new long[1];

    private static boolean beginPass(int clearMask, MemoryStack stack) {
        int count = attachments(drawFbo, A_COLORS, A_FORMATS, A_DEPTH);
        if (count < 0) return false;
        long views = stack.nmalloc(8, 8 * 8);
        for (int i = 0; i < 8; i++) MemoryUtil.memPutLong(views + i * 8L, A_COLORS[i]);
        long colors = stack.nmalloc(4, 4 * 4 * 8);
        for (int i = 0; i < 8; i++) for (int c = 0; c < 4; c++) MemoryUtil.memPutFloat(colors + (i * 4L + c) * 4, clearColor[c]);
        int mask = 0;
        if ((clearMask & 0x4000) != 0) for (int i = 0; i < count; i++) if (A_COLORS[i] != 0L) mask |= 1 << i;
        if ((clearMask & 0x100) != 0 && A_DEPTH[0] != 0L) mask |= 1 << 31;
        if (clearMask != 0 && mask == 0) return false;
        int ax = 0, ay = 0, aw = 1 << 20, ah = 1 << 20;
        if (scissorTest) {
            ax = scissor[0];
            ay = scissor[1];
            aw = scissor[2];
            ah = scissor[3];
        }
        return NeoNative.passBegin(ctx, views, count, mask, colors, A_DEPTH[0], clearDepth, ax, ay, aw, ah);
    }

    static void clear(int mask) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (beginPass(mask & (0x4000 | 0x100), stack)) NeoNative.passEnd(ctx);
        }
    }

    static void blitFramebuffer(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask) {
        long[] sc = new long[8], sf = new long[8], sd = new long[1], dc = new long[8], df = new long[8], dd = new long[1];
        if (attachments(readFbo, sc, sf, sd) < 0 || attachments(drawFbo, dc, df, dd) < 0) return;
        int w = sx1 - sx0, h = sy1 - sy0;
        if (w != dx1 - dx0 || h != dy1 - dy0 || w <= 0 || h <= 0) {
            warnOnce("blitscale", "glBlitFramebuffer with scaling is not supported yet");
            return;
        }
        if ((mask & 0x4000) != 0 && sc[0] != 0L && dc[0] != 0L && sf[0] == df[0]) {
            NeoNative.copyTextureToTexture(ctx, sc[0], dc[0], 0, sx0, sy0, dx0, dy0, w, h);
        }
        if ((mask & 0x100) != 0 && sd[0] != 0L && dd[0] != 0L) {
            NeoNative.copyTextureToTexture(ctx, sd[0], dd[0], 0, sx0, sy0, dx0, dy0, w, h);
        }
    }

    // ---- shaders and programs --------------------------------------------------------------------------------------

    static int createShader(int type) {
        int id = gen();
        Shader s = new Shader();
        s.type = type;
        SHADERS.put(id, s);
        return id;
    }

    static void shaderSource(int shader, int count, long strings, long lengths) {
        Shader s = SHADERS.get(shader);
        if (s == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            long str = MemoryUtil.memGetAddress(strings + i * 8L);
            int len = lengths != 0L ? MemoryUtil.memGetInt(lengths + i * 4L) : -1;
            sb.append(len >= 0 ? MemoryUtil.memUTF8(str, len) : MemoryUtil.memUTF8(str));
        }
        s.src = sb.toString();
    }

    static int createProgram() {
        int id = gen();
        PROGRAMS.put(id, new Program());
        return id;
    }

    static void attachShader(int program, int shader) {
        Program p = PROGRAMS.get(program);
        if (p != null) p.shaders.add(shader);
    }

    static void bindAttribLocation(int program, int index, String name) {
        Program p = PROGRAMS.get(program);
        if (p != null) p.attribBindings.put(name, index);
    }

    static void deleteShader(int id) {
        SHADERS.remove(id);
    }

    static void deleteProgram(int id) {
        Program p = PROGRAMS.remove(id);
        if (p != null) for (long pl : p.pipelines.values()) retire(pl);
    }

    static void linkProgram(int program) {
        dirty();
        Program p = PROGRAMS.get(program);
        if (p == null) return;
        String vs = null, fs = null;
        for (int sh : p.shaders) {
            Shader s = SHADERS.get(sh);
            if (s == null) continue;
            if (s.type == 0x8B31) vs = s.src;
            else if (s.type == 0x8B30) fs = s.src;
        }
        for (long pl : p.pipelines.values()) retire(pl);
        p.pipelines.clear();
        p.uniforms.clear();
        p.locations.clear();
        p.result = null;
        p.error = null;
        if (vs == null || fs == null) {
            p.error = "missing vertex or fragment shader";
            return;
        }
        try {
            p.result = GlProgramCompiler.compile("program" + program, vs, fs);
        } catch (ShaderCompileException | RuntimeException e) {
            p.error = e.getMessage();
            LOG.error("[MineMetal GL] program {} failed to translate: {}", program, e.getMessage());
            return;
        }
        GlProgramCompiler.StageResult v = p.result.vertex(), f = p.result.fragment();
        p.vsBlock = block(v.blockSize);
        p.fsBlock = block(f.blockSize);
        for (GlProgramCompiler.BlockMember m : v.members) uniform(p, m.name()).vs = m;
        for (GlProgramCompiler.BlockMember m : f.members) uniform(p, m.name()).fs = m;
        List<GlProgramCompiler.Interface> ins = v.inputs;
        p.inputLocs = new int[ins.size()];
        for (int i = 0; i < ins.size(); i++) {
            Integer b = p.attribBindings.get(ins.get(i).name());
            p.inputLocs[i] = b != null ? b : ins.get(i).location();
        }
        List<String> samplers = p.result.samplers();
        p.samplerUnits = new int[samplers.size()];
        for (int i = 0; i < samplers.size(); i++) uniform(p, samplers.get(i)).samplerIndex = i;
    }

    private static ByteBuffer block(int size) {
        return size <= 0 ? null : ByteBuffer.allocateDirect((size + 15) & ~15).order(ByteOrder.nativeOrder());
    }

    private static Uniform uniform(Program p, String name) {
        Integer loc = p.locations.get(name);
        if (loc != null) return p.uniforms.get(loc);
        Uniform u = new Uniform(name);
        p.locations.put(name, p.uniforms.size());
        p.uniforms.add(u);
        return u;
    }

    static int uniformLocation(int program, String name) {
        Program p = PROGRAMS.get(program);
        if (p == null || p.result == null) return -1;
        if (name.endsWith("[0]")) name = name.substring(0, name.length() - 3);
        Integer loc = p.locations.get(name);
        return loc == null ? -1 : loc;
    }

    static int attribLocation(int program, String name) {
        Program p = PROGRAMS.get(program);
        if (p == null) return -1;
        Integer b = p.attribBindings.get(name);
        if (b != null) return b;
        if (p.result != null) for (GlProgramCompiler.Interface in : p.result.vertex().inputs) if (in.name().equals(name)) return in.location();
        return -1;
    }

    static boolean linked(int program) {
        Program p = PROGRAMS.get(program);
        return p != null && p.result != null;
    }

    static String infoLog(int program) {
        Program p = PROGRAMS.get(program);
        return p == null || p.error == null ? "" : p.error;
    }

    static void useProgram(int program) {
        currentProgram = program;
    }

    private static Uniform current(int location) {
        Program p = PROGRAMS.get(currentProgram);
        if (p == null || location < 0 || location >= p.uniforms.size()) return null;
        return p.uniforms.get(location);
    }

    /** Writes {@code count} elements of {@code comps} components (ints or floats) to the uniform at {@code location}. */
    static void uniform(int location, int count, int comps, long src, boolean ints) {
        Uniform u = current(location);
        if (u == null) return;
        Program p = PROGRAMS.get(currentProgram);
        if (u.samplerIndex >= 0) {
            if (ints) p.samplerUnits[u.samplerIndex] = MemoryUtil.memGetInt(src);
            return;
        }
        if (u.vs != null) write(p.vsBlock, u.vs, count, comps, src, ints);
        if (u.fs != null) write(p.fsBlock, u.fs, count, comps, src, ints);
    }

    private static void write(ByteBuffer block, GlProgramCompiler.BlockMember m, int count, int comps, long src, boolean ints) {
        if (block == null) return;
        long base = MemoryUtil.memAddress(block);
        int elems = Math.min(count, Math.max(m.arraySize(), 1));
        boolean memberInt = m.baseType() == 7 || m.baseType() == 8 || m.baseType() == 2;
        for (int e = 0; e < elems; e++) {
            long off = m.offset() + (long) e * (m.arraySize() > 0 ? m.arrayStride() : 0);
            for (int c = 0; c < comps && c < Math.max(m.vecSize(), 1); c++) {
                long dst = base + off + c * 4L;
                if (dst + 4 > base + block.capacity()) return;
                long s = src + ((long) e * comps + c) * 4;
                if (memberInt == ints) MemoryUtil.memPutInt(dst, MemoryUtil.memGetInt(s));
                else if (memberInt) MemoryUtil.memPutInt(dst, (int) MemoryUtil.memGetFloat(s));
                else MemoryUtil.memPutFloat(dst, MemoryUtil.memGetInt(s));
            }
        }
    }

    static void uniformMatrix(int location, int count, int cols, int rows, boolean transpose, long src) {
        Uniform u = current(location);
        if (u == null || u.samplerIndex >= 0) return;
        Program p = PROGRAMS.get(currentProgram);
        if (u.vs != null) writeMatrix(p.vsBlock, u.vs, count, cols, rows, transpose, src);
        if (u.fs != null) writeMatrix(p.fsBlock, u.fs, count, cols, rows, transpose, src);
    }

    private static void writeMatrix(ByteBuffer block, GlProgramCompiler.BlockMember m, int count, int cols, int rows, boolean transpose, long src) {
        if (block == null) return;
        long base = MemoryUtil.memAddress(block);
        int elems = Math.min(count, Math.max(m.arraySize(), 1));
        int stride = m.matrixStride() > 0 ? m.matrixStride() : 16;
        for (int e = 0; e < elems; e++) {
            long off = m.offset() + (long) e * (m.arraySize() > 0 ? m.arrayStride() : 0);
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) {
                    long dst = base + off + (long) c * stride + r * 4L;
                    if (dst + 4 > base + block.capacity()) return;
                    int idx = transpose ? r * cols + c : c * rows + r;
                    MemoryUtil.memPutFloat(dst, MemoryUtil.memGetFloat(src + ((long) e * cols * rows + idx) * 4));
                }
            }
        }
    }

    // ---- vertex arrays ---------------------------------------------------------------------------------------------

    static void bindVertexArray(int id) {
        dirty();
        vao = id;
        VAOS.computeIfAbsent(id, k -> new Vao());
    }

    static void deleteVertexArray(int id) {
        if (id != 0) VAOS.remove(id);
        if (vao == id) vao = 0;
    }

    static void enableAttrib(int index, boolean on) {
        dirty();
        if (index >= 0 && index < 16) VAOS.get(vao).attribs[index].enabled = on;
    }

    static void attribPointer(int index, int size, int type, boolean normalized, boolean integer, int stride, long offset) {
        dirty();
        if (index < 0 || index >= 16) return;
        Attrib a = VAOS.get(vao).attribs[index];
        a.buffer = arrayBuffer;
        a.size = size;
        a.type = type;
        a.normalized = normalized;
        a.integer = integer;
        a.stride = stride;
        a.offset = offset;
    }

    private static String vertexFormatName(Attrib a) {
        String c = switch (a.size) {
            case 1 -> "R";
            case 2 -> "RG";
            case 3 -> "RGB";
            default -> "RGBA";
        };
        boolean norm = a.normalized && !a.integer;
        return switch (a.type) {
            case 0x1400 -> c + "8_" + (norm ? "SNORM" : "SINT");
            case 0x1401 -> c + "8_" + (norm ? "UNORM" : "UINT");
            case 0x1402 -> c + "16_" + (norm ? "SNORM" : "SINT");
            case 0x1403 -> c + "16_" + (norm ? "UNORM" : "UINT");
            case 0x1404 -> c + "32_SINT";
            case 0x1405 -> c + "32_UINT";
            case 0x140B -> c + "16_FLOAT";
            default -> c + "32_FLOAT";
        };
    }

    private static long vertexFormat(String name) {
        return VERTEX_FORMATS.computeIfAbsent(name, n -> NeoNative.enumValue("vertex", n));
    }

    private static int typeBytes(int type) {
        int s = typeSize(type);
        return s < 0 ? 4 : s;
    }

    // ---- pipelines and draws ---------------------------------------------------------------------------------------

    private static int topology(int mode) {
        return switch (mode) {
            case 0 -> 3;
            case 1 -> 1;
            case 3 -> 2;
            case 5 -> 5;
            case 6 -> 6;
            default -> 4;
        };
    }

    private static int blendFactor(int f) {
        return switch (f) {
            case 0 -> 0;
            case 1 -> 1;
            case 0x300 -> 2;
            case 0x301 -> 3;
            case 0x302 -> 4;
            case 0x303 -> 5;
            case 0x306 -> 6;
            case 0x307 -> 7;
            case 0x304 -> 8;
            case 0x305 -> 9;
            case 0x308 -> 10;
            case 0x8001 -> 11;
            case 0x8002 -> 12;
            case 0x8003 -> 13;
            case 0x8004 -> 14;
            default -> 1;
        };
    }

    private static int blendOp(int op) {
        return switch (op) {
            case 0x800A -> 1;
            case 0x800B -> 2;
            case 0x8007 -> 3;
            case 0x8008 -> 4;
            default -> 0;
        };
    }

    /** Vertex slot plan for the current draw: GL buffer per Metal buffer slot. */
    private static final int VERTEX_SLOT_BASE = 22, ZERO_SLOT = 21;
    private static final int[] SLOT_BUFFERS = new int[8];
    private static final int[] SLOT_STRIDES = new int[8];
    private static final long[] SLOT_BASE_OFFSET = new long[8];

    private static long pipeline(Program p, Vao va, int mode, StringBuilder key, int colorCount) {
        if (p.memoPso != 0L && p.memoVersion == stateVersion && p.memoVao == vao && p.memoMode == mode && p.memoFbo == drawFbo) {
            System.arraycopy(p.memoSlotBuffers, 0, SLOT_BUFFERS, 0, 8);
            System.arraycopy(p.memoSlotStrides, 0, SLOT_STRIDES, 0, 8);
            memoHits++;
            return p.memoPso;
        }
        memoMisses++;
        long h = stateHash(p, va, mode, colorCount);
        long cached = p.pipelinesByHash.get(h);
        long pso;
        if (cached != 0L) {
            pso = cached;
            hashHits++;
        } else {
            pso = pipelineSlow(p, va, mode, key, colorCount);
            p.pipelinesByHash.put(h, pso);
        }
        p.memoVersion = stateVersion;
        p.memoVao = vao;
        p.memoMode = mode;
        p.memoFbo = drawFbo;
        p.memoPso = pso;
        System.arraycopy(SLOT_BUFFERS, 0, p.memoSlotBuffers, 0, 8);
        System.arraycopy(SLOT_STRIDES, 0, p.memoSlotStrides, 0, 8);
        return pso;
    }

    private static long pipelineSlow(Program p, Vao va, int mode, StringBuilder key, int colorCount) {
        GlProgramCompiler.StageResult vs = p.result.vertex();
        // vertex layout
        int slots = 0;
        key.setLength(0);
        key.append(mode).append('|');
        boolean needZero = false;
        for (GlProgramCompiler.Interface in : vs.inputs) {
            Integer glLoc = p.attribBindings.get(in.name());
            int loc = glLoc != null ? glLoc : in.location();
            Attrib a = loc >= 0 && loc < 16 ? va.attribs[loc] : null;
            if (a == null || !a.enabled || BUFFERS.get(a.buffer) == null) {
                key.append(in.location()).append("z,");
                needZero = true;
                continue;
            }
            int stride = a.stride > 0 ? a.stride : a.size * typeBytes(a.type);
            int slot = -1;
            for (int s = 0; s < slots; s++) if (SLOT_BUFFERS[s] == a.buffer && SLOT_STRIDES[s] == stride) slot = s;
            if (slot < 0 && slots < 8) {
                slot = slots++;
                SLOT_BUFFERS[slot] = a.buffer;
                SLOT_STRIDES[slot] = stride;
                SLOT_BASE_OFFSET[slot] = 0;
            }
            if (slot < 0) continue;
            key.append(in.location()).append(':').append(slot).append(':').append(stride).append(':').append(a.offset).append(':')
                .append(vertexFormatName(a)).append(',');
        }
        // fixed-function state + attachment formats
        key.append('|').append(blend ? blendSrcRgb + "," + blendDstRgb + "," + blendSrcA + "," + blendDstA + "," + blendOpRgb + "," + blendOpA : "-")
            .append('|').append(colorMask).append('|').append(depthTest ? depthFunc + (depthMask ? "w" : "r") : "-")
            .append('|').append(cullFace ? cullMode : 0).append(frontFace).append('|')
            .append(polygonOffset ? offsetFactor + "," + offsetUnits : "-").append('|');
        for (int i = 0; i < colorCount; i++) key.append(A_FORMATS[i]).append(',');
        key.append(A_DEPTH[0] != 0L ? 'D' : 'N');
        String k = key.toString();
        Long cached = p.pipelines.get(k);
        if (cached != null) return cached;
        long pso = createPipeline(p, va, mode, colorCount, slots, needZero);
        p.pipelines.put(k, pso);
        return pso;
    }

    private static long createPipeline(Program p, Vao va, int mode, int colorCount, int slots, boolean needZero) {
        GlProgramCompiler.StageResult vs = p.result.vertex(), fs = p.result.fragment();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int layoutCount = slots + (needZero ? 1 : 0);
            long layouts = stack.nmalloc(4, Math.max(layoutCount, 1) * 12);
            for (int s = 0; s < slots; s++) {
                MemoryUtil.memPutInt(layouts + s * 12L, VERTEX_SLOT_BASE + s);
                MemoryUtil.memPutInt(layouts + s * 12L + 4, SLOT_STRIDES[s]);
                MemoryUtil.memPutInt(layouts + s * 12L + 8, 0);
            }
            if (needZero) {
                MemoryUtil.memPutInt(layouts + slots * 12L, ZERO_SLOT);
                MemoryUtil.memPutInt(layouts + slots * 12L + 4, 16);
                MemoryUtil.memPutInt(layouts + slots * 12L + 8, -1);
            }
            int attribCount = vs.inputs.size();
            long attribs = stack.nmalloc(4, Math.max(attribCount, 1) * 16);
            int n = 0;
            for (GlProgramCompiler.Interface in : vs.inputs) {
                Integer glLoc = p.attribBindings.get(in.name());
                int loc = glLoc != null ? glLoc : in.location();
                Attrib a = loc >= 0 && loc < 16 ? va.attribs[loc] : null;
                long base = attribs + n * 16L;
                if (a == null || !a.enabled || BUFFERS.get(a.buffer) == null) {
                    boolean isInt = in.baseType() == 7 || in.baseType() == 8;
                    String f = isInt ? (in.baseType() == 8 ? "RGBA32_UINT" : "RGBA32_SINT") : "RGBA32_FLOAT";
                    MemoryUtil.memPutInt(base, in.location());
                    MemoryUtil.memPutInt(base + 4, ZERO_SLOT);
                    MemoryUtil.memPutInt(base + 8, 0);
                    MemoryUtil.memPutInt(base + 12, (int) vertexFormat(f));
                } else {
                    int stride = a.stride > 0 ? a.stride : a.size * typeBytes(a.type);
                    int slot = 0;
                    for (int s = 0; s < slots; s++) if (SLOT_BUFFERS[s] == a.buffer && SLOT_STRIDES[s] == stride) slot = s;
                    MemoryUtil.memPutInt(base, in.location());
                    MemoryUtil.memPutInt(base + 4, VERTEX_SLOT_BASE + slot);
                    MemoryUtil.memPutInt(base + 8, (int) a.offset);
                    MemoryUtil.memPutInt(base + 12, (int) vertexFormat(vertexFormatName(a)));
                }
                n++;
            }
            int targetCount = Math.max(colorCount, 0);
            long targets = stack.nmalloc(8, Math.max(targetCount, 1) * 9 * 8);
            for (int i = 0; i < targetCount; i++) {
                long t = targets + i * 72L;
                MemoryUtil.memPutLong(t, A_FORMATS[i]);
                MemoryUtil.memPutLong(t + 8, colorMask);
                MemoryUtil.memPutLong(t + 16, blend && !NO_BLEND ? 1 : 0);
                MemoryUtil.memPutLong(t + 24, blendFactor(blendSrcRgb));
                MemoryUtil.memPutLong(t + 32, blendFactor(blendDstRgb));
                MemoryUtil.memPutLong(t + 40, blendOp(blendOpRgb));
                MemoryUtil.memPutLong(t + 48, blendFactor(blendSrcA));
                MemoryUtil.memPutLong(t + 56, blendFactor(blendDstA));
                MemoryUtil.memPutLong(t + 64, blendOp(blendOpA));
            }
            long raster = stack.nmalloc(4, 7 * 4);
            MemoryUtil.memPutInt(raster, topology(mode));
            MemoryUtil.memPutInt(raster + 4, cullFace && cullMode == 0x405 && !NO_CULL ? 1 : 0);
            MemoryUtil.memPutInt(raster + 8, 0);
            // raster[3]: 0 = MTLWindingClockwise, 1 = counter-clockwise. GL CCW front faces -> Metal clockwise.
            boolean glCcw = frontFace == 0x901;
            MemoryUtil.memPutInt(raster + 12, (glCcw ^ FLIP_WINDING) ? 0 : 1);
            MemoryUtil.memPutInt(raster + 16, depthTest && !NO_DEPTH ? 1 : 0);
            MemoryUtil.memPutInt(raster + 20, depthFunc - 0x200);
            MemoryUtil.memPutInt(raster + 24, depthTest && depthMask ? 1 : 0);
            long bias = stack.nmalloc(4, 8);
            MemoryUtil.memPutFloat(bias, polygonOffset ? offsetUnits : 0f);
            MemoryUtil.memPutFloat(bias + 4, polygonOffset ? offsetFactor : 0f);
            long err = stack.nmalloc(1, 1024);
            MemoryUtil.memPutByte(err, (byte) 0);
            long t0 = System.nanoTime();
            ByteBuffer vsSrc = MemoryUtil.memUTF8(vs.msl), fsSrc = MemoryUtil.memUTF8(fs.msl);
            long pso;
            try {
                pso = NeoNative.pipelineCreate(ctx, MemoryUtil.memAddress(stack.UTF8("gl-program-" + currentProgram)),
                    MemoryUtil.memAddress(vsSrc), MemoryUtil.memAddress(stack.UTF8(vs.entryPoint)),
                    MemoryUtil.memAddress(fsSrc), MemoryUtil.memAddress(stack.UTF8(fs.entryPoint)),
                    layouts, layoutCount, attribs, attribCount, targets, targetCount, raster, bias, A_DEPTH[0] != 0L ? 1 : 0, err, 1024);
            } finally {
                MemoryUtil.memFree(vsSrc);
                MemoryUtil.memFree(fsSrc);
            }
            if (pso == 0L) {
                LOG.error("[MineMetal GL] pipeline for program {} failed: {}", currentProgram, MemoryUtil.memUTF8(err));
            } else if (System.nanoTime() - t0 > 20_000_000L) {
                LOG.info("[MineMetal GL] pipeline for program {} took {} ms", currentProgram, (System.nanoTime() - t0) / 1_000_000);
            }
            return pso;
        } catch (RuntimeException e) {
            LOG.error("[MineMetal GL] pipeline for program {} failed", currentProgram, e);
            return 0L;
        }
    }

    private static final StringBuilder KEY = new StringBuilder(256);
    private static final Set<Integer> TRACED = new HashSet<>();
    private static final int TRACE_DRAWS = Integer.getInteger("minemetal.traceDraws", 0);

    private static void trace(Program p, Vao va, int mode, int count, boolean indexed, int indexType, int colorCount) {
        StringBuilder sb = new StringBuilder("[MineMetal GL] draw#").append(draws).append(" prog=").append(currentProgram)
            .append(" mode=").append(mode).append(" count=").append(count).append(indexed ? " idx=" + Integer.toHexString(indexType) : "")
            .append(" fbo=").append(drawFbo).append(" colors=").append(colorCount).append(" depth=").append(A_DEPTH[0] != 0L)
            .append(" vp=").append(java.util.Arrays.toString(viewport)).append(" scissor=").append(scissorTest)
            .append(" blend=").append(blend).append(" depthTest=").append(depthTest).append(" cull=").append(cullFace)
            .append(" mask=").append(colorMask).append("\n   inputs:");
        for (GlProgramCompiler.Interface in : p.result.vertex().inputs) {
            Integer b = p.attribBindings.get(in.name());
            int loc = b != null ? b : in.location();
            Attrib a = loc >= 0 && loc < 16 ? va.attribs[loc] : null;
            sb.append(' ').append(in.name()).append("@").append(in.location()).append("->gl").append(loc)
                .append(a == null || !a.enabled ? "(off)" : "(" + vertexFormatName(a) + " off=" + a.offset + " stride=" + a.stride + " buf=" + a.buffer + ")");
        }
        sb.append("\n   uniforms(vs ").append(p.vsBlock == null ? 0 : p.vsBlock.capacity()).append("B):");
        for (Uniform u : p.uniforms) {
            if (u.vs == null) continue;
            sb.append(' ').append(u.name).append('[');
            for (int i = 0; i < Math.min(4, Math.max(u.vs.vecSize(), 1) * Math.max(u.vs.columns(), 1)); i++) {
                sb.append(p.vsBlock.getFloat(u.vs.offset() + i * 4)).append(',');
            }
            sb.append(']');
        }
        sb.append("\n   uniforms(fs ").append(p.fsBlock == null ? 0 : p.fsBlock.capacity()).append("B):");
        for (Uniform u : p.uniforms) {
            if (u.fs == null) continue;
            sb.append(' ').append(u.name).append("@").append(u.fs.offset()).append('[');
            for (int i = 0; i < Math.min(4, Math.max(u.fs.vecSize(), 1) * Math.max(u.fs.columns(), 1)); i++) {
                sb.append(p.fsBlock.getFloat(u.fs.offset() + i * 4)).append(',');
            }
            sb.append(']');
        }
        sb.append("\n   samplers=").append(p.result.samplers()).append(" units=").append(java.util.Arrays.toString(p.samplerUnits));
        LOG.info(sb.toString());
        if (draws == 1) LOG.info("[MineMetal GL] debug switches: flipWinding={} nocull={} noblend={} nodepth={}", FLIP_WINDING, NO_CULL, NO_BLEND, NO_DEPTH);
        if (TRACED.add(currentProgram)) LOG.info("[MineMetal GL] fragment MSL of program {}:\n{}", currentProgram, p.result.fragment().msl);
    }

    // multi-draw / base-vertex arguments for the next draw() (set by the entry points, cleared after)
    static int multiCount, baseVertex;
    static long multiCounts, multiIndices, multiBase;

    /** glMultiDrawElements(BaseVertex): one pass, one pipeline, many indexed draws. */
    static void multiDrawElements(int mode, long counts, int type, long indices, int drawCount, long baseVertices) {
        if (drawCount <= 0) return;
        if (drawCount > 2048) {
            for (int off = 0; off < drawCount; off += 2048) {
                multiDrawElements(mode, counts + off * 4L, type, indices + off * 8L, Math.min(2048, drawCount - off),
                    baseVertices != 0L ? baseVertices + off * 4L : 0L);
            }
            return;
        }
        multiCount = drawCount;
        multiCounts = counts;
        multiIndices = indices;
        multiBase = baseVertices;
        try {
            draw(mode, 1, type, 0L, 0, true);
        } finally {
            multiCount = 0;
        }
    }

    static void drawElementsBaseVertex(int mode, int count, int type, long indices, int base) {
        baseVertex = base;
        try {
            draw(mode, count, type, indices, 0, true);
        } finally {
            baseVertex = 0;
        }
    }

    static void copyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        Buf src = buffer(readTarget), dst = buffer(writeTarget);
        if (src == null || dst == null || src.mtl == 0L || dst.mtl == 0L || size <= 0) return;
        if (readOffset + size > src.size || writeOffset + size > dst.size) return;
        NeoNative.copyBuffer(ctx, src.mtl, readOffset, dst.mtl, writeOffset, size);
    }

    // ---- fences ----------------------------------------------------------------------------------------------------

    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap FENCES = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private static long nextFence = 1;

    /** A GL sync object is the submit index of the command buffer being recorded when it was created. */
    static long fenceSync() {
        long id = nextFence++;
        FENCES.put(id, NeoNative.currentSubmitIndex(ctx));
        return id;
    }

    static boolean fenceSignaled(long sync) {
        long idx = FENCES.get(sync);
        return idx == 0L || NeoNative.completedIndex(ctx) >= idx;
    }

    /** glClientWaitSync: flushes (submits) the fence's command buffer if needed, then waits. */
    static int clientWaitSync(long sync, int flags, long timeoutNs) {
        long idx = FENCES.get(sync);
        if (idx == 0L || NeoNative.completedIndex(ctx) >= idx) return 0x911A; // ALREADY_SIGNALED
        if (timeoutNs <= 0L && (flags & 1) == 0) return 0x911B;           // TIMEOUT_EXPIRED
        if (NeoNative.currentSubmitIndex(ctx) <= idx) NeoNative.submit(ctx);
        if (timeoutNs <= 0L) return 0x911B;
        return NeoNative.waitFor(ctx, idx, timeoutNs) ? 0x911C : 0x911B; // CONDITION_SATISFIED / TIMEOUT_EXPIRED
    }

    static void deleteSync(long sync) {
        FENCES.remove(sync);
    }

    static void draw(int mode, int count, int indexType, long indexOffset, int first, boolean indexed) {
        if (count <= 0) return;
        Program p = PROGRAMS.get(currentProgram);
        if (p == null || p.result == null) {
            if (p != null && !p.failedLogged) {
                p.failedLogged = true;
                warnOnce("prog" + currentProgram, "draw with unusable program " + currentProgram + ": " + p.error);
            }
            return;
        }
        Vao va = VAOS.get(vao);
        Buf ebo = null;
        if (indexed) {
            ebo = BUFFERS.get(va.ebo);
            if (ebo == null || ebo.mtl == 0L) return;
            if (indexType == 0x1401) {
                warnOnce("ubyteidx", "GL_UNSIGNED_BYTE indices are not supported yet");
                return;
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!beginPass(0, stack)) {
                warnOnce("pass" + drawFbo, "could not begin a render pass on framebuffer " + drawFbo);
                return;
            }
            try {
                int colorCount = 0;
                for (int i = 0; i < 8; i++) if (A_COLORS[i] != 0L) colorCount = i + 1;
                long pso = pipeline(p, va, mode, KEY, colorCount);
                if (pso == 0L || !NeoNative.passSetPipeline(ctx, pso)) {
                    warnOnce("pso" + currentProgram, "pipeline unusable for program " + currentProgram);
                    return;
                }
                NeoNative.passSetViewport(ctx, viewport[0], viewport[1], viewport[2], viewport[3]);
                // vertex buffers (slots computed by pipeline())
                int slots = 0;
                for (int s = 0; s < 8; s++) {
                    if (SLOT_STRIDES[s] == 0) break;
                    slots = s + 1;
                }
                for (int loc : p.inputLocs) {
                    Attrib a = loc >= 0 && loc < 16 ? va.attribs[loc] : null;
                    if (a == null || !a.enabled) continue;
                    Buf b = BUFFERS.get(a.buffer);
                    if (b == null || b.mtl == 0L) return;
                    for (int s = 0; s < 8; s++) {
                        if (SLOT_BUFFERS[s] == a.buffer) {
                            NeoNative.passSetVertexBuffer(ctx, b.mtl, 0, VERTEX_SLOT_BASE + s);
                            break;
                        }
                    }
                }
                NeoNative.passSetVertexBuffer(ctx, zeroBuffer, 0, ZERO_SLOT);
                // uniforms
                if (p.vsBlock != null) NeoNative.passSetBytes(ctx, 1, MemoryUtil.memAddress(p.vsBlock), p.vsBlock.capacity(), 0);
                if (p.fsBlock != null) NeoNative.passSetBytes(ctx, 2, MemoryUtil.memAddress(p.fsBlock), p.fsBlock.capacity(), 0);
                // textures
                for (int i = 0; i < p.samplerUnits.length; i++) {
                    int unit = p.samplerUnits[i];
                    Tex t = unit >= 0 && unit < units.length ? TEXTURES.get(units[unit]) : null;
                    long tex = t != null && t.mtl != 0L ? t.mtl : dummyTexture;
                    long smp = t != null && t.mtl != 0L ? sampler(t) : defaultSampler;
                    NeoNative.passSetTexture(ctx, 3, tex, i);
                    NeoNative.passSetSampler(ctx, 3, smp, i % 16);
                }
                if (indexed && multiCount > 0) {
                    boolean is32 = indexType == 0x1405;
                    int isz = is32 ? 4 : 2;
                    NeoNative.passSetIndexBuffer(ctx, ebo.mtl, is32);
                    long params = stack.nmalloc(4, multiCount * 12);
                    for (int i = 0; i < multiCount; i++) {
                        long base = params + i * 12L;
                        MemoryUtil.memPutInt(base, (int) (MemoryUtil.memGetAddress(multiIndices + i * 8L) / isz));
                        MemoryUtil.memPutInt(base + 4, MemoryUtil.memGetInt(multiCounts + i * 4L));
                        MemoryUtil.memPutInt(base + 8, multiBase != 0L ? MemoryUtil.memGetInt(multiBase + i * 4L) : 0);
                    }
                    NeoNative.passMultiDrawIndexed(ctx, params, 1, 0, multiCount);
                } else if (indexed) {
                    boolean is32 = indexType == 0x1405;
                    NeoNative.passSetIndexBuffer(ctx, ebo.mtl, is32);
                    NeoNative.passDrawIndexed(ctx, count, 1, (int) (indexOffset / (is32 ? 4 : 2)), baseVertex, 0);
                } else {
                    NeoNative.passDraw(ctx, count, 1, first, 0);
                }
                draws++;
                frameDraws++;
                if (draws <= TRACE_DRAWS) trace(p, va, mode, count, indexed, indexType, colorCount);
            } finally {
                NeoNative.passEnd(ctx);
                java.util.Arrays.fill(SLOT_STRIDES, 0);
            }
        }
    }

    // ---- queries ---------------------------------------------------------------------------------------------------

    /** GL state queries the tables in NeoGL do not answer by themselves; null = not tracked here. */
    static Integer stateInteger(int pname) {
        return switch (pname) {
            case 0x8069 -> units[activeUnit];       // TEXTURE_BINDING_2D
            case 0x84E0 -> 0x84C0 + activeUnit;     // ACTIVE_TEXTURE
            case 0x8CA6 -> drawFbo;                 // DRAW_FRAMEBUFFER_BINDING
            case 0x8CAA -> readFbo;                 // READ_FRAMEBUFFER_BINDING
            case 0x8B8D -> currentProgram;          // CURRENT_PROGRAM
            case 0x8894 -> arrayBuffer;             // ARRAY_BUFFER_BINDING
            case 0x8895 -> VAOS.get(vao).ebo;       // ELEMENT_ARRAY_BUFFER_BINDING
            case 0x85B5 -> vao;                     // VERTEX_ARRAY_BINDING
            case 0x8CA7 -> renderbuffer;            // RENDERBUFFER_BINDING
            case 0x0B74 -> depthFunc;
            case 0x0B72 -> depthMask ? 1 : 0;
            default -> null;
        };
    }

    static void putViewport(long ptr) {
        for (int i = 0; i < 4; i++) MemoryUtil.memPutInt(ptr + i * 4L, viewport[i]);
    }

    static boolean isEnabled(int cap) {
        return switch (cap) {
            case 0x0BE2 -> blend;
            case 0x0B71 -> depthTest;
            case 0x0B44 -> cullFace;
            case 0x0C11 -> scissorTest;
            case 0x8037 -> polygonOffset;
            default -> false;
        };
    }

    static void enable(int cap, boolean on) {
        dirty();
        switch (cap) {
            case 0x0BE2 -> blend = on;
            case 0x0B71 -> depthTest = on;
            case 0x0B44 -> cullFace = on;
            case 0x0C11 -> scissorTest = on;
            case 0x8037 -> polygonOffset = on;
            default -> {
            }
        }
    }

    static boolean isTexture(int id) {
        return TEXTURES.containsKey(id);
    }

    static void deleteTextures(int n, long ptr) {
        for (int i = 0; i < n; i++) deleteTexture(MemoryUtil.memGetInt(ptr + i * 4L));
    }

    static List<Integer> names(int n, long ptr) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(MemoryUtil.memGetInt(ptr + i * 4L));
        return out;
    }

    static Map<String, Object> debugSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("textures", TEXTURES.size());
        m.put("buffers", BUFFERS.size());
        m.put("programs", PROGRAMS.size());
        m.put("draws", draws);
        m.put("retired", RETIRED.size());
        return m;
    }
}
