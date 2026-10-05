package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-submit scratch memory.
 * <p>
 * On Apple Silicon the CPU and GPU share memory, so "staging", "GPU" and "GPU mapped" allocations all come from one pool
 * of shared MTLBuffers (the Vulkan backend does the same when its device-local heap is host visible). Blocks are recycled
 * only after the GPU finished the submit that used them (via the encoder's destruction queue).
 */
public final class MetalTransientMemory implements TransientMemory {
    private static final long BLOCK_SIZE = 524288L;
    private static final long MAX_CPU_ALIGNMENT = 16L;
    private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpuBlockAllocator = new TransientBlockAllocator<>(
        BLOCK_SIZE, MAX_CPU_ALIGNMENT, TransientBlockAllocator.Allocator.CpuBlock.memalloc()
    );
    private final TransientBlockAllocator<SharedBlock> sharedBlockAllocator;
    private final BufferPool pool;
    private long submitIndex = 0L;

    MetalTransientMemory(final MetalDevice device, final MetalCommandEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
        this.pool = new BufferPool(device);
        // Blocks only reach free() once the GPU is done with them (rotate() defers via the destruction queue),
        // so they can go straight back into the pool.
        this.sharedBlockAllocator = new TransientBlockAllocator<>(
            BLOCK_SIZE, MAX_GPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(this.pool::acquire, this.pool::release)
        );
    }

    void destroy() {
        this.cpuBlockAllocator.close();
        this.sharedBlockAllocator.close();
        this.pool.destroy();
    }

    String poolStats() {
        return this.pool.stats();
    }

    void endSubmit() {
        this.cpuBlockAllocator.rotate().run();
        Runnable recycle = this.sharedBlockAllocator.rotate();
        this.encoder.queueForDestroy(recycle);
        this.pool.tick();
        this.submitIndex++;
    }

    @Override
    public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> alloc = this.cpuBlockAllocator
            .allocate(size, alignment, minimumAllocation, elementSize);
        return MemoryUtil.memByteBuffer(alloc.block().address() + alloc.offset(), (int) alloc.size());
    }

    private GpuBufferSlice.MappedView allocateShared(final long size, final long alignment, final @GpuBuffer.Usage int usage,
                                                     final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<SharedBlock> alloc = this.sharedBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        TransientBuffer apiBuffer = new TransientBuffer(alloc.block(), usage, this.submitIndex);
        ByteBuffer cpu = MemoryUtil.memByteBuffer(alloc.block().contents() + alloc.offset(), (int) alloc.size());
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(apiBuffer, alloc.offset(), alloc.size()), cpu, () -> {});
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(final long size, final long alignment, final @GpuBuffer.Usage int usage,
                                                     final long minimumAllocation, final long elementSize) {
        return this.allocateShared(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice allocateGpu(final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation,
                                      final long elementSize) {
        return this.allocateShared(size, alignment, usage, minimumAllocation, elementSize).slice();
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(final long size, final long alignment, final @GpuBuffer.Usage int usage,
                                                       final long minimumAllocation, final long elementSize) {
        return this.allocateShared(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage,
                                        final long minimumAllocation, final long elementSize) {
        return this.upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage,
                                    final long minimumAllocation, final long elementSize) {
        return this.upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    private GpuBufferSlice upload(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage,
                                  final long minimumAllocation, final long elementSize) {
        long totalSize = 0L;
        for (ByteBuffer buffer : data) {
            totalSize += buffer.remaining();
            totalSize = Mth.roundToward(totalSize, alignment);
        }
        try (GpuBufferSlice.MappedView mapped = this.allocateShared(totalSize, alignment, usage, minimumAllocation, elementSize)) {
            long mappedPtr = MemoryUtil.memAddress(mapped.data());
            long offset = 0L;
            for (ByteBuffer buffer : data) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), mappedPtr + offset, Math.min(mapped.slice().length() - offset, buffer.remaining()));
                offset += buffer.remaining();
                offset = Mth.roundToward(offset, alignment);
                if (offset >= mapped.slice().length()) {
                    break;
                }
            }
            return mapped.slice();
        }
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        return this.multiUpload(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        return this.multiUpload(data, alignment, usage);
    }

    private List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        ReferenceArrayList<GpuBufferSlice> uploaded = new ReferenceArrayList<>();
        uploaded.size(data.size());
        IntArrayList sorted = IntArrayList.toList(IntStream.range(0, data.size()));
        sorted.sort(IntComparator.comparing(index -> data.get(index).remaining()));
        while (!sorted.isEmpty()) {
            boolean allocatedAnything = false;
            for (int i = sorted.size() - 1; i >= 0; i--) {
                int bufferIndex = sorted.getInt(i);
                ByteBuffer current = data.get(bufferIndex);
                if (this.sharedBlockAllocator.canAllocateInCurrentBlock(current.remaining(), alignment)) {
                    sorted.removeInt(i);
                    try (GpuBufferSlice.MappedView view = this.allocateShared(current.remaining(), alignment, usage, current.remaining(), 1L)) {
                        MemoryUtil.memCopy(current, view.data());
                        uploaded.set(bufferIndex, view.slice());
                    }
                    allocatedAnything = true;
                    break;
                }
            }
            if (!allocatedAnything) {
                int bufferIndex = sorted.popInt();
                ByteBuffer current = data.get(bufferIndex);
                try (GpuBufferSlice.MappedView view = this.allocateShared(current.remaining(), alignment, usage, current.remaining(), 1L)) {
                    MemoryUtil.memCopy(current, view.data());
                    uploaded.set(bufferIndex, view.slice());
                }
            }
        }
        return uploaded;
    }

    record SharedBlock(long handle, long contents, long size) implements TransientBlockAllocator.Allocator.Block {
        @Override
        public boolean suboptimal() {
            return false;
        }
    }

    /** API-facing view of a transient block. Valid only during the submit it was allocated in. */
    private final class TransientBuffer extends MetalGpuBuffer {
        private final SharedBlock block;
        private final long bufferSubmitIndex;
        private boolean closed;

        TransientBuffer(final SharedBlock block, final @GpuBuffer.Usage int usage, final long bufferSubmitIndex) {
            super(usage, block.size());
            this.block = block;
            this.bufferSubmitIndex = bufferSubmitIndex;
        }

        @Override
        public long handle() {
            return this.block.handle();
        }

        @Override
        public long contents() {
            return this.block.contents();
        }

        @Override
        public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
            throw new IllegalStateException("Cannot map transient buffer");
        }

        @Override
        public boolean isClosed() {
            if (!this.closed) {
                this.closed = this.bufferSubmitIndex < MetalTransientMemory.this.submitIndex;
            }
            return this.closed;
        }

        @Override
        public void close() {
            this.closed = true;
        }

        @Override
        public GpuBufferSlice slice(final long offset, final long length) {
            throw new IllegalStateException("Cannot slice transient buffer");
        }

        @Override
        public GpuBufferSlice slice() {
            throw new IllegalStateException("Cannot slice transient buffer");
        }
    }
}
