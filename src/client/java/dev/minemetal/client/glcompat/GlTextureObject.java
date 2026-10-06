package dev.minemetal.client.glcompat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.minemetal.client.metal.MetalDevice;
import dev.minemetal.client.metal.MetalTexture;
import dev.minemetal.client.metal.MetalTextureView;
import dev.minemetal.client.metal.Native;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import static org.lwjgl.opengl.GL46C.*;

/** An emulated GL texture name: storage is a MineMetal {@link MetalTexture}; filtering/wrap state lives here. */
final class GlTextureObject {
    final int id;
    @Nullable MetalTexture texture;
    @Nullable MetalTextureView sampleView;
    private final Int2ObjectOpenHashMap<MetalTextureView> levelViews = new Int2ObjectOpenHashMap<>();
    /** Texture owned by Minecraft (registered for Iris), never freed by us. */
    boolean external;
    int target = GL_TEXTURE_2D;
    int internalFormat;
    @Nullable GpuFormat format;
    int width;
    int height;
    /** Depth of a 3D texture (1 otherwise). */
    int depth = 1;
    /** 1, 2 or 3 (GL_TEXTURE_1D / 2D / 3D storage). */
    int dims = 2;
    int levels = 1;
    /** Created with shader-write usage (needed for image load/store). */
    boolean writable;

    // texture parameters (GL defaults)
    int minFilter = GL_NEAREST_MIPMAP_LINEAR;
    int magFilter = GL_LINEAR;
    int wrapS = GL_REPEAT;
    int wrapT = GL_REPEAT;
    int compareMode = GL_NONE;
    int compareFunc = GL_LEQUAL;
    int maxLevel = 1000;
    private long sampler;

    GlTextureObject(final int id) {
        this.id = id;
    }

    boolean hasStorage() {
        return this.texture != null;
    }

    void allocate(final MetalDevice device, final GpuFormat format, final int internalFormat, final int width, final int height, final int levels) {
        this.allocate(device, format, internalFormat, width, height, 1, 2, levels, false);
    }

    void allocate(final MetalDevice device, final GpuFormat format, final int internalFormat, final int width, final int height, final int depth,
                  final int dims, final int levels, final boolean writable) {
        this.release(device);
        this.format = format;
        this.internalFormat = internalFormat;
        this.width = width;
        this.height = dims == 1 ? 1 : height;
        this.depth = dims == 3 ? Math.max(1, depth) : 1;
        this.dims = dims;
        this.levels = Math.max(1, levels);
        this.writable = writable;
        this.texture = this.createStorage(device, writable);
        this.sampleView = (MetalTextureView) device.createTextureView(this.texture, 0, this.levels);
        this.external = false;
    }

    private MetalTexture createStorage(final MetalDevice device, final boolean writable) {
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
        if (writable) {
            usage |= MetalTexture.USAGE_SHADER_WRITE;
        }
        if (this.dims == 1) {
            usage |= MetalTexture.USAGE_TYPE_1D;
        } else if (this.dims == 3) {
            usage |= MetalTexture.USAGE_TYPE_3D;
        }
        return (MetalTexture) device.createTexture("gl#" + this.id, usage, this.format, this.width, this.height, this.depth, this.levels);
    }

    /**
     * Image load/store needs shader-write usage, which Metal fixes at creation: swaps in a writable copy of the texture
     * (contents copied on the GPU). Must not be called while a render pass is open. Returns false for textures owned
     * by Minecraft, which cannot be replaced.
     */
    boolean makeWritable(final MetalDevice device) {
        if (this.writable) {
            return true;
        }
        if (this.texture == null || this.external || this.format == null) {
            return false;
        }
        MetalTexture old = this.texture;
        MetalTexture fresh = this.createStorage(device, true);
        Native.copyTextureAll(device.ctx(), old.handle(), fresh.handle());
        this.parametersChanged();
        for (MetalTextureView view : this.levelViews.values()) {
            view.close();
        }
        this.levelViews.clear();
        if (this.sampleView != null) {
            this.sampleView.close();
        }
        old.close();
        this.texture = fresh;
        this.sampleView = (MetalTextureView) device.createTextureView(fresh, 0, this.levels);
        this.writable = true;
        return true;
    }

    void wrapExternal(final MetalDevice device, final MetalTexture texture) {
        this.release(device);
        this.texture = texture;
        this.format = texture.getFormat();
        this.width = texture.getWidth(0);
        this.height = texture.getHeight(0);
        this.depth = 1;
        this.dims = 2;
        this.writable = (texture.usage() & MetalTexture.USAGE_SHADER_WRITE) != 0;
        this.levels = texture.getMipLevels();
        this.sampleView = (MetalTextureView) device.createTextureView(texture, 0, this.levels);
        this.external = true;
    }

    MetalTextureView levelView(final MetalDevice device, final int level) {
        MetalTextureView view = this.levelViews.get(level);
        if (view == null) {
            view = (MetalTextureView) device.createTextureView(this.texture, level, 1);
            this.levelViews.put(level, view);
        }
        return view;
    }

    void parametersChanged() {
        if (this.sampler != 0L) {
            long old = this.sampler;
            MetalDevice device = MetalDevice.current();
            if (device != null) {
                device.encoder().queueForDestroy(() -> Native.release(old));
            }
            this.sampler = 0L;
        }
    }

    long sampler(final MetalDevice device) {
        if (this.sampler == 0L) {
            this.sampler = createSampler(device, this.minFilter, this.magFilter, this.wrapS, this.wrapT, this.compareMode, this.compareFunc, this.maxLevel);
        }
        return this.sampler;
    }

    static long createSampler(final MetalDevice device, final int minFilter, final int magFilter, final int wrapS, final int wrapT,
                              final int compareMode, final int compareFunc, final int maxLevel) {
        boolean minLinear = minFilter == GL_LINEAR || minFilter == GL_LINEAR_MIPMAP_NEAREST || minFilter == GL_LINEAR_MIPMAP_LINEAR;
        int mipMode = switch (minFilter) {
            case GL_NEAREST_MIPMAP_NEAREST, GL_LINEAR_MIPMAP_NEAREST -> 1;
            case GL_NEAREST_MIPMAP_LINEAR, GL_LINEAR_MIPMAP_LINEAR -> 2;
            default -> 0;
        };
        long compare = compareMode == GL_COMPARE_REF_TO_TEXTURE ? GlEnums.compareFunction(compareFunc) : -1L;
        return Native.samplerCreateEx(device.ctx(), GlEnums.addressMode(wrapS), GlEnums.addressMode(wrapT), minLinear, magFilter == GL_LINEAR,
            mipMode, 1, 0.0F, mipMode == 0 ? 0.0F : Math.max(0, maxLevel), compare);
    }

    void release(final MetalDevice device) {
        this.parametersChanged();
        for (MetalTextureView view : this.levelViews.values()) {
            view.close();
        }
        this.levelViews.clear();
        if (this.sampleView != null) {
            this.sampleView.close();
            this.sampleView = null;
        }
        if (this.texture != null && !this.external) {
            this.texture.close();
        }
        this.texture = null;
    }
}
