package dev.minemetal.client.glcompat;

import com.mojang.renderpearl.util.ShaderCompileException;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * Compiles an OpenGL-style GLSL program (as written by shader packs / patched by Iris) for Metal.
 * <ol>
 *   <li>GLSL → SPIR-V with Shaderc using <b>relaxed Vulkan rules</b>: loose {@code uniform float x;} declarations are
 *       gathered into an implicit uniform block (one per stage), exactly like OpenGL's default uniform block.</li>
 *   <li>Reflection (SPIRV-Cross): byte offset/type of every loose uniform, sampler names, stage inputs/outputs.</li>
 *   <li>Vertex→fragment varyings are matched <b>by name</b> (GL semantics; Shaderc numbers them per stage).</li>
 *   <li>SPIR-V → MSL with fixed slots: uniform block of each stage at buffer 0 (further blocks 1..), sampler <i>i</i>
 *       (shared numbering across stages, by name) at texture/sampler <i>i</i>.</li>
 * </ol>
 */
public final class GlProgramCompiler {
    public static final int STAGE_VERTEX = 0;
    public static final int STAGE_FRAGMENT = 1;
    private static final int MSL_VERSION = 2 * 10000 + 4 * 100;
    private static final int EXEC_VERTEX = 0;
    private static final int EXEC_FRAGMENT = 4;
    private static final int DECORATION_LOCATION = 30;
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int BASETYPE_BOOL = 2;
    private static final int BASETYPE_INT32 = 7;
    private static final int BASETYPE_UINT32 = 8;
    private static final int BASETYPE_FP32 = 13;

    private GlProgramCompiler() {
    }

    /** One member of a stage's loose-uniform block. */
    public record BlockMember(String name, int offset, int baseType, int vecSize, int columns, int arraySize, int arrayStride, int matrixStride) {
    }

    /** Reflected interface variable (stage input or output). */
    public record Interface(String name, int location, int baseType, int vecSize) {
    }

    public static final class StageResult {
        public final int stage;
        public String msl = "";
        public String entryPoint = "main0";
        /** Size in bytes of the loose-uniform block (0 if the stage has none). */
        public int blockSize;
        public final List<BlockMember> members = new ArrayList<>();
        public final List<String> samplers = new ArrayList<>();
        public final List<Interface> inputs = new ArrayList<>();
        public final List<Interface> outputs = new ArrayList<>();
        /** Uniform blocks other than the implicit one (name -> MSL buffer index); bound through glBindBufferBase. */
        public final Map<String, Integer> extraBlocks = new LinkedHashMap<>();

        StageResult(final int stage) {
            this.stage = stage;
        }
    }

    public record Result(StageResult vertex, StageResult fragment, List<String> samplers) {
    }

    /** Compiles and links a vertex + fragment pair. */
    public static Result compile(final String name, final String vertexSource, final String fragmentSource) throws ShaderCompileException {
        ByteBuffer vsSpv = toSpirv(name + ".vsh", renameReserved(vertexSource), Shaderc.shaderc_vertex_shader);
        ByteBuffer fsSpv = null;
        try {
            fsSpv = toSpirv(name + ".fsh", renameReserved(fragmentSource), Shaderc.shaderc_fragment_shader);
            // Pass 1: reflect both stages to build the shared sampler numbering and the varying locations.
            StageResult vsInfo = new StageResult(STAGE_VERTEX);
            StageResult fsInfo = new StageResult(STAGE_FRAGMENT);
            reflectOnly(vsSpv, EXEC_VERTEX, vsInfo);
            reflectOnly(fsSpv, EXEC_FRAGMENT, fsInfo);
            Object2IntLinkedOpenHashMap<String> samplerIndex = new Object2IntLinkedOpenHashMap<>();
            for (String s : vsInfo.samplers) {
                samplerIndex.putIfAbsent(s, samplerIndex.size());
            }
            for (String s : fsInfo.samplers) {
                samplerIndex.putIfAbsent(s, samplerIndex.size());
            }
            if (samplerIndex.size() > 16) {
                throw new ShaderCompileException(name + ": " + samplerIndex.size() + " samplers exceed Metal's limit of 16 per stage");
            }
            Map<String, Integer> varyingLocations = new LinkedHashMap<>();
            for (Interface out : vsInfo.outputs) {
                varyingLocations.put(out.name(), out.location());
            }
            // Pass 2: generate MSL with final slots.
            StageResult vs = new StageResult(STAGE_VERTEX);
            StageResult fs = new StageResult(STAGE_FRAGMENT);
            toMsl(name, vsSpv, EXEC_VERTEX, vs, samplerIndex, null);
            toMsl(name, fsSpv, EXEC_FRAGMENT, fs, samplerIndex, varyingLocations);
            return new Result(vs, fs, new ArrayList<>(samplerIndex.keySet()));
        } finally {
            MemoryUtil.memFree(vsSpv);
            if (fsSpv != null) {
                MemoryUtil.memFree(fsSpv);
            }
        }
    }

