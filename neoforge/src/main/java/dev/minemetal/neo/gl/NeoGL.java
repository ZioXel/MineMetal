package dev.minemetal.neo.gl;

import com.mojang.logging.LogUtils;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * LWJGL FunctionProvider backed by FFM upcall stubs: every glXxx name LWJGL asks for resolves to a native
 * function pointer that calls back into Java.
 *
 * Milestone 1 is a "null driver": it reports an OpenGL 4.1 core context, hands out object names, says every
 * shader compiles and links, and otherwise does nothing. Functions without an implementation are logged
 * (once each, plus call counts) so we can see exactly what vanilla and mods use.
 */
public final class NeoGL {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final Map<String, Long> ADDRESSES = new HashMap<>();
    private static final Map<String, Impl> IMPLS = new HashMap<>();
    private static final List<String> UNKNOWN_NAMES = new ArrayList<>();
    private static int[] unknownCalls = new int[256];
    private static boolean[] unknownLogged = new boolean[256];
    private static int lookups;

    private record Impl(FunctionDescriptor fd, String method) {
    }

    public static final FunctionProvider PROVIDER = new FunctionProvider() {
        public long getFunctionAddress(CharSequence functionName) {
            return address(functionName.toString());
        }

        @Override
        public long getFunctionAddress(ByteBuffer functionName) {
            return address(MemoryUtil.memASCII(functionName));
        }
    };

    private NeoGL() {
    }

    // ---- registry ------------------------------------------------------------------------------------------------

    private static FunctionDescriptor v(MemoryLayout... a) {
        return FunctionDescriptor.ofVoid(a);
    }

    private static FunctionDescriptor f(MemoryLayout r, MemoryLayout... a) {
        return FunctionDescriptor.of(r, a);
    }

    private static void reg(String name, FunctionDescriptor fd) {
        IMPLS.put(name, new Impl(fd, name));
    }

    private static void reg(String name, FunctionDescriptor fd, String method) {
        IMPLS.put(name, new Impl(fd, method));
    }

