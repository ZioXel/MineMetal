package dev.minemetal.client.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.minemetal.client.metal.MetalDevice;
import dev.minemetal.client.metal.MetalProfiler;
import dev.minemetal.client.metal.MetalTexture;
import dev.minemetal.client.metal.Native;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Renders the world at a lower resolution and scales it up to the window before the GUI is drawn (the GUI stays sharp).
 * <p>
 * {@code GameRenderer.render()} swaps its main render target for a smaller one around {@code renderLevel()} and the
 * world post effects, then {@link #finishWorld} scales that image into the real main target with MetalFX, AMD FSR 1 or
 * plain bilinear filtering. Everything that renders the world (vanilla, Sodium, Iris shader packs) reads the size from
 * the main render target, so it simply sees a smaller screen.
 * <p>
 * Settings live in {@code config/minemetal.properties} and in Video Settings → MineMetal.
 */
public final class MetalUpscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal");
    public static final int MIN_SCALE = 50;
    public static final int MAX_SCALE = 100;

    private static UpscaleMode mode = UpscaleMode.OFF;
    private static int scalePercent = 75;
    private static int sharpnessPercent = 80;
    private static boolean loaded;

    private static @Nullable TextureTarget scaled;
    private static UpscaleMode lastUsed = UpscaleMode.OFF;

    private static final TemporalState TEMPORAL = new TemporalState();
    private static boolean temporalThisFrame;
    private static boolean depthCaptured;
    private static boolean standardDepthThisFrame;
    private static boolean packMovesBob;
    /**
     * Sign conventions for MetalFX's jitter offset per axis (tested on macOS 27: X as applied, Y negated) and motion
     * vectors. Overridable with -Dminemetal.taa.jitterSignX/Y=±1 and -Dminemetal.taa.motionSign=±1.
     */
    private static final float JITTER_SIGN = Float.parseFloat(System.getProperty("minemetal.taa.jitterSign", "-1"));
    private static final float JITTER_SIGN_X = Float.parseFloat(System.getProperty("minemetal.taa.jitterSignX", System.getProperty("minemetal.taa.jitterSign", "1")));
    private static final float JITTER_SIGN_Y = Float.parseFloat(System.getProperty("minemetal.taa.jitterSignY", Float.toString(JITTER_SIGN)));
    /**
     * Depth convention of the captured depth: reversed (default; Minecraft's reversed Z, which shader packs on MineMetal also
     * end up with), standard, or auto (shader pack = standard). Tested: reversed is correct with Complementary on Iris.
     */
    private static final String DEPTH_MODE = System.getProperty("minemetal.taa.depth", "reversed");
    /** How much MetalFX should trust the current frame on sky pixels (shader-pack clouds have no depth): 0..1, 0 = off. */
    private static final float SKY_REACTIVE = Float.parseFloat(System.getProperty("minemetal.taa.skyReactive", "0.7"));
    /**
     * Which view matrix the motion vectors use: level (first LevelRenderer.render of the frame; includes Iris' view
     * bobbing), extract (as extracted, no bobbing), bob (extract + captured bobbing), late (after the frame).
     */
    private static final String VIEW_MODE = System.getProperty("minemetal.taa.view", "extract");
    private static final float MOTION_SIGN = Float.parseFloat(System.getProperty("minemetal.taa.motionSign", "1"));

    private MetalUpscaler() {
    }

    // -- settings ------------------------------------------------------------------------------------------------------

    public static UpscaleMode mode() {
        load();
        return mode;
    }

    public static int scalePercent() {
        load();
        return scalePercent;
    }

    public static int sharpnessPercent() {
        load();
        return sharpnessPercent;
    }

    public static void setMode(final UpscaleMode m) {
        load();
        mode = m;
        TEMPORAL.invalidate();
        save();
    }

    public static void setScalePercent(final int v) {
        load();
        scalePercent = Math.clamp(v, MIN_SCALE, MAX_SCALE);
        save();
    }

    public static void setSharpnessPercent(final int v) {
        load();
        sharpnessPercent = Math.clamp(v, 0, 100);
        save();
    }

    /** The upscaler the native side really used last frame (MetalFX falls back to FSR if unsupported). */
    public static UpscaleMode lastUsed() {
        return lastUsed;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("minemetal.properties");
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Properties p = new Properties();
        Path f = file();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                p.load(r);
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.warn("[MineMetal] could not read {}: {}", f, e.toString());
            }
        }
        mode = UpscaleMode.parse(System.getProperty("minemetal.upscale", p.getProperty("upscale", "off")));
        scalePercent = Math.clamp(parseInt(System.getProperty("minemetal.renderScale", p.getProperty("renderScale")), 75), MIN_SCALE, MAX_SCALE);
        sharpnessPercent = Math.clamp(parseInt(p.getProperty("sharpness"), 80), 0, 100);
    }

    private static int parseInt(final @Nullable String s, final int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim().replace("%", ""));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static synchronized void save() {
        Properties p = new Properties();
        p.setProperty("upscale", mode.name().toLowerCase());
        p.setProperty("renderScale", Integer.toString(scalePercent));
        p.setProperty("sharpness", Integer.toString(sharpnessPercent));
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) {
                p.store(w, "MineMetal settings (upscale: off, metalfx_temporal, metalfx, fsr1, bilinear; renderScale: 50-100 %; sharpness: 0-100 %)");
            }
        } catch (IOException e) {
            LOGGER.warn("[MineMetal] could not write {}: {}", f, e.toString());
        }
    }

    // -- per frame -----------------------------------------------------------------------------------------------------

    /** True when the world should be rendered at a reduced resolution this frame. */
    public static boolean active() {
        load();
        return mode != UpscaleMode.OFF && (scalePercent < MAX_SCALE || mode.temporal()) && MetalDevice.current() != null;
    }

    /**
     * Called right before the world renders. Returns the reduced-size target that should stand in for the main target
     * until {@link #finishWorld}, or null to render at full resolution.
     */
    public static @Nullable RenderTarget beginWorld(final RenderTarget main, final CameraRenderState camera) {
        temporalThisFrame = false;
        depthCaptured = false;
        if (!active()) {
            lastUsed = UpscaleMode.OFF;
            releaseIfIdle();
            return null;
        }
        int w = Math.max(1, Math.round(main.width * scalePercent / 100.0F));
        int h = Math.max(1, Math.round(main.height * scalePercent / 100.0F));
        if (w >= main.width && h >= main.height && !mode.temporal()) {
            return null;
        }
        try {
            if (scaled == null) {
                scaled = new TextureTarget("MineMetal scaled world", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            } else if (scaled.width != w || scaled.height != h) {
                scaled.resize(w, h);
            }
            GpuTexture color = scaled.getColorTexture();
            GpuTexture depth = scaled.getDepthTexture();
            if (color == null || depth == null) {
                return null;
            }
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, new Vector4f(0.0F, 0.0F, 0.0F, 1.0F), depth, 0.0);
            if (mode.temporal() && camera.initialized) {
                TEMPORAL.jitter(camera.projectionMatrix, w, h);
                if (VIEW_MODE.equals("extract") || VIEW_MODE.equals("bob")) {
                    TEMPORAL.captureView(camera.viewRotationMatrix);  // as extracted, before anything adds view bobbing
                }
                temporalThisFrame = true;
                packMovesBob = IrisCheck.shaderPackInUse();  // Iris applies bobbing to the view matrix instead
                standardDepthThisFrame = switch (DEPTH_MODE) {
                    case "standard" -> true;
                    case "reversed" -> false;
                    default -> packMovesBob;
                };
            }
            return scaled;
        } catch (RuntimeException e) {
            LOGGER.error("[MineMetal] upscaling disabled: could not create the scaled render target", e);
            mode = UpscaleMode.OFF;
            return null;
        }
    }

    /** The projection renderLevel actually used (jittered, with view bobbing). */
    public static void captureProjection(final Matrix4fc projection) {
        if (temporalThisFrame) {
            TEMPORAL.capture(projection);
        }
    }

    /** View bobbing / hurt shake of this frame (as multiplied onto the projection by vanilla). */
    public static void captureBob(final Matrix4fc bob) {
        if (temporalThisFrame) {
            TEMPORAL.captureBob(bob, packMovesBob && VIEW_MODE.equals("bob"));
        }
    }

    /** The view matrix the level renders with (includes view bobbing when Iris moved it there). */
    public static void captureView(final Matrix4fc view) {
        if (temporalThisFrame && VIEW_MODE.equals("level")) {
            TEMPORAL.captureView(view);
        }
    }

    /** Copies the world depth before the hand clears/overwrites it (MetalFX Temporal needs scene depth). */
    public static void captureDepth(final RenderTarget low) {
        MetalDevice device = MetalDevice.current();
        if (!temporalThisFrame || device == null || !(low.getDepthTexture() instanceof MetalTexture depth)) {
            return;
        }
        Native.upscaleCaptureDepth(device.ctx(), depth.handle());
        depthCaptured = true;
    }

    /** Puts the unjittered projection back once the world has rendered. */
    public static void afterLevel(final CameraRenderState camera) {
        if (temporalThisFrame) {
            TEMPORAL.restore(camera.projectionMatrix);
        }
    }

    /** Scales the low-resolution world image up into the real main target. */
    public static void finishWorld(final RenderTarget low, final RenderTarget main, final CameraRenderState camera) {
        MetalDevice device = MetalDevice.current();
        GpuTexture src = low.getColorTexture();
        GpuTexture dst = main.getColorTexture();
        if (device == null || !(src instanceof MetalTexture s) || !(dst instanceof MetalTexture d)) {
            return;
        }
        MetalProfiler.phase("upscale");
        int used = -1;
        if (temporalThisFrame && depthCaptured
                && TEMPORAL.prepare(camera.viewRotationMatrix, camera.pos.x, camera.pos.y, camera.pos.z, standardDepthThisFrame, MOTION_SIGN, SKY_REACTIVE)) {
            TEMPORAL.params[36] *= JITTER_SIGN_X;
            TEMPORAL.params[37] *= JITTER_SIGN_Y;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.FloatBuffer buf = stack.floats(TEMPORAL.params);
                used = Native.upscaleTemporal(device.ctx(), s.handle(), d.handle(), org.lwjgl.system.MemoryUtil.memAddress(buf),
                    TEMPORAL.resetRequested ? 1 : 0, sharpnessPercent / 100.0F);
            }
            TEMPORAL.resetRequested = false;
        }
        if (used < 0) {
            used = Native.upscale(device.ctx(), s.handle(), d.handle(), mode.temporal() ? UpscaleMode.METALFX.nativeId : mode.nativeId,
                sharpnessPercent / 100.0F);
        }
        lastUsed = switch (used) {
            case 4 -> UpscaleMode.METALFX_TEMPORAL;
            case 3 -> UpscaleMode.METALFX;
            case 2 -> UpscaleMode.FSR1;
            case 1 -> UpscaleMode.BILINEAR;
            default -> UpscaleMode.OFF;
        };
        MetalProfiler.phase("gui");
    }

    /** Iris API lookup without a compile-time dependency (shader packs write standard depth, vanilla reversed Z). */
    private static final class IrisCheck {
        private static java.lang.reflect.Method inUse;
        private static Object api;
        private static boolean looked;

        static boolean shaderPackInUse() {
            if (!looked) {
                looked = true;
                try {
                    Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = c.getMethod("getInstance").invoke(null);
                    inUse = c.getMethod("isShaderPackInUse");
                } catch (ReflectiveOperationException | LinkageError e) {
                    api = null;
                }
            }
            try {
                return api != null && (Boolean) inUse.invoke(api);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
    }

    private static void releaseIfIdle() {
        if (scaled != null) {
            scaled.destroyBuffers();
            scaled = null;
        }
    }
}
