package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.DestructionQueue;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;

/**
 * One command encoder per device (like the Vulkan backend). Each {@link #submit()} commits one MTLCommandBuffer;
 * at most {@link #MAX_SUBMITS_IN_FLIGHT} submits are in flight, and deferred destruction runs once the GPU is done.
 */
public final class MetalCommandEncoder implements CommandEncoderBackend {
    public static final int MAX_SUBMITS_IN_FLIGHT = 2;
    private static final long SUBMIT_TIMEOUT_NS = 5_000_000_000L;

    private final MetalDevice device;
    private final MetalTransientMemory transientMemory;
    private final DestructionQueue<Runnable> destroyQueue = new DestructionQueue<>(MAX_SUBMITS_IN_FLIGHT, Runnable::run);
    final FrameStats stats = new FrameStats();
    private boolean presentedThisSubmit;
    private @Nullable MetalRenderPass currentRenderPass;
    private boolean destroyed;

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
    }

    long ctx() {
        return this.device.ctx();
    }

    /** True while a renderpearl render pass is open (the GL emulation must not start its own passes then). */
    public boolean isInRenderPass() {
        return this.currentRenderPass != null;
    }

    public void queueForDestroy(final Runnable destroyable) {
        if (this.destroyed) {
            destroyable.run();
        } else {
            this.destroyQueue.add(destroyable);
        }
    }

    void destroy() {
        if (this.destroyed) {
            return;
        }
        this.transientMemory.endSubmit();
        Native.waitIdle(this.ctx());
        this.destroyQueue.close();
        this.transientMemory.destroy();
        this.destroyQueue.close();
        this.destroyed = true;
    }

    @Override
    public void submit() {
        if (this.currentRenderPass != null) {
            throw new IllegalStateException("Cannot submit while a render pass is open");
        }
        this.transientMemory.endSubmit();
        long t0 = System.nanoTime();
        long submitted = Native.submit(this.ctx());
        long t1 = System.nanoTime();
        long previous = submitted - (MAX_SUBMITS_IN_FLIGHT - 1);
        if (previous > 0L && !Native.await(this.ctx(), previous, SUBMIT_TIMEOUT_NS)) {
            throw new IllegalStateException("5s timeout reached waiting for Metal submit " + previous);
        }
        long t2 = System.nanoTime();
        this.destroyQueue.rotate();
        this.stats.submitNs += t1 - t0;
        this.stats.waitNs += t2 - t1;
        if (this.presentedThisSubmit) {
            this.presentedThisSubmit = false;
            if (FrameStats.ENABLED) {
                this.stats.endFrame(this.transientMemory.poolStats());
            }
        }
    }

    @Override
    public TransientMemory transientMemory() {
        return this.transientMemory;
    }

    // --- render passes -----------------------------------------------------------------------------------------------

    @Override
    public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        if (this.currentRenderPass != null) {
            throw new IllegalStateException("Render pass already open");
        }
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        int count = colorAttachments.size();
        int width = 0;
        int height = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer views = stack.callocPointer(Math.max(count, 1));
            FloatBuffer clearColors = stack.callocFloat(Math.max(count, 1) * 4);
            int clearMask = 0;
            for (int i = 0; i < count; i++) {
                RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colorAttachments.get(i);
                if (attachment == null) {
                    continue;
                }
                MetalTextureView view = (MetalTextureView) attachment.textureView();
                views.put(i, view.handle());
                width = view.getWidth(0);
                height = view.getHeight(0);
                if (attachment.clearValue().isPresent()) {
                    Vector4fc c = attachment.clearValue().get();
                    clearMask |= 1 << i;
                    clearColors.put(i * 4, c.x()).put(i * 4 + 1, c.y()).put(i * 4 + 2, c.z()).put(i * 4 + 3, c.w());
                }
            }
            long depthView = 0L;
            double clearDepth = 0.0;
            if (depthAttachment != null) {
                MetalTextureView view = (MetalTextureView) depthAttachment.textureView();
                depthView = view.handle();
                if (width == 0) {
                    width = view.getWidth(0);
                    height = view.getHeight(0);
                }
                if (depthAttachment.clearValue().isPresent()) {
                    clearMask |= 1 << 31;
                    clearDepth = depthAttachment.clearValue().getAsDouble();
                }
            }
            long label = this.device.labelsEnabled() ? MemoryUtil.memAddress(stack.UTF8(descriptor.label().get())) : 0L;
            var area = descriptor.renderArea();
            boolean ok = Native.passBegin(this.ctx(), MemoryUtil.memAddress(views), count, clearMask, MemoryUtil.memAddress(clearColors), depthView,
                clearDepth, area.x(), area.y(), area.width(), area.height(), label);
            if (!ok) {
                throw new IllegalStateException("Failed to begin Metal render pass " + descriptor.label().get());
            }
            this.currentRenderPass = new MetalRenderPass(this.device, area, width, height);
            return this.currentRenderPass;
        }
    }

    @Override
    public void submitRenderPass() {
        if (this.currentRenderPass == null) {
            throw new IllegalStateException("Cannot submit a renderpass if one hasn't been started!");
        }
        Native.passEnd(this.ctx());
        this.currentRenderPass = null;
    }

    // --- clears ------------------------------------------------------------------------------------------------------

    @Override
    public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc clearColor) {
        Native.clearColorTexture(this.ctx(), ((MetalTexture) colorTexture).handle(), clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w());
    }

    @Override
    public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture,
                                           final double clearDepth) {
        this.clearColorTexture(colorTexture, clearColor);
        this.clearDepthTexture(depthTexture, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture,
                                           final double clearDepth, final int regionX, final int regionY, final int regionWidth,
                                           final int regionHeight, final int mipLevel) {
        Native.clearRegion(this.ctx(), ((MetalTexture) colorTexture).handle(), ((MetalTexture) depthTexture).handle(), mipLevel, clearColor.x(),
            clearColor.y(), clearColor.z(), clearColor.w(), clearDepth, regionX, regionY, regionWidth, regionHeight);
    }

    @Override
    public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
        Native.clearDepthTexture(this.ctx(), ((MetalTexture) depthTexture).handle(), clearDepth);
    }

    // --- copies ------------------------------------------------------------------------------------------------------

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        int size = data.remaining();
        GpuBufferSlice staging = this.transientMemory.uploadStaging(data, 1L, GpuBuffer.USAGE_COPY_SRC);
        Native.copyBuffer(this.ctx(), ((MetalGpuBuffer) staging.buffer()).handle(), staging.offset(), ((MetalGpuBuffer) destination.buffer()).handle(),
            destination.offset(), size);
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        Native.copyBuffer(this.ctx(), ((MetalGpuBuffer) source.buffer()).handle(), source.offset(), ((MetalGpuBuffer) target.buffer()).handle(),
            target.offset(), source.length());
    }

    @Override
    public void writeToTexture(final GpuTexture destination, final ByteBuffer source, final int mipLevel, final int depthOrLayer, final int destX,
                               final int destY, final int width, final int height) {
        int texelSize = destination.getFormat().blockSize();
        long bytesPerRow = (long) width * texelSize;
        GpuBufferSlice staging = this.transientMemory.uploadStaging(source, Math.max(16L, texelSize), GpuBuffer.USAGE_COPY_SRC);
        Native.copyBufferToTexture(this.ctx(), ((MetalGpuBuffer) staging.buffer()).handle(), staging.offset(), bytesPerRow, bytesPerRow * height,
            ((MetalTexture) destination).handle(), depthOrLayer, mipLevel, destX, destY, width, height);
    }

    @Override
    public void copyBufferToTexture(final GpuBufferSlice source, final int sourceX, final int sourceY, final int sourceWidth, final int sourceHeight,
                                    final GpuTexture destination, final int destinationX, final int destinationY, final int copyWidth,
                                    final int copyHeight, final int mipLevel, final int arrayLayer) {
        int texelSize = destination.getFormat().blockSize();
        long skipBytes = (sourceX + (long) sourceY * sourceWidth) * texelSize;
        long bytesPerRow = (long) sourceWidth * texelSize;
        Native.copyBufferToTexture(this.ctx(), ((MetalGpuBuffer) source.buffer()).handle(), source.offset() + skipBytes, bytesPerRow,
            bytesPerRow * sourceHeight, ((MetalTexture) destination).handle(), arrayLayer, mipLevel, destinationX, destinationY, copyWidth, copyHeight);
    }

    @Override
    public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback,
                                    final int mipLevel) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback,
                                    final int mipLevel, final int x, final int y, final int width, final int height) {
        long bytesPerRow = (long) width * source.getFormat().blockSize();
        Native.copyTextureToBuffer(this.ctx(), ((MetalTexture) source).handle(), 0, mipLevel, x, y, width, height,
            ((MetalGpuBuffer) destination).handle(), offset, bytesPerRow, bytesPerRow * height);
        this.queueForDestroy(callback);
    }

    @Override
    public void copyTextureToTexture(final GpuTexture source, final GpuTexture destination, final int mipLevel, final int destX, final int destY,
                                     final int sourceX, final int sourceY, final int width, final int height) {
        Native.copyTextureToTexture(this.ctx(), ((MetalTexture) source).handle(), ((MetalTexture) destination).handle(), mipLevel, sourceX, sourceY,
            destX, destY, width, height);
    }

    // --- sync --------------------------------------------------------------------------------------------------------

    @Override
    public GpuFence createFence() {
        final long fenceSubmit = Native.currentSubmitIndex(this.ctx());
        return new GpuFence() {
            private boolean completed;

            @Override
            public boolean awaitCompletion(final long timeoutNs) {
                if (!this.completed) {
                    long current = Native.currentSubmitIndex(MetalCommandEncoder.this.ctx());
                    if (fenceSubmit >= current) {
                        if (timeoutNs == 0L) {
                            return false;
                        }
                        throw new IllegalStateException("Cannot wait on a fence for the current submit");
                    }
                    this.completed = Native.await(MetalCommandEncoder.this.ctx(), fenceSubmit, timeoutNs);
                }
                return this.completed;
            }

            @Override
            public void close() {
                this.completed = true;
            }
        };
    }

    @Override
    public void writeTimestamp(final GpuQueryPool pool, final int index) {
        // GPU timestamps are not wired up yet (only used by the Tracy profiler).
    }

    void presentBlit(final MetalSurface surface, final GpuTextureView view, final int copyWidth, final int copyHeight) {
        long t0 = System.nanoTime();
        Native.surfaceBlit(this.ctx(), surface.handle(), ((MetalTextureView) view).handle(), copyWidth, copyHeight);
        this.stats.blitNs += System.nanoTime() - t0;
        this.stats.surface(surface);
        this.presentedThisSubmit = true;
    }
}
