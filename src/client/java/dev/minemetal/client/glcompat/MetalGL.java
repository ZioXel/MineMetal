package dev.minemetal.client.glcompat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.util.ShaderCompileException;
import dev.minemetal.client.metal.MetalDevice;
import dev.minemetal.client.metal.MetalEnums;
import dev.minemetal.client.metal.MetalGpuBuffer;
import dev.minemetal.client.metal.MetalTexture;
import dev.minemetal.client.metal.MetalTextureView;
import dev.minemetal.client.metal.Native;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL46C.*;

/**
 * A small OpenGL emulation on top of MineMetal's Metal backend, covering the subset of GL that Iris uses for shader
 * packs (textures, framebuffers, programs with loose uniforms, samplers, fullscreen draws, clears, copies).
 * <p>
 * Object names are plain ints like in GL. Each draw/clear becomes one Metal render pass on the backend's current
 * command buffer, so it is ordered correctly with Minecraft's own (renderpearl) rendering. Must be called on the render
 * thread, outside renderpearl render passes.
 */
public final class MetalGL {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal/GL");
    private static final int MAX_UNITS = 32;
    private static final int MAX_DRAW_BUFFERS = 8;

    // object tables
    private static final Int2ObjectOpenHashMap<GlTextureObject> TEXTURES = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<GlFramebufferObject> FRAMEBUFFERS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<GlShaderObject> SHADERS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<GlProgramObject> PROGRAMS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<GlSamplerObject> SAMPLERS = new Int2ObjectOpenHashMap<>();
    private static final Map<GpuTexture, Integer> EXTERNAL_IDS = new IdentityHashMap<>();
    /** Names start high so they never collide with ids handed out by a real GL context. */
    private static int nextName = 0x4D000001;

    // state
    private static int activeUnit;
    private static final int[] boundTextures = new int[MAX_UNITS];
    private static final int[] boundSamplers = new int[MAX_UNITS];
    private static int currentProgram;
    private static int drawFramebuffer;
    private static int readFramebuffer;
    private static final int[] viewport = new int[4];
    private static boolean blend;
    private static int blendSrcRgb = GL_ONE;
    private static int blendDstRgb = GL_ZERO;
    private static int blendSrcAlpha = GL_ONE;
    private static int blendDstAlpha = GL_ZERO;
    private static int blendEquationRgb = GL_FUNC_ADD;
    private static int blendEquationAlpha = GL_FUNC_ADD;
    private static int colorMask = 15;
    private static boolean depthTest;
    private static boolean depthMask = true;
    private static int depthFunc = GL_LESS;
    private static final float[] clearColor = new float[4];
    private static double clearDepth = 1.0;
    private static boolean scissorTest;
    private static final int[] scissor = new int[4];

    private static @Nullable GpuBuffer quadVertices;
    private static @Nullable GpuBuffer quadIndices;

    private MetalGL() {
    }

    /** True when MineMetal's Metal device is active (i.e. calls should come here instead of real GL). */
    public static boolean isActive() {
        return MetalDevice.current() != null;
    }

