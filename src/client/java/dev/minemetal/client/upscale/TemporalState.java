package dev.minemetal.client.upscale;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Per-frame data for MetalFX Temporal: the sub-pixel camera jitter and the camera matrices of this and the previous
 * frame, from which the native side builds motion vectors (camera motion only: depth + reprojection).
 */
final class TemporalState {
    /** Jitter cycle length; MetalFX wants at least 8 × (output/input)² distinct samples for its upscaling factor. */
    private static final int PHASES = 32;

    private int frame;
    float jitterX;  // render-resolution pixels, -0.5..0.5
    float jitterY;
    private int renderW;
    private int renderH;

    private final Matrix4f originalProjection = new Matrix4f();
    private boolean projectionJittered;
    private final Matrix4f capturedProjection = new Matrix4f();
    private boolean captured;

    private final Matrix4f prevViewProj = new Matrix4f();
    private double prevX, prevY, prevZ;
    private boolean hasPrev;
    boolean resetRequested = true;

    final float[] params = new float[44];

    private static float halton(int index, final int base) {
        float f = 1.0F;
        float r = 0.0F;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }

    /** Picks this frame's jitter and applies it to the projection the world is about to render with. */
    void jitter(final Matrix4f projection, final int w, final int h) {
        if (w != this.renderW || h != this.renderH) {
            this.renderW = w;
            this.renderH = h;
            this.resetRequested = true;
        }
        this.frame = (this.frame + 1) % PHASES;
        this.jitterX = halton(this.frame + 1, 2) - 0.5F;
        this.jitterY = halton(this.frame + 1, 3) - 0.5F;
        this.originalProjection.set(projection);
        // NDC offset; texture rows run top-down while NDC y runs up.
        new Matrix4f().translation(2.0F * this.jitterX / w, -2.0F * this.jitterY / h, 0.0F).mul(projection, projection);
        this.projectionJittered = true;
        this.captured = false;
    }

    void restore(final Matrix4f projection) {
        if (this.projectionJittered) {
            projection.set(this.originalProjection);
            this.projectionJittered = false;
        }
    }

    /** The final (jittered, view-bobbed) projection the level was rendered with. */
    void capture(final Matrix4fc projection) {
        this.capturedProjection.set(projection);
        this.captured = true;
    }

    /**
     * Fills {@link #params} for mm_upscale_temporal. Returns false if this frame has no usable matrices.
     * Layout: [0..15] inverse(current unjittered view-projection), [16..31] previous view-projection,
     * [32..34] camera movement since last frame (current - previous), [36..37] jitter, [38] standard depth, [39] motion sign, [40] sky reactive-mask value.
     */
    boolean prepare(final Matrix4fc viewRotation, final double camX, final double camY, final double camZ,
                    final boolean standardDepth, final float motionSign, final float skyReactive) {
        Matrix4f proj = this.captured ? new Matrix4f(this.capturedProjection) : new Matrix4f(this.originalProjection);
        if (this.captured) {
            // Remove the jitter again: motion vectors describe the unjittered scene.
            new Matrix4f().translation(-2.0F * this.jitterX / this.renderW, 2.0F * this.jitterY / this.renderH, 0.0F).mul(proj, proj);
        }
        Matrix4f viewProj = proj.mul(viewRotation, new Matrix4f());
        Matrix4f inv = viewProj.invert(new Matrix4f());
        if (!Float.isFinite(inv.m00())) {
            return false;
        }
        double dx = this.hasPrev ? camX - this.prevX : 0.0;
        double dy = this.hasPrev ? camY - this.prevY : 0.0;
        double dz = this.hasPrev ? camZ - this.prevZ : 0.0;
        if (!this.hasPrev || dx * dx + dy * dy + dz * dz > 64.0) {
            this.resetRequested = true;  // first frame, teleport or dimension change: drop the history
        }
        inv.get(this.params, 0);
        (this.hasPrev ? this.prevViewProj : viewProj).get(this.params, 16);
        this.params[32] = (float) dx;
        this.params[33] = (float) dy;
        this.params[34] = (float) dz;
        this.params[35] = 0.0F;
        this.params[36] = this.jitterX;
        this.params[37] = this.jitterY;
        this.params[38] = standardDepth ? 1.0F : 0.0F;
        this.params[39] = motionSign;
        this.params[40] = skyReactive;
        this.prevViewProj.set(viewProj);
        this.prevX = camX;
        this.prevY = camY;
        this.prevZ = camZ;
        this.hasPrev = true;
        return true;
    }

    void invalidate() {
        this.hasPrev = false;
        this.resetRequested = true;
    }
}
