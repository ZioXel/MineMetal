package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.util.List;

/**
 * A compiled pipeline plus the Metal argument-table slot of every uniform (index = uniform index in the
 * frontend's flattened bind group layout).
 */
public final class MetalRenderPipeline implements BackendRenderPipeline {
    /** Push constants live in this buffer slot in both stages. */
    public static final int PUSH_CONSTANT_BUFFER_INDEX = 20;
    /** Vertex buffer slot N is bound at Metal buffer index (VERTEX_BUFFER_BASE - N). */
    public static final int VERTEX_BUFFER_BASE = 30;
    public static final int MAX_UNIFORM_BUFFERS = PUSH_CONSTANT_BUFFER_INDEX;

    private final MetalDevice device;
    private final long handle;
    private final String name;
    private final List<BindGroupLayout.UniformDescription> uniforms;
    private final MslTranslator.Bindings bindings;
    private boolean closed;

    MetalRenderPipeline(final MetalDevice device, final long handle, final String name, final List<BindGroupLayout.UniformDescription> uniforms,
                        final MslTranslator.Bindings bindings) {
        this.device = device;
        this.handle = handle;
        this.name = name;
        this.uniforms = uniforms;
        this.bindings = bindings;
    }

    public long handle() {
        return this.handle;
    }

    public String name() {
        return this.name;
    }

    public List<BindGroupLayout.UniformDescription> uniforms() {
        return this.uniforms;
    }

    public MslTranslator.Bindings bindings() {
        return this.bindings;
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
