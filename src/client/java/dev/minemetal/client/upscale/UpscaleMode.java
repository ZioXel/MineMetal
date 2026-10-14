package dev.minemetal.client.upscale;

/** World upscalers, in the order Video Settings cycles through them. {@link #nativeId} is the mode passed to mm_upscale. */
public enum UpscaleMode {
    OFF(0, "Off"),
    METALFX_TEMPORAL(4, "MetalFX Temporal"),
    METALFX(3, "MetalFX Spatial"),
    FSR1(2, "AMD FSR 1"),
    BILINEAR(1, "Bilinear");

    public final int nativeId;
    public final String label;

    UpscaleMode(final int nativeId, final String label) {
        this.nativeId = nativeId;
        this.label = label;
    }

    public boolean temporal() {
        return this == METALFX_TEMPORAL;
    }

    public static UpscaleMode parse(final String s) {
        for (UpscaleMode m : values()) {
            if (m.name().equalsIgnoreCase(s)) {
                return m;
            }
        }
        return OFF;
    }
}
