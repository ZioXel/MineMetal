package dev.minemetal.client.glcompat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import dev.minemetal.client.metal.MetalEnums;
import org.jspecify.annotations.Nullable;

import static org.lwjgl.opengl.GL46C.*;

/** OpenGL enum → renderpearl / Metal translations used by the GL emulation. */
final class GlEnums {
    private GlEnums() {
    }

    /**
     * GL sized (or unsized) internal format → storage format. Metal has no 3-component formats, so RGB formats are stored
     * as RGBA (the GL semantics of sampling .a = 1 are not preserved for RGB render targets; packs rarely rely on it).
     */
    static @Nullable GpuFormat textureFormat(final int internalFormat) {
        return switch (internalFormat) {
            case GL_RGBA8, GL_RGBA, GL_RGB8, GL_RGB, GL_SRGB8_ALPHA8, GL_SRGB8, GL_RGB5_A1, GL_RGBA4, GL_RGB565 -> GpuFormat.RGBA8_UNORM;
            case GL_RGBA8_SNORM, GL_RGB8_SNORM -> GpuFormat.RGBA8_SNORM;
            case GL_RGBA16, GL_RGB16 -> GpuFormat.RGBA16_UNORM;
            case GL_RGBA16_SNORM, GL_RGB16_SNORM -> GpuFormat.RGBA16_SNORM;
            case GL_RGBA16F, GL_RGB16F -> GpuFormat.RGBA16_FLOAT;
            case GL_RGBA32F, GL_RGB32F -> GpuFormat.RGBA32_FLOAT;
            case GL_R11F_G11F_B10F -> GpuFormat.RG11B10_FLOAT;
            case GL_RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
            case GL_RGB10_A2UI -> GpuFormat.RGB10A2_UINT;
            case GL_R8, GL_RED -> GpuFormat.R8_UNORM;
            case GL_R8_SNORM -> GpuFormat.R8_SNORM;
            case GL_RG8, GL_RG -> GpuFormat.RG8_UNORM;
            case GL_RG8_SNORM -> GpuFormat.RG8_SNORM;
            case GL_R16 -> GpuFormat.R16_UNORM;
            case GL_R16_SNORM -> GpuFormat.R16_SNORM;
            case GL_RG16 -> GpuFormat.RG16_UNORM;
            case GL_RG16_SNORM -> GpuFormat.RG16_SNORM;
            case GL_R16F -> GpuFormat.R16_FLOAT;
            case GL_RG16F -> GpuFormat.RG16_FLOAT;
            case GL_R32F -> GpuFormat.R32_FLOAT;
            case GL_RG32F -> GpuFormat.RG32_FLOAT;
            case GL_R8I -> GpuFormat.R8_SINT;
            case GL_R8UI -> GpuFormat.R8_UINT;
            case GL_R16I -> GpuFormat.R16_SINT;
            case GL_R16UI -> GpuFormat.R16_UINT;
            case GL_R32I -> GpuFormat.R32_SINT;
            case GL_R32UI -> GpuFormat.R32_UINT;
            case GL_RG8I -> GpuFormat.RG8_SINT;
            case GL_RG8UI -> GpuFormat.RG8_UINT;
            case GL_RG16I -> GpuFormat.RG16_SINT;
            case GL_RG16UI -> GpuFormat.RG16_UINT;
            case GL_RG32I -> GpuFormat.RG32_SINT;
            case GL_RG32UI -> GpuFormat.RG32_UINT;
            case GL_RGBA8I, GL_RGB8I -> GpuFormat.RGBA8_SINT;
            case GL_RGBA8UI, GL_RGB8UI -> GpuFormat.RGBA8_UINT;
            case GL_RGBA16I, GL_RGB16I -> GpuFormat.RGBA16_SINT;
            case GL_RGBA16UI, GL_RGB16UI -> GpuFormat.RGBA16_UINT;
            case GL_RGBA32I, GL_RGB32I -> GpuFormat.RGBA32_SINT;
            case GL_RGBA32UI, GL_RGB32UI -> GpuFormat.RGBA32_UINT;
            case GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT32, GL_DEPTH_COMPONENT24 -> GpuFormat.D32_FLOAT;
            case GL_DEPTH_COMPONENT16 -> GpuFormat.D16_UNORM;
            case GL_DEPTH24_STENCIL8, GL_DEPTH32F_STENCIL8, GL_DEPTH_STENCIL -> GpuFormat.D32_FLOAT_S8_UINT;
            default -> null;
        };
    }

    /** Components and bytes per component of client pixel data (format/type of glTexImage2D), or null if unsupported. */
    static int @Nullable [] clientPixelLayout(final int format, final int type) {
        int components = switch (format) {
            case GL_RED, GL_RED_INTEGER, GL_DEPTH_COMPONENT -> 1;
            case GL_RG, GL_RG_INTEGER -> 2;
            case GL_RGB, GL_RGB_INTEGER, GL_BGR -> 3;
            case GL_RGBA, GL_RGBA_INTEGER, GL_BGRA -> 4;
            default -> 0;
        };
        int bytes = switch (type) {
            case GL_UNSIGNED_BYTE, GL_BYTE -> 1;
            case GL_UNSIGNED_SHORT, GL_SHORT, GL_HALF_FLOAT -> 2;
            case GL_UNSIGNED_INT, GL_INT, GL_FLOAT -> 4;
            default -> 0;
        };
        return components == 0 || bytes == 0 ? null : new int[]{components, bytes};
    }

