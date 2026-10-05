package dev.minemetal.client.glcompat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.minemetal.client.metal.MetalDevice;
import dev.minemetal.client.metal.MetalEnums;
import dev.minemetal.client.metal.MetalGpuBuffer;
import dev.minemetal.client.metal.MetalRenderPass;
import dev.minemetal.client.metal.MetalRenderPipeline;
import dev.minemetal.client.metal.MetalSampler;
import dev.minemetal.client.metal.MetalTextureView;
import dev.minemetal.client.metal.Native;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A renderpearl pipeline whose shaders are an emulated GL program (an Iris gbuffer program) instead of the
 * pipeline's own. It mirrors what the GL backend does when Iris overrides a pipeline:
 * <ul>
 *   <li>the original pipeline's fixed-function state (blend of target 0, depth, cull, topology, vertex layout) is used,
 *       then {@link Hooks#setupState()} runs (Iris binds its program, uniforms, samplers, framebuffer, blend overrides);</li>
 *   <li>the draw goes into the emulated GL draw framebuffer (the pack's render targets), not the pass attachments;</li>
 *   <li>the pass's uniform buffers / textures are bound through GL binding points: {@link Hooks#uniformBinding} gives the
 *       binding of a renderpearl uniform name, the program's blocks get their binding from glUniformBlockBinding and
 *       its samplers their texture unit from glUniform1i.</li>
 * </ul>
 */
public final class GlOverridePipeline implements MetalRenderPass.OverridePipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal/GL");

    /** Supplied by the caller (the Iris fork). */
    public interface Hooks {
        /** Before each draw: use the program, update its uniforms/samplers, bind the target framebuffer. */
        void setupState();

        /** After the render pass that used the pipeline. */
        void clearState();

        /** GL uniform buffer binding point for a renderpearl uniform buffer name, or -1. */
        int uniformBinding(String name);

        /** GL texture unit for a renderpearl sampler / texel buffer name, or -1. */
        int samplerBinding(String name);

        /** GL uniform buffer binding point of the push-constant block, or -1. */
        int pushConstantBinding();
    }

    private final MetalRenderPipeline original;
    private final BackendRenderPipeline.CreateInfo info;
    private final int program;
    private final List<@Nullable VertexFormat> vertexFormats;
    private final Hooks hooks;
    private final String name;
    private final Object2LongOpenHashMap<String> pipelines = new Object2LongOpenHashMap<>();
    private boolean closed;
    private boolean loggedFirstDraw;

    public GlOverridePipeline(final BackendRenderPipeline original, final int program, final List<@Nullable VertexFormat> vertexFormats, final Hooks hooks) {
        this.original = (MetalRenderPipeline) original;
        this.info = this.original.createInfo();
        this.program = program;
        this.vertexFormats = vertexFormats;
        this.hooks = hooks;
        GlProgramObject p = MetalGL.program(program);
        this.name = this.info.name() + " + " + (p != null && p.debugName != null ? p.debugName : "glprogram" + program);
    }

    @Override
    public List<BindGroupLayout.UniformDescription> uniforms() {
        return this.info.uniforms();
    }

    @Override
    public MetalRenderPass.@Nullable Target beforeDraw() {
        List<@Nullable ColorTargetState> targets = this.info.colorTargetStates();
        MetalGL.applyColorTargetState(targets.isEmpty() ? null : targets.get(0));
        this.hooks.setupState();
        GlProgramObject p = MetalGL.program(this.program);
        if (p == null || !p.linked || p.compiled == null) {
            return null;
        }
        MetalGL.DrawTarget t = MetalGL.drawTarget(p);
        if (t == null) {
            return null;
        }
        this.lastFormats = t.formats();
        return new MetalRenderPass.Target(t.views(), t.depthView());
    }

    private GpuFormat[] lastFormats = new GpuFormat[0];

    @Override
    public boolean bind(final long ctx, final MetalRenderPass.Target target, final List<BindGroupLayout.UniformDescription> descriptions,
                        final @Nullable Object[] values, final @Nullable ByteBuffer pushConstants) {
        GlProgramObject p = MetalGL.program(this.program);
        if (p == null || p.compiled == null) {
            return false;
        }
        long pipeline = this.pipelineFor(p, this.lastFormats, target.depthView() != 0L);
        if (pipeline == 0L || !Native.passSetPipeline(ctx, pipeline)) {
            return false;
        }
        bindDefaultAttributes(ctx);
        MetalGL.bindLooseUniforms(p);
        long usedUnits = 0L;
        for (int i = 0; i < descriptions.size(); i++) {
            Object value = values != null && i < values.length ? values[i] : null;
            if (value == null) {
                continue;
            }
            BindGroupLayout.UniformDescription d = descriptions.get(i);
            switch (d.type()) {
                case UNIFORM_BUFFER -> {
                    int binding = this.hooks.uniformBinding(d.name());
                    if (binding < 0) {
                        continue;
                    }
                    GpuBufferSlice slice = (GpuBufferSlice) value;
                    long handle = ((MetalGpuBuffer) slice.buffer()).handle();
                    for (int stage = 0; stage < 2; stage++) {
                        int index = p.bufferIndexForBinding(stage, binding);
                        if (index >= 0) {
                            Native.passSetBuffer(ctx, 1 << stage, handle, slice.offset(), index);
                        }
                    }
                }
                case COMBINED_IMAGE_SAMPLER -> {
                    int unit = this.hooks.samplerBinding(d.name());
                    if (unit < 0) {
                        continue;
                    }
                    TextureViewAndSampler pair = (TextureViewAndSampler) value;
                    for (GlProgramObject.Slot slot : p.slots) {
                        if (slot.sampler && slot.unit == unit) {
                            Native.passSetTexture(ctx, 3, ((MetalTextureView) pair.view()).handle(), slot.samplerIndex);
                            Native.passSetSampler(ctx, 3, ((MetalSampler) pair.sampler()).handle(), slot.samplerIndex);
                            usedUnits |= 1L << unit;
                        }
                    }
                }
                case TEXEL_BUFFER -> {
                    int unit = this.hooks.samplerBinding(d.name());
                    GpuFormat format = d.gpuFormat();
                    if (unit < 0 || format == null) {
                        continue;
                    }
                    GpuBufferSlice slice = (GpuBufferSlice) value;
                    for (GlProgramObject.Slot slot : p.slots) {
                        if (slot.sampler && slot.unit == unit) {
                            Native.passSetTexelBuffer(ctx, 3, ((MetalGpuBuffer) slice.buffer()).handle(), slice.offset(), slice.length(),
                                MetalEnums.pixelFormat(format), format.blockSize(), slot.samplerIndex);
                            usedUnits |= 1L << unit;
                        }
                    }
                }
            }
        }
        if (pushConstants != null && pushConstants.remaining() > 0) {
            int binding = this.hooks.pushConstantBinding();
            if (binding >= 0) {
                for (int stage = 0; stage < 2; stage++) {
                    int index = p.bufferIndexForBinding(stage, binding);
                    if (index >= 0) {
                        Native.passSetBytes(ctx, 1 << stage, MemoryUtil.memAddress(pushConstants), pushConstants.remaining(), index);
                    }
                }
            }
        }
        MetalGL.bindUnitSamplers(p, usedUnits);
        if (!this.loggedFirstDraw) {
            this.loggedFirstDraw = true;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < descriptions.size(); i++) {
                BindGroupLayout.UniformDescription d = descriptions.get(i);
                Object value = values != null && i < values.length ? values[i] : null;
                sb.append(' ').append(d.name()).append('(').append(d.type()).append(value == null ? ",null" : "");
                if (d.type().name().equals("UNIFORM_BUFFER")) {
                    int binding = this.hooks.uniformBinding(d.name());
                    sb.append(",b").append(binding).append("->v").append(p.bufferIndexForBinding(0, binding)).append("/f").append(p.bufferIndexForBinding(1, binding));
                }
                sb.append(')');
            }
            sb.append(" blocks ").append(p.blockNames).append(" bindings ").append(java.util.Arrays.toString(p.blockBindings));
            sb.append(" push ").append(pushConstants == null ? "none" : pushConstants.remaining() + "B");
            LOGGER.info("override {}: resources{}", this.name, sb);
            LOGGER.info("override {}: first draw into {} color target(s){}, units from pass {}", this.name, target.colorViews().length,
                target.depthView() != 0L ? " + depth" : "", Long.toBinaryString(usedUnits));
        }
        return true;
    }

    @Override
    public void passEnded() {
        this.hooks.clearState();
    }

    private long pipelineFor(final GlProgramObject p, final GpuFormat[] formats, final boolean hasDepth) {
        StringBuilder key = new StringBuilder(48);
        for (GpuFormat f : formats) {
            key.append(f == null ? "-" : f.ordinal()).append(',');
        }
        key.append(hasDepth).append('|').append(MetalGL.blendKey());
        String k = key.toString();
        long cached = this.pipelines.getOrDefault(k, 0L);
        if (cached != 0L) {
            return cached == -1L ? 0L : cached;
        }
        long created = this.create(p, formats, hasDepth);
        this.pipelines.put(k, created != 0L ? created : -1L);
        return created;
    }

    private long create(final GlProgramObject p, final GpuFormat[] formats, final boolean hasDepth) {
        GlProgramCompiler.Result compiled = p.compiled;
        if (compiled == null) {
            return 0L;
        }
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            return 0L;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Vertex layout: one Metal buffer per vertex format slot, attributes matched to the program inputs by name
            // ("iris_" + element name first, like Iris on GL).
            int slots = this.vertexFormats.size();
            IntBuffer layouts = stack.callocInt((slots + 1) * 3);
            int layoutCount = 0;
            List<GlProgramCompiler.Interface> inputs = compiled.vertex().inputs;
            IntBuffer attribs = stack.callocInt(Math.max(inputs.size(), 1) * 4);
            int attribCount = 0;
            String baseFormat = System.getProperty("minemetal.debugEntityFormat");
            boolean useBase = baseFormat != null && !baseFormat.isEmpty() && this.name.contains(baseFormat);
            if (useBase) {
                LOGGER.info("override {}: using vanilla ENTITY vertex layout (minemetal.debugEntityFormat)", this.name);
            }
            for (int slot = 0; slot < slots; slot++) {
                VertexFormat format = this.vertexFormats.get(slot);
                if (format == null) {
                    continue;
                }
                if (useBase && slot == 0) {
                    format = com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY;
                }
                layouts.put(layoutCount * 3, MetalRenderPipeline.VERTEX_BUFFER_BASE - slot);
                layouts.put(layoutCount * 3 + 1, format.getVertexSize());
                layouts.put(layoutCount * 3 + 2, format.getStepRate());
                layoutCount++;
                for (VertexFormatElement element : format.getElements()) {
                    GlProgramCompiler.Interface in = findInput(inputs, "iris_" + element.name());
                    if (in == null) {
                        in = findInput(inputs, element.name());
                    }
                    if (in == null || attribCount >= inputs.size()) {
                        continue;
                    }
                    long fmt = MetalEnums.vertexFormat(matchSignedness(element.format(), in.baseType()));
                    if (fmt < 0) {
                        LOGGER.warn("{}: vertex element {} has no Metal format", this.name, element.name());
                        continue;
                    }
                    attribs.put(attribCount * 4, in.location());
                    attribs.put(attribCount * 4 + 1, MetalRenderPipeline.VERTEX_BUFFER_BASE - slot);
                    attribs.put(attribCount * 4 + 2, element.offset());
                    attribs.put(attribCount * 4 + 3, (int) fmt);
                    attribCount++;
                }
            }
            // GL reads (0,0,0,1) for attributes without an array; Metal needs every stage input in the descriptor:
            // feed the missing ones from a constant zero buffer (components it lacks default to 0, w to 1).
            boolean needsDefaults = false;
            for (GlProgramCompiler.Interface in : inputs) {
                boolean bound = false;
                for (int i = 0; i < attribCount; i++) {
                    if (attribs.get(i * 4) == in.location()) {
                        bound = true;
                        break;
                    }
                }
                if (bound || attribCount >= attribs.capacity() / 4) {
                    continue;
                }
                long scalar = GlProgramCompiler.isFloat(in.baseType()) ? MTL_FLOAT : in.baseType() == BASETYPE_UINT32 ? MTL_UINT : MTL_INT;
                attribs.put(attribCount * 4, in.location());
                attribs.put(attribCount * 4 + 1, DEFAULT_ATTRIBUTE_BUFFER);
                attribs.put(attribCount * 4 + 2, 0);
                attribs.put(attribCount * 4 + 3, (int) scalar);
                attribCount++;
                needsDefaults = true;
            }
            if (needsDefaults) {
                layouts.put(layoutCount * 3, DEFAULT_ATTRIBUTE_BUFFER);
                layouts.put(layoutCount * 3 + 1, 16);
                layouts.put(layoutCount * 3 + 2, -1);
                layoutCount++;
            }
            if (LOGGER.isInfoEnabled()) {
                StringBuilder sb = new StringBuilder();
                for (int slot = 0; slot < slots; slot++) {
                    VertexFormat format = this.vertexFormats.get(slot);
                    if (format == null) {
                        continue;
                    }
                    sb.append(" slot").append(slot).append("[stride ").append(format.getVertexSize()).append(':');
                    for (VertexFormatElement e : format.getElements()) {
                        sb.append(' ').append(e.name()).append('=').append(e.format()).append('@').append(e.offset());
                    }
                    sb.append(']');
                }
                sb.append(" | inputs:");
                for (GlProgramCompiler.Interface in : inputs) {
                    sb.append(' ').append(in.name()).append('#').append(in.location()).append('/').append(in.baseType()).append('x').append(in.vecSize());
                }
                sb.append(" | attribs:");
                for (int i = 0; i < attribCount; i++) {
                    sb.append(' ').append(attribs.get(i * 4)).append("<-b").append(attribs.get(i * 4 + 1)).append('+').append(attribs.get(i * 4 + 2))
                        .append(" f").append(attribs.get(i * 4 + 3));
                }
                LOGGER.info("override {} vertex layout:{}", this.name, sb);
            }
            LongBuffer targets = stack.callocLong(Math.max(formats.length, 1) * 9);
            MetalGL.fillColorTargets(targets, formats, formats.length);
            DepthStencilState depth = this.info.depthStencilState();
            IntBuffer raster = stack.callocInt(7);
            raster.put(0, this.info.primitiveTopology().ordinal());
            raster.put(1, this.info.cull() ? 1 : 0);
            raster.put(2, this.info.polygonMode() == PolygonMode.WIREFRAME ? 1 : 0);
            raster.put(3, Boolean.getBoolean("minemetal.frontFaceCCW") ? 1 : 0); // same winding as the vanilla pipelines
            raster.put(4, hasDepth ? 1 : 0);
            raster.put(5, (int) MetalEnums.compare(hasDepth && depth != null ? depth.depthTest()
                : com.mojang.renderpearl.api.pipeline.CompareOp.ALWAYS_PASS));
            raster.put(6, hasDepth && depth != null && depth.writeDepth() ? 1 : 0);
            String noDepth = System.getProperty("minemetal.debugNoDepth");
            if (noDepth != null && !noDepth.isEmpty() && this.name.contains(noDepth)) {
                // debugging aid: draw matching override pipelines without depth test/write
                raster.put(5, (int) MetalEnums.compare(com.mojang.renderpearl.api.pipeline.CompareOp.ALWAYS_PASS));
                raster.put(6, 0);
                LOGGER.info("override {}: depth test disabled (minemetal.debugNoDepth)", this.name);
            }
            FloatBuffer bias = stack.callocFloat(2);
            if (depth != null) {
                bias.put(0, depth.depthBiasConstant());
                bias.put(1, depth.depthBiasScaleFactor());
            }
            ByteBuffer err = stack.calloc(2048);
            ByteBuffer vs = MemoryUtil.memUTF8(compiled.vertex().msl);
            String solid = System.getProperty("minemetal.debugSolid");
            boolean debugSolid = solid != null && !solid.isEmpty() && this.name.contains(solid);
            if (debugSolid) {
                LOGGER.info("override {}: fragment shader replaced by solid magenta (minemetal.debugSolid)", this.name);
            }
            ByteBuffer fs = MemoryUtil.memUTF8(debugSolid
                ? "#include <metal_stdlib>\nusing namespace metal;\nfragment float4 mmDebugSolid() { return float4(1.0, 0.0, 1.0, 1.0); }\n"
                : compiled.fragment().msl);
            try {
                long handle = Native.pipelineCreate(device.ctx(), MemoryUtil.memAddress(stack.UTF8(this.name)), MemoryUtil.memAddress(vs),
                    MemoryUtil.memAddress(stack.UTF8(compiled.vertex().entryPoint)), MemoryUtil.memAddress(fs),
                    MemoryUtil.memAddress(stack.UTF8(debugSolid ? "mmDebugSolid" : compiled.fragment().entryPoint)), MemoryUtil.memAddress(layouts), layoutCount,
                    MemoryUtil.memAddress(attribs), attribCount, MemoryUtil.memAddress(targets), formats.length, MemoryUtil.memAddress(raster),
                    MemoryUtil.memAddress(bias), hasDepth, MemoryUtil.memAddress(err), err.capacity());
                if (handle == 0L) {
                    LOGGER.error("Metal rejected override pipeline {}: {}", this.name, MemoryUtil.memUTF8(MemoryUtil.memAddress(err)));
                }
                return handle;
            } finally {
                MemoryUtil.memFree(vs);
                MemoryUtil.memFree(fs);
            }
        }
    }

    /** MTLVertexFormat Float / Int / UInt (stable Metal API values). */
    private static final long MTL_FLOAT = 28;
    private static final long MTL_INT = 32;
    private static final long MTL_UINT = 36;
    private static final int BASETYPE_INT32 = 7;
    private static final int BASETYPE_UINT32 = 8;
    /** Metal buffer index of the constant zero buffer (between push constants at 20 and vertex buffers at 23..30). */
    static final int DEFAULT_ATTRIBUTE_BUFFER = 21;
    private static @Nullable GpuBuffer zeroBuffer;

    /**
     * GL converts integer vertex data to the shader's int/uint type (glVertexAttribIPointer); Metal requires the
     * signedness to match, so pick the signed/unsigned variant of the element format the shader input asks for.
     */
    private static GpuFormat matchSignedness(final GpuFormat format, final int shaderBaseType) {
        String n = format.name();
        try {
            if (shaderBaseType == BASETYPE_INT32 && n.endsWith("_UINT")) {
                return GpuFormat.valueOf(n.substring(0, n.length() - 5) + "_SINT");
            }
            if (shaderBaseType == BASETYPE_UINT32 && n.endsWith("_SINT")) {
                return GpuFormat.valueOf(n.substring(0, n.length() - 5) + "_UINT");
            }
        } catch (IllegalArgumentException ignored) {
            // no such variant: keep the original
        }
        return format;
    }

    static void bindDefaultAttributes(final long ctx) {
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            return;
        }
        if (zeroBuffer == null) {
            ByteBuffer zeros = MemoryUtil.memCalloc(64);
            zeroBuffer = device.createBuffer(() -> "MineMetal default vertex attributes", GpuBuffer.USAGE_VERTEX, zeros);
            MemoryUtil.memFree(zeros);
        }
        Native.passSetVertexBuffer(ctx, ((MetalGpuBuffer) zeroBuffer).handle(), 0L, DEFAULT_ATTRIBUTE_BUFFER);
    }

    private static GlProgramCompiler.@Nullable Interface findInput(final List<GlProgramCompiler.Interface> inputs, final String name) {
        for (GlProgramCompiler.Interface in : inputs) {
            if (in.name().equals(name)) {
                return in;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        MetalDevice device = MetalDevice.current();
        for (long handle : this.pipelines.values()) {
            if (handle != -1L && device != null) {
                device.encoder().queueForDestroy(() -> Native.release(handle));
            }
        }
        this.pipelines.clear();
    }
}
