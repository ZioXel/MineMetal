package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MetalRenderPass implements RenderPassBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal");
    private static final int STAGES_ALL = 3;
    private static boolean warnedMissingUniform;

    private final MetalDevice device;
    private final long ctx;
    private final RenderPass.RenderArea renderArea;
    private final int outputWidth;
    private final int outputHeight;
    private @Nullable MetalRenderPipeline pipeline;
    /** Pipelines are applied lazily at the next draw: the frontend often sets one and replaces it before drawing. */
    private boolean pipelineApplied;
    private boolean pipelineUsable;
    private @Nullable Object[] uniforms = new Object[0];
    /** Bit i set = uniform i changed since the last draw (uniforms beyond 63 are always re-sent). */
    private long dirtyMask;
    private boolean bindingsDirty;
    private int pushedDebugGroups;

    MetalRenderPass(final MetalDevice device, final RenderPass.RenderArea renderArea, final int outputWidth, final int outputHeight) {
        this.device = device;
        this.ctx = device.ctx();
        this.renderArea = renderArea;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    @Override
    public void pushDebugGroup(final Supplier<String> label) {
        this.pushedDebugGroups++;
        if (this.device.labelsEnabled()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Native.passPushDebug(this.ctx, MemoryUtil.memAddress(stack.UTF8(label.get())));
            }
        }
    }

    @Override
    public void popDebugGroup() {
        if (this.pushedDebugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        this.pushedDebugGroups--;
        if (this.device.labelsEnabled()) {
            Native.passPopDebug(this.ctx);
        }
    }

    @Override
    public void setPipeline(final BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof MetalRenderPipeline metal)) {
            throw new IllegalArgumentException("Pipeline must be instance of MetalRenderPipeline");
        }
        this.pipeline = metal;
        this.pipelineApplied = false;
        int count = metal.uniforms().size();
        if (this.uniforms.length != count) {
            this.uniforms = new Object[count];
        } else {
            java.util.Arrays.fill(this.uniforms, null);
        }
        this.dirtyMask = -1L;
        this.bindingsDirty = true;
    }

    @Override
    public void setUniform(final int index, final @Nullable Object value) {
        Object previous = this.uniforms[index];
        if (previous == value || (value != null && value.equals(previous))) {
            return; // same slice / same texture+sampler pair: nothing to re-bind
        }
        this.uniforms[index] = value;
        if (index < 64) {
            this.dirtyMask |= 1L << index;
        }
        this.bindingsDirty = true;
    }

    @Override
    public void pushConstants(final ByteBuffer value) {
        int length = value.remaining();
        if (length == 0) {
            return;
        }
        int stages = this.pipeline != null ? this.pipeline.bindings().pushStageMask()[0] : STAGES_ALL;
        if (stages == 0) {
            return;
        }
        if (value.isDirect()) {
            Native.passSetBytes(this.ctx, stages, MemoryUtil.memAddress(value), length, MetalRenderPipeline.PUSH_CONSTANT_BUFFER_INDEX);
        } else {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer copy = stack.malloc(length);
                copy.put(value.duplicate()).flip();
                Native.passSetBytes(this.ctx, stages, MemoryUtil.memAddress(copy), length, MetalRenderPipeline.PUSH_CONSTANT_BUFFER_INDEX);
            }
        }
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        // Same origin convention as Vulkan: offsets count from memory row 0 (we flip Y in the vertex stage).
        Native.passSetScissor(this.ctx, x, y, width, height);
    }

    @Override
    public void disableScissor() {
        Native.passSetScissor(this.ctx, this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
    }

    @Override
    public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
        if (vertexBuffer != null) {
            MetalGpuBuffer buffer = (MetalGpuBuffer) vertexBuffer.buffer();
            Native.passSetVertexBuffer(this.ctx, buffer.handle(), vertexBuffer.offset(), MetalRenderPipeline.VERTEX_BUFFER_BASE - slot);
        }
    }

    @Override
    public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
        Native.passSetIndexBuffer(this.ctx, ((MetalGpuBuffer) indexBuffer).handle(), indexType == IndexType.INT);
    }

    private boolean prepareDraw() {
        if (this.pipeline == null) {
            return false;
        }
        if (!this.pipelineApplied) {
            this.pipelineApplied = true;
            this.pipelineUsable = Native.passSetPipeline(this.ctx, this.pipeline.handle());
        }
        if (!this.pipelineUsable) {
            return false;
        }
        if (this.bindingsDirty) {
            this.flushBindings(this.pipeline);
            this.bindingsDirty = false;
        }
        return true;
    }

    private void flushBindings(final MetalRenderPipeline pipeline) {
        List<BindGroupLayout.UniformDescription> descriptions = pipeline.uniforms();
        MslTranslator.Bindings b = pipeline.bindings();
        long dirty = this.dirtyMask;
        this.dirtyMask = 0L;
        for (int i = 0; i < descriptions.size(); i++) {
            if (i < 64 && (dirty & (1L << i)) == 0L) {
                continue;
            }
            int stages = b.stageMask()[i];
            if (stages == 0) {
                continue; // declared by the pipeline but read by neither shader stage
            }
            Object value = this.uniforms[i];
            BindGroupLayout.UniformDescription description = descriptions.get(i);
            if (value == null) {
                if (!warnedMissingUniform) {
                    warnedMissingUniform = true;
                    LOGGER.warn("Missing uniform {} ({}) for pipeline {}", description.name(), description.type(), pipeline.name());
                }
                continue;
            }
            switch (description.type()) {
                case UNIFORM_BUFFER -> {
                    GpuBufferSlice slice = (GpuBufferSlice) value;
                    Native.passSetBuffer(this.ctx, stages, ((MetalGpuBuffer) slice.buffer()).handle(), slice.offset(), b.bufferIndex()[i]);
                }
                case COMBINED_IMAGE_SAMPLER -> {
                    TextureViewAndSampler pair = (TextureViewAndSampler) value;
                    Native.passSetTexture(this.ctx, stages, ((MetalTextureView) pair.view()).handle(), b.textureIndex()[i]);
                    Native.passSetSampler(this.ctx, stages, ((MetalSampler) pair.sampler()).handle(), b.samplerIndex()[i]);
                }
                case TEXEL_BUFFER -> {
                    GpuBufferSlice slice = (GpuBufferSlice) value;
                    Native.passSetTexelBuffer(this.ctx, stages, ((MetalGpuBuffer) slice.buffer()).handle(), slice.offset(), slice.length(),
                        b.texelPixelFormat()[i], b.texelSize()[i], b.textureIndex()[i]);
                }
            }
        }
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        if (indexCount > 0 && instanceCount > 0 && this.prepareDraw()) {
            Native.passDrawIndexed(this.ctx, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
        if (drawCount > 0 && instanceCount > 0 && this.prepareDraw()) {
            Native.passMultiDrawIndexed(this.ctx, MemoryUtil.memAddress(drawParameters), instanceCount, firstInstance, drawCount);
        }
    }

    @Override
    public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
        throw new UnsupportedOperationException("Metal backend does not expose the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
        if (drawCount > 0 && this.prepareDraw()) {
            Native.passDrawIndexedIndirect(this.ctx, ((MetalGpuBuffer) commands.buffer()).handle(), commands.offset(), drawCount);
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        if (vertexCount > 0 && instanceCount > 0 && this.prepareDraw()) {
            Native.passDraw(this.ctx, vertexCount, instanceCount, firstVertex, firstInstance);
        }
    }

    @Override
    public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
        if (drawCount > 0 && instanceCount > 0 && this.prepareDraw()) {
            Native.passMultiDraw(this.ctx, MemoryUtil.memAddress(drawParameters), instanceCount, firstInstance, drawCount);
        }
    }

    @Override
    public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
        throw new UnsupportedOperationException("Metal backend does not expose the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
        if (drawCount > 0 && this.prepareDraw()) {
            Native.passDrawIndirect(this.ctx, ((MetalGpuBuffer) commands.buffer()).handle(), commands.offset(), drawCount);
        }
    }

    @Override
    public void writeTimestamp(final GpuQueryPool pool, final int index) {
    }

    int outputWidth() {
        return this.outputWidth;
    }

    int outputHeight() {
        return this.outputHeight;
    }
}