    /** 0 repeat, 1 clamp to edge, 2 mirrored repeat, 3 clamp to border. */
    static int addressMode(final int wrap) {
        return switch (wrap) {
            case GL_CLAMP_TO_EDGE -> 1;
            case GL_MIRRORED_REPEAT -> 2;
            case GL_CLAMP_TO_BORDER -> 3;
            default -> 0;
        };
    }

    static CompareOp compareOp(final int func) {
        return switch (func) {
            case GL_NEVER -> CompareOp.NEVER_PASS;
            case GL_LESS -> CompareOp.LESS_THAN;
            case GL_EQUAL -> CompareOp.EQUAL;
            case GL_LEQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
            case GL_GREATER -> CompareOp.GREATER_THAN;
            case GL_NOTEQUAL -> CompareOp.NOT_EQUAL;
            case GL_GEQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
            default -> CompareOp.ALWAYS_PASS;
        };
    }

    static long compareFunction(final int func) {
        return MetalEnums.compare(compareOp(func));
    }

    static BlendFactor blendFactor(final int factor) {
        return switch (factor) {
            case GL_ZERO -> BlendFactor.ZERO;
            case GL_SRC_COLOR -> BlendFactor.SRC_COLOR;
            case GL_ONE_MINUS_SRC_COLOR -> BlendFactor.ONE_MINUS_SRC_COLOR;
            case GL_SRC_ALPHA -> BlendFactor.SRC_ALPHA;
            case GL_ONE_MINUS_SRC_ALPHA -> BlendFactor.ONE_MINUS_SRC_ALPHA;
            case GL_DST_ALPHA -> BlendFactor.DST_ALPHA;
            case GL_ONE_MINUS_DST_ALPHA -> BlendFactor.ONE_MINUS_DST_ALPHA;
            case GL_DST_COLOR -> BlendFactor.DST_COLOR;
            case GL_ONE_MINUS_DST_COLOR -> BlendFactor.ONE_MINUS_DST_COLOR;
            case GL_SRC_ALPHA_SATURATE -> BlendFactor.SRC_ALPHA_SATURATE;
            case GL_CONSTANT_COLOR -> BlendFactor.CONSTANT_COLOR;
            case GL_ONE_MINUS_CONSTANT_COLOR -> BlendFactor.ONE_MINUS_CONSTANT_COLOR;
            case GL_CONSTANT_ALPHA -> BlendFactor.CONSTANT_ALPHA;
            case GL_ONE_MINUS_CONSTANT_ALPHA -> BlendFactor.ONE_MINUS_CONSTANT_ALPHA;
            default -> BlendFactor.ONE;
        };
    }

    static int glBlendFactor(final BlendFactor factor) {
        return switch (factor) {
            case ZERO -> GL_ZERO;
            case ONE -> GL_ONE;
            case SRC_COLOR -> GL_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR -> GL_ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> GL_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA -> GL_ONE_MINUS_SRC_ALPHA;
            case DST_ALPHA -> GL_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA -> GL_ONE_MINUS_DST_ALPHA;
            case DST_COLOR -> GL_DST_COLOR;
            case ONE_MINUS_DST_COLOR -> GL_ONE_MINUS_DST_COLOR;
            case SRC_ALPHA_SATURATE -> GL_SRC_ALPHA_SATURATE;
            case CONSTANT_COLOR -> GL_CONSTANT_COLOR;
            case ONE_MINUS_CONSTANT_COLOR -> GL_ONE_MINUS_CONSTANT_COLOR;
            case CONSTANT_ALPHA -> GL_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_ALPHA -> GL_ONE_MINUS_CONSTANT_ALPHA;
            default -> GL_ONE;
        };
    }

    static int glBlendOp(final BlendOp op) {
        return switch (op) {
            case SUBTRACT -> GL_FUNC_SUBTRACT;
            case REVERSE_SUBTRACT -> GL_FUNC_REVERSE_SUBTRACT;
            case MIN -> GL_MIN;
            case MAX -> GL_MAX;
            default -> GL_FUNC_ADD;
        };
    }

    static BlendOp blendOp(final int equation) {
        return switch (equation) {
            case GL_FUNC_SUBTRACT -> BlendOp.SUBTRACT;
            case GL_FUNC_REVERSE_SUBTRACT -> BlendOp.REVERSE_SUBTRACT;
            case GL_MIN -> BlendOp.MIN;
            case GL_MAX -> BlendOp.MAX;
            default -> BlendOp.ADD;
        };
    }
}
