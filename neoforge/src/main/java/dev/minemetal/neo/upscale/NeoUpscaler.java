package dev.minemetal.neo.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.logging.LogUtils;
import dev.minemetal.neo.gl.Gl;
import dev.minemetal.neo.metal.MetalBootstrap;
import dev.minemetal.neo.metal.NeoNative;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * World upscaling for 1.21.1: while the level (and Iris's whole shader pipeline) renders, Minecraft's main render
 * target is swapped for a smaller one; before the GUI draws, the result is scaled into the real main target with
 * MetalFX Spatial / FSR 1 / bilinear (native, shared with the 26.3 mod).
 * <p>
 * Settings live in config/minemetal.properties and are re-read when the file changes, so they can be tuned while
 * the game runs:  upscale = off | metalfx | fsr | bilinear,  renderScale = 50..100 (%),  sharpness = 0..100 (%).
 */
public final class NeoUpscaler {
    private static final Logger LOG = LogUtils.getLogger();

    public enum Mode {
        OFF(0), BILINEAR(1), FSR(2), METALFX(3);
        final int nativeId;

        Mode(int id) {
            this.nativeId = id;
        }
    }

    private static Mode mode = Mode.OFF;
    private static int scale = 67;
    private static int sharpness = 50;
    private static long lastModified = Long.MIN_VALUE;
    private static long lastCheck;
    private static TextureTarget low;
    private static int lastUsed;

    private NeoUpscaler() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("minemetal.properties");
    }

    /** Re-reads the config at most twice a second, and only when the file changed. */
    private static void refresh() {
        long now = System.nanoTime();
        if (now - lastCheck < 500_000_000L && lastModified != Long.MIN_VALUE) return;
        lastCheck = now;
        Path f = file();
        try {
            if (!Files.exists(f)) {
                writeDefaults(f);
            }
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m == lastModified) return;
            lastModified = m;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f)) {
                p.load(r);
            }
            mode = switch (p.getProperty("upscale", "off").trim().toLowerCase(Locale.ROOT)) {
                case "metalfx", "metalfx_spatial", "spatial" -> Mode.METALFX;
                case "fsr", "fsr1" -> Mode.FSR;
                case "bilinear" -> Mode.BILINEAR;
                default -> Mode.OFF;
            };
            scale = clamp(parse(p.getProperty("renderScale"), 67), 50, 100);
            sharpness = clamp(parse(p.getProperty("sharpness"), 50), 0, 100);
            LOG.info("[MineMetal] upscaling: {} at {}% render scale, sharpness {}%", mode, scale, sharpness);
        } catch (IOException e) {
            LOG.warn("[MineMetal] could not read {}: {}", f, e.toString());
            lastModified = 0;
        }
    }

    private static void writeDefaults(Path f) throws IOException {
        Files.createDirectories(f.getParent());
        try (Writer w = Files.newBufferedWriter(f)) {
            w.write("# MineMetal (NeoForge) - changes apply while the game runs\n");
            w.write("# upscale: off | metalfx | fsr | bilinear\n");
            w.write("upscale=off\n");
            w.write("# renderScale: world resolution in percent of the window (50..100)\n");
            w.write("renderScale=67\n");
            w.write("# sharpness: 0..100 (RCAS sharpening after the upscale)\n");
            w.write("sharpness=50\n");
        }
    }

    private static int parse(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Before the level renders: the smaller stand-in for the main target, or null to render at full size. */
    public static RenderTarget begin(RenderTarget main) {
        if (!MetalBootstrap.ENABLED) return null;
        refresh();
        if (mode == Mode.OFF || scale >= 100) {
            release();
            return null;
        }
        int w = Math.max(1, Math.round(main.width * scale / 100f));
        int h = Math.max(1, Math.round(main.height * scale / 100f));
        if (low == null) {
            low = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            low.setClearColor(0f, 0f, 0f, 0f);
        } else if (low.width != w || low.height != h) {
            low.resize(w, h, Minecraft.ON_OSX);
        }
        low.clear(Minecraft.ON_OSX);
        Gl.upscaleFullW = main.width;
        Gl.upscaleFullH = main.height;
        low.bindWrite(true); // the frame's viewport was set for the full window just before renderLevel
        return low;
    }

    /** After the level: scale {@code lowTarget} into {@code main}. */
    /** Ends the low-resolution section (also when the frame is aborted). */
    public static void end() {
        Gl.upscaleFullW = 0;
        Gl.upscaleFullH = 0;
    }

    public static void finish(RenderTarget lowTarget, RenderTarget main) {
        long src = Gl.metalTexture(lowTarget.getColorTextureId());
        long dst = Gl.metalTexture(main.getColorTextureId());
        if (src == 0L || dst == 0L) return;
        int used = NeoNative.upscale(Gl.context(), src, dst, mode.nativeId, sharpness / 100f);
        if (used != lastUsed) {
            lastUsed = used;
            LOG.info("[MineMetal] upscaler in use: {}", switch (used) {
                case 3 -> "MetalFX Spatial";
                case 2 -> "FSR 1";
                case 1 -> "bilinear";
                default -> "none (" + used + ")";
            });
        }
    }

    private static void release() {
        if (low != null) {
            low.destroyBuffers();
            low = null;
        }
    }
}
