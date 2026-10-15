package dev.minemetal.client.glcompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs shader/pipeline compile work that is slow enough to cause a visible hitch (> 15 ms), to find stutter sources. */
public final class CompileTiming {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal");
    private static final long THRESHOLD_NS = 15_000_000L;

    private CompileTiming() {
    }

    static void spirv(final String name, final long ns) {
        if (ns > THRESHOLD_NS) {
            LOGGER.info("[MineMetal] compile: GLSL->SPIR-V {} took {} ms (now cached on disk)", name, String.format("%.1f", ns / 1e6));
        }
    }

    public static void pipeline(final String name, final long ns) {
        if (ns > THRESHOLD_NS) {
            LOGGER.info("[MineMetal] compile: Metal pipeline {} took {} ms (shader cache: {} hits, {} misses)", name,
                String.format("%.1f", ns / 1e6), SpirvCache.hits, SpirvCache.misses);
        }
    }
}
