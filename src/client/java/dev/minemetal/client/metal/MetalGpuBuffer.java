package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** A GpuBuffer backed by an MTLBuffer (shared storage: CPU address is always available). */
public abstract class MetalGpuBuffer extends BaseGpuBuffer {
    protected MetalGpuBuffer(final @GpuBuffer.Usage int usage, final long size) {
        super(usage, size);
    }

    /** id&lt;MTLBuffer&gt; */
    public abstract long handle();

    /** CPU address of offset 0. */
    public abstract long contents();

    /** A regular, persistent buffer. */
    public static final class Direct extends MetalGpuBuffer {
        private final MetalDevice device;
        private final long handle;
        private final long contents;
        private boolean closed;
        private int mappingRefCount;

        Direct(final MetalDevice device, final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
            super(usage, size);
            this.device = device;
            this.handle = Native.bufferCreate(device.ctx(), size);
            if (this.handle == 0L) {
                throw new IllegalStateException("Failed to allocate MTLBuffer of " + size + " bytes");
            }
            this.contents = Native.bufferContents(this.handle);
            if (label != null && device.labelsEnabled()) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    Native.setLabel(this.handle, MemoryUtil.memAddress(stack.UTF8(label.get())));
                }
            }
        }

        @Override
        public long handle() {
            return this.handle;
        }

        @Override
        public long contents() {
            return this.contents;
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                if (this.mappingRefCount != 0) {
                    throw new IllegalStateException("Attempt to close a mapped buffer");
                }
                long h = this.handle;
                this.device.encoder().queueForDestroy(() -> Native.release(h));
            }
        }

        @Override
        public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
            if (this.closed) {
                throw new IllegalStateException("Buffer already closed");
            }
            if (!read && !write) {
                throw new IllegalArgumentException("At least read or write must be true");
            }
            if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
                throw new IllegalStateException("Buffer is not readable");
            }
            if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
                throw new IllegalStateException("Buffer is not writable");
            }
            if (offset < 0L || length < 0L || offset + length > this.size()) {
                throw new IllegalArgumentException("Cannot map " + length + " bytes at offset " + offset + " from a " + this.size() + " byte buffer");
            }
            if (length > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Mapping buffer slice larger than 2GB is not supported");
            }
            this.mappingRefCount++;
            return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.contents + offset, (int) length), new Runnable() {
                private boolean done;

                @Override
                public void run() {
                    if (!this.done) {
                        this.done = true;
                        Direct.this.mappingRefCount--;
                    }
                }
            });
        }
    }
}
