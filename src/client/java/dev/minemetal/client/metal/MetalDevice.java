package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MetalDevice implements GpuDeviceBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal");
    /** Metal's winding test after our Y flip; overridable while we validate culling on real hardware. */
    private static final boolean FRONT_FACE_CCW = Boolean.getBoolean("minemetal.frontFaceCCW");

    private final long ctx;
    private final DeviceInfo deviceInfo;
    private final MetalCommandEncoder encoder;
    private final boolean labels;
    private boolean closed;

    MetalDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
        MetalEnums.init();
        // Fast math (with preserveInvariance) is the default; -Dminemetal.safeMath=true restores IEEE-exact shaders.
        Native.setFastMath(!Boolean.getBoolean("minemetal.safeMath"));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer err = stack.calloc(1024);
            this.ctx = Native.createContext(MemoryUtil.memAddress(err), err.capacity());
            if (this.ctx == 0L) {
                throw new BackendCreationException("Metal: " + MemoryUtil.memUTF8(MemoryUtil.memAddress(err)), BackendCreationException.Reason.PLATFORM_ERROR);
            }
            ByteBuffer name = stack.calloc(256);
            LongBuffer info = stack.callocLong(8);
            Native.contextInfo(this.ctx, MemoryUtil.memAddress(name), name.capacity(), MemoryUtil.memAddress(info));
            String deviceName = MemoryUtil.memUTF8(MemoryUtil.memAddress(name));
            if (Boolean.getBoolean("minemetal.gpuDebug")) {
                Native.setGpuDebug(this.ctx, true);
                LOGGER.info("MineMetal: per-encoder GPU error reporting enabled");
            }
            long maxBufferLength = info.get(0);
            boolean unified = info.get(2) != 0;
            this.labels = debugOptions.useLabels() || Boolean.getBoolean("minemetal.labels");
            this.deviceInfo = new DeviceInfo(
                deviceName,
                "Apple",
                "Metal via MineMetal (macOS " + System.getProperty("os.version") + ")",
                true,
                "Metal",
                1.0F,
                new DeviceLimits(16, 256, 16384, maxBufferLength, 1 << 16, 8, Integer.MAX_VALUE),
                new DeviceFeatures(true, true, true, false, true, true, true, true),
                Set.of(unified ? "unifiedMemory" : "discreteMemory"),
                // isExplicitDepthRequired: same Apple-GPU invariance workaround the Vulkan backend enables on Apple Silicon.
                new HintsAndWorkarounds(false, false, true, false),
                unified ? DeviceType.INTEGRATED : DeviceType.DISCRETE
            );
        }
        this.encoder = new MetalCommandEncoder(this);
        LOGGER.info("MineMetal: created Metal device '{}'", this.deviceInfo.name());
    }

    public long ctx() {
        return this.ctx;
    }

    public MetalCommandEncoder encoder() {
        return this.encoder;
    }

    boolean labelsEnabled() {
        return this.labels;
    }

    @Override
    public GpuSurfaceBackend createSurface(final long windowHandle, final BooleanSupplier isIconified) {
        return new MetalSurface(this, windowHandle, isIconified);
    }

    @Override
    public MetalCommandEncoder createCommandEncoder() {
        return this.encoder;
    }

    @Override
    public GpuSampler createSampler(final AddressMode addressModeU, final AddressMode addressModeV, final FilterMode minFilter,
                                    final FilterMode magFilter, final int maxAnisotropy, final OptionalDouble maxLod) {
        return new MetalSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(final @Nullable String label, final @GpuTexture.Usage int usage, final GpuFormat format, final int width,
                                    final int height, final int depthOrLayers, final int mipLevels) {
        return new MetalTexture(this, usage, label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalTextureView(this, (MetalTexture) texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
        return new MetalGpuBuffer.Direct(this, label, usage, size);
    }

    @Override
    public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
        MetalGpuBuffer.Direct buffer = new MetalGpuBuffer.Direct(this, label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
        // Brand-new shared-storage buffer: no GPU work can reference it yet, so a plain memcpy is safe.
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.contents(), data.remaining());
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.labels;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(final BackendRenderPipeline.CreateInfo info) {
        MetalRenderPipeline pipeline;
        try {
            pipeline = this.compile(info);
        } catch (ShaderCompileException | RuntimeException e) {
            LOGGER.error("MineMetal: couldn't compile pipeline {}", info.name(), e);
            pipeline = null;
        }
        MetalRenderPipeline result = pipeline;
        return () -> result;
    }

    private MetalRenderPipeline compile(final BackendRenderPipeline.CreateInfo info) throws ShaderCompileException {
        MslTranslator.Bindings bindings = MslTranslator.assign(info.uniforms());
        MslTranslator.Result vertex = null;
        MslTranslator.Result fragment = null;
        for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
            MslTranslator.Result r = MslTranslator.translate(shader, info.uniforms(), bindings);
            if (shader.module().type() == ShaderType.VERTEX) {
                vertex = r;
            } else {
                fragment = r;
            }
        }
        if (vertex == null || fragment == null) {
            throw new ShaderCompileException("Pipeline " + info.name() + " needs both a vertex and a fragment shader");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<BackendRenderPipeline.CreateInfo.VertexBuffer> vbs = info.vertexBuffers();
            IntBuffer layouts = stack.callocInt(Math.max(vbs.size(), 1) * 3);
            for (int i = 0; i < vbs.size(); i++) {
                BackendRenderPipeline.CreateInfo.VertexBuffer vb = vbs.get(i);
                layouts.put(i * 3, MetalRenderPipeline.VERTEX_BUFFER_BASE - vb.bufferSlot());
                layouts.put(i * 3 + 1, vb.stride());
                layouts.put(i * 3 + 2, vb.stepRate());
            }

            List<BackendRenderPipeline.CreateInfo.AttribBinding> attrs = info.attribBindings();
            IntBuffer attribs = stack.callocInt(Math.max(attrs.size(), 1) * 4);
            for (int i = 0; i < attrs.size(); i++) {
                BackendRenderPipeline.CreateInfo.AttribBinding a = attrs.get(i);
                long fmt = MetalEnums.vertexFormat(a.format());
                if (fmt < 0) {
                    throw new ShaderCompileException("Vertex format " + a.format() + " has no Metal equivalent (" + info.name() + ")");
                }
                attribs.put(i * 4, a.location());
                attribs.put(i * 4 + 1, MetalRenderPipeline.VERTEX_BUFFER_BASE - a.bufferSlot());
                attribs.put(i * 4 + 2, a.offset());
                attribs.put(i * 4 + 3, (int) fmt);
            }

            List<@Nullable ColorTargetState> targets = info.colorTargetStates();
            LongBuffer targetData = stack.callocLong(Math.max(targets.size(), 1) * 9);
            for (int i = 0; i < targets.size(); i++) {
                ColorTargetState t = targets.get(i);
                int base = i * 9;
                if (t == null) {
                    targetData.put(base, 0L);
                    continue;
                }
                long fmt = MetalEnums.pixelFormat(t.format());
                if (fmt < 0) {
                    throw new ShaderCompileException("Color target format " + t.format() + " has no Metal equivalent (" + info.name() + ")");
                }
                targetData.put(base, fmt);
                targetData.put(base + 1, t.writeMask());
                if (t.blendFunction().isPresent()) {
                    BlendFunction bf = t.blendFunction().get();
                    targetData.put(base + 2, 1L);
                    targetData.put(base + 3, MetalEnums.blendFactor(bf.color().sourceFactor()));
                    targetData.put(base + 4, MetalEnums.blendFactor(bf.color().destFactor()));
                    targetData.put(base + 5, MetalEnums.blendOp(bf.color().op()));
                    targetData.put(base + 6, MetalEnums.blendFactor(bf.alpha().sourceFactor()));
                    targetData.put(base + 7, MetalEnums.blendFactor(bf.alpha().destFactor()));
                    targetData.put(base + 8, MetalEnums.blendOp(bf.alpha().op()));
                }
            }

            DepthStencilState depth = info.depthStencilState();
            IntBuffer raster = stack.callocInt(7);
            raster.put(0, info.primitiveTopology().ordinal());
            raster.put(1, info.cull() ? 1 : 0);
            raster.put(2, info.polygonMode() == PolygonMode.WIREFRAME ? 1 : 0);
            raster.put(3, FRONT_FACE_CCW ? 1 : 0);
            raster.put(4, depth != null ? 1 : 0);
            raster.put(5, depth != null ? (int) MetalEnums.compare(depth.depthTest()) : 0);
            raster.put(6, depth != null && depth.writeDepth() ? 1 : 0);
            FloatBuffer bias = stack.callocFloat(2);
            if (depth != null) {
                bias.put(0, depth.depthBiasConstant());
                bias.put(1, depth.depthBiasScaleFactor());
            }

            ByteBuffer err = stack.calloc(4096);
            // Shader sources can be large: allocate them off the (small) stack and free them right after.
            ByteBuffer vsSource = MemoryUtil.memUTF8(vertex.source());
            ByteBuffer fsSource = MemoryUtil.memUTF8(fragment.source());
            long handle;
            try {
                handle = Native.pipelineCreate(
                this.ctx,
                MemoryUtil.memAddress(stack.UTF8(info.name())),
                MemoryUtil.memAddress(vsSource),
                MemoryUtil.memAddress(stack.UTF8(vertex.entryPoint())),
                MemoryUtil.memAddress(fsSource),
                MemoryUtil.memAddress(stack.UTF8(fragment.entryPoint())),
                MemoryUtil.memAddress(layouts), vbs.size(),
                MemoryUtil.memAddress(attribs), attrs.size(),
                MemoryUtil.memAddress(targetData), targets.size(),
                MemoryUtil.memAddress(raster),
                MemoryUtil.memAddress(bias),
                depth != null,
                MemoryUtil.memAddress(err), err.capacity()
                );
            } finally {
                MemoryUtil.memFree(vsSource);
                MemoryUtil.memFree(fsSource);
            }
            if (handle == 0L) {
                String message = MemoryUtil.memUTF8(MemoryUtil.memAddress(err));
                if (Boolean.getBoolean("minemetal.dumpShaders")) {
                    LOGGER.error("--- {} vertex MSL ---\n{}\n--- fragment MSL ---\n{}", info.name(), vertex.source(), fragment.source());
                }
                throw new ShaderCompileException("Metal rejected pipeline " + info.name() + ": " + message);
            }
            return new MetalRenderPipeline(this, handle, info.name(), info.uniforms(), bindings);
        }
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.encoder.destroy();
            Native.destroyContext(this.ctx);
        }
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalQueryPool(size);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }
}