    static {
        final var I = JAVA_INT;
        final var J = JAVA_LONG;
        final var F = JAVA_FLOAT;
        final var B = JAVA_BYTE;
        // queries
        reg("glGetError", f(I));
        reg("glGetString", f(J, I));
        reg("glGetStringi", f(J, I, I));
        reg("glGetIntegerv", v(I, J));
        reg("glGetInteger64v", v(I, J));
        reg("glGetFloatv", v(I, J));
        reg("glGetDoublev", v(I, J));
        reg("glGetBooleanv", v(I, J));
        reg("glIsEnabled", f(B, I));
        reg("glIsTexture", f(B, I));
        // object names
        for (String g : new String[]{"glGenTextures", "glGenBuffers", "glGenFramebuffers", "glGenRenderbuffers",
            "glGenVertexArrays", "glGenQueries", "glGenSamplers", "glGenTransformFeedbacks", "glGenProgramPipelines"}) {
            reg(g, v(I, J), "gen");
        }
        reg("glDeleteTextures", v(I, J));
        reg("glDeleteBuffers", v(I, J));
        reg("glDeleteFramebuffers", v(I, J));
        reg("glDeleteRenderbuffers", v(I, J), "ignoreNames");
        reg("glDeleteVertexArrays", v(I, J));
        reg("glDeleteQueries", v(I, J), "ignoreNames");
        // state
        reg("glEnable", v(I));
        reg("glDisable", v(I));
        reg("glEnablei", v(I, I));
        reg("glDisablei", v(I, I));
        reg("glViewport", v(I, I, I, I));
        reg("glScissor", v(I, I, I, I));
        reg("glClearColor", v(F, F, F, F));
        reg("glClearDepth", v(JAVA_DOUBLE));
        reg("glClearDepthf", v(F));
        reg("glClear", v(I));
        reg("glBlendFunc", v(I, I));
        reg("glBlendFuncSeparate", v(I, I, I, I));
        reg("glBlendEquation", v(I));
        reg("glBlendEquationSeparate", v(I, I));
        reg("glDepthFunc", v(I));
        reg("glDepthMask", v(B));
        reg("glColorMask", v(B, B, B, B));
        reg("glColorMaski", v(I, B, B, B, B));
        reg("glClearBufferfv", v(I, I, J));
        reg("glCullFace", v(I));
        reg("glFrontFace", v(I));
        reg("glPolygonOffset", v(F, F));
        reg("glPixelStorei", v(I, I));
        reg("glActiveTexture", v(I));
        // buffers
        reg("glBindBuffer", v(I, I));
        reg("glBufferData", v(I, J, J, I));
        reg("glBufferStorage", v(I, J, J, I));
        reg("glBufferSubData", v(I, J, J, J));
        reg("glGetBufferParameteriv", v(I, I, J));
        reg("glGetBufferParameteri64v", v(I, I, J));
        reg("glMapBufferRange", f(J, I, J, J, I));
        reg("glMapBuffer", f(J, I, I));
        reg("glUnmapBuffer", f(B, I));
        // vertex arrays
        reg("glBindVertexArray", v(I));
        reg("glEnableVertexAttribArray", v(I));
        reg("glDisableVertexAttribArray", v(I));
        reg("glVertexAttribPointer", v(I, I, I, B, I, J));
        reg("glVertexAttribIPointer", v(I, I, I, I, J));
        // textures
        reg("glBindTexture", v(I, I));
        reg("glTexImage2D", v(I, I, I, I, I, I, I, I, J));
        reg("glTexSubImage2D", v(I, I, I, I, I, I, I, I, J));
        reg("glTexParameteri", v(I, I, I));
        reg("glTexParameterf", v(I, I, F));
        reg("glGenerateMipmap", v(I));
        reg("glGetTexLevelParameteriv", v(I, I, I, J));
        for (String g : new String[]{"glGetTexParameteriv", "glGetTexParameterfv", "glGetQueryiv", "glGetRenderbufferParameteriv",
            "glGetVertexAttribiv", "glGetSamplerParameteriv"}) {
            reg(g, v(I, I, J), "writeZero3");
        }
        reg("glGetTexLevelParameterfv", v(I, I, I, J), "writeZero4");
        reg("glGetFramebufferAttachmentParameteriv", v(I, I, I, J), "writeZero4");
        reg("glReadPixels", v(I, I, I, I, I, I, J));
        reg("glGetTexImage", v(I, I, I, I, J));
        // framebuffers
        reg("glBindFramebuffer", v(I, I));
        reg("glFramebufferTexture2D", v(I, I, I, I, I));
        reg("glFramebufferRenderbuffer", v(I, I, I, I));
        reg("glBindRenderbuffer", v(I, I));
        reg("glRenderbufferStorage", v(I, I, I, I));
        reg("glCheckFramebufferStatus", f(I, I));
        reg("glBlitFramebuffer", v(I, I, I, I, I, I, I, I, I, I));
        // shaders
        reg("glCreateShader", f(I, I));
        reg("glShaderSource", v(I, I, J, J));
        reg("glCompileShader", v(I), "ignore1");
        reg("glDeleteShader", v(I));
        reg("glCreateProgram", f(I));
        reg("glAttachShader", v(I, I));
        reg("glDetachShader", v(I, I), "ignore2");
        reg("glBindAttribLocation", v(I, I, J));
        reg("glLinkProgram", v(I));
        reg("glUseProgram", v(I));
        reg("glDeleteProgram", v(I));
        reg("glGetShaderiv", v(I, I, J));
        reg("glGetProgramiv", v(I, I, J));
        reg("glGetShaderInfoLog", v(I, I, J, J));
        reg("glGetProgramInfoLog", v(I, I, J, J));
        reg("glGetUniformLocation", f(I, I, J));
        reg("glGetAttribLocation", f(I, I, J));
        reg("glGetUniformBlockIndex", f(I, I, J));
        reg("glUniformBlockBinding", v(I, I, I));
        reg("glBindBufferBase", v(I, I, I));
        reg("glBindBufferRange", v(I, I, I, J, J));
        reg("glTexBuffer", v(I, I, I));
        reg("glTexBufferRange", v(I, I, I, J, J));
        reg("glVertexAttribDivisor", v(I, I));
        reg("glBindSampler", v(I, I));
        reg("glTexParameteriv", v(I, I, J));
        reg("glCopyTexSubImage2D", v(I, I, I, I, I, I, I, I));
        reg("glCopyTexImage2D", v(I, I, I, I, I, I, I, I));
        reg("glSamplerParameteri", v(I, I, I));
        reg("glSamplerParameterf", v(I, I, F));
        reg("glDeleteSamplers", v(I, J), "ignoreNames");
        reg("glDrawArraysInstanced", v(I, I, I, I));
        reg("glDrawElementsInstanced", v(I, I, I, J, I));
        reg("glDrawElementsInstancedBaseVertex", v(I, I, I, J, I, I));
        reg("glGetActiveUniformBlockiv", v(I, I, I, J), "writeZero4");
        for (int n = 1; n <= 4; n++) {
            reg("glUniform" + n + "ui", v(withFirstInt(n, I)), "uniform" + n + "i");
            reg("glUniform" + n + "uiv", v(I, I, J), "uniform" + n + "iv");
        }
        for (int n = 1; n <= 4; n++) {
            reg("glUniform" + n + "f", v(withFirstInt(n, F)), "uniform" + n + "f");
            reg("glUniform" + n + "i", v(withFirstInt(n, I)), "uniform" + n + "i");
            reg("glUniform" + n + "fv", v(I, I, J), "uniform" + n + "fv");
            reg("glUniform" + n + "iv", v(I, I, J), "uniform" + n + "iv");
        }
        reg("glUniformMatrix2fv", v(I, I, B, J));
        reg("glUniformMatrix3fv", v(I, I, B, J));
        reg("glUniformMatrix4fv", v(I, I, B, J));
        // draws
        reg("glDrawArrays", v(I, I, I));
        reg("glDrawElements", v(I, I, I, J));
        reg("glDrawElementsBaseVertex", v(I, I, I, J, I));
        reg("glMultiDrawElementsBaseVertex", v(I, J, I, J, I, J));
        reg("glMultiDrawElements", v(I, J, I, J, I));
        reg("glCopyBufferSubData", v(I, I, J, J, J));
        reg("glBindFragDataLocation", v(I, I, J), "ignoreIIJ");
        // sync and queries
        reg("glFenceSync", f(J, I, I));
        reg("glClientWaitSync", f(I, J, I, J));
        reg("glGetSynciv", v(J, I, I, J, J));
        reg("glGetQueryObjectiv", v(I, I, J), "queryObject");
        reg("glGetQueryObjectuiv", v(I, I, J), "queryObject");
        reg("glGetQueryObjecti64v", v(I, I, J), "queryObject64");
        reg("glGetQueryObjectui64v", v(I, I, J), "queryObject64");
        // harmless no-ops (logged once via the unknown path otherwise)
        for (String g : new String[]{"glFlush", "glFinish"}) reg(g, v(), "ignore0");
        // buffers are shared CPU/GPU memory: an explicit flush of a mapped range has nothing to do
        reg("glFlushMappedBufferRange", v(I, J, J), "ignoreIJJ");
        // GPU timestamps (26.3 profiler): not measured, queries read back 0
        reg("glQueryCounter", v(I, I), "ignore2");
        for (String g : new String[]{"glLineWidth"}) reg(g, v(F), "ignoreF");
        for (String g : new String[]{"glDrawBuffer", "glReadBuffer", "glLogicOp", "glProvokingVertex"}) reg(g, v(I), "ignore1");
        for (String g : new String[]{"glPolygonMode", "glBeginQuery", "glHint"}) reg(g, v(I, I), "ignore2");
        reg("glEndQuery", v(I), "ignore1");
        reg("glDeleteSync", v(J));
        reg("glDrawBuffers", v(I, J), "ignoreNames");
    }

