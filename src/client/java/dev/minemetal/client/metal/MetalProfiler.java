package dev.minemetal.client.metal;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-phase frame profiler, enabled with {@code -Dminemetal.gpuProfile=true}.
 * <p>
 * Callers (MineMetal itself, the Iris fork) mark phases with {@link #phase(String)}. Every Metal encoder started during a
 * phase is timestamped on the GPU (stage-boundary counters) and its time is added to that phase; the CPU time between
 * phase switches is measured too. Every two seconds an average per frame is logged.
 */
public final class MetalProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal/Profile");
    /** "true"/"split": one command buffer per phase (exact phase times); "encoders": encoder timestamps (overlapping). */
    private static final String MODE = System.getProperty("minemetal.gpuProfile", "false");
    public static final boolean ENABLED = MODE.equals("true") || MODE.equals("split") || MODE.equals("encoders");
    private static final int MAX_TAGS = 48; // = MM_PROF_TAGS
    private static final long INTERVAL_NS = 2_000_000_000L;

    private static final List<String> NAMES = new ArrayList<>(List.of("other"));
    private static long ctx;
    private static boolean active;
    private static int current;
    private static long lastSwitch = System.nanoTime();
    private static final long[] cpuNs = new long[MAX_TAGS];
    private static int frames;
    private static long windowStart = System.nanoTime();
    private static long out;

    private MetalProfiler() {
    }

    static void init(final long context) {
        if (!ENABLED) {
            return;
        }
        ctx = context;
        int mode = Native.profEnable(ctx, !MODE.equals("encoders"));
        active = mode != 0;
        if (active) {
            out = MemoryUtil.nmemCalloc(MAX_TAGS * 2 + 4, 8);
            LOGGER.info("GPU profiler on ({}): per-phase times are logged every {} s",
                mode == 2 ? "one command buffer per phase" : "encoder timestamps", INTERVAL_NS / 1_000_000_000L);
        } else {
            LOGGER.warn("GPU profiler requested, but it could not be enabled");
        }
    }

    public static boolean active() {
        return active;
    }

    /** Marks the start of a phase (render thread). Cheap no-op when profiling is off. */
    public static void phase(final String name) {
        if (FrameTrace.active()) {
            FrameTrace.event("== " + name);
        }
        if (!active) {
            return;
        }
        int tag = NAMES.indexOf(name);
        if (tag < 0) {
            if (NAMES.size() >= MAX_TAGS) {
                tag = 0;
            } else {
                NAMES.add(name);
                tag = NAMES.size() - 1;
            }
        }
        if (tag == current) {
            return;
        }
        long now = System.nanoTime();
        cpuNs[current] += now - lastSwitch;
        lastSwitch = now;
        current = tag;
        Native.profSetTag(ctx, tag);
    }

    /** Called once per presented frame. */
    static void frame() {
        if (!active) {
            return;
        }
        frames++;
        long now = System.nanoTime();
        if (now - windowStart < INTERVAL_NS) {
            return;
        }
        cpuNs[current] += now - lastSwitch;
        lastSwitch = now;
        Native.profRead(ctx, out);
        double f = Math.max(frames, 1);
        StringBuilder gpu = new StringBuilder();
        StringBuilder cpu = new StringBuilder();
        double gpuSum = 0.0;
        for (int t = 0; t < NAMES.size(); t++) {
            double ms = MemoryUtil.memGetDouble(out + t * 16L) / f;
            double enc = MemoryUtil.memGetDouble(out + t * 16L + 8) / f;
            gpuSum += ms;
            if (ms > 0.005 || enc > 0.0) {
                gpu.append(String.format(" %s %.2f (%.0f enc)", NAMES.get(t), ms, enc));
            }
            double c = cpuNs[t] / 1.0e6 / f;
            if (c > 0.005) {
                cpu.append(String.format(" %s %.2f", NAMES.get(t), c));
            }
            cpuNs[t] = 0L;
        }
        double cbMs = MemoryUtil.memGetDouble(out + MAX_TAGS * 16L) / f;
        double cbs = MemoryUtil.memGetDouble(out + MAX_TAGS * 16L + 8) / f;
        double seconds = (now - windowStart) / 1.0e9;
        LOGGER.info(String.format("%.1f fps | GPU ms/frame: command buffers %.2f (%.1f per frame), encoders %.2f ->%s | CPU ms/frame:%s",
            frames / seconds, cbMs, cbs, gpuSum, gpu, cpu));
        frames = 0;
        windowStart = now;
    }
}
