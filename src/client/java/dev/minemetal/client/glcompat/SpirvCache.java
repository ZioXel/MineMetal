package dev.minemetal.client.glcompat;

import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Disk cache for GLSL -> SPIR-V results (shaderc is the slowest step when a shader pack's programs are first used).
 * Keyed by a hash of the final GLSL source and the shader stage, so a changed pack or option set simply misses.
 * Lives in .minecraft/minemetal-cache/spirv; safe to delete. -Dminemetal.noShaderCache=true disables it.
 */
final class SpirvCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal");
    /** Bump when the shaderc options in GlProgramCompiler change, so old entries are not reused. */
    private static final String VERSION = "v1";
    private static final boolean ENABLED = !Boolean.getBoolean("minemetal.noShaderCache");
    private static @Nullable Path dir;
    private static boolean broken;
    static int hits;
    static int misses;

    private SpirvCache() {
    }

    static String key(final String source, final int kind) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((VERSION + ":" + kind + ":").getBytes(StandardCharsets.UTF_8));
            md.update(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @Nullable Path dir() {
        if (!ENABLED || broken) {
            return null;
        }
        if (dir == null) {
            try {
                dir = FabricLoader.getInstance().getGameDir().resolve("minemetal-cache").resolve("spirv");
                Files.createDirectories(dir);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[MineMetal] shader cache disabled: {}", e.toString());
                broken = true;
                return null;
            }
        }
        return dir;
    }

    /** Cached SPIR-V as a newly allocated (MemoryUtil.memAlloc) buffer, or null on a miss. */
    static @Nullable ByteBuffer load(final String key) {
        Path d = dir();
        if (d == null) {
            return null;
        }
        Path f = d.resolve(key + ".spv");
        try {
            if (!Files.isRegularFile(f)) {
                misses++;
                return null;
            }
            byte[] bytes = Files.readAllBytes(f);
            if (bytes.length < 20 || bytes.length % 4 != 0) {
                misses++;
                return null;
            }
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
            buf.put(bytes).flip();
            hits++;
            return buf;
        } catch (IOException e) {
            misses++;
            return null;
        }
    }

    static void store(final String key, final ByteBuffer spv) {
        Path d = dir();
        if (d == null) {
            return;
        }
        try {
            byte[] bytes = new byte[spv.remaining()];
            spv.duplicate().get(bytes);
            Path tmp = Files.createTempFile(d, key, ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, d.resolve(key + ".spv"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.debug("[MineMetal] could not write shader cache entry: {}", e.toString());
        }
    }
}
