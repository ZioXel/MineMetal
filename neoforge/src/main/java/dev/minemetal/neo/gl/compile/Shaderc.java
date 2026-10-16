package dev.minemetal.neo.gl.compile;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Minimal FFM binding of libshaderc's C API (same names as LWJGL's Shaderc class). Minecraft 1.21.1 ships LWJGL 3.3.3,
 * whose shaderc binding predates {@code shaderc_compile_options_set_vulkan_rules_relaxed}; we bundle a newer
 * libshaderc and call it directly.
 */
public final class Shaderc {
    public static final int shaderc_vertex_shader = 0;
    public static final int shaderc_fragment_shader = 1;
    public static final int shaderc_compute_shader = 2;
    public static final int shaderc_target_env_vulkan = 0;
    public static final int shaderc_env_version_vulkan_1_2 = (1 << 22) | (2 << 12);
    public static final int shaderc_compilation_status_success = 0;

    private static MethodHandle COMPILER_INIT, COMPILER_RELEASE, OPTIONS_INIT, OPTIONS_RELEASE, SET_TARGET_ENV, SET_RELAXED,
        SET_AUTO_BIND, SET_AUTO_MAP, SET_DEBUG, COMPILE, RESULT_RELEASE, RESULT_STATUS, RESULT_ERROR, RESULT_BYTES, RESULT_LENGTH;

    private Shaderc() {
    }

    public static synchronized void load(Path library) {
        Linker l = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
        var J = JAVA_LONG;
        var I = JAVA_INT;
        var B = JAVA_BYTE;
        COMPILER_INIT = h(l, lib, "shaderc_compiler_initialize", FunctionDescriptor.of(J));
        COMPILER_RELEASE = h(l, lib, "shaderc_compiler_release", FunctionDescriptor.ofVoid(J));
        OPTIONS_INIT = h(l, lib, "shaderc_compile_options_initialize", FunctionDescriptor.of(J));
        OPTIONS_RELEASE = h(l, lib, "shaderc_compile_options_release", FunctionDescriptor.ofVoid(J));
        SET_TARGET_ENV = h(l, lib, "shaderc_compile_options_set_target_env", FunctionDescriptor.ofVoid(J, I, I));
        SET_RELAXED = h(l, lib, "shaderc_compile_options_set_vulkan_rules_relaxed", FunctionDescriptor.ofVoid(J, B));
        SET_AUTO_BIND = h(l, lib, "shaderc_compile_options_set_auto_bind_uniforms", FunctionDescriptor.ofVoid(J, B));
        SET_AUTO_MAP = h(l, lib, "shaderc_compile_options_set_auto_map_locations", FunctionDescriptor.ofVoid(J, B));
        SET_DEBUG = h(l, lib, "shaderc_compile_options_set_generate_debug_info", FunctionDescriptor.ofVoid(J));
        COMPILE = h(l, lib, "shaderc_compile_into_spv", FunctionDescriptor.of(J, J, J, J, I, J, J, J));
        RESULT_RELEASE = h(l, lib, "shaderc_result_release", FunctionDescriptor.ofVoid(J));
        RESULT_STATUS = h(l, lib, "shaderc_result_get_compilation_status", FunctionDescriptor.of(I, J));
        RESULT_ERROR = h(l, lib, "shaderc_result_get_error_message", FunctionDescriptor.of(J, J));
        RESULT_BYTES = h(l, lib, "shaderc_result_get_bytes", FunctionDescriptor.of(J, J));
        RESULT_LENGTH = h(l, lib, "shaderc_result_get_length", FunctionDescriptor.of(J, J));
    }

    private static MethodHandle h(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd) {
        return l.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd);
    }

    private static RuntimeException rethrow(Throwable t) {
        return t instanceof RuntimeException r ? r : new RuntimeException(t);
    }

    public static long shaderc_compiler_initialize() {
        try { return (long) COMPILER_INIT.invokeExact(); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void shaderc_compiler_release(long c) {
        try { COMPILER_RELEASE.invokeExact(c); } catch (Throwable t) { throw rethrow(t); }
    }

    public static long shaderc_compile_options_initialize() {
        try { return (long) OPTIONS_INIT.invokeExact(); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void shaderc_compile_options_release(long o) {
        try { OPTIONS_RELEASE.invokeExact(o); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void shaderc_compile_options_set_target_env(long o, int target, int version) {
        try { SET_TARGET_ENV.invokeExact(o, target, version); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void shaderc_compile_options_set_vulkan_rules_relaxed(long o, boolean v) {
        try { SET_RELAXED.invokeExact(o, (byte) (v ? 1 : 0)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void shaderc_compile_options_set_auto_bind_uniforms(long o, boolean v) {
        try { SET_AUTO_BIND.invokeExact(o, (byte) (v ? 1 : 0)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void shaderc_compile_options_set_auto_map_locations(long o, boolean v) {
        try { SET_AUTO_MAP.invokeExact(o, (byte) (v ? 1 : 0)); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void shaderc_compile_options_set_generate_debug_info(long o) {
        try { SET_DEBUG.invokeExact(o); } catch (Throwable t) { throw rethrow(t); }
    }

    /** {@code source} is not null-terminated (its remaining bytes are the length); file/entry are. */
    public static long shaderc_compile_into_spv(long compiler, ByteBuffer source, int kind, ByteBuffer file, ByteBuffer entry, long options) {
        try {
            return (long) COMPILE.invokeExact(compiler, MemoryUtil.memAddress(source), (long) source.remaining(), kind,
                MemoryUtil.memAddress(file), MemoryUtil.memAddress(entry), options);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static void shaderc_result_release(long r) {
        try { RESULT_RELEASE.invokeExact(r); } catch (Throwable t) { throw rethrow(t); }
    }

    public static int shaderc_result_get_compilation_status(long r) {
        try { return (int) RESULT_STATUS.invokeExact(r); } catch (Throwable t) { throw rethrow(t); }
    }

    public static String shaderc_result_get_error_message(long r) {
        try {
            long p = (long) RESULT_ERROR.invokeExact(r);
            return p == 0L ? "" : MemoryUtil.memUTF8(p);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static ByteBuffer shaderc_result_get_bytes(long r) {
        try {
            long p = (long) RESULT_BYTES.invokeExact(r);
            long n = (long) RESULT_LENGTH.invokeExact(r);
            return MemoryUtil.memByteBuffer(p, (int) n);
        } catch (Throwable t) { throw rethrow(t); }
    }
}