    /**
     * Identifiers that are legal in GLSL but reserved in Metal Shading Language (C++14) and that SPIRV-Cross does not
     * rename itself (e.g. BSL declares {@code bool new = ...}). Renamed consistently in both stages before compiling.
     */
    private static final java.util.regex.Pattern RESERVED = java.util.regex.Pattern.compile(
        "\\b(new|delete|this|template|typename|namespace|operator|friend|virtual|explicit|mutable|using|constexpr|nullptr|auto|register"
            + "|try|catch|throw|decltype|alignas|alignof|noexcept|static_assert|thread_local|char16_t|char32_t|wchar_t)\\b");

    static String renameReserved(final String source) {
        java.util.regex.Matcher m = RESERVED.matcher(source);
        return m.find() ? m.replaceAll("mm_$1") : source;
    }

    private static ByteBuffer toSpirv(final String fileName, final String source, final int kind) throws ShaderCompileException {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        ByteBuffer src = MemoryUtil.memUTF8(source, false);
        ByteBuffer file = MemoryUtil.memUTF8(fileName);
        ByteBuffer entry = MemoryUtil.memUTF8("main");
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(options, true);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            Shaderc.shaderc_compile_options_set_generate_debug_info(options);
            result = Shaderc.shaderc_compile_into_spv(compiler, src, kind, file, entry, options);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new ShaderCompileException(fileName + ": " + Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer copy = MemoryUtil.memAlloc(spv.remaining());
            MemoryUtil.memCopy(spv, copy);
            return copy;
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
            MemoryUtil.memFree(src);
            MemoryUtil.memFree(file);
            MemoryUtil.memFree(entry);
        }
    }

    // --- SPIRV-Cross --------------------------------------------------------------------------------------------------

    private interface SpvcBody {
        void run(long context, long compiler, long resources, MemoryStack stack) throws ShaderCompileException;
    }