    private static MemoryLayout[] withFirstInt(int n, MemoryLayout l) {
        MemoryLayout[] a = new MemoryLayout[n + 1];
        a[0] = JAVA_INT;
        for (int i = 1; i <= n; i++) a[i] = l;
        return a;
    }

    public static int lookups() {
        return lookups;
    }

    private static synchronized long address(String name) {
        Long cached = ADDRESSES.get(name);
        if (cached != null) return cached;
        lookups++;
        long addr;
        try {
            Impl impl = IMPLS.get(name);
            if (impl != null) {
                MethodHandle mh = guard(name, LOOKUP.findStatic(GlEntry.class, impl.method, impl.fd.toMethodType()));
                addr = LINKER.upcallStub(mh, impl.fd, Arena.global()).address();
            } else {
                int id = UNKNOWN_NAMES.size();
                UNKNOWN_NAMES.add(name);
                if (id >= unknownCalls.length) {
                    unknownCalls = java.util.Arrays.copyOf(unknownCalls, id * 2);
                    unknownLogged = java.util.Arrays.copyOf(unknownLogged, id * 2);
                }
                MethodHandle mh = MethodHandles.insertArguments(
                    LOOKUP.findStatic(NeoGL.class, "unknown", java.lang.invoke.MethodType.methodType(long.class, int.class)), 0, id);
                // Arguments are ignored (arm64: caller-cleaned stack, args in registers), returns 0 in x0.
                addr = LINKER.upcallStub(mh, FunctionDescriptor.of(JAVA_LONG), Arena.global()).address();
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MineMetal GL stub for " + name, e);
        }
        ADDRESSES.put(name, addr);
        return addr;
    }

    /** An exception escaping an upcall kills the JVM: log it (once per function) and return zero instead. */
    private static MethodHandle guard(String name, MethodHandle target) throws ReflectiveOperationException {
        java.lang.invoke.MethodType type = target.type();
        MethodHandle log = MethodHandles.insertArguments(
            LOOKUP.findStatic(NeoGL.class, "failed", java.lang.invoke.MethodType.methodType(void.class, String.class, Throwable.class)), 0, name);
        Class<?> r = type.returnType();
        MethodHandle handler;
        if (r == void.class) {
            handler = log;
        } else {
            MethodHandle zero = MethodHandles.dropArguments(MethodHandles.zero(r), 0, Throwable.class);
            handler = MethodHandles.foldArguments(zero, log);
        }
        handler = MethodHandles.dropArguments(handler, 1, type.parameterList());
        return MethodHandles.catchException(target, Throwable.class, handler);
    }

    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();

    private static void failed(String name, Throwable t) {
        if (FAILED.add(name)) LOG.error("[MineMetal GL] {} threw", name, t);
    }

    private static long unknown(int id) {
        unknownCalls[id]++;
        if (!unknownLogged[id]) {
            unknownLogged[id] = true;
            LOG.info("[MineMetal GL] unimplemented: {}", UNKNOWN_NAMES.get(id));
        }
        return 0L;
    }

    /** Called once per presented frame. */
    public static void endFrame(long frame) {
        if (frame == 1 || frame == 120 || frame % 1200 == 0) {
            StringBuilder sb = new StringBuilder("[MineMetal GL] frame ").append(frame).append(", unimplemented calls so far:");
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < UNKNOWN_NAMES.size(); i++) if (unknownCalls[i] > 0) ids.add(i);
            ids.sort((a, b) -> Integer.compare(unknownCalls[b], unknownCalls[a]));
            for (int i : ids) sb.append("\n  ").append(UNKNOWN_NAMES.get(i)).append(" x").append(unknownCalls[i]);
            LOG.info(sb.toString());
        }
    }

    public static float[] clearColor() {
        return Gl.clearColor;
    }
}
