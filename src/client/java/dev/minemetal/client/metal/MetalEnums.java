package dev.minemetal.client.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import java.util.EnumMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Resolves Mojang enums to Metal enum values once, by name, through the native library
 * (so no MTL* numeric constants are hardcoded on the Java side).
 */
public final class MetalEnums {
    private static final Map<GpuFormat, Long> PIXEL = new EnumMap<>(GpuFormat.class);
    private static final Map<GpuFormat, Long> VERTEX = new EnumMap<>(GpuFormat.class);
    private static final Map<CompareOp, Long> COMPARE = new EnumMap<>(CompareOp.class);
    private static final Map<BlendFactor, Long> BLEND = new EnumMap<>(BlendFactor.class);
    private static final Map<BlendOp, Long> BLEND_OP = new EnumMap<>(BlendOp.class);
    private static long bgra8 = -1;
    private static boolean initialized;

    private MetalEnums() {
    }

    static synchronized void init() {
        if (initialized) {
            return;
        }
        for (GpuFormat f : GpuFormat.values()) {
            PIXEL.put(f, lookup("pixel", f.name()));
            VERTEX.put(f, lookup("vertex", f.name()));
        }
        for (CompareOp op : CompareOp.values()) {
            COMPARE.put(op, require("compare." + op.name()));
        }
        for (BlendFactor bf : BlendFactor.values()) {
            BLEND.put(bf, require("blend." + bf.name()));
        }
        for (BlendOp op : BlendOp.values()) {
            BLEND_OP.put(op, require("blendop." + op.name()));
        }
        bgra8 = lookup("pixel", "BGRA8_UNORM");
        initialized = true;
    }

    private static long lookup(String kind, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long k = MemoryUtil.memAddress(stack.UTF8(kind));
            long n = MemoryUtil.memAddress(stack.UTF8(name));
            return Native.enumValue(k, n);
        }
    }

    private static long require(String name) {
        long v = lookup("misc", name);
        if (v < 0) {
            throw new IllegalStateException("Native library does not know enum " + name);
        }
        return v;
    }

    /** MTLPixelFormat, or -1 if Metal has no matching texture format (e.g. 3-component formats). */
    public static long pixelFormat(GpuFormat format) {
        return PIXEL.get(format);
    }

    /** MTLVertexFormat, or -1 if unsupported. */
    public static long vertexFormat(GpuFormat format) {
        return VERTEX.get(format);
    }

    public static long compare(CompareOp op) {
        return COMPARE.get(op);
    }

    public static long blendFactor(BlendFactor factor) {
        return BLEND.get(factor);
    }

    public static long blendOp(BlendOp op) {
        return BLEND_OP.get(op);
    }

    public static long bgra8() {
        return bgra8;
    }
}