    private static void withCompiler(final ByteBuffer spv, final SpvcBody body) throws ShaderCompileException {
        long context = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer ptr = stack.callocPointer(1);
            check(Spvc.spvc_context_create(ptr), 0L, "create context");
            context = ptr.get(0);
            IntBuffer words = spv.asIntBuffer();
            check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), ptr), context, "parse SPIR-V");
            long ir = ptr.get(0);
            check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr), context,
                "create MSL compiler");
            long compiler = ptr.get(0);
            check(Spvc.spvc_compiler_create_shader_resources(compiler, ptr), context, "reflect");
            body.run(context, compiler, ptr.get(0), stack);
        } finally {
            if (context != 0L) {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static SpvcReflectedResource.Buffer list(final long resources, final int type, final long context, final MemoryStack stack)
        throws ShaderCompileException {
        PointerBuffer ptr = stack.callocPointer(1);
        PointerBuffer count = stack.callocPointer(1);
        check(Spvc.spvc_resources_get_resource_list_for_type(resources, type, ptr, count), context, "list resources");
        return SpvcReflectedResource.create(ptr.get(0), (int) count.get(0));
    }

    private static void reflectOnly(final ByteBuffer spv, final int model, final StageResult out) throws ShaderCompileException {
        withCompiler(spv, (context, compiler, resources, stack) -> reflect(context, compiler, resources, stack, out));
    }

    private static void reflect(final long context, final long compiler, final long resources, final MemoryStack stack, final StageResult out)
        throws ShaderCompileException {
        SpvcReflectedResource.Buffer samplers = list(resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, context, stack);
        for (int i = 0; i < samplers.capacity(); i++) {
            out.samplers.add(samplers.get(i).nameString());
        }
        SpvcReflectedResource.Buffer inputs = list(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, context, stack);
        for (int i = 0; i < inputs.capacity(); i++) {
            out.inputs.add(iface(compiler, inputs.get(i)));
        }
        SpvcReflectedResource.Buffer outputs = list(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, context, stack);
        for (int i = 0; i < outputs.capacity(); i++) {
            out.outputs.add(iface(compiler, outputs.get(i)));
        }
    }

    private static Interface iface(final long compiler, final SpvcReflectedResource r) {
        long type = Spvc.spvc_compiler_get_type_handle(compiler, r.type_id());
        return new Interface(r.nameString(), Spvc.spvc_compiler_get_decoration(compiler, r.id(), DECORATION_LOCATION),
            Spvc.spvc_type_get_basetype(type), Spvc.spvc_type_get_vector_size(type));
    }

    private static void toMsl(final String name, final ByteBuffer spv, final int model, final StageResult out,
                              final Object2IntLinkedOpenHashMap<String> samplerIndex, final @Nullable Map<String, Integer> varyingLocations)
        throws ShaderCompileException {
        withCompiler(spv, (context, compiler, resources, stack) -> {
            reflect(context, compiler, resources, stack, out);
            PointerBuffer ptr = stack.callocPointer(1);

            check(Spvc.spvc_compiler_create_compiler_options(compiler, ptr), context, "options");
            long options = ptr.get(0);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
            if (model == EXEC_VERTEX) {
                Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
            }
            check(Spvc.spvc_compiler_install_compiler_options(compiler, options), context, "install options");

            // Uniform blocks: implicit loose-uniform block first at buffer 0, anything else after it.
            SpvcReflectedResource.Buffer ubos = list(resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, context, stack);
            int nextBuffer = 1;
            // Give every resource its own (set 0, binding n): GLSL blocks/samplers without explicit bindings can share
            // binding 0 (e.g. the implicit loose-uniform block and the first named block), which SPIRV-Cross would
            // turn into one aliased buffer argument and our per-resource slot assignment would silently collapse.
            int uniqueBinding = 0;
            for (int i = 0; i < ubos.capacity(); i++) {
                Spvc.spvc_compiler_set_decoration(compiler, ubos.get(i).id(), DECORATION_DESCRIPTOR_SET, 0);
                Spvc.spvc_compiler_set_decoration(compiler, ubos.get(i).id(), DECORATION_BINDING, uniqueBinding++);
            }
            SpvcReflectedResource.Buffer allSamplers = list(resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, context, stack);
            for (int i = 0; i < allSamplers.capacity(); i++) {
                Spvc.spvc_compiler_set_decoration(compiler, allSamplers.get(i).id(), DECORATION_DESCRIPTOR_SET, 0);
                Spvc.spvc_compiler_set_decoration(compiler, allSamplers.get(i).id(), DECORATION_BINDING, uniqueBinding++);
            }
            for (int i = 0; i < ubos.capacity(); i++) {
                SpvcReflectedResource r = ubos.get(i);
                String blockName = Spvc.spvc_compiler_get_name(compiler, r.base_type_id());
                boolean implicit = blockName == null || blockName.isEmpty() || blockName.startsWith("gl_DefaultUniformBlock");
                int mslIndex;
                if (implicit && out.blockSize == 0 && out.members.isEmpty()) {
                    mslIndex = 0;
                    readBlock(compiler, r, stack, out);
                } else {
                    mslIndex = nextBuffer++;
                    out.extraBlocks.put(blockName != null ? blockName : r.nameString(), mslIndex);
                }
                bind(compiler, context, model, r, mslIndex, 0, 0, stack);
            }
            // Samplers: shared numbering by name.
            SpvcReflectedResource.Buffer samplers = list(resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, context, stack);
            for (int i = 0; i < samplers.capacity(); i++) {
                SpvcReflectedResource r = samplers.get(i);
                int index = samplerIndex.getInt(r.nameString());
                bind(compiler, context, model, r, 0, index, index, stack);
            }
            // Varyings: give fragment inputs the vertex output location of the same name.
            if (varyingLocations != null) {
                SpvcReflectedResource.Buffer inputs = list(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, context, stack);
                for (int i = 0; i < inputs.capacity(); i++) {
                    SpvcReflectedResource r = inputs.get(i);
                    Integer loc = varyingLocations.get(r.nameString());
                    if (loc != null) {
                        Spvc.spvc_compiler_set_decoration(compiler, r.id(), DECORATION_LOCATION, loc);
                    }
                }
            }
            check(Spvc.spvc_compiler_set_entry_point(compiler, "main", model), context, "entry point");
            check(Spvc.spvc_compiler_compile(compiler, ptr), context, "compile " + name + (model == EXEC_VERTEX ? " (vertex)" : " (fragment)"));
            out.msl = MemoryUtil.memUTF8(ptr.get(0));
            String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model);
            out.entryPoint = entry != null ? entry : "main0";
        });
    }

    private static void bind(final long compiler, final long context, final int model, final SpvcReflectedResource r, final int buffer,
                             final int texture, final int sampler, final MemoryStack stack) throws ShaderCompileException {
        SpvcMslResourceBinding rb = SpvcMslResourceBinding.calloc(stack);
        Spvc.spvc_msl_resource_binding_init(rb);
        rb.stage(model);
        rb.desc_set(Spvc.spvc_compiler_get_decoration(compiler, r.id(), DECORATION_DESCRIPTOR_SET));
        rb.binding(Spvc.spvc_compiler_get_decoration(compiler, r.id(), DECORATION_BINDING));
        rb.msl_buffer(buffer);
        rb.msl_texture(texture);
        rb.msl_sampler(sampler);
        check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, rb), context, "bind " + r.nameString());
    }

    private static void readBlock(final long compiler, final SpvcReflectedResource r, final MemoryStack stack, final StageResult out) {
        long type = Spvc.spvc_compiler_get_type_handle(compiler, r.base_type_id());
        PointerBuffer size = stack.callocPointer(1);
        Spvc.spvc_compiler_get_declared_struct_size(compiler, type, size);
        out.blockSize = (int) size.get(0);
        IntBuffer tmp = stack.callocInt(1);
        int count = Spvc.spvc_type_get_num_member_types(type);
        for (int i = 0; i < count; i++) {
            int memberTypeId = Spvc.spvc_type_get_member_type(type, i);
            long member = Spvc.spvc_compiler_get_type_handle(compiler, memberTypeId);
            String memberName = Spvc.spvc_compiler_get_member_name(compiler, r.base_type_id(), i);
            Spvc.spvc_compiler_type_struct_member_offset(compiler, type, i, tmp);
            int offset = tmp.get(0);
            int arrayDims = Spvc.spvc_type_get_num_array_dimensions(member);
            int arraySize = arrayDims > 0 ? Spvc.spvc_type_get_array_dimension(member, 0) : 0;
            int arrayStride = 0;
            if (arrayDims > 0 && Spvc.spvc_compiler_type_struct_member_array_stride(compiler, type, i, tmp) == Spvc.SPVC_SUCCESS) {
                arrayStride = tmp.get(0);
            }
            int columns = Spvc.spvc_type_get_columns(member);
            int matrixStride = 0;
            if (columns > 1 && Spvc.spvc_compiler_type_struct_member_matrix_stride(compiler, type, i, tmp) == Spvc.SPVC_SUCCESS) {
                matrixStride = tmp.get(0);
            }
            out.members.add(new BlockMember(memberName != null ? memberName : ("_m" + i), offset, Spvc.spvc_type_get_basetype(member),
                Spvc.spvc_type_get_vector_size(member), columns, arraySize, arrayStride, matrixStride));
        }
    }

    static boolean isFloat(final int baseType) {
        return baseType == BASETYPE_FP32;
    }

    static boolean isIntegerLike(final int baseType) {
        return baseType == BASETYPE_INT32 || baseType == BASETYPE_UINT32 || baseType == BASETYPE_BOOL;
    }

    private static void check(final int result, final long context, final String what) throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            String detail = context != 0L ? Spvc.spvc_context_get_last_error_string(context) : null;
            throw new ShaderCompileException("SPIRV-Cross: " + what + " failed (" + result + ")" + (detail != null ? ": " + detail : ""));
        }
    }
}
