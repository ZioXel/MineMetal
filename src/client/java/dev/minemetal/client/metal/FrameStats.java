package dev.minemetal.client.metal;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.nio.LongBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight frame-pacing diagnostics for the Metal backend (render thread only).
 * <p>
 * Every 5 s logs a summary; additionally logs every "hitch" (a frame much slower than the recent average) with a
 * breakdown of where the backend spent its time. Disable with -Dminemetal.stats=false.
 */
final class FrameStats {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal");
    static final boolean ENABLED = !"false".equals(System.getProperty("minemetal.stats"));
    private static final long SUMMARY_NS = 5_000_000_000L;
    private static final int MAX_HITCH_LOGS_PER_SUMMARY = 15;

    private @Nullable MetalSurface surface;
    private long lastFrameEnd;
    private double avgFrameNs = 8_000_000.0;

    // per-frame accumulators
    long acquireNs;
    long blitNs;
    long submitNs;
    long waitNs;

    // per-summary accumulators
    private long summaryStart;
    private int frames;
    private long maxFrameNs;
    private long totalWaitNs;
    private long maxWaitNs;
    private int hitches;
    private int hitchLogs;

    void surface(final MetalSurface surface) {
        this.surface = surface;
    }

    /** Called at the end of every submit (= once per frame for the main loop). */
    void endFrame(final String poolStats) {
        long now = System.nanoTime();
        if (this.lastFrameEnd == 0L) {
            this.lastFrameEnd = now;
            this.summaryStart = now;
            this.resetFrame();
            return;
        }
        long frameNs = now - this.lastFrameEnd;
        this.lastFrameEnd = now;
        this.frames++;
        this.maxFrameNs = Math.max(this.maxFrameNs, frameNs);
        this.totalWaitNs += this.waitNs;
        this.maxWaitNs = Math.max(this.maxWaitNs, this.waitNs);

        boolean hitch = frameNs > Math.max(this.avgFrameNs * 2.5, 12_000_000.0);
        if (hitch) {
            this.hitches++;
            if (this.hitchLogs++ < MAX_HITCH_LOGS_PER_SUMMARY) {
                LOGGER.info("MineMetal hitch: frame {} ms (avg {} ms) | backend: gpu-wait {} ms, acquire {} ms, present-blit {} ms, commit {} ms -> rest is game/CPU",
                    ms(frameNs), ms((long) this.avgFrameNs), ms(this.waitNs), ms(this.acquireNs), ms(this.blitNs), ms(this.submitNs));
            }
        }
        // Always adapt (clamped), so a new steady state (VSync, AFK limiter) stops counting as hitches.
        this.avgFrameNs = this.avgFrameNs * 0.95 + Math.min(frameNs, this.avgFrameNs * 4.0) * 0.05;
        this.resetFrame();

        if (now - this.summaryStart >= SUMMARY_NS) {
            double seconds = (now - this.summaryStart) / 1e9;
            String surfaceInfo = "";
            if (this.surface != null) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    LongBuffer out = stack.callocLong(8);
                    Native.surfaceStats(this.surface.handle(), MemoryUtil.memAddress(out));
                    String mode = switch ((int) out.get(7)) {
                        case 0 -> "vsync";
                        case 1 -> "paced";
                        default -> "immediate";
                    };
                    surfaceInfo = String.format(" | present[%s, display %.1f Hz]: shown %d, skipped %d, forced-blocking %d, acquire max %s ms, in-flight %d",
                        mode, out.get(6) > 0 ? 1e6 / out.get(6) : 0.0, out.get(0), out.get(1), out.get(2), ms(out.get(4)), out.get(5));
                }
            }
            long[] clears = Native.clearDeferredStats();
            LOGGER.info("MineMetal stats: {} fps, frame max {} ms, gpu-wait avg {} / max {} ms, hitches {}{} | {} | clears folded {}, flushed {}",
                String.format("%.0f", this.frames / seconds), ms(this.maxFrameNs),
                ms(this.frames > 0 ? this.totalWaitNs / this.frames : 0), ms(this.maxWaitNs), this.hitches, surfaceInfo, poolStats,
                clears[0], clears[1]);
            this.summaryStart = now;
            this.frames = 0;
            this.maxFrameNs = 0L;
            this.totalWaitNs = 0L;
            this.maxWaitNs = 0L;
            this.hitches = 0;
            this.hitchLogs = 0;
        }
    }

    private void resetFrame() {
        this.acquireNs = 0L;
        this.blitNs = 0L;
        this.submitNs = 0L;
        this.waitNs = 0L;
    }

    private static String ms(final long ns) {
        return String.format("%.2f", ns / 1e6);
    }
}
