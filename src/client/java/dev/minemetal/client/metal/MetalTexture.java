package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class MetalTexture extends BaseGpuTexture {
    private final MetalDevice device;
    private final long handle;
    private boolean closed;

    MetalTexture(final MetalDevice device, final @GpuTexture.Usage int usage, final String label, final GpuFormat format, final int width,
                 final int height, final int depthOrLayers, final int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        long pixelFormat = MetalEnums.pixelFormat(format);
        if (pixelFormat < 0) {
            throw new UnsupportedOperationException("Metal has no texture format equivalent to " + format);
        }
        this.handle = Native.textureCreate(device.ctx(), pixelFormat, width, height, depthOrLayers, mipLevels, usage);
        if (this.handle == 0L) {
            throw new IllegalStateException("Failed to create " + width + "x" + height + " " + format + " texture '" + label + "'");
        }
        if (!label.isEmpty() && device.labelsEnabled()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Native.setLabel(this.handle, MemoryUtil.memAddress(stack.UTF8(label)));
            }
        }
    }

    public long handle() {
        return this.handle;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            long h = this.handle;
            this.device.encoder().queueForDestroy(() -> Native.release(h));
        }
    }
}
