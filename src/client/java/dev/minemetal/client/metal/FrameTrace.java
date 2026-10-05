package dev.minemetal.client.metal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Debugging aid: records the order of render events of one frame and logs it (-Dminemetal.traceFrame=true). */
public final class FrameTrace {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal/Trace");
    private static final boolean ENABLED = Boolean.getBoolean("minemetal.traceFrame");
    private static final long START_NANOS = System.nanoTime();
    private static int state; // 0 waiting, 1 recording, 2 done
    private static final StringBuilder LOG = new StringBuilder();
    private static String last = "";
    private static int count;

    private FrameTrace() {
    }

    public static boolean active() {
        return ENABLED && state == 1;
    }

    public static void event(final String what) {
        if (!ENABLED || state != 1 || what.equals(last)) {
            return;
        }
        last = what;
        if (count++ < 600) {
            LOG.append("\n  ").append(what);
        }
    }

    /** Called at every present: starts recording 25 s after launch, logs the recorded frame at the next present. */
    public static void framePresented() {
        if (!ENABLED) {
            return;
        }
        if (state == 0 && System.nanoTime() - START_NANOS > 25_000_000_000L) {
            state = 1;
        } else if (state == 1) {
            state = 2;
            LOGGER.info("frame trace ({} events):{}", count, LOG);
        }
    }
}
