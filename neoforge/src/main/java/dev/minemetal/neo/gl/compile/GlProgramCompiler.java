package dev.minemetal.neo.gl.compile;


import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

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
    /** Programs with image load/store, SSBOs or compute: MSL 3.1 (native texture atomics). */
    private static final int MSL_VERSION_STORAGE = 3 * 10000 + 1 * 100;
    static final String MSL31_MARKER = "// mm:msl31\n";
    private static final int EXEC_VERTEX = 0;
    private static final int EXEC_FRAGMENT = 4;
    private static final int EXEC_COMPUTE = 5;
    private static final int EXECUTION_MODE_LOCAL_SIZE = 17;
    /** MSL buffer slots: loose block 0, named blocks 1.., SSBOs 10..18, buffer sizes 19, push 20, zero attrib 21, vertex 22..30. */
    static final int SSBO_BUFFER_BASE = 10;
    static final int MAX_SSBOS = 9;
    static final int BUFFER_SIZE_BUFFER = 19;
    /** Metal limits per stage: 16 sampler states; textures (combined samplers + storage images): 32 GL units + 8 images. */
    static final int MAX_SAMPLER_STATES = 16;
    static final int MAX_TEXTURES = 40;
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
        /** Storage images used by this stage (names; slots are program-wide, see {@link Result#images()}). */
        public final List<String> images = new ArrayList<>();
        /** Shader storage blocks: GL binding point -> MSL buffer index. */
        public final Map<Integer, Integer> ssbos = new LinkedHashMap<>();
        /** SPIRV-Cross wants the byte sizes of the storage buffers (array .length()) at buffer {@link #BUFFER_SIZE_BUFFER}. */
        public boolean needsBufferSizes;
        /** Compute only: local work group size. */
        public final int[] localSize = {1, 1, 1};

        StageResult(final int stage) {
            this.stage = stage;
        }
    }

    /**
     * Compiled program. For a compute program {@code vertex} is the kernel and {@code fragment} is null.
     * Storage image <i>i</i> uses Metal texture slot {@code samplers.size() + i}.
     */
    public record Result(StageResult vertex, StageResult fragment, List<String> samplers, List<String> images, boolean compute) {
        public List<StageResult> stages() {
            return this.fragment == null ? List.of(this.vertex) : List.of(this.vertex, this.fragment);
        }
    }

    /** Program-wide resource numbering shared by all stages. */
    private record Slots(Object2IntLinkedOpenHashMap<String> samplers, Object2IntLinkedOpenHashMap<String> images,
                         Map<Integer, Integer> ssbos, boolean storage, int [] samplerSlots) {
        /** Metal sampler-state slot of combined sampler {@code index} (its texture slot is {@code index}). */
        int samplerSlot(final int index) {
            return this.samplerSlots != null && index < this.samplerSlots.length ? this.samplerSlots[index] : index % MAX_SAMPLER_STATES;
        }
    }

    private static Slots slots(final String name, final int [] samplerSlots, final StageResult... infos) throws ShaderCompileException {
        Object2IntLinkedOpenHashMap<String> samplerIndex = new Object2IntLinkedOpenHashMap<>();
        for (StageResult info : infos) {
            for (String sn : info.samplers) {
                samplerIndex.putIfAbsent(sn, samplerIndex.size());
            }
        }

        Object2IntLinkedOpenHashMap<String> imageIndex = new Object2IntLinkedOpenHashMap<>();
        for (StageResult info : infos) {
            for (String im : info.images) {
                imageIndex.putIfAbsent(im, samplerIndex.size() + imageIndex.size());
            }
        }
        java.util.TreeSet<Integer> bindings = new java.util.TreeSet<>();
        for (StageResult info : infos) {
            bindings.addAll(info.ssbos.keySet());
        }
        if (bindings.size() > MAX_SSBOS) {
            throw new ShaderCompileException(name + ": " + bindings.size() + " shader storage buffers (MineMetal supports " + MAX_SSBOS + ")");
        }
        Map<Integer, Integer> ssbos = new LinkedHashMap<>();
        for (int b : bindings) {
            ssbos.put(b, SSBO_BUFFER_BASE + ssbos.size());
        }
        if (samplerIndex.size() + imageIndex.size() > MAX_TEXTURES) {
            throw new ShaderCompileException(name + ": " + (samplerIndex.size() + imageIndex.size()) + " textures exceed Metal's limit of "
                + MAX_TEXTURES + " per stage");
        }
        return new Slots(samplerIndex, imageIndex, ssbos, !imageIndex.isEmpty() || !ssbos.isEmpty(), samplerSlots);
    }

    /** Compiles a compute shader. */
    public static Result compileCompute(final String name, final String source) throws ShaderCompileException {
        return compileCompute(name, source, null);
    }

    /**
     * {@code samplerSlots}: Metal sampler-state slot per combined sampler, for programs with more than 16 samplers
     * (identical sampler states share a slot); null = one slot each.
     */
    public static Result compileCompute(final String name, final String source, final int [] samplerSlots) throws ShaderCompileException {
        ByteBuffer spv = toSpirv(name + ".csh", renameReserved(source), Shaderc.shaderc_compute_shader);
        try {
            StageResult info = new StageResult(STAGE_VERTEX);
            reflectOnly(spv, EXEC_COMPUTE, info);
            Slots slots = slots(name, samplerSlots, info);
            StageResult cs = new StageResult(STAGE_VERTEX);
            toMsl(name, spv, EXEC_COMPUTE, cs, slots, null);
            return new Result(cs, null, new ArrayList<>(slots.samplers().keySet()), new ArrayList<>(slots.images().keySet()), true);
        } finally {
            MemoryUtil.memFree(spv);
        }
    }

    /** Compiles and links a vertex + fragment pair. */
    public static Result compile(final String name, final String vertexSource, final String fragmentSource) throws ShaderCompileException {
        return compile(name, vertexSource, fragmentSource, null);
    }

    /** See {@link #compileCompute(String, String, int[])} for {@code samplerSlots}. */
    public static Result compile(final String name, final String vertexSource, final String fragmentSource, final int [] samplerSlots)
        throws ShaderCompileException {
        ByteBuffer vsSpv = toSpirv(name + ".vsh", renameReserved(vertexSource), Shaderc.shaderc_vertex_shader);
        ByteBuffer fsSpv = null;
        try {
            fsSpv = toSpirv(name + ".fsh", renameReserved(fragmentSource), Shaderc.shaderc_fragment_shader);
            // Pass 1: reflect both stages to build the shared sampler numbering and the varying locations.
            StageResult vsInfo = new StageResult(STAGE_VERTEX);
            StageResult fsInfo = new StageResult(STAGE_FRAGMENT);
            reflectOnly(vsSpv, EXEC_VERTEX, vsInfo);
            reflectOnly(fsSpv, EXEC_FRAGMENT, fsInfo);
            Slots slots = slots(name, samplerSlots, vsInfo, fsInfo);
            Map<String, Integer> varyingLocations = new LinkedHashMap<>();
            for (Interface out : vsInfo.outputs) {
                varyingLocations.put(out.name(), out.location());
            }
            // Pass 2: generate MSL with final slots.
            StageResult vs = new StageResult(STAGE_VERTEX);
            StageResult fs = new StageResult(STAGE_FRAGMENT);
            toMsl(name, vsSpv, EXEC_VERTEX, vs, slots, null);
            toMsl(name, fsSpv, EXEC_FRAGMENT, fs, slots, varyingLocations);
            return new Result(vs, fs, new ArrayList<>(slots.samplers().keySet()), new ArrayList<>(slots.images().keySet()), false);
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

    private static final java.util.regex.Pattern VERSION_LINE = java.util.regex.Pattern.compile("(?m)^[ \\t]*#version[ \\t]+(\\d+)[^\\n]*\\n");

    /**
     * GL drivers accept layout(binding = N), SSBOs and image load/store in older #versions (420pack / 430 features via
     * extensions, or leniency); glslang is strict. Enables those extensions for shaders below #version 430.
     */
    static String enableModernExtensions(final String source) {
        java.util.regex.Matcher m = VERSION_LINE.matcher(source);
        if (!m.find()) {
            return source;
        }
        int version;
        try {
            version = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return source;
        }
        if (version >= 430) {
            return source;
        }
        StringBuilder ext = new StringBuilder();
        for (String e : new String[]{"GL_ARB_shading_language_420pack", "GL_ARB_shader_storage_buffer_object", "GL_ARB_shader_image_load_store",
            "GL_ARB_explicit_uniform_location"}) {
            if (!source.contains(e)) {
                ext.append("#extension ").append(e).append(" : enable\n");
            }
        }
        return source.substring(0, m.end()) + ext + source.substring(m.end());
    }

    static String renameReserved(final String source) {
        java.util.regex.Matcher m = RESERVED.matcher(source);
        return m.find() ? m.replaceAll("mm_$1") : source;
    }

    private static ByteBuffer toSpirv(final String fileName, final String source, final int kind) throws ShaderCompileException {
        String finalSource = enableModernExtensions(source);
        String key = SpirvCache.key(finalSource, kind);
        ByteBuffer cached = SpirvCache.load(key);
        if (cached != null) {
            return cached;
        }
        long t0 = System.nanoTime();
        ByteBuffer spv = toSpirvUncached(fileName, finalSource, kind);
        SpirvCache.store(key, spv);
        CompileTiming.spirv(fileName, System.nanoTime() - t0);
        return spv;
    }

    private static ByteBuffer toSpirvUncached(final String fileName, final String finalSource, final int kind) throws ShaderCompileException {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        ByteBuffer src = MemoryUtil.memUTF8(finalSource, false);
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
        SpvcReflectedResource.Buffer images = list(resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, context, stack);
        for (int i = 0; i < images.capacity(); i++) {
            out.images.add(images.get(i).nameString());
        }
        SpvcReflectedResource.Buffer ssbos = list(resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, context, stack);
        for (int i = 0; i < ssbos.capacity(); i++) {
            // GL binding point as written in the shader (layout(binding = N)); our unique renumbering happens later.
            out.ssbos.putIfAbsent(Spvc.spvc_compiler_get_decoration(compiler, ssbos.get(i).id(), DECORATION_BINDING), -1);
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
                              final Slots slots, final Map<String, Integer> varyingLocations)
        throws ShaderCompileException {
        Object2IntLinkedOpenHashMap<String> samplerIndex = slots.samplers();
        boolean storage = slots.storage() || model == EXEC_COMPUTE;
        withCompiler(spv, (context, compiler, resources, stack) -> {
            reflect(context, compiler, resources, stack, out);
            // GL binding point of each storage block, read before the bindings are renumbered below.
            SpvcReflectedResource.Buffer storageBuffers = list(resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, context, stack);
            int[] ssboGlBinding = new int[storageBuffers.capacity()];
            for (int i = 0; i < storageBuffers.capacity(); i++) {
                ssboGlBinding[i] = Spvc.spvc_compiler_get_decoration(compiler, storageBuffers.get(i).id(), DECORATION_BINDING);
            }
            out.ssbos.clear();
            PointerBuffer ptr = stack.callocPointer(1);

            check(Spvc.spvc_compiler_create_compiler_options(compiler, ptr), context, "options");
            long options = ptr.get(0);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, storage ? MSL_VERSION_STORAGE : MSL_VERSION);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_BUFFER_SIZE_BUFFER_INDEX, BUFFER_SIZE_BUFFER);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
            if (model == EXEC_VERTEX) {
                Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
                // OpenGL clip space z is -w..w; Metal clips at 0..w.
                Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, true);
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
            SpvcReflectedResource.Buffer storageImages = list(resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, context, stack);
            for (int i = 0; i < storageImages.capacity(); i++) {
                Spvc.spvc_compiler_set_decoration(compiler, storageImages.get(i).id(), DECORATION_DESCRIPTOR_SET, 0);
                Spvc.spvc_compiler_set_decoration(compiler, storageImages.get(i).id(), DECORATION_BINDING, uniqueBinding++);
            }
            for (int i = 0; i < storageBuffers.capacity(); i++) {
                Spvc.spvc_compiler_set_decoration(compiler, storageBuffers.get(i).id(), DECORATION_DESCRIPTOR_SET, 0);
                Spvc.spvc_compiler_set_decoration(compiler, storageBuffers.get(i).id(), DECORATION_BINDING, uniqueBinding++);
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
                bind(compiler, context, model, r, 0, index, slots.samplerSlot(index), stack);
            }
            // Storage images: texture slots after the samplers, shared numbering by name.
            for (int i = 0; i < storageImages.capacity(); i++) {
                SpvcReflectedResource r = storageImages.get(i);
                int index = slots.images().getInt(r.nameString());
                bind(compiler, context, model, r, 0, index, 0, stack);
            }
            // Storage buffers: by GL binding point.
            for (int i = 0; i < storageBuffers.capacity(); i++) {
                SpvcReflectedResource r = storageBuffers.get(i);
                Integer index = slots.ssbos().get(ssboGlBinding[i]);
                if (index == null) {
                    continue;
                }
                out.ssbos.put(ssboGlBinding[i], index);
                bind(compiler, context, model, r, index, 0, 0, stack);
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
            if (model == EXEC_COMPUTE) {
                for (int d = 0; d < 3; d++) {
                    out.localSize[d] = Math.max(1, Spvc.spvc_compiler_get_execution_mode_argument_by_index(compiler, EXECUTION_MODE_LOCAL_SIZE, d));
                }
            }
            check(Spvc.spvc_compiler_compile(compiler, ptr), context,
                "compile " + name + (model == EXEC_VERTEX ? " (vertex)" : model == EXEC_COMPUTE ? " (compute)" : " (fragment)"));
            out.msl = (storage ? MSL31_MARKER : "") + MemoryUtil.memUTF8(ptr.get(0));
            out.needsBufferSizes = Spvc.spvc_compiler_msl_needs_buffer_size_buffer(compiler);
            String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model);
            out.entryPoint = entry != null ? entry : "main0";
            if (slots.samplerSlots() != null) {
                out.msl = mergeSharedSamplers(out.msl, out.entryPoint);
            }
        });
    }

    private static final java.util.regex.Pattern SAMPLER_PARAM = java.util.regex.Pattern.compile("sampler (\\w+) \\[\\[sampler\\((\\d+)\\)\\]\\]");

    /**
     * Combined samplers that share a Metal sampler slot (see GlProgramObject.planSamplers) become several entry-point
     * parameters with the same [[sampler(n)]], which Metal rejects. Keeps the first parameter per slot and points the
     * entry function's uses of the others at it (helper functions receive samplers as arguments and are unaffected).
     */
    static String mergeSharedSamplers(final String msl, final String entry) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(?:vertex|fragment|kernel)\\b[^;{}]*?\\b"
            + java.util.regex.Pattern.quote(entry) + "\\s*\\(").matcher(msl);
        if (!m.find()) {
            return msl;
        }
        int sigStart = m.end();
        int depth = 1;
        int i = sigStart;
        while (i < msl.length() && depth > 0) {
            char c = msl.charAt(i++);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        int sigEnd = i - 1;
        String sig = msl.substring(sigStart, sigEnd);
        Map<String, String> firstBySlot = new LinkedHashMap<>();
        Map<String, String> rename = new LinkedHashMap<>();
        java.util.regex.Matcher sm = SAMPLER_PARAM.matcher(sig);
        while (sm.find()) {
            String keep = firstBySlot.putIfAbsent(sm.group(2), sm.group(1));
            if (keep != null) {
                rename.put(sm.group(1), keep);
            }
        }
        if (rename.isEmpty()) {
            return msl;
        }
        for (String dup : rename.keySet()) {
            sig = sig.replaceFirst(",\\s*sampler " + java.util.regex.Pattern.quote(dup) + " \\[\\[sampler\\(\\d+\\)\\]\\]", "");
        }
        int bodyStart = msl.indexOf('{', sigEnd);
        if (bodyStart < 0) {
            return msl;
        }
        depth = 0;
        int bodyEnd = bodyStart;
        for (int j = bodyStart; j < msl.length(); j++) {
            char c = msl.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                bodyEnd = j;
                break;
            }
        }
        String body = msl.substring(bodyStart, bodyEnd + 1);
        for (Map.Entry<String, String> e : rename.entrySet()) {
            body = body.replaceAll("\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b", java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return msl.substring(0, sigStart) + sig + msl.substring(sigEnd, bodyStart) + body + msl.substring(bodyEnd + 1);
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
