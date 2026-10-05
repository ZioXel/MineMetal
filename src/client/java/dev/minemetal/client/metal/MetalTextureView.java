package dev.minemetal.client.metal;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

public final class MetalTextureView extends BaseGpuTextureView {
    private final MetalDevice device;
    private final long handle;
    private boolean closed;

    MetalTextureView(final MetalDevice device, final MetalTexture texture, final int baseMipLevel, final int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        this.device = device;
        this.handle = Native.textureView(texture.handle(), baseMipLevel, mipLevels);
        if (this.handle == 0L) {
            throw new IllegalStateException("Failed to create texture view of '" + texture.getLabel() + "'");
        }
    }

    public long handle() {
        return this.handle;
    }

    @Override
    public MetalTexture texture() {
        return (MetalTexture) super.texture();
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.texture().isClosed();
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