    private static MetalDevice device() {
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            throw new IllegalStateException("MetalGL used without an active Metal device");
        }
        return device;
    }

    private static int newName() {
        return nextName++;
    }

    // =================================================================================================================
    // Textures
    // =================================================================================================================

    public static int glGenTextures() {
        int id = newName();
        TEXTURES.put(id, new GlTextureObject(id));
        return id;
    }

    public static int glCreateTextures(final int target) {
        int id = glGenTextures();
        TEXTURES.get(id).target = target;
        return id;
    }

    /** Gives a renderpearl texture (e.g. Minecraft's main color/depth target) a GL name. */
    public static int registerExternalTexture(final GpuTexture texture) {
        Integer existing = EXTERNAL_IDS.get(texture);
        if (existing != null) {
            GlTextureObject obj = TEXTURES.get(existing);
            if (obj != null && obj.texture == texture) {
                return existing;
            }
        }
        int id = glGenTextures();
        TEXTURES.get(id).wrapExternal(device(), (MetalTexture) texture);
        EXTERNAL_IDS.put(texture, id);
        return id;
    }

    public static void glDeleteTextures(final int id) {
        GlTextureObject tex = TEXTURES.remove(id);
        if (tex != null) {
            if (tex.texture != null && tex.external) {
                EXTERNAL_IDS.remove(tex.texture);
            }
            tex.release(device());
            for (int i = 0; i < MAX_UNITS; i++) {
                if (boundTextures[i] == id) {
                    boundTextures[i] = 0;
                }
            }
        }
    }

    public static void glActiveTexture(final int unit) {
        activeUnit = Math.max(0, Math.min(MAX_UNITS - 1, unit - GL_TEXTURE0));
    }

    public static int activeTextureUnit() {
        return activeUnit;
    }

    public static void glBindTexture(final int target, final int id) {
        boundTextures[activeUnit] = id;
        if (id != 0 && !TEXTURES.containsKey(id)) {
            GlTextureObject obj = new GlTextureObject(id);
            obj.target = target;
            TEXTURES.put(id, obj);
        }
    }

    /** glBindTextureUnit (DSA). */
    public static void glBindTextureUnit(final int unit, final int id) {
        if (unit >= 0 && unit < MAX_UNITS) {
            boundTextures[unit] = id;
        }
    }

    public static int boundTexture(final int unit) {
        return boundTextures[unit];
    }

    private static @Nullable GlTextureObject boundTextureObject() {
        return TEXTURES.get(boundTextures[activeUnit]);
    }

    public static void glTexImage2D(final int target, final int level, final int internalFormat, final int width, final int height, final int border,
                                    final int format, final int type, final long pixels) {
        GlTextureObject tex = boundTextureObject();
        if (tex == null) {
            return;
        }
        texImage2D(tex, level, internalFormat, width, height, format, type, pixels);
    }

    static void texImage2D(final GlTextureObject tex, final int level, final int internalFormat, final int width, final int height,
                                  final int format, final int type, final long pixels) {
        if (level == 0) {
            GpuFormat gpuFormat = GlEnums.textureFormat(internalFormat);
            if (gpuFormat == null) {
                LOGGER.warn("Unsupported internal format 0x{} for texture {}", Integer.toHexString(internalFormat), tex.id);
                return;
            }
            boolean resize = !tex.hasStorage() || tex.width != width || tex.height != height || tex.format != gpuFormat;
            if (resize) {
                // Color textures get a full mip chain up front: Iris generates mipmaps on render targets after drawing.
                int levels = gpuFormat.hasColorAspect() ? 1 + (int) Math.floor(Math.log(Math.max(1, Math.max(width, height))) / Math.log(2)) : 1;
                tex.allocate(device(), gpuFormat, internalFormat, Math.max(1, width), Math.max(1, height), levels);
            }
        }
        if (pixels != 0L && tex.hasStorage()) {
            upload(tex, level, 0, 0, width >> 0, height, format, type, pixels);
        }
    }

    public static void glTexSubImage2D(final int target, final int level, final int x, final int y, final int width, final int height, final int format,
                                       final int type, final long pixels) {
        GlTextureObject tex = boundTextureObject();
        if (tex != null && tex.hasStorage() && pixels != 0L) {
            upload(tex, level, x, y, width, height, format, type, pixels);
        }
    }

    private static void upload(final GlTextureObject tex, final int level, final int x, final int y, final int width, final int height, final int format,
                               final int type, final long pixels) {
        if (level >= tex.levels || tex.texture == null || tex.format == null) {
            return;
        }
        int[] layout = GlEnums.clientPixelLayout(format, type);
        if (layout == null) {
            LOGGER.warn("Unsupported pixel upload format 0x{}/0x{}", Integer.toHexString(format), Integer.toHexString(type));
            return;
        }
        int texel = tex.format.blockSize();
        int srcTexel = layout[0] * layout[1];
        long count = (long) width * height;
        ByteBuffer data;
        boolean swapRB = format == GL_BGRA || format == GL_BGR;
        if (srcTexel == texel && !swapRB) {
            data = MemoryUtil.memByteBuffer(pixels, (int) (count * texel));
            device().encoder().writeToTexture(tex.texture, data, level, 0, x, y, width, height);
            return;
        }
        if (layout[0] == 3 && srcTexel / 3 * 4 == texel || swapRB && srcTexel == texel) {
            // RGB → RGBA (alpha = max) and/or BGR(A) → RGB(A)
            int comp = layout[1];
            data = MemoryUtil.memAlloc((int) (count * texel));
            try {
                for (long i = 0; i < count; i++) {
                    long src = pixels + i * srcTexel;
                    long dst = MemoryUtil.memAddress(data) + i * texel;
                    for (int c = 0; c < 4; c++) {
                        int sc = c;
                        if (swapRB && c < 3) {
                            sc = 2 - c;
                        }
                        if (sc < layout[0]) {
                            MemoryUtil.memCopy(src + (long) sc * comp, dst + (long) c * comp, comp);
                        } else {
                            fillMax(dst + (long) c * comp, comp, type);
                        }
                    }
                }
                device().encoder().writeToTexture(tex.texture, data, level, 0, x, y, width, height);
            } finally {
                MemoryUtil.memFree(data);
            }
            return;
        }
        LOGGER.warn("Pixel upload size mismatch for texture {} ({} B/texel source, {} B/texel storage)", tex.id, srcTexel, texel);
    }

    private static void fillMax(final long at, final int bytes, final int type) {
        switch (type) {
            case GL_FLOAT -> MemoryUtil.memPutFloat(at, 1.0F);
            case GL_HALF_FLOAT -> MemoryUtil.memPutShort(at, (short) 0x3C00);
            default -> MemoryUtil.memSet(at, 0xFF, bytes);
        }
    }

    public static void glTexParameteri(final int target, final int pname, final int value) {
        GlTextureObject tex = boundTextureObject();
        if (tex != null) {
            texParameter(tex, pname, value);
        }
    }

    public static void glTextureParameteri(final int texture, final int pname, final int value) {
        GlTextureObject tex = TEXTURES.get(texture);
        if (tex != null) {
            texParameter(tex, pname, value);
        }
    }

    static void texParameter(final GlTextureObject tex, final int pname, final int value) {
        switch (pname) {
            case GL_TEXTURE_MIN_FILTER -> tex.minFilter = value;
            case GL_TEXTURE_MAG_FILTER -> tex.magFilter = value;
            case GL_TEXTURE_WRAP_S -> tex.wrapS = value;
            case GL_TEXTURE_WRAP_T -> tex.wrapT = value;
            case GL_TEXTURE_COMPARE_MODE -> tex.compareMode = value;
            case GL_TEXTURE_COMPARE_FUNC -> tex.compareFunc = value;
            case GL_TEXTURE_MAX_LEVEL -> tex.maxLevel = value;
            default -> {
                return;
            }
        }
        tex.parametersChanged();
    }

    public static void glGenerateMipmap(final int target) {
        GlTextureObject tex = boundTextureObject();
        if (tex != null && tex.texture != null && tex.levels > 1 && !passOpen("glGenerateMipmap")) {
            Native.generateMipmaps(device().ctx(), tex.texture.handle());
        }
    }

    public static void glGenerateTextureMipmap(final int texture) {
        GlTextureObject tex = TEXTURES.get(texture);
        if (tex != null && tex.texture != null && tex.levels > 1 && !passOpen("glGenerateMipmap")) {
            Native.generateMipmaps(device().ctx(), tex.texture.handle());
        }
    }

    public static int textureWidth(final int texture, final int level) {
        GlTextureObject tex = TEXTURES.get(texture);
        return tex == null ? 0 : Math.max(1, tex.width >> level);
    }

    public static int textureHeight(final int texture, final int level) {
        GlTextureObject tex = TEXTURES.get(texture);
        return tex == null ? 0 : Math.max(1, tex.height >> level);
    }

    public static int textureInternalFormat(final int texture) {
        GlTextureObject tex = TEXTURES.get(texture);
        return tex == null ? 0 : tex.internalFormat;
    }

    /** The backing renderpearl texture (for interop with Minecraft rendering), or null. */
    public static @Nullable GpuTexture gpuTexture(final int texture) {
        GlTextureObject tex = TEXTURES.get(texture);
        return tex == null ? null : tex.texture;
    }

    // =================================================================================================================
    // Sampler objects
    // =================================================================================================================

    public static int glGenSamplers() {
        int id = newName();
        SAMPLERS.put(id, new GlSamplerObject(id));
        return id;
    }

    public static void glDeleteSamplers(final int id) {
        GlSamplerObject s = SAMPLERS.remove(id);
        if (s != null) {
            s.release();
        }
    }

    public static void glBindSampler(final int unit, final int sampler) {
        if (unit >= 0 && unit < MAX_UNITS) {
            boundSamplers[unit] = sampler;
        }
    }

    public static void glSamplerParameteri(final int sampler, final int pname, final int value) {
        GlSamplerObject s = SAMPLERS.get(sampler);
        if (s != null) {
            s.set(pname, value);
        }
    }

    // =================================================================================================================
    // Framebuffers
    // =================================================================================================================

    public static int glGenFramebuffers() {
        int id = newName();
        FRAMEBUFFERS.put(id, new GlFramebufferObject(id));
        return id;
    }

    public static void glDeleteFramebuffers(final int id) {
        FRAMEBUFFERS.remove(id);
        if (drawFramebuffer == id) {
            drawFramebuffer = 0;
        }
        if (readFramebuffer == id) {
            readFramebuffer = 0;
        }
    }

    public static void glBindFramebuffer(final int target, final int id) {
        if (id != 0 && !FRAMEBUFFERS.containsKey(id)) {
            FRAMEBUFFERS.put(id, new GlFramebufferObject(id));
        }
        if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) {
            drawFramebuffer = id;
        }
        if (target == GL_FRAMEBUFFER || target == GL_READ_FRAMEBUFFER) {
            readFramebuffer = id;
        }
    }

    public static int boundDrawFramebuffer() {
        return drawFramebuffer;
    }

    private static @Nullable GlFramebufferObject framebufferFor(final int target) {
        return FRAMEBUFFERS.get(target == GL_READ_FRAMEBUFFER ? readFramebuffer : drawFramebuffer);
    }

    public static void glFramebufferTexture2D(final int target, final int attachment, final int texTarget, final int texture, final int level) {
        GlFramebufferObject fb = framebufferFor(target);
        if (fb != null) {
            fb.attach(attachment, texture, level);
        }
    }

    public static void glNamedFramebufferTexture(final int framebuffer, final int attachment, final int texture, final int level) {
        GlFramebufferObject fb = FRAMEBUFFERS.get(framebuffer);
        if (fb != null) {
            fb.attach(attachment, texture, level);
        }
    }

    public static void glDrawBuffers(final int[] buffers) {
        GlFramebufferObject fb = FRAMEBUFFERS.get(drawFramebuffer);
        if (fb != null) {
            fb.setDrawBuffers(buffers);
        }
    }

    public static void glNamedFramebufferDrawBuffers(final int framebuffer, final int[] buffers) {
        GlFramebufferObject fb = FRAMEBUFFERS.get(framebuffer);
        if (fb != null) {
            fb.setDrawBuffers(buffers);
        }
    }

    public static void glReadBuffer(final int buffer) {
        GlFramebufferObject fb = FRAMEBUFFERS.get(readFramebuffer);
        if (fb != null) {
            fb.readBuffer = buffer;
        }
    }

    public static int glCheckFramebufferStatus(final int target) {
        GlFramebufferObject fb = framebufferFor(target);
        return fb == null || fb.isComplete(TEXTURES) ? GL_FRAMEBUFFER_COMPLETE : GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
    }

    // =================================================================================================================
    // Shaders & programs
    // =================================================================================================================

    public static int glCreateShader(final int type) {
        int id = newName();
        SHADERS.put(id, new GlShaderObject(id, type));
        return id;
    }

    public static void glShaderSource(final int shader, final CharSequence source) {
        GlShaderObject s = SHADERS.get(shader);
        if (s != null) {
            s.source = source.toString();
        }
    }

    /** Compilation is deferred to link time (both stages are needed to match varyings); always reports success. */
    public static void glCompileShader(final int shader) {
    }

    public static int glGetShaderi(final int shader, final int pname) {
        return switch (pname) {
            case GL_COMPILE_STATUS -> GL_TRUE;
            case GL_SHADER_TYPE -> SHADERS.containsKey(shader) ? SHADERS.get(shader).type : 0;
            default -> 0;
        };
    }

    public static String glGetShaderInfoLog(final int shader) {
        return "";
    }

    public static void glDeleteShader(final int shader) {
        SHADERS.remove(shader);
    }

    public static int glCreateProgram() {
        int id = newName();
        PROGRAMS.put(id, new GlProgramObject(id));
        return id;
    }

    public static void glAttachShader(final int program, final int shader) {
        GlProgramObject p = PROGRAMS.get(program);
        if (p != null && !p.shaders.contains(shader)) {
            p.shaders.add(shader);
        }
    }

    public static void glDetachShader(final int program, final int shader) {
        GlProgramObject p = PROGRAMS.get(program);
        if (p != null) {
            p.shaders.remove((Integer) shader);
        }
    }

    public static void glBindAttribLocation(final int program, final int index, final CharSequence name) {
        GlProgramObject p = PROGRAMS.get(program);
        if (p != null) {
            p.attribBindings.put(name.toString(), index);
        }
    }

    public static void setProgramDebugName(final int program, final String name) {
        GlProgramObject p = PROGRAMS.get(program);
        if (p != null) {
            p.debugName = name;
        }
    }

    public static void glLinkProgram(final int program) {
        GlProgramObject p = PROGRAMS.get(program);
        if (p == null) {
            return;
        }
        String vs = null;
        String fs = null;
        for (int shader : p.shaders) {
            GlShaderObject s = SHADERS.get(shader);
            if (s == null) {
                continue;
            }
            if (s.type == GL_VERTEX_SHADER) {
                vs = s.source;
            } else if (s.type == GL_FRAGMENT_SHADER) {
                fs = s.source;
            } else {
                p.linked = false;
                p.infoLog = "MineMetal: shader stage 0x" + Integer.toHexString(s.type) + " (geometry/tessellation/compute) is not supported on Metal yet";
                LOGGER.warn("{}: {}", p.debugName, p.infoLog);
                return;
            }
        }
        if (vs == null || fs == null) {
            p.linked = false;
            p.infoLog = "MineMetal: program needs a vertex and a fragment shader";
            return;
        }
        String name = p.debugName != null ? p.debugName : "program" + p.id;
        try {
            long t0 = System.nanoTime();
            p.setCompiled(GlProgramCompiler.compile(name, vs, fs));
            LOGGER.debug("Compiled {} in {} ms", name, (System.nanoTime() - t0) / 1_000_000);
            p.infoLog = "";
        } catch (ShaderCompileException | RuntimeException e) {
            p.linked = false;
            p.infoLog = e.getMessage() != null ? e.getMessage() : e.toString();
            LOGGER.error("Failed to compile {} for Metal: {}", name, p.infoLog);
        }
    }

    public static int glGetProgrami(final int program, final int pname) {
        GlProgramObject p = PROGRAMS.get(program);
        if (p == null) {
            return 0;
        }
        return switch (pname) {
            case GL_LINK_STATUS, GL_VALIDATE_STATUS -> p.linked ? GL_TRUE : GL_FALSE;
            case GL_INFO_LOG_LENGTH -> p.infoLog.length();
            case GL_ATTACHED_SHADERS -> p.shaders.size();
            default -> 0;
        };
    }

    public static String glGetProgramInfoLog(final int program) {
        GlProgramObject p = PROGRAMS.get(program);
        return p == null ? "" : p.infoLog;
    }

    public static void glDeleteProgram(final int program) {
        GlProgramObject p = PROGRAMS.remove(program);
        if (p != null) {
            p.freeBlocks();
            for (long pipeline : p.pipelines.values()) {
                if (pipeline == -1L) {
                    continue;
                }
                device().encoder().queueForDestroy(() -> Native.release(pipeline));
            }
            p.pipelines.clear();
        }
        if (currentProgram == program) {
            currentProgram = 0;
        }
    }

    public static void glUseProgram(final int program) {
        currentProgram = program;
    }

    public static int currentProgram() {
        return currentProgram;
    }

    public static int glGetUniformLocation(final int program, final CharSequence name) {
        GlProgramObject p = PROGRAMS.get(program);
        return p == null || !p.linked ? -1 : p.location(name.toString());
    }

    public static int glGetAttribLocation(final int program, final CharSequence name) {
        GlProgramObject p = PROGRAMS.get(program);
        if (p == null || p.compiled == null) {
            return -1;
        }
        for (GlProgramCompiler.Interface in : p.compiled.vertex().inputs) {
            if (in.name().contentEquals(name)) {
                return in.location();
            }
        }
        return -1;
    }

    private static @Nullable GlProgramObject current() {
        return PROGRAMS.get(currentProgram);
    }

    public static void glUniform1f(final int location, final float x) {
        GlProgramObject p = current();
        if (p != null) {
            p.setFloats(location, new float[]{x}, 1, 1);
        }
    }

    public static void glUniform2f(final int location, final float x, final float y) {
        GlProgramObject p = current();
        if (p != null) {
            p.setFloats(location, new float[]{x, y}, 2, 1);
        }
    }

    public static void glUniform3f(final int location, final float x, final float y, final float z) {
        GlProgramObject p = current();
        if (p != null) {
            p.setFloats(location, new float[]{x, y, z}, 3, 1);
        }
    }

    public static void glUniform4f(final int location, final float x, final float y, final float z, final float w) {
        GlProgramObject p = current();
        if (p != null) {
            p.setFloats(location, new float[]{x, y, z, w}, 4, 1);
        }
    }

    public static void glUniform1i(final int location, final int x) {
        GlProgramObject p = current();
        if (p != null) {
            p.setInts(location, new int[]{x}, 1, 1);
        }
    }

    public static void glUniform2i(final int location, final int x, final int y) {
        GlProgramObject p = current();
        if (p != null) {
            p.setInts(location, new int[]{x, y}, 2, 1);
        }
    }

    public static void glUniform3i(final int location, final int x, final int y, final int z) {
        GlProgramObject p = current();
        if (p != null) {
            p.setInts(location, new int[]{x, y, z}, 3, 1);
        }
    }

    public static void glUniform4i(final int location, final int x, final int y, final int z, final int w) {
        GlProgramObject p = current();
        if (p != null) {
            p.setInts(location, new int[]{x, y, z, w}, 4, 1);
        }
    }

    public static void glUniform1fv(final int location, final FloatBuffer values) {
        GlProgramObject p = current();
        if (p != null) {
            float[] v = new float[values.remaining()];
            values.duplicate().get(v);
            p.setFloats(location, v, 1, v.length);
        }
    }

    public static void glUniformMatrix3fv(final int location, final boolean transpose, final FloatBuffer values) {
        GlProgramObject p = current();
        if (p != null) {
            float[] v = new float[9];
            values.duplicate().get(v, 0, Math.min(9, values.remaining()));
            p.setMatrix(location, 3, transpose, v);
        }
    }

    public static void glUniformMatrix4fv(final int location, final boolean transpose, final FloatBuffer values) {
        GlProgramObject p = current();
        if (p != null) {
            float[] v = new float[16];
            values.duplicate().get(v, 0, Math.min(16, values.remaining()));
            p.setMatrix(location, 4, transpose, v);
        }
    }

    // =================================================================================================================
    // Fixed-function state
    // =================================================================================================================

    public static void glViewport(final int x, final int y, final int width, final int height) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = width;
        viewport[3] = height;
    }

    public static void glScissor(final int x, final int y, final int width, final int height) {
        scissor[0] = x;
        scissor[1] = y;
        scissor[2] = width;
        scissor[3] = height;
    }

    public static void glEnable(final int cap) {
        setCap(cap, true);
    }

    public static void glDisable(final int cap) {
        setCap(cap, false);
    }

    private static void setCap(final int cap, final boolean on) {
        switch (cap) {
            case GL_BLEND -> blend = on;
            case GL_DEPTH_TEST -> depthTest = on;
            case GL_SCISSOR_TEST -> scissorTest = on;
            default -> {
            }
        }
    }

    public static void glBlendFuncSeparate(final int srcRgb, final int dstRgb, final int srcAlpha, final int dstAlpha) {
        blendSrcRgb = srcRgb;
        blendDstRgb = dstRgb;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
    }

    public static void glBlendFunc(final int src, final int dst) {
        glBlendFuncSeparate(src, dst, src, dst);
    }

    public static void glBlendEquationSeparate(final int rgb, final int alpha) {
        blendEquationRgb = rgb;
        blendEquationAlpha = alpha;
    }

    public static void glColorMask(final boolean r, final boolean g, final boolean b, final boolean a) {
        colorMask = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
    }

    public static void glDepthMask(final boolean flag) {
        depthMask = flag;
    }

    public static void glDepthFunc(final int func) {
        depthFunc = func;
    }

    public static void glClearColor(final float r, final float g, final float b, final float a) {
        clearColor[0] = r;
        clearColor[1] = g;
        clearColor[2] = b;
        clearColor[3] = a;
    }

    public static void glClearDepth(final double depth) {
        clearDepth = depth;
    }

    // =================================================================================================================
    // Clears, copies
    // =================================================================================================================

    public static void glClear(final int mask) {
        if (dev.minemetal.client.metal.FrameTrace.active()) {
            dev.minemetal.client.metal.FrameTrace.event("glClear fb " + drawFramebuffer + " mask " + Integer.toHexString(mask));
        }
        GlFramebufferObject fb = FRAMEBUFFERS.get(drawFramebuffer);
        if (fb == null) {
            return;
        }
        if (passOpen("glClear")) {
            return;
        }
        MetalDevice device = device();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer views = stack.callocPointer(MAX_DRAW_BUFFERS);
            FloatBuffer colors = stack.callocFloat(MAX_DRAW_BUFFERS * 4);
            int count = 0;
            int clearMask = 0;
            int w = 0;
            int h = 0;
            if ((mask & GL_COLOR_BUFFER_BIT) != 0) {
                for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
                    int attachment = fb.drawBuffer(i);
                    GlTextureObject tex = attachment >= 0 ? TEXTURES.get(fb.color[attachment]) : null;
                    if (tex == null || tex.texture == null) {
                        continue;
                    }
                    views.put(i, tex.levelView(device, fb.colorLevel[attachment]).handle());
                    clearMask |= 1 << i;
                    for (int c = 0; c < 4; c++) {
                        colors.put(i * 4 + c, clearColor[c]);
                    }
                    count = i + 1;
                    w = textureWidth(tex.id, fb.colorLevel[attachment]);
                    h = textureHeight(tex.id, fb.colorLevel[attachment]);
                }
            }
            long depthView = 0L;
            if ((mask & GL_DEPTH_BUFFER_BIT) != 0) {
                GlTextureObject depth = TEXTURES.get(fb.depth);
                if (depth != null && depth.texture != null) {
                    depthView = depth.levelView(device, 0).handle();
                    clearMask |= 1 << 31;
                    if (w == 0) {
                        w = depth.width;
                        h = depth.height;
                    }
                }
            }
            if (count == 0 && depthView == 0L) {
                return;
            }
            Native.passBegin(device.ctx(), MemoryUtil.memAddress(views), count, clearMask, MemoryUtil.memAddress(colors), depthView, clearDepth, 0, 0, w, h, 0L);
            Native.passEnd(device.ctx());
        }
    }

    /** glCopyTexSubImage2D: copies from the read framebuffer's read buffer into the bound texture (same pixel format). */
    public static void glCopyTexSubImage2D(final int target, final int level, final int xoffset, final int yoffset, final int x, final int y,
                                           final int width, final int height) {
        GlTextureObject dst = boundTextureObject();
        GlFramebufferObject fb = FRAMEBUFFERS.get(readFramebuffer);
        if (dst == null || dst.texture == null || fb == null) {
            return;
        }
        GlTextureObject src = TEXTURES.get(fb.readTexture());
        if (src == null || src.texture == null) {
            return;
        }
        copyTexture(src, dst, level, x, y, xoffset, yoffset, width, height);
    }

    public static void glCopyImageSubData(final int srcName, final int srcLevel, final int srcX, final int srcY, final int dstName, final int dstLevel,
                                          final int dstX, final int dstY, final int width, final int height) {
        GlTextureObject src = TEXTURES.get(srcName);
        GlTextureObject dst = TEXTURES.get(dstName);
        if (src != null && dst != null && src.texture != null && dst.texture != null && srcLevel == dstLevel) {
            copyTexture(src, dst, srcLevel, srcX, srcY, dstX, dstY, width, height);
        }
    }

    /** glBlitFramebuffer limited to same-size, same-format copies of the read buffer / depth. */
    public static void glBlitFramebuffer(final int srcX0, final int srcY0, final int srcX1, final int srcY1, final int dstX0, final int dstY0,
                                         final int dstX1, final int dstY1, final int mask, final int filter) {
        GlFramebufferObject read = FRAMEBUFFERS.get(readFramebuffer);
        GlFramebufferObject draw = FRAMEBUFFERS.get(drawFramebuffer);
        if (read == null || draw == null) {
            return;
        }
        int w = srcX1 - srcX0;
        int h = srcY1 - srcY0;
        if (w != dstX1 - dstX0 || h != dstY1 - dstY0) {
            LOGGER.warn("Scaled glBlitFramebuffer is not supported on Metal yet");
            return;
        }
        if ((mask & GL_COLOR_BUFFER_BIT) != 0) {
            GlTextureObject src = TEXTURES.get(read.readTexture());
            int attachment = draw.drawBuffer(0);
            GlTextureObject dst = attachment >= 0 ? TEXTURES.get(draw.color[attachment]) : null;
            if (src != null && dst != null) {
                copyTexture(src, dst, 0, srcX0, srcY0, dstX0, dstY0, w, h);
            }
        }
        if ((mask & GL_DEPTH_BUFFER_BIT) != 0) {
            GlTextureObject src = TEXTURES.get(read.depth);
            GlTextureObject dst = TEXTURES.get(draw.depth);
            if (src != null && dst != null) {
                copyTexture(src, dst, 0, srcX0, srcY0, dstX0, dstY0, w, h);
            }
        }
    }

    private static void copyTexture(final GlTextureObject src, final GlTextureObject dst, final int level, final int srcX, final int srcY, final int dstX,
                                    final int dstY, final int width, final int height) {
        if (src.texture == null || dst.texture == null) {
            return;
        }
        if (src.format != dst.format) {
            LOGGER.warn("Copy between different formats ({} -> {}) is not supported on Metal", src.format, dst.format);
            return;
        }
        if (passOpen("copyTexture")) {
            return;
        }
        int w = Math.min(width, Math.min(src.width >> level, dst.width >> level) - Math.max(srcX, dstX));
        int h = Math.min(height, Math.min(src.height >> level, dst.height >> level) - Math.max(srcY, dstY));
        if (w > 0 && h > 0) {
            device().encoder().copyTextureToTexture(src.texture, dst.texture, level, dstX, dstY, srcX, srcY, w, h);
        }
    }

    // =================================================================================================================
    // Draws
    // =================================================================================================================

    /**
     * Draws the fullscreen quad (two triangles over [0,1]², POSITION_TEX like Iris' FullScreenQuadRenderer) with the
     * current program into the current draw framebuffer.
     */
    public static void drawFullscreenQuad() {
        ensureQuad();
        draw(6, true);
    }

    /** glDrawElements / glDrawArrays with the fullscreen-quad vertex source (the only vertex source Iris uses for its own passes). */
    public static void glDrawElements(final int mode, final int count, final int type, final long indices) {
        ensureQuad();
        draw(count, true);
    }

    public static void glDrawArrays(final int mode, final int first, final int count) {
        ensureQuad();
        draw(count, false);
    }

    private static void ensureQuad() {
        if (quadVertices != null) {
            return;
        }
        MetalDevice device = device();
        ByteBuffer v = MemoryUtil.memAlloc(4 * 20).order(ByteOrder.nativeOrder());
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float[] c : corners) {
            v.putFloat(c[0]).putFloat(c[1]).putFloat(0.0F).putFloat(c[0]).putFloat(c[1]);
        }
        v.flip();
        ByteBuffer i = MemoryUtil.memAlloc(12).order(ByteOrder.nativeOrder());
        i.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 2).putShort((short) 3).putShort((short) 0).flip();
        quadVertices = device.createBuffer(() -> "MetalGL quad", GpuBuffer.USAGE_VERTEX, v);
        quadIndices = device.createBuffer(() -> "MetalGL quad indices", GpuBuffer.USAGE_INDEX, i);
        MemoryUtil.memFree(v);
        MemoryUtil.memFree(i);
    }

    private static void draw(final int indexCount, final boolean indexed) {
        if (dev.minemetal.client.metal.FrameTrace.active()) {
            GlProgramObject tp = current();
            dev.minemetal.client.metal.FrameTrace.event("glDraw " + (tp != null ? tp.debugName + "#" + tp.id : "none") + " fb " + drawFramebuffer);
        }
        GlProgramObject program = current();
        GlFramebufferObject fb = FRAMEBUFFERS.get(drawFramebuffer);
        if (program == null || !program.linked || program.compiled == null || fb == null) {
            return;
        }
        if (passOpen("draw")) {
            return;
        }
        MetalDevice device = device();
        long ctx = device.ctx();
        int outputs = program.maxFragmentOutput() + 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer views = stack.callocPointer(MAX_DRAW_BUFFERS);
            GpuFormat[] formats = new GpuFormat[MAX_DRAW_BUFFERS];
            int count = 0;
            int w = 0;
            int h = 0;
            for (int i = 0; i < Math.min(outputs, MAX_DRAW_BUFFERS); i++) {
                int attachment = fb.drawBuffer(i);
                GlTextureObject tex = attachment >= 0 ? TEXTURES.get(fb.color[attachment]) : null;
                if (tex == null || tex.texture == null) {
                    continue;
                }
                views.put(i, tex.levelView(device, fb.colorLevel[attachment]).handle());
                formats[i] = tex.format;
                count = i + 1;
                w = textureWidth(tex.id, fb.colorLevel[attachment]);
                h = textureHeight(tex.id, fb.colorLevel[attachment]);
            }
            GlTextureObject depth = TEXTURES.get(fb.depth);
            long depthView = depth != null && depth.texture != null ? depth.levelView(device, 0).handle() : 0L;
            if (count == 0 && depthView == 0L) {
                return;
            }
            if (w == 0 && depth != null) {
                w = depth.width;
                h = depth.height;
            }
            long pipeline = pipelineFor(program, formats, count, depthView != 0L);
            if (pipeline == 0L) {
                return;
            }
            int vx = viewport[2] > 0 ? viewport[0] : 0;
            int vy = viewport[2] > 0 ? viewport[1] : 0;
            int vw = viewport[2] > 0 ? viewport[2] : w;
            int vh = viewport[3] > 0 ? viewport[3] : h;
            int[] area = scissorTest ? scissor : new int[]{vx, vy, vw, vh};
            Native.setDepthFlip(ctx, false); // GL depth functions are used as given
            if (!Native.passBegin(ctx, MemoryUtil.memAddress(views), count, 0, 0L, depthView, 0.0, area[0], area[1], area[2], area[3], 0L)) {
                return;
            }
            try {
                Native.passSetViewport(ctx, vx, vy, vw, vh, 0.0, 1.0);
                if (!Native.passSetPipeline(ctx, pipeline)) {
                    return;
                }
                bindUniforms(device, program, stack);
                bindSamplers(device, program);
                Native.passSetVertexBuffer(ctx, ((MetalGpuBuffer) quadVertices).handle(), 0L, 30);
                if (indexed) {
                    Native.passSetIndexBuffer(ctx, ((MetalGpuBuffer) quadIndices).handle(), false);
                    Native.passDrawIndexed(ctx, Math.min(indexCount, 6), 1, 0, 0, 0);
                } else {
                    Native.passDraw(ctx, Math.min(indexCount, 4), 1, 0, 0);
                }
            } finally {
                Native.passEnd(ctx);
            }
        }
    }

    private static void bindUniforms(final MetalDevice device, final GlProgramObject program, final MemoryStack stack) {
        long ctx = device.ctx();
        for (int stage = 0; stage < 2; stage++) {
            ByteBuffer block = program.blocks[stage];
            if (block == null) {
                continue;
            }
            int stageBit = stage == 0 ? 1 : 2;
            if (block.capacity() <= 4096) {
                Native.passSetBytes(ctx, stageBit, MemoryUtil.memAddress(block), block.capacity(), 0);
            } else {
                GpuBufferSlice slice = device.encoder().transientMemory().uploadGpu(block.duplicate().clear(), 256L, GpuBuffer.USAGE_UNIFORM);
                Native.passSetBuffer(ctx, stageBit, ((MetalGpuBuffer) slice.buffer()).handle(), slice.offset(), 0);
            }
        }
    }

    private static void bindSamplers(final MetalDevice device, final GlProgramObject program) {
        long ctx = device.ctx();
        for (GlProgramObject.Slot slot : program.slots) {
            if (!slot.sampler || slot.unit < 0 || slot.unit >= MAX_UNITS) {
                continue;
            }
            GlTextureObject tex = TEXTURES.get(boundTextures[slot.unit]);
            if (tex == null || tex.sampleView == null) {
                continue;
            }
            GlSamplerObject samplerObject = SAMPLERS.get(boundSamplers[slot.unit]);
            long sampler = samplerObject != null ? samplerObject.handle(device, tex) : tex.sampler(device);
            Native.passSetTexture(ctx, 3, tex.sampleView.handle(), slot.samplerIndex);
            Native.passSetSampler(ctx, 3, sampler, slot.samplerIndex);
        }
    }

    /** Color target descriptors (pixel format, write mask, blend) for the current emulated blend state. */
    static void fillColorTargets(final LongBuffer targets, final GpuFormat[] formats, final int count) {
        for (int i = 0; i < count; i++) {
            int base = i * 9;
            if (formats[i] == null) {
                continue;
            }
            targets.put(base, MetalEnums.pixelFormat(formats[i]));
            targets.put(base + 1, colorMask);
            GpuFormat.ComponentType ct = formats[i].componentType();
            if (blend && ct != GpuFormat.ComponentType.UINT_8 && ct != GpuFormat.ComponentType.UINT_16 && ct != GpuFormat.ComponentType.UINT_32
                && ct != GpuFormat.ComponentType.SINT_32) {
                targets.put(base + 2, 1L);
                targets.put(base + 3, MetalEnums.blendFactor(GlEnums.blendFactor(blendSrcRgb)));
                targets.put(base + 4, MetalEnums.blendFactor(GlEnums.blendFactor(blendDstRgb)));
                targets.put(base + 5, MetalEnums.blendOp(GlEnums.blendOp(blendEquationRgb)));
                targets.put(base + 6, MetalEnums.blendFactor(GlEnums.blendFactor(blendSrcAlpha)));
                targets.put(base + 7, MetalEnums.blendFactor(GlEnums.blendFactor(blendDstAlpha)));
                targets.put(base + 8, MetalEnums.blendOp(GlEnums.blendOp(blendEquationAlpha)));
            }
        }
    }

    /** Cache key part for the emulated blend/color-mask state. */
    static String blendKey() {
        return blend ? blendSrcRgb + ":" + blendDstRgb + ":" + blendSrcAlpha + ":" + blendDstAlpha + ":" + blendEquationRgb + ":" + blendEquationAlpha + "/" + colorMask
            : "off/" + colorMask;
    }

    /** Loads a renderpearl color target state into the emulated blend state (what GL's pipeline bind does before Iris' setup). */
    static void applyColorTargetState(final com.mojang.renderpearl.api.pipeline.@Nullable ColorTargetState target) {
        if (target == null) {
            blend = false;
            colorMask = 15;
            return;
        }
        colorMask = target.writeMask();
        if (target.blendFunction().isPresent()) {
            var f = target.blendFunction().get();
            blend = true;
            blendSrcRgb = GlEnums.glBlendFactor(f.color().sourceFactor());
            blendDstRgb = GlEnums.glBlendFactor(f.color().destFactor());
            blendEquationRgb = GlEnums.glBlendOp(f.color().op());
            blendSrcAlpha = GlEnums.glBlendFactor(f.alpha().sourceFactor());
            blendDstAlpha = GlEnums.glBlendFactor(f.alpha().destFactor());
            blendEquationAlpha = GlEnums.glBlendOp(f.alpha().op());
        } else {
            blend = false;
        }
    }

    /** Draw target of the current draw framebuffer for {@code program}'s fragment outputs. */
    record DrawTarget(long[] views, GpuFormat[] formats, long depthView) {
    }

    static @Nullable DrawTarget drawTarget(final GlProgramObject program) {
        GlFramebufferObject fb = FRAMEBUFFERS.get(drawFramebuffer);
        if (fb == null) {
            return null;
        }
        MetalDevice device = device();
        int outputs = Math.min(program.maxFragmentOutput() + 1, MAX_DRAW_BUFFERS);
        int count = 0;
        long[] views = new long[Math.max(outputs, 0)];
        GpuFormat[] formats = new GpuFormat[Math.max(outputs, 0)];
        for (int i = 0; i < outputs; i++) {
            int attachment = fb.drawBuffer(i);
            GlTextureObject tex = attachment >= 0 ? TEXTURES.get(fb.color[attachment]) : null;
            if (tex == null || tex.texture == null) {
                continue;
            }
            views[i] = tex.levelView(device, fb.colorLevel[attachment]).handle();
            formats[i] = tex.format;
            count = i + 1;
        }
        GlTextureObject depth = TEXTURES.get(fb.depth);
        long depthView = depth != null && depth.texture != null ? depth.levelView(device, 0).handle() : 0L;
        if (count == 0 && depthView == 0L) {
            return null;
        }
        return new DrawTarget(java.util.Arrays.copyOf(views, count), java.util.Arrays.copyOf(formats, count), depthView);
    }

    static @Nullable GlProgramObject program(final int id) {
        return PROGRAMS.get(id);
    }

    /**
     * Loose-uniform blocks of both stages at buffer 0. The blocks are copied to GPU memory once per change (and per
     * submit, since transient memory is recycled) and the same slice is re-bound for every draw until then, instead
     * of re-sending several KB of uniforms with every draw.
     */
    static void bindLooseUniforms(final GlProgramObject program) {
        MetalDevice device = device();
        long ctx = device.ctx();
        long submit = Native.currentSubmitIndex(ctx);
        for (int stage = 0; stage < 2; stage++) {
            ByteBuffer block = program.blocks[stage];
            if (block == null) {
                continue;
            }
            if (program.uploadedVersion[stage] != program.uniformVersion || program.uploadedSubmit[stage] != submit) {
                GpuBufferSlice slice = device.encoder().transientMemory().uploadGpu(block.duplicate().clear(), 256L, GpuBuffer.USAGE_UNIFORM);
                program.uploadedHandle[stage] = ((MetalGpuBuffer) slice.buffer()).handle();
                program.uploadedOffset[stage] = slice.offset();
                program.uploadedVersion[stage] = program.uniformVersion;
                program.uploadedSubmit[stage] = submit;
            }
            Native.passSetBuffer(ctx, stage == 0 ? 1 : 2, program.uploadedHandle[stage], program.uploadedOffset[stage], 0);
        }
    }

    /** Textures/samplers bound to GL texture units, except units in {@code skipUnits} (bit mask). */
    static void bindUnitSamplers(final GlProgramObject program, final long skipUnits) {
        MetalDevice device = device();
        long ctx = device.ctx();
        for (GlProgramObject.Slot slot : program.slots) {
            if (!slot.sampler || slot.unit < 0 || slot.unit >= MAX_UNITS || (skipUnits & (1L << slot.unit)) != 0L) {
                continue;
            }
            GlTextureObject tex = TEXTURES.get(boundTextures[slot.unit]);
            if (tex == null || tex.sampleView == null) {
                continue;
            }
            GlSamplerObject samplerObject = SAMPLERS.get(boundSamplers[slot.unit]);
            long sampler = samplerObject != null ? samplerObject.handle(device, tex) : tex.sampler(device);
            Native.passSetTexture(ctx, 3, tex.sampleView.handle(), slot.samplerIndex);
            Native.passSetSampler(ctx, 3, sampler, slot.samplerIndex);
        }
    }

    public static int glGetUniformBlockIndex(final int program, final CharSequence name) {
        GlProgramObject p = PROGRAMS.get(program);
        return p == null ? -1 : p.blockIndex(name.toString());
    }

    public static void glUniformBlockBinding(final int program, final int blockIndex, final int binding) {
        GlProgramObject p = PROGRAMS.get(program);
        if (p != null) {
            p.setBlockBinding(blockIndex, binding);
        }
    }

    private static long pipelineFor(final GlProgramObject program, final GpuFormat[] formats, final int count, final boolean hasDepth) {
        StringBuilder key = new StringBuilder(64);
        for (int i = 0; i < count; i++) {
            key.append(formats[i] == null ? "-" : formats[i].ordinal()).append(',');
        }
        key.append(hasDepth).append(blend).append(blendSrcRgb).append(':').append(blendDstRgb).append(':').append(blendSrcAlpha).append(':')
            .append(blendDstAlpha).append(':').append(blendEquationRgb).append(':').append(blendEquationAlpha).append(colorMask)
            .append(depthTest).append(depthMask).append(depthFunc);
        String k = key.toString();
        long cached = program.pipelines.getOrDefault(k, 0L);
        if (cached != 0L) {
            return cached == -1L ? 0L : cached; // -1 = Metal rejected this variant before; don't retry (and re-log) every frame
        }
        long pipeline = createPipeline(program, formats, count, hasDepth);
        program.pipelines.put(k, pipeline != 0L ? pipeline : -1L);
        return pipeline;
    }

    private static long createPipeline(final GlProgramObject program, final GpuFormat[] formats, final int count, final boolean hasDepth) {
        GlProgramCompiler.Result compiled = program.compiled;
        if (compiled == null) {
            return 0L;
        }
        MetalDevice device = device();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Vertex layout: POSITION_TEX (Position float3 @0, UV0 float2 @12), stride 20, Metal buffer 30.
            IntBuffer layouts = stack.ints(30, 20, 0);
            var inputs = compiled.vertex().inputs;
            IntBuffer attribs = stack.callocInt(Math.max(1, inputs.size()) * 4);
            int attribCount = 0;
            for (GlProgramCompiler.Interface in : inputs) {
                boolean position = in.name().equals("Position") || in.name().endsWith("_Position") || in.name().equals("vaPosition");
                boolean uv = in.name().equals("UV0") || in.name().endsWith("_UV0") || in.name().equals("vaUV0") || in.name().equals("texCoord");
                GpuFormat fmt;
                int offset;
                if (uv) {
                    fmt = GpuFormat.RG32_FLOAT;
                    offset = 12;
                } else {
                    if (!position) {
                        LOGGER.warn("{}: vertex input {} is fed from Position (fullscreen quad)", program.debugName, in.name());
                    }
                    fmt = GpuFormat.RGB32_FLOAT;
                    offset = 0;
                }
                attribs.put(attribCount * 4, in.location()).put(attribCount * 4 + 1, 30).put(attribCount * 4 + 2, offset)
                    .put(attribCount * 4 + 3, (int) MetalEnums.vertexFormat(fmt));
                attribCount++;
            }
            LongBuffer targets = stack.callocLong(Math.max(1, count) * 9);
            fillColorTargets(targets, formats, count);
            IntBuffer raster = stack.callocInt(7);
            raster.put(0, PrimitiveTopology.TRIANGLES.ordinal());
            raster.put(4, hasDepth ? 1 : 0);
            raster.put(5, (int) MetalEnums.compare(depthTest ? GlEnums.compareOp(depthFunc) : com.mojang.renderpearl.api.pipeline.CompareOp.ALWAYS_PASS));
            raster.put(6, hasDepth && depthTest && depthMask ? 1 : 0);
            FloatBuffer bias = stack.callocFloat(2);
            ByteBuffer err = stack.calloc(2048);
            String name = program.debugName != null ? program.debugName : "glprogram" + program.id;
            ByteBuffer vs = MemoryUtil.memUTF8(compiled.vertex().msl);
            ByteBuffer fs = MemoryUtil.memUTF8(compiled.fragment().msl);
            try {
                long handle = Native.pipelineCreate(device.ctx(), MemoryUtil.memAddress(stack.UTF8(name)), MemoryUtil.memAddress(vs),
                    MemoryUtil.memAddress(stack.UTF8(compiled.vertex().entryPoint)), MemoryUtil.memAddress(fs),
                    MemoryUtil.memAddress(stack.UTF8(compiled.fragment().entryPoint)), MemoryUtil.memAddress(layouts), 1, MemoryUtil.memAddress(attribs),
                    attribCount, MemoryUtil.memAddress(targets), count, MemoryUtil.memAddress(raster), MemoryUtil.memAddress(bias), hasDepth,
                    MemoryUtil.memAddress(err), err.capacity());
                if (handle == 0L) {
                    LOGGER.error("Metal rejected pipeline for {}: {}", name, MemoryUtil.memUTF8(MemoryUtil.memAddress(err)));
                    if (Boolean.getBoolean("minemetal.dumpShaders")) {
                        LOGGER.error("--- {} vertex ---\n{}\n--- fragment ---\n{}", name, compiled.vertex().msl, compiled.fragment().msl);
                    }
                }
                return handle;
            } finally {
                MemoryUtil.memFree(vs);
                MemoryUtil.memFree(fs);
            }
        }
    }

    private static final java.util.Set<String> PASS_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * GL work cannot be encoded while a renderpearl render pass is open on the same command buffer. Instead of crashing,
     * the call is skipped and the first occurrence per call site is logged with a stack trace (for fixing the caller).
     */
    private static boolean passOpen(final String what) {
        if (!device().encoder().isInRenderPass()) {
            return false;
        }
        if (PASS_WARNED.add(what)) {
            LOGGER.error("MetalGL {} skipped: a renderpearl render pass is open", what, new IllegalStateException("call site"));
        }
        return true;
    }

    // =================================================================================================================
    // Helpers for Iris on Metal (renderpearl render targets as GL framebuffers, format-converting copies)
    // =================================================================================================================

    private record ExternalFbo(GpuTexture color, @Nullable GpuTexture depth) {
    }

    private static final Map<ExternalFbo, Integer> EXTERNAL_FBOS = new java.util.HashMap<>();

    /**
     * An emulated framebuffer whose attachments are renderpearl textures (Minecraft's main render target): color on
     * attachment 0, optional depth. Cached per texture pair; entries of closed textures are dropped.
     */
    public static int externalFramebuffer(final GpuTexture color, final @Nullable GpuTexture depth) {
        ExternalFbo key = new ExternalFbo(color, depth);
        Integer existing = EXTERNAL_FBOS.get(key);
        if (existing != null && FRAMEBUFFERS.containsKey(existing.intValue())) {
            return existing;
        }
        pruneClosedExternals();
        int fbo = glGenFramebuffers();
        GlFramebufferObject fb = FRAMEBUFFERS.get(fbo);
        fb.attach(GL_COLOR_ATTACHMENT0, registerExternalTexture(color), 0);
        if (depth != null) {
            fb.attach(GL_DEPTH_ATTACHMENT, registerExternalTexture(depth), 0);
        }
        fb.setDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0});
        EXTERNAL_FBOS.put(key, fbo);
        return fbo;
    }

    private static void pruneClosedExternals() {
        EXTERNAL_FBOS.entrySet().removeIf(e -> {
            boolean closed = e.getKey().color().isClosed() || (e.getKey().depth() != null && e.getKey().depth().isClosed());
            if (closed) {
                glDeleteFramebuffers(e.getValue());
            }
            return closed;
        });
        java.util.List<Integer> stale = new java.util.ArrayList<>();
        EXTERNAL_IDS.forEach((texture, id) -> {
            if (texture.isClosed()) {
                stale.add(id);
            }
        });
        for (int id : stale) {
            glDeleteTextures(id);
        }
    }

    private static int blitProgram;
    private static int blitFramebuffer;

    private static final String BLIT_VS = """
        #version 330 core
        in vec3 Position;
        void main() {
            gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    private static final String BLIT_FS = """
        #version 330 core
        uniform sampler2D src;
        layout(location = 0) out vec4 fragColor;
        void main() {
            fragColor = texelFetch(src, ivec2(gl_FragCoord.xy), 0);
        }
        """;

    /**
     * Copies texture {@code src} into {@code dst} (level 0, same size, any float/normalized color formats) with a
     * fullscreen draw, leaving all emulated GL state as it was.
     */
    public static void blitColor(final int src, final int dst) {
        GlTextureObject s = TEXTURES.get(src);
        GlTextureObject d = TEXTURES.get(dst);
        if (s == null || d == null || s.texture == null || d.texture == null) {
            return;
        }
        if (blitProgram == 0) {
            int vs = glCreateShader(GL_VERTEX_SHADER);
            glShaderSource(vs, BLIT_VS);
            int fs = glCreateShader(GL_FRAGMENT_SHADER);
            glShaderSource(fs, BLIT_FS);
            blitProgram = glCreateProgram();
            setProgramDebugName(blitProgram, "minemetal:blit");
            glAttachShader(blitProgram, vs);
            glAttachShader(blitProgram, fs);
            glLinkProgram(blitProgram);
            glDeleteShader(vs);
            glDeleteShader(fs);
            blitFramebuffer = glGenFramebuffers();
        }
        int prevProgram = currentProgram;
        int prevDraw = drawFramebuffer;
        int prevUnit = activeUnit;
        int prevTexture = boundTextures[0];
        int prevSampler = boundSamplers[0];
        int[] prevViewport = viewport.clone();
        boolean prevBlend = blend;
        boolean prevDepthTest = depthTest;
        boolean prevScissor = scissorTest;
        int prevColorMask = colorMask;
        try {
            GlFramebufferObject fb = FRAMEBUFFERS.get(blitFramebuffer);
            fb.attach(GL_COLOR_ATTACHMENT0, dst, 0);
            fb.setDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0});
            drawFramebuffer = blitFramebuffer;
            currentProgram = blitProgram;
            glUniform1i(glGetUniformLocation(blitProgram, "src"), 0);
            activeUnit = 0;
            boundTextures[0] = src;
            boundSamplers[0] = 0;
            blend = false;
            depthTest = false;
            scissorTest = false;
            colorMask = 15;
            viewport[0] = 0;
            viewport[1] = 0;
            viewport[2] = d.width;
            viewport[3] = d.height;
            drawFullscreenQuad();
        } finally {
            currentProgram = prevProgram;
            drawFramebuffer = prevDraw;
            activeUnit = prevUnit;
            boundTextures[0] = prevTexture;
            boundSamplers[0] = prevSampler;
            System.arraycopy(prevViewport, 0, viewport, 0, 4);
            blend = prevBlend;
            depthTest = prevDepthTest;
            scissorTest = prevScissor;
            colorMask = prevColorMask;
        }
    }

    // =================================================================================================================
    // Queries
    // =================================================================================================================

    public static int glGetInteger(final int pname) {
        return switch (pname) {
            case GL_MAX_TEXTURE_IMAGE_UNITS -> 16;
            case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS -> MAX_UNITS;
            case GL_MAX_DRAW_BUFFERS, GL_MAX_COLOR_ATTACHMENTS -> MAX_DRAW_BUFFERS;
            case GL_MAX_TEXTURE_SIZE, GL_MAX_RENDERBUFFER_SIZE -> 16384;
            case GL_MAX_ARRAY_TEXTURE_LAYERS -> 2048;
            case GL_MAX_IMAGE_UNITS -> 8;
            case GL_MAX_SAMPLES -> 4;
            case GL_MAX_VERTEX_ATTRIBS -> 31;
            case GL_MAX_UNIFORM_BUFFER_BINDINGS -> 14;
            case GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS -> 0;
            case GL_MAJOR_VERSION -> 4;
            case GL_MINOR_VERSION -> 1;
            case GL_NUM_EXTENSIONS -> 0;
            case GL_FRAMEBUFFER_BINDING -> drawFramebuffer;
            case GL_READ_FRAMEBUFFER_BINDING -> readFramebuffer;
            case GL_CURRENT_PROGRAM -> currentProgram;
            case GL_ACTIVE_TEXTURE -> GL_TEXTURE0 + activeUnit;
            case GL_TEXTURE_BINDING_2D -> boundTextures[activeUnit];
            case GL_SAMPLER_BINDING -> boundSamplers[activeUnit];
            case GL_DEPTH_FUNC -> depthFunc;
            case GL_BLEND_SRC_RGB -> blendSrcRgb;
            case GL_BLEND_DST_RGB -> blendDstRgb;
            case GL_BLEND_SRC_ALPHA -> blendSrcAlpha;
            case GL_BLEND_DST_ALPHA -> blendDstAlpha;
            default -> 0;
        };
    }

    /** Fills {@code out} like glGetIntegerv; handles the multi-value queries Iris uses. */
    public static void glGetIntegerv(final int pname, final int[] out) {
        if (pname == GL_VIEWPORT && out.length >= 4) {
            System.arraycopy(viewport, 0, out, 0, 4);
        } else if (pname == GL_SCISSOR_BOX && out.length >= 4) {
            System.arraycopy(scissor, 0, out, 0, 4);
        } else if (out.length > 0) {
            out[0] = glGetInteger(pname);
        }
    }

    public static float glGetFloat(final int pname) {
        return pname == 0x84FF /* GL_MAX_TEXTURE_MAX_ANISOTROPY */ ? 16.0F : glGetInteger(pname);
    }

    public static String glGetString(final int name) {
        MetalDevice device = MetalDevice.current();
        return switch (name) {
            case GL_VENDOR -> "Apple";
            case GL_RENDERER -> (device != null ? device.getDeviceInfo().name() : "Apple GPU") + " (MineMetal)";
            case GL_VERSION -> "4.1 MineMetal (Metal)";
            case GL_SHADING_LANGUAGE_VERSION -> "4.10";
            default -> "";
        };
    }

    public static boolean glIsEnabled(final int cap) {
        return switch (cap) {
            case GL_BLEND -> blend;
            case GL_DEPTH_TEST -> depthTest;
            case GL_SCISSOR_TEST -> scissorTest;
            default -> false;
        };
    }

    public static int glGetTexParameteri(final int target, final int pname) {
        GlTextureObject tex = boundTextureObject();
        if (tex == null) {
            return 0;
        }
        return switch (pname) {
            case GL_TEXTURE_MIN_FILTER -> tex.minFilter;
            case GL_TEXTURE_MAG_FILTER -> tex.magFilter;
            case GL_TEXTURE_WRAP_S -> tex.wrapS;
            case GL_TEXTURE_WRAP_T -> tex.wrapT;
            case GL_TEXTURE_COMPARE_MODE -> tex.compareMode;
            case GL_TEXTURE_COMPARE_FUNC -> tex.compareFunc;
            case GL_TEXTURE_MAX_LEVEL -> tex.maxLevel;
            default -> 0;
        };
    }

    public static int glGetTexLevelParameteri(final int target, final int level, final int pname) {
        GlTextureObject tex = boundTextureObject();
        if (tex == null) {
            return 0;
        }
        return switch (pname) {
            case GL_TEXTURE_WIDTH -> tex.hasStorage() ? Math.max(1, tex.width >> level) : 0;
            case GL_TEXTURE_HEIGHT -> tex.hasStorage() ? Math.max(1, tex.height >> level) : 0;
            case GL_TEXTURE_INTERNAL_FORMAT -> tex.internalFormat;
            default -> 0;
        };
    }

    /** glCopyTexImage2D: (re)allocates the bound texture and copies the read buffer into it. */
    public static void glCopyTexImage2D(final int target, final int level, final int internalFormat, final int x, final int y, final int width,
                                        final int height, final int border) {
        GlTextureObject dst = boundTextureObject();
        if (dst == null) {
            return;
        }
        texImage2D(dst, level, internalFormat, width, height, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        glCopyTexSubImage2D(target, level, 0, 0, x, y, width, height);
    }

    /** glClearBufferfv(GL_COLOR, drawbuffer, value) / (GL_DEPTH, 0, value). */
    public static void glClearBufferfv(final int buffer, final int drawBuffer, final float[] value) {
        float[] saved = clearColor.clone();
        double savedDepth = clearDepth;
        GlFramebufferObject fb = FRAMEBUFFERS.get(drawFramebuffer);
        if (fb == null) {
            return;
        }
        if (buffer == GL_DEPTH) {
            clearDepth = value[0];
            glClear(GL_DEPTH_BUFFER_BIT);
        } else {
            System.arraycopy(value, 0, clearColor, 0, Math.min(4, value.length));
            int[] previous = new int[MAX_DRAW_BUFFERS];
            for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
                int b = fb.drawBuffer(i);
                previous[i] = b < 0 ? GL_NONE : GL_COLOR_ATTACHMENT0 + b;
            }
            int target = fb.drawBuffer(drawBuffer);
            fb.setDrawBuffers(new int[]{target < 0 ? GL_NONE : GL_COLOR_ATTACHMENT0 + target});
            glClear(GL_COLOR_BUFFER_BIT);
            fb.setDrawBuffers(previous);
        }
        System.arraycopy(saved, 0, clearColor, 0, 4);
        clearDepth = savedDepth;
    }

    // =================================================================================================================
    // Buffer objects (names only for now: Iris' SSBO/UBO features are not supported on Metal yet)
    // =================================================================================================================

    public static int glGenBuffers() {
        return newName();
    }

    public static int glGenVertexArrays() {
        return newName();
    }

    /** Resolves a GL texture name to the Metal texture view used for sampling (for interop/debugging). */
    public static @Nullable MetalTextureView sampleView(final int texture) {
        GlTextureObject tex = TEXTURES.get(texture);
        return tex == null ? null : tex.sampleView;
    }
}
