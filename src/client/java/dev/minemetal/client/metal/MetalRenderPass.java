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
    /**
     * A pipeline that draws somewhere else than the pass attachments (Iris gbuffer programs drawing into the shader
     * pack's render targets through MineMetal's GL emulation). The pass switches its Metal encoder to the target the
     * pipeline reports and back, keeping consecutive draws with the same target in one Metal render pass.
     */
    public interface OverridePipeline extends BackendRenderPipeline {
        List<BindGroupLayout.UniformDescription> uniforms();

        /** Called before every draw (mirrors GL's per-draw program setup). Returns the target, or null to skip the draw. */
        @Nullable Target beforeDraw();

        /** Binds pipeline state and every resource on the current encoder (which renders into {@code target}). */
        boolean bind(long ctx, Target target, List<BindGroupLayout.UniformDescription> descriptions, @Nullable Object[] values,
                     @Nullable ByteBuffer pushConstants);

        /** The render pass that used this pipeline ended. */
        void passEnded();
    }

    /** Attachments of an override target: color views (0 = unused slot), optional depth view. */
    public record Target(long[] colorViews, long depthView) {
        boolean sameAs(final long[] views, final long depth) {
            return this.depthView == depth && java.util.Arrays.equals(this.colorViews, views);
        }
    }

    private static final int MAX_VERTEX_SLOTS = 8;
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal");
    private static final int STAGES_ALL = 3;
    private static boolean warnedMissingUniform;

    private final MetalDevice device;
    private final long ctx;
    private final RenderPass.RenderArea renderArea;
    private final int outputWidth;
    private final int outputHeight;
    /** Original attachments (to come back to after drawing into an override target). */
    private final long[] passViews;
    private final long passDepthView;
    private long[] activeViews;
    private long activeDepthView;
    private final long[] vertexBuffers = new long[MAX_VERTEX_SLOTS];
    private final long[] vertexOffsets = new long[MAX_VERTEX_SLOTS];
    private long indexBuffer;
    private boolean indexInt;
    private boolean scissorEnabled;
    private final int[] scissorRect = new int[4];
    private @Nullable ByteBuffer pushConstants;
    private int nativeDebugGroups;
    private @Nullable OverridePipeline override;
    private final java.util.Set<OverridePipeline> overridesUsed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private @Nullable MetalRenderPipeline pipeline;
    /** Pipelines are applied lazily at the next draw: the frontend often sets one and replaces it before drawing. */
    private boolean pipelineApplied;
    private boolean pipelineUsable;
    private @Nullable Object[] uniforms = new Object[0];
    /** Bit i set = uniform i changed since the last draw (uniforms beyond 63 are always re-sent). */
    private long dirtyMask;
    private boolean bindingsDirty;
    private int pushedDebugGroups;

    MetalRenderPass(final MetalDevice device, final RenderPass.RenderArea renderArea, final int outputWidth, final int outputHeight,
                    final long[] passViews, final long passDepthView) {
        this.device = device;
        this.passViews = passViews;
        this.passDepthView = passDepthView;
        this.activeViews = passViews;
        this.activeDepthView = passDepthView;
        this.ctx = device.ctx();
        this.renderArea = renderArea;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    @Override
    public void pushDebugGroup(final Supplier<String> label) {
        this.pushedDebugGroups++;
        if (this.device.labelsEnabled()) {
            this.nativeDebugGroups++;
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
        if (this.device.labelsEnabled() && this.nativeDebugGroups > 0) {
            this.nativeDebugGroups--;
            Native.passPopDebug(this.ctx);
        }
    }

    @Override
    public void setPipeline(final BackendRenderPipeline pipeline) {
        List<BindGroupLayout.UniformDescription> descriptions;
        if (pipeline instanceof OverridePipeline ov) {
            this.override = ov;
            this.overridesUsed.add(ov);
            this.pipeline = null;
            descriptions = ov.uniforms();
        } else if (pipeline instanceof MetalRenderPipeline metal) {
            this.override = null;
            this.pipeline = metal;
            descriptions = metal.uniforms();
        } else {
            throw new IllegalArgumentException("Pipeline must be instance of MetalRenderPipeline");
        }
        this.pipelineApplied = false;
        int count = descriptions.size();
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
        if (this.pushConstants == null || this.pushConstants.capacity() < length) {
            if (this.pushConstants != null) {
                MemoryUtil.memFree(this.pushConstants);
            }
            this.pushConstants = MemoryUtil.memAlloc(Math.max(length, 256));
        }
        this.pushConstants.clear();
        this.pushConstants.put(value.duplicate()).flip();
        if (this.override != null) {
            return; // bound at draw time into the override program's push-constant block
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
        this.scissorEnabled = true;
        this.scissorRect[0] = x;
        this.scissorRect[1] = y;
        this.scissorRect[2] = width;
        this.scissorRect[3] = height;
        Native.passSetScissor(this.ctx, x, y, width, height);
    }

    @Override
    public void disableScissor() {
        this.scissorEnabled = false;
        Native.passSetScissor(this.ctx, this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
    }

    @Override
    public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
        if (vertexBuffer != null) {
            MetalGpuBuffer buffer = (MetalGpuBuffer) vertexBuffer.buffer();
            if (slot < MAX_VERTEX_SLOTS) {
                this.vertexBuffers[slot] = buffer.handle();
                this.vertexOffsets[slot] = vertexBuffer.offset();
            }
            Native.passSetVertexBuffer(this.ctx, buffer.handle(), vertexBuffer.offset(), MetalRenderPipeline.VERTEX_BUFFER_BASE - slot);
        }
    }

    @Override
    public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
        this.indexBuffer = ((MetalGpuBuffer) indexBuffer).handle();
        this.indexInt = indexType == IndexType.INT;
        Native.passSetIndexBuffer(this.ctx, this.indexBuffer, this.indexInt);
    }

    /** Ends the current Metal encoder and begins one on the given attachments (contents loaded), restoring pass state. */
    private boolean switchTarget(final long[] views, final long depthView) {
        if (this.device.labelsEnabled()) {
            while (this.nativeDebugGroups > 0) {
                this.nativeDebugGroups--;
                Native.passPopDebug(this.ctx);
            }
        }
        boolean ok;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.callocPointer(Math.max(views.length, 1));
            for (int i = 0; i < views.length; i++) {
                p.put(i, views[i]);
            }
            ok = Native.passBegin(this.ctx, MemoryUtil.memAddress(p), views.length, 0, 0L, depthView, 0.0, this.renderArea.x(), this.renderArea.y(),
                this.renderArea.width(), this.renderArea.height(), 0L);
        }
        this.activeViews = views;
        this.activeDepthView = depthView;
        if (!ok) {
            return false;
        }
        for (int slot = 0; slot < MAX_VERTEX_SLOTS; slot++) {
            if (this.vertexBuffers[slot] != 0L) {
                Native.passSetVertexBuffer(this.ctx, this.vertexBuffers[slot], this.vertexOffsets[slot], MetalRenderPipeline.VERTEX_BUFFER_BASE - slot);
            }
        }
        if (this.indexBuffer != 0L) {
            Native.passSetIndexBuffer(this.ctx, this.indexBuffer, this.indexInt);
        }
        if (this.scissorEnabled) {
            Native.passSetScissor(this.ctx, this.scissorRect[0], this.scissorRect[1], this.scissorRect[2], this.scissorRect[3]);
        }
        this.pipelineApplied = false;
        this.dirtyMask = -1L;
        this.bindingsDirty = true;
        return true;
    }

    private boolean prepareDraw() {
        OverridePipeline ov = this.override;
        if (ov != null) {
            Target target = ov.beforeDraw();
            if (target == null) {
                return false;
            }
            if (!target.sameAs(this.activeViews, this.activeDepthView) && !this.switchTarget(target.colorViews(), target.depthView())) {
                return false;
            }
            ByteBuffer push = this.pushConstants;
            return ov.bind(this.ctx, target, ov.uniforms(), this.uniforms, push);
        }
        if (this.pipeline == null) {
            return false;
        }
        if (this.activeViews != this.passViews || this.activeDepthView != this.passDepthView) {
            if (!this.switchTarget(this.passViews, this.passDepthView)) {
                return false;
            }
            if (this.pushConstants != null && this.pushConstants.remaining() > 0) {
                int stages = this.pipeline.bindings().pushStageMask()[0];
                if (stages != 0) {
                    Native.passSetBytes(this.ctx, stages, MemoryUtil.memAddress(this.pushConstants), this.pushConstants.remaining(),
                        MetalRenderPipeline.PUSH_CONSTANT_BUFFER_INDEX);
                }
            }
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

    /** Called by the command encoder right before the pass's Metal encoder ends. */
    void onEnd() {
        for (OverridePipeline ov : this.overridesUsed) {
            try {
                ov.passEnded();
            } catch (RuntimeException e) {
                LOGGER.error("Override pipeline cleanup failed", e);
            }
        }
        this.overridesUsed.clear();
        if (this.pushConstants != null) {
            MemoryUtil.memFree(this.pushConstants);
            this.pushConstants = null;
        }
    }

    int outputWidth() {
        return this.outputWidth;
    }

    int outputHeight() {
        return this.outputHeight;
    }
}
