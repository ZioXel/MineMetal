package dev.minemetal.client.glcompat;

import dev.minemetal.client.metal.MetalDevice;
import dev.minemetal.client.metal.Native;

import static org.lwjgl.opengl.GL46C.*;

/** Emulated GL sampler object (overrides the bound texture's own filtering/wrap state). */
final class GlSamplerObject {
    final int id;
    int minFilter = GL_NEAREST_MIPMAP_LINEAR;
    int magFilter = GL_LINEAR;
    int wrapS = GL_REPEAT;
    int wrapT = GL_REPEAT;
    int compareMode = GL_NONE;
    int compareFunc = GL_LEQUAL;
    private long handle;

    GlSamplerObject(final int id) {
        this.id = id;
    }

    void set(final int pname, final int value) {
        switch (pname) {
            case GL_TEXTURE_MIN_FILTER -> this.minFilter = value;
            case GL_TEXTURE_MAG_FILTER -> this.magFilter = value;
            case GL_TEXTURE_WRAP_S -> this.wrapS = value;
            case GL_TEXTURE_WRAP_T -> this.wrapT = value;
            case GL_TEXTURE_COMPARE_MODE -> this.compareMode = value;
            case GL_TEXTURE_COMPARE_FUNC -> this.compareFunc = value;
            default -> {
                return;
            }
        }
        this.release();
    }

    long handle(final MetalDevice device, final GlTextureObject texture) {
        if (this.handle == 0L) {
            this.handle = GlTextureObject.createSampler(device, this.minFilter, this.magFilter, this.wrapS, this.wrapT, this.compareMode, this.compareFunc,
                1000);
        }
        return this.handle;
    }

    void release() {
        if (this.handle != 0L) {
            long old = this.handle;
            MetalDevice device = MetalDevice.current();
            if (device != null) {
                device.encoder().queueForDestroy(() -> Native.release(old));
            }
            this.handle = 0L;
        }
    }
}
