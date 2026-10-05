package dev.minemetal.client.metal;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Recycles shared MTLBuffers used for transient memory, so terrain/chunk uploads don't allocate (and zero-fill)
 * fresh GPU memory every frame. Sizes are rounded up to powers of two; idle buffers are released after a while
 * and the pool never holds more than {@link #MAX_POOLED_BYTES}.
 */
final class BufferPool {
    private static final long MAX_POOLED_BYTES = 192L * 1024 * 1024;
    private static final long MAX_IDLE_SUBMITS = 600; // ~5-10 s at typical frame rates

    record Pooled(long handle, long contents, long capacity, long freedAt) {
    }

    private final MetalDevice device;
    private final Long2ObjectOpenHashMap<ArrayDeque<Pooled>> free = new Long2ObjectOpenHashMap<>();
    private long pooledBytes;
    private long submit;
    private long hits;
    private long misses;

    BufferPool(final MetalDevice device) {
        this.device = device;
    }

    static long sizeClass(final long size) {
        long s = Math.max(size, 4096L);
        return Long.highestOneBit(s - 1) << 1;
    }

    MetalTransientMemory.SharedBlock acquire(final long size) {
        long capacity = sizeClass(size);
        ArrayDeque<Pooled> list = this.free.get(capacity);
        if (list != null && !list.isEmpty()) {
            Pooled p = list.pollLast();
            this.pooledBytes -= p.capacity();
            this.hits++;
            return new MetalTransientMemory.SharedBlock(p.handle(), p.contents(), p.capacity());
        }
        this.misses++;
        // +256 bytes of slack: a small uniform block placed at the very end of a block still has room for MSL's
        // 16-byte struct padding (the API-visible size stays `capacity`).
        long handle = Native.bufferCreate(this.device.ctx(), capacity + 256);
        if (handle == 0L) {
            this.trim(0L);
            handle = Native.bufferCreate(this.device.ctx(), capacity + 256);
            if (handle == 0L) {
                throw new IllegalStateException("Failed to allocate transient MTLBuffer of " + capacity + " bytes");
            }
        }
        return new MetalTransientMemory.SharedBlock(handle, Native.bufferContents(handle), capacity);
    }

    /** Must only be called once the GPU no longer uses the block. */
    void release(final MetalTransientMemory.SharedBlock block) {
        if (this.pooledBytes + block.size() > MAX_POOLED_BYTES) {
            Native.release(block.handle());
            return;
        }
        this.free.computeIfAbsent(block.size(), k -> new ArrayDeque<>()).addLast(new Pooled(block.handle(), block.contents(), block.size(), this.submit));
        this.pooledBytes += block.size();
    }

    /** Called once per submit: ages out buffers that have not been reused recently. */
    void tick() {
        this.submit++;
        if ((this.submit & 63) == 0) {
            this.trim(this.submit - MAX_IDLE_SUBMITS);
        }
    }

    private void trim(final long olderThan) {
        for (ArrayDeque<Pooled> list : this.free.values()) {
            Iterator<Pooled> it = list.iterator();
            while (it.hasNext()) {
                Pooled p = it.next();
                if (olderThan <= 0L || p.freedAt() < olderThan) {
                    Native.release(p.handle());
                    this.pooledBytes -= p.capacity();
                    it.remove();
                }
            }
        }
    }

    void destroy() {
        this.trim(0L);
    }

    String stats() {
        return "pool " + (this.pooledBytes >> 20) + " MB idle, hits " + this.hits + ", misses " + this.misses;
    }
}
