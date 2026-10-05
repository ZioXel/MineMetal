package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.nio.IntBuffer;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * SPIR-V (as produced by the renderpearl frontend: descriptor set 0, binding N = uniform N) to Metal Shading Language.
 * <p>
 * Binding model: every uniform gets a dedicated Metal slot of its kind (buffer / texture / sampler), assigned in uniform
 * order and shared by both stages. Push constants go to {@link MetalRenderPipeline#PUSH_CONSTANT_BUFFER_INDEX}; vertex
 * buffers occupy the top of the buffer table.
 */
public final class MslTranslator {
    private static final int MSL_VERSION = 2 * 10000 + 4 * 100; // MSL 2.4
    private static final int EXEC_MODEL_VERTEX = 0;
    private static final int EXEC_MODEL_FRAGMENT = 4;

    private MslTranslator() {
    }

    public static final int STAGE_VERTEX = 1;
    public static final int STAGE_FRAGMENT = 2;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int DECORATION_BINDING = 33;

    /**
     * Per-uniform Metal slots (-1 = unused for that kind) plus, filled in during translation, which shader stages
     * actually read each uniform / the push constants (so we never bind to a stage that ignores it).
     */
    public record Bindings(int[] bufferIndex, int[] textureIndex, int[] samplerIndex, long[] texelPixelFormat, int[] texelSize, int[] stageMask,
                           int[] pushStageMask) {
        public int size() {
            return this.bufferIndex.length;
        }
    }

    public record Result(String source, String entryPoint) {
    }

    public static Bindings assign(final List<BindGroupLayout.UniformDescription> uniforms) throws ShaderCompileException {
        int n = uniforms.size();
        int[] buffers = new int[n];
        int[] textures = new int[n];
        int[] samplers = new int[n];
        long[] texelFormats = new long[n];
        int[] texelSizes = new int[n];
        int nextBuffer = 0;
        int nextTexture = 0;
        int nextSampler = 0;
        for (int i = 0; i < n; i++) {
            BindGroupLayout.UniformDescription u = uniforms.get(i);
            buffers[i] = -1;
            textures[i] = -1;
            samplers[i] = -1;
            texelFormats[i] = -1;
            switch (u.type()) {
                case UNIFORM_BUFFER -> buffers[i] = nextBuffer++;
                case COMBINED_IMAGE_SAMPLER -> {
                    textures[i] = nextTexture++;
                    samplers[i] = nextSampler++;
                }
                case TEXEL_BUFFER -> {
                    textures[i] = nextTexture++;
                    GpuFormat format = u.gpuFormat();
                    if (format == null) {
                        throw new ShaderCompileException("Texel buffer " + u.name() + " has no format");
                    }
                    texelFormats[i] = MetalEnums.pixelFormat(format);
                    texelSizes[i] = format.blockSize();
                    if (texelFormats[i] < 0) {
                        throw new ShaderCompileException("Texel buffer format " + format + " has no Metal equivalent");
                    }
                }
            }
        }
        if (nextBuffer > MetalRenderPipeline.MAX_UNIFORM_BUFFERS) {
            throw new ShaderCompileException("Too many uniform buffers for Metal binding model: " + nextBuffer);
        }
        if (nextSampler > 16) {
            throw new ShaderCompileException("Too many samplers for Metal (max 16): " + nextSampler);
        }
        return new Bindings(buffers, textures, samplers, texelFormats, texelSizes, new int[n], new int[1]);
    }

    public static Result translate(final BackendRenderPipeline.CreateInfo.Shader shader, final List<BindGroupLayout.UniformDescription> uniforms,
                                   final Bindings bindings) throws ShaderCompileException {
        ShaderType type = shader.module().type();
        int model = type == ShaderType.VERTEX ? EXEC_MODEL_VERTEX : EXEC_MODEL_FRAGMENT;
        IntBuffer spirv = shader.module().spv().asIntBuffer();
        long context = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer ptr = stack.callocPointer(1);
            check(Spvc.spvc_context_create(ptr), 0L, "create spvc context");
            context = ptr.get(0);
            check(Spvc.spvc_context_parse_spirv(context, spirv, spirv.remaining(), ptr), context, "parse SPIR-V");
            long ir = ptr.get(0);
            check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr), context,
                "create MSL compiler");
            long compiler = ptr.get(0);

            check(Spvc.spvc_compiler_create_compiler_options(compiler, ptr), context, "create options");
            long options = ptr.get(0);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
            if (type == ShaderType.VERTEX) {
                // Renderpearl's SPIR-V targets the GL/Vulkan framebuffer layout (NDC y=-1 -> memory row 0).
                // Metal maps NDC y=-1 to the *last* row, so flip to keep textures, scissors and copies identical.
                Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
            }
            check(Spvc.spvc_compiler_install_compiler_options(compiler, options), context, "install options");

            for (int i = 0; i < bindings.size(); i++) {
                SpvcMslResourceBinding rb = SpvcMslResourceBinding.calloc(stack);
                Spvc.spvc_msl_resource_binding_init(rb);
                rb.stage(model);
                rb.desc_set(0);
                rb.binding(i);
                rb.msl_buffer(Math.max(bindings.bufferIndex()[i], 0));
                rb.msl_texture(Math.max(bindings.textureIndex()[i], 0));
                rb.msl_sampler(Math.max(bindings.samplerIndex()[i], 0));
                check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, rb), context, "add resource binding " + uniforms.get(i).name());
            }
            SpvcMslResourceBinding pc = SpvcMslResourceBinding.calloc(stack);
            Spvc.spvc_msl_resource_binding_init(pc);
            pc.stage(model);
            pc.desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET);
            pc.binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING);
            pc.msl_buffer(MetalRenderPipeline.PUSH_CONSTANT_BUFFER_INDEX);
            check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, pc), context, "add push constant binding");

            check(Spvc.spvc_compiler_set_entry_point(compiler, shader.entryPoint(), model), context, "set entry point");
            check(Spvc.spvc_compiler_compile(compiler, ptr), context, "compile to MSL");
            String source = MemoryUtil.memUTF8(ptr.get(0));
            String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, shader.entryPoint(), model);
            recordStageUsage(compiler, context, type == ShaderType.VERTEX ? STAGE_VERTEX : STAGE_FRAGMENT, bindings, stack);
            return new Result(source, entry != null ? entry : shader.entryPoint());
        } finally {
            if (context != 0L) {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static final int[] DESCRIPTOR_RESOURCE_TYPES = {
        Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
        Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
        Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
        Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
        Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
        Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
    };

    private static void recordStageUsage(final long compiler, final long context, final int stageBit, final Bindings bindings,
                                         final MemoryStack stack) throws ShaderCompileException {
        PointerBuffer ptr = stack.callocPointer(1);
        PointerBuffer count = stack.callocPointer(1);
        // Only resources the entry point actually reaches: the frontend compiles GLSL at -O0, so SPIR-V still declares
        // uniform blocks pulled in through shared #includes that this stage never reads (and MSL never emits).
        check(Spvc.spvc_compiler_get_active_interface_variables(compiler, ptr), context, "get active variables");
        long activeSet = ptr.get(0);
        check(Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, ptr, activeSet), context, "reflect resources");
        long resources = ptr.get(0);
        for (int resourceType : DESCRIPTOR_RESOURCE_TYPES) {
            check(Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, ptr, count), context, "list resources");
            SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(ptr.get(0), (int) count.get(0));
            for (int i = 0; i < list.capacity(); i++) {
                int id = list.get(i).id();
                int set = Spvc.spvc_compiler_get_decoration(compiler, id, DECORATION_DESCRIPTOR_SET);
                int binding = Spvc.spvc_compiler_get_decoration(compiler, id, DECORATION_BINDING);
                if (set == 0 && binding >= 0 && binding < bindings.size()) {
                    synchronized (bindings) {
                        bindings.stageMask()[binding] |= stageBit;
                    }
                }
            }
        }
        check(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, ptr, count), context,
            "list push constants");
        if (count.get(0) > 0) {
            synchronized (bindings) {
                bindings.pushStageMask()[0] |= stageBit;
            }
        }
    }

    private static void check(final int result, final long context, final String what) throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            String detail = context != 0L ? Spvc.spvc_context_get_last_error_string(context) : null;
            throw new ShaderCompileException("SPIRV-Cross failed to " + what + " (" + result + ")" + (detail != null ? ": " + detail : ""));
        }
    }

    static boolean isSampled(final UniformType type) {
        return type == UniformType.COMBINED_IMAGE_SAMPLER;
    }
}
