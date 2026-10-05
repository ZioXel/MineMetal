package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

public final class MetalSampler implements GpuSampler {
    private final MetalDevice device;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private final long handle;
    private boolean closed;

    MetalSampler(final MetalDevice device, final AddressMode addressModeU, final AddressMode addressModeV, final FilterMode minFilter,
                 final FilterMode magFilter, final int maxAnisotropy, final OptionalDouble maxLod) {
        this.device = device;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        // Matches the OpenGL backend: min filter is always *_MIPMAP_LINEAR.
        this.handle = Native.samplerCreate(device.ctx(), addressModeU == AddressMode.REPEAT, addressModeV == AddressMode.REPEAT,
            minFilter == FilterMode.LINEAR, magFilter == FilterMode.LINEAR, true, maxAnisotropy,
            maxLod.isPresent() ? (float) maxLod.getAsDouble() : Float.MAX_VALUE);
        if (this.handle == 0L) {
            throw new IllegalStateException("Failed to create MTLSamplerState");
        }
    }

    public long handle() {
        return this.handle;
    }

    @Override
    public AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return this.maxLod;
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
