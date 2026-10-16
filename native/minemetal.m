// MineMetal native bridge: a thin C ABI over Apple Metal, called from Java via FFM (java.lang.foreign).
//
// Conventions
//  * Every Metal object handed to Java is retained (__bridge_retained) and must be freed with mm_release().
//  * The MMContext owns the device, the queue and the *current* command buffer + encoder state.
//    All mm_* calls that record commands must come from the render thread (same as the Java backend).
//  * Enum values that cross the boundary are resolved by name once (mm_enum), so Java never hardcodes MTL numbers.
//
// Build: see compileMetalNative in build.gradle (clang -dynamiclib -fobjc-arc ...).

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#import <CoreGraphics/CoreGraphics.h>
#import <MetalFX/MetalFX.h>
#include "fsr_shaders.h"
#include <pthread.h>
#include <stdatomic.h>
#include <math.h>
#include <string.h>
#include <stdint.h>

#define MM_EXPORT __attribute__((visibility("default")))

static int64_t mm_now_ns(void) {
    return (int64_t)clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
}

#define MM_STAGE_VERTEX   1
#define MM_STAGE_FRAGMENT 2

static void mm_copy_error(char *buf, int len, NSString *msg) {
    if (buf == NULL || len <= 0) return;
    const char *utf8 = msg != nil ? [msg UTF8String] : "unknown error";
    strncpy(buf, utf8 != NULL ? utf8 : "unknown error", (size_t)len - 1);
    buf[len - 1] = 0;
}

// ---------------------------------------------------------------------------------------------------------------------
// Enum lookup by name
// ---------------------------------------------------------------------------------------------------------------------

static NSDictionary<NSString *, NSNumber *> *mm_pixel_formats(void) {
    static NSDictionary *d;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        d = @{
            @"R8_UNORM": @(MTLPixelFormatR8Unorm), @"R8_SNORM": @(MTLPixelFormatR8Snorm),
            @"RG8_UNORM": @(MTLPixelFormatRG8Unorm), @"RG8_SNORM": @(MTLPixelFormatRG8Snorm),
            @"RGBA8_UNORM": @(MTLPixelFormatRGBA8Unorm), @"RGBA8_SNORM": @(MTLPixelFormatRGBA8Snorm),
            @"R16_UNORM": @(MTLPixelFormatR16Unorm), @"R16_SNORM": @(MTLPixelFormatR16Snorm),
            @"RG16_UNORM": @(MTLPixelFormatRG16Unorm), @"RG16_SNORM": @(MTLPixelFormatRG16Snorm),
            @"RGBA16_UNORM": @(MTLPixelFormatRGBA16Unorm), @"RGBA16_SNORM": @(MTLPixelFormatRGBA16Snorm),
            @"R8_UINT": @(MTLPixelFormatR8Uint), @"R8_SINT": @(MTLPixelFormatR8Sint),
            @"RG8_UINT": @(MTLPixelFormatRG8Uint), @"RG8_SINT": @(MTLPixelFormatRG8Sint),
            @"RGBA8_UINT": @(MTLPixelFormatRGBA8Uint), @"RGBA8_SINT": @(MTLPixelFormatRGBA8Sint),
            @"R16_UINT": @(MTLPixelFormatR16Uint), @"R16_SINT": @(MTLPixelFormatR16Sint),
            @"RG16_UINT": @(MTLPixelFormatRG16Uint), @"RG16_SINT": @(MTLPixelFormatRG16Sint),
            @"RGBA16_UINT": @(MTLPixelFormatRGBA16Uint), @"RGBA16_SINT": @(MTLPixelFormatRGBA16Sint),
            @"R32_UINT": @(MTLPixelFormatR32Uint), @"R32_SINT": @(MTLPixelFormatR32Sint),
            @"RG32_UINT": @(MTLPixelFormatRG32Uint), @"RG32_SINT": @(MTLPixelFormatRG32Sint),
            @"RGBA32_UINT": @(MTLPixelFormatRGBA32Uint), @"RGBA32_SINT": @(MTLPixelFormatRGBA32Sint),
            @"R16_FLOAT": @(MTLPixelFormatR16Float), @"RG16_FLOAT": @(MTLPixelFormatRG16Float),
            @"RGBA16_FLOAT": @(MTLPixelFormatRGBA16Float),
            @"R32_FLOAT": @(MTLPixelFormatR32Float), @"RG32_FLOAT": @(MTLPixelFormatRG32Float),
            @"RGBA32_FLOAT": @(MTLPixelFormatRGBA32Float),
            @"RGB10A2_UNORM": @(MTLPixelFormatRGB10A2Unorm), @"RGB10A2_UINT": @(MTLPixelFormatRGB10A2Uint),
            @"RG11B10_FLOAT": @(MTLPixelFormatRG11B10Float),
            @"D32_FLOAT": @(MTLPixelFormatDepth32Float),
            @"D32_FLOAT_S8_UINT": @(MTLPixelFormatDepth32Float_Stencil8),
            // Apple GPUs have no D24S8; the closest lossless substitute is D32F_S8.
            @"D24_UNORM_S8_UINT": @(MTLPixelFormatDepth32Float_Stencil8),
            @"D16_UNORM": @(MTLPixelFormatDepth16Unorm),
            @"S8_UINT": @(MTLPixelFormatStencil8),
            // 3-component texture formats do not exist in Metal (RGB8/RGB16/RGB32): intentionally absent.
            @"BGRA8_UNORM": @(MTLPixelFormatBGRA8Unorm),
        };
    });
    return d;
}

static NSDictionary<NSString *, NSNumber *> *mm_vertex_formats(void) {
    static NSDictionary *d;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        d = @{
            @"R8_UNORM": @(MTLVertexFormatUCharNormalized), @"R8_SNORM": @(MTLVertexFormatCharNormalized),
            @"RG8_UNORM": @(MTLVertexFormatUChar2Normalized), @"RG8_SNORM": @(MTLVertexFormatChar2Normalized),
            @"RGB8_UNORM": @(MTLVertexFormatUChar3Normalized), @"RGB8_SNORM": @(MTLVertexFormatChar3Normalized),
            @"RGBA8_UNORM": @(MTLVertexFormatUChar4Normalized), @"RGBA8_SNORM": @(MTLVertexFormatChar4Normalized),
            @"R16_UNORM": @(MTLVertexFormatUShortNormalized), @"R16_SNORM": @(MTLVertexFormatShortNormalized),
            @"RG16_UNORM": @(MTLVertexFormatUShort2Normalized), @"RG16_SNORM": @(MTLVertexFormatShort2Normalized),
            @"RGB16_UNORM": @(MTLVertexFormatUShort3Normalized), @"RGB16_SNORM": @(MTLVertexFormatShort3Normalized),
            @"RGBA16_UNORM": @(MTLVertexFormatUShort4Normalized), @"RGBA16_SNORM": @(MTLVertexFormatShort4Normalized),
            @"R8_UINT": @(MTLVertexFormatUChar), @"R8_SINT": @(MTLVertexFormatChar),
            @"RG8_UINT": @(MTLVertexFormatUChar2), @"RG8_SINT": @(MTLVertexFormatChar2),
            @"RGB8_UINT": @(MTLVertexFormatUChar3), @"RGB8_SINT": @(MTLVertexFormatChar3),
            @"RGBA8_UINT": @(MTLVertexFormatUChar4), @"RGBA8_SINT": @(MTLVertexFormatChar4),
            @"R16_UINT": @(MTLVertexFormatUShort), @"R16_SINT": @(MTLVertexFormatShort),
            @"RG16_UINT": @(MTLVertexFormatUShort2), @"RG16_SINT": @(MTLVertexFormatShort2),
            @"RGB16_UINT": @(MTLVertexFormatUShort3), @"RGB16_SINT": @(MTLVertexFormatShort3),
            @"RGBA16_UINT": @(MTLVertexFormatUShort4), @"RGBA16_SINT": @(MTLVertexFormatShort4),
            @"R32_UINT": @(MTLVertexFormatUInt), @"R32_SINT": @(MTLVertexFormatInt),
            @"RG32_UINT": @(MTLVertexFormatUInt2), @"RG32_SINT": @(MTLVertexFormatInt2),
            @"RGB32_UINT": @(MTLVertexFormatUInt3), @"RGB32_SINT": @(MTLVertexFormatInt3),
            @"RGBA32_UINT": @(MTLVertexFormatUInt4), @"RGBA32_SINT": @(MTLVertexFormatInt4),
            @"R16_FLOAT": @(MTLVertexFormatHalf), @"RG16_FLOAT": @(MTLVertexFormatHalf2),
            @"RGB16_FLOAT": @(MTLVertexFormatHalf3), @"RGBA16_FLOAT": @(MTLVertexFormatHalf4),
            @"R32_FLOAT": @(MTLVertexFormatFloat), @"RG32_FLOAT": @(MTLVertexFormatFloat2),
            @"RGB32_FLOAT": @(MTLVertexFormatFloat3), @"RGBA32_FLOAT": @(MTLVertexFormatFloat4),
            @"RGB10A2_UNORM": @(MTLVertexFormatUInt1010102Normalized),
            @"RG11B10_FLOAT": @(MTLVertexFormatFloatRG11B10),
        };
    });
    return d;
}

static NSDictionary<NSString *, NSNumber *> *mm_misc_enums(void) {
    static NSDictionary *d;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        d = @{
            // CompareOp
            @"compare.ALWAYS_PASS": @(MTLCompareFunctionAlways), @"compare.LESS_THAN": @(MTLCompareFunctionLess),
            @"compare.LESS_THAN_OR_EQUAL": @(MTLCompareFunctionLessEqual), @"compare.EQUAL": @(MTLCompareFunctionEqual),
            @"compare.NOT_EQUAL": @(MTLCompareFunctionNotEqual),
            @"compare.GREATER_THAN_OR_EQUAL": @(MTLCompareFunctionGreaterEqual),
            @"compare.GREATER_THAN": @(MTLCompareFunctionGreater), @"compare.NEVER_PASS": @(MTLCompareFunctionNever),
            // BlendFactor
            @"blend.CONSTANT_ALPHA": @(MTLBlendFactorBlendAlpha), @"blend.CONSTANT_COLOR": @(MTLBlendFactorBlendColor),
            @"blend.DST_ALPHA": @(MTLBlendFactorDestinationAlpha), @"blend.DST_COLOR": @(MTLBlendFactorDestinationColor),
            @"blend.ONE": @(MTLBlendFactorOne),
            @"blend.ONE_MINUS_CONSTANT_ALPHA": @(MTLBlendFactorOneMinusBlendAlpha),
            @"blend.ONE_MINUS_CONSTANT_COLOR": @(MTLBlendFactorOneMinusBlendColor),
            @"blend.ONE_MINUS_DST_ALPHA": @(MTLBlendFactorOneMinusDestinationAlpha),
            @"blend.ONE_MINUS_DST_COLOR": @(MTLBlendFactorOneMinusDestinationColor),
            @"blend.ONE_MINUS_SRC_ALPHA": @(MTLBlendFactorOneMinusSourceAlpha),
            @"blend.ONE_MINUS_SRC_COLOR": @(MTLBlendFactorOneMinusSourceColor),
            @"blend.SRC_ALPHA": @(MTLBlendFactorSourceAlpha),
            @"blend.SRC_ALPHA_SATURATE": @(MTLBlendFactorSourceAlphaSaturated),
            @"blend.SRC_COLOR": @(MTLBlendFactorSourceColor), @"blend.ZERO": @(MTLBlendFactorZero),
            // BlendOp
            @"blendop.ADD": @(MTLBlendOperationAdd), @"blendop.SUBTRACT": @(MTLBlendOperationSubtract),
            @"blendop.REVERSE_SUBTRACT": @(MTLBlendOperationReverseSubtract),
            @"blendop.MIN": @(MTLBlendOperationMin), @"blendop.MAX": @(MTLBlendOperationMax),
        };
    });
    return d;
}

/** Resolves an enum by kind ("pixel", "vertex", "misc") and name. Returns -1 when unsupported. */
MM_EXPORT int64_t mm_enum(const char *kind, const char *name) {
    @autoreleasepool {
        NSString *k = [NSString stringWithUTF8String:kind];
        NSString *n = [NSString stringWithUTF8String:name];
        NSDictionary *dict = nil;
        if ([k isEqualToString:@"pixel"]) dict = mm_pixel_formats();
        else if ([k isEqualToString:@"vertex"]) dict = mm_vertex_formats();
        else dict = mm_misc_enums();
        NSNumber *v = dict[n];
        return v != nil ? (int64_t)[v longLongValue] : -1;
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Pipeline object
// ---------------------------------------------------------------------------------------------------------------------

@interface MMPipeline : NSObject
@property(nonatomic, strong) MTLRenderPipelineDescriptor *descriptor;
@property(nonatomic, strong) id<MTLRenderPipelineState> withDepth;
@property(nonatomic, strong) id<MTLRenderPipelineState> withoutDepth;
@property(nonatomic, strong) id<MTLDepthStencilState> depthState;   // nil when the pipeline has no depth state
@property(nonatomic, strong) id<MTLDepthStencilState> depthStateFlipped; // compare op mirrored (standard vs reversed Z)
@property(nonatomic) MTLPrimitiveType primitiveType;
@property(nonatomic) BOOL skipDraws;                                  // topology Metal cannot express (TRIANGLE_FAN)
@property(nonatomic) MTLCullMode cullMode;
@property(nonatomic) MTLWinding winding;
@property(nonatomic) MTLTriangleFillMode fillMode;
@property(nonatomic) float depthBiasConstant;
@property(nonatomic) float depthBiasSlope;
@property(nonatomic, copy) NSString *name;
@end

@implementation MMPipeline
@end

// ---------------------------------------------------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------------------------------------------------

static int g_noCoalesce = 0; // -Dminemetal.noCoalesce=true: one encoder per pass (debugging)
#define MM_PROF_SLOTS 4096
#define MM_PROF_TAGS 48
#define MM_PROF_BLIT 0x80

@interface MMContext : NSObject {
@public
    _Atomic double gpuFrameTime;  // smoothed GPU execution time of a submitted command buffer (s)
    int depthFlip;   // 1 = mirror depth compare ops (shader packs expect standard depth, Minecraft uses reversed Z)
    pthread_mutex_t completionMutex;
    pthread_cond_t completionCond;
    uint64_t completedIndex;
    uint64_t submitIndex;
    // Redundant-state filter for the open render encoder ([stage][index]; stage 0 = vertex, 1 = fragment).
    void *boundBuffer[2][31];
    uint64_t boundOffset[2][31];
    void *boundTexture[2][64];
    void *boundSampler[2][16];
    int scissor[4];
    void *boundDepthState;
    int boundCull;
    int boundWinding;
    int boundFill;
    float boundBias[2];
@public
    int gpuDebug;
    // GPU profiler (-Dminemetal.gpuProfile): stage-boundary timestamps per encoder, summed per Java-set tag.
    // Encoder coalescing: mm_pass_end leaves the encoder open ("lingering"); a following mm_pass_begin on exactly the
    // same attachments without clears continues it instead of storing and reloading every attachment (TBDR).
    int renderLingering;
    int renderStorage;           // the open render encoder wrote storage images/buffers: never continue it
    int nextDontCare;            // color attachments the next mm_pass_begin may leave unloaded (fully overwritten)
    int rpCount;
    void *rpColor[8];
    void *rpDepth;
    int profEnabled;
    int profSplit;               // fallback: one command buffer per phase, timed with GPUStart/EndTime
    int profCbTag;               // tag the open command buffer belongs to (split mode)
    uint32_t profCbEncoders;     // encoders recorded into the open command buffer (split mode)
    int profTag;
    id<MTLCounterSampleBuffer> profBuffer;
    NSUInteger profSlots;        // capacity of profBuffer in encoder slots
    NSUInteger profCursor;       // next encoder slot (4 samples per slot)
    NSUInteger profCbStart;      // first slot of the open command buffer
    uint8_t profTagOf[MM_PROF_SLOTS];
    pthread_mutex_t profMutex;
    double profTicks[MM_PROF_TAGS];
    uint32_t profEncoders[MM_PROF_TAGS];
    double profCbSeconds;
    uint32_t profCbCount;
    MTLTimestamp profCpu0, profGpu0;
}
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLCommandBuffer> cmd;
@property(nonatomic, strong) id<MTLRenderCommandEncoder> render;
@property(nonatomic, strong) id<MTLBlitCommandEncoder> blit;
@property(nonatomic, strong) id<MTLComputeCommandEncoder> compute;
@property(nonatomic, strong) id<MTLBuffer> zeroBuffer;   // shared, all zero: source for texture clears
// render pass state
@property(nonatomic, strong) id<MTLBuffer> indexBuffer;
@property(nonatomic) MTLIndexType indexType;
@property(nonatomic) NSUInteger indexSize;
@property(nonatomic, strong) MMPipeline *pipeline;
@property(nonatomic) BOOL passHasDepth;
@property(nonatomic) NSUInteger passWidth;
@property(nonatomic) NSUInteger passHeight;
// built-in helpers
@property(nonatomic, strong) id<MTLLibrary> helperLibrary;
@property(nonatomic, strong) NSMutableDictionary<NSString *, id<MTLRenderPipelineState>> *helperPipelines;
@property(nonatomic, strong) id<MTLDepthStencilState> depthDisabled;
@property(nonatomic, strong) id<MTLDepthStencilState> depthAlwaysWrite;
@end

@implementation MMContext
- (instancetype)init {
    if ((self = [super init])) {
        pthread_mutex_init(&completionMutex, NULL);
        pthread_mutex_init(&profMutex, NULL);
        pthread_cond_init(&completionCond, NULL);
        completedIndex = 0;
        submitIndex = 1;
        _helperPipelines = [NSMutableDictionary dictionary];
    }
    return self;
}
- (void)dealloc {
    pthread_cond_destroy(&completionCond);
    pthread_mutex_destroy(&completionMutex);
    pthread_mutex_destroy(&profMutex);
}
@end

static inline MMContext *CTX(void *p) { return (__bridge MMContext *)p; }

static void mm_reset_bound_state(MMContext *ctx) {
    memset(ctx->boundBuffer, 0, sizeof(ctx->boundBuffer));
    memset(ctx->boundOffset, 0, sizeof(ctx->boundOffset));
    memset(ctx->boundTexture, 0, sizeof(ctx->boundTexture));
    memset(ctx->boundSampler, 0, sizeof(ctx->boundSampler));
    ctx->scissor[0] = ctx->scissor[1] = ctx->scissor[2] = ctx->scissor[3] = -1;
    ctx->boundDepthState = NULL;
    ctx->boundCull = -1;
    ctx->boundWinding = -1;
    ctx->boundFill = -1;
    ctx->boundBias[0] = NAN;
    ctx->boundBias[1] = NAN;
}

static NSString *const kHelperSource =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct MMClearOut { float4 pos [[position]]; };\n"
     "vertex MMClearOut mm_clear_vs(uint vid [[vertex_id]], constant float &depth [[buffer(0)]]) {\n"
     "  float2 p = float2((vid & 1) ? 1.0 : -1.0, (vid & 2) ? 1.0 : -1.0);\n"
     "  MMClearOut o; o.pos = float4(p, depth, 1.0); return o;\n"
     "}\n"
     "fragment float4 mm_clear_fs(constant float4 &color [[buffer(0)]]) { return color; }\n"
     "struct MMPresentOut { float4 pos [[position]]; };\n"
     "vertex MMPresentOut mm_present_vs(uint vid [[vertex_id]]) {\n"
     "  float2 p = float2(float((vid << 1) & 2), float(vid & 2));\n"
     "  MMPresentOut o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;\n"
     "}\n"
     // Game textures use the GL/Vulkan memory layout (row 0 = bottom of the image); the drawable is top-down.
     "fragment float4 mm_present_fs(MMPresentOut in [[stage_in]], texture2d<float> src [[texture(0)]],\n"
     "                              constant uint2 &size [[buffer(0)]]) {\n"
     "  uint2 p = uint2(in.pos.xy);\n"
     "  if (p.x >= size.x || p.y >= size.y) return float4(0.0, 0.0, 0.0, 1.0);\n"
     "  float4 c = src.read(uint2(p.x, size.y - 1 - p.y));\n"
     "  return float4(c.rgb, 1.0);\n"
     "}\n";

MM_EXPORT void *mm_create_context(char *err, int errLen) {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device == nil) {
            mm_copy_error(err, errLen, @"MTLCreateSystemDefaultDevice returned nil");
            return NULL;
        }
        if (![device supportsFamily:MTLGPUFamilyApple7] && ![device supportsFamily:MTLGPUFamilyMac2]) {
            mm_copy_error(err, errLen, [NSString stringWithFormat:@"GPU %@ does not support a required Metal family", device.name]);
            return NULL;
        }
        MMContext *ctx = [[MMContext alloc] init];
        ctx.device = device;
        ctx.queue = [device newCommandQueueWithMaxCommandBufferCount:16];
        ctx.queue.label = @"MineMetal queue";

        NSError *error = nil;
        MTLCompileOptions *options = [[MTLCompileOptions alloc] init];
        ctx.helperLibrary = [device newLibraryWithSource:kHelperSource options:options error:&error];
        if (ctx.helperLibrary == nil) {
            mm_copy_error(err, errLen, [NSString stringWithFormat:@"helper shaders: %@", error.localizedDescription]);
            return NULL;
        }

        MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor alloc] init];
        dd.depthCompareFunction = MTLCompareFunctionAlways;
        dd.depthWriteEnabled = NO;
        ctx.depthDisabled = [device newDepthStencilStateWithDescriptor:dd];
        dd.depthWriteEnabled = YES;
        ctx.depthAlwaysWrite = [device newDepthStencilStateWithDescriptor:dd];
        return (__bridge_retained void *)ctx;
    }
}

/** Enables per-encoder GPU error reporting (slower; for validation runs). */
MM_EXPORT void mm_set_gpu_debug(void *p, int enabled) { CTX(p)->gpuDebug = enabled; }

MM_EXPORT void mm_set_depth_flip(void *p, int flip) {
    MMContext *ctx = CTX(p);
    if (ctx->depthFlip != flip) {
        ctx->depthFlip = flip;
        ctx.pipeline = nil; // re-apply depth state / bias with the next pipeline bind
    }
}

/** Turns the GPU profiler on (returns 0 when the device cannot sample timestamps at encoder boundaries). */
MM_EXPORT int mm_prof_enable(void *p, int forceSplit) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        if (forceSplit) {
            // One command buffer per phase: exact per-phase GPU time (encoder timestamps overlap on Apple GPUs).
            ctx->profSplit = 1;
            ctx->profEnabled = 1;
            return 2;
        }
        if (ctx->profBuffer != nil || ctx->profSplit) { ctx->profEnabled = 1; return ctx->profSplit ? 2 : 1; }
        id<MTLCounterSet> timestamps = nil;
        for (id<MTLCounterSet> set in ctx.device.counterSets) {
            if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]) timestamps = set;
        }
        if (timestamps == nil || ![ctx.device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
            // Apple GPUs: no encoder-boundary timestamps. Time one command buffer per phase instead.
            ctx->profSplit = 1;
            ctx->profEnabled = 1;
            return 2;
        }
        NSError *err = nil;
        for (NSUInteger slots = MM_PROF_SLOTS; slots >= 256 && ctx->profBuffer == nil; slots /= 2) {
            MTLCounterSampleBufferDescriptor *d = [[MTLCounterSampleBufferDescriptor alloc] init];
            d.counterSet = timestamps;
            d.storageMode = MTLStorageModeShared;
            d.sampleCount = slots * 4;
            d.label = @"MineMetal profiler";
            err = nil;
            ctx->profBuffer = [ctx.device newCounterSampleBufferWithDescriptor:d error:&err];
            if (ctx->profBuffer != nil) ctx->profSlots = slots;
        }
        if (ctx->profBuffer == nil) {
            NSLog(@"[MineMetal] profiler: no counter sample buffer (%@); timing one command buffer per phase", err);
            ctx->profSplit = 1;
            ctx->profEnabled = 1;
            return 2;
        }
        [ctx.device sampleTimestamps:&ctx->profCpu0 gpuTimestamp:&ctx->profGpu0];
        ctx->profEnabled = 1;
        return 1;
    }
}

MM_EXPORT void mm_set_next_pass_dont_care(void *p, int mask) {
    CTX(p)->nextDontCare = mask;
}

MM_EXPORT void mm_set_coalesce(int enabled) {
    g_noCoalesce = enabled ? 0 : 1;
}

MM_EXPORT void mm_prof_set_tag(void *p, int tag) {
    CTX(p)->profTag = tag;
}

/**
 * Reads and resets the totals: out[t*2] = GPU ms of tag t's encoders, out[t*2+1] = encoder count (t < 16),
 * out[32] = summed command buffer GPU time (ms), out[33] = command buffers, out[34] = GPU ticks per ns used.
 */
MM_EXPORT void mm_prof_read(void *p, double *out) {
    MMContext *ctx = CTX(p);
    MTLTimestamp cpu = 0, gpu = 0;
    [ctx.device sampleTimestamps:&cpu gpuTimestamp:&gpu];
    double ticksPerNs = (cpu > ctx->profCpu0 && gpu > ctx->profGpu0) ? (double)(gpu - ctx->profGpu0) / (double)(cpu - ctx->profCpu0) : 1.0;
    if (ticksPerNs <= 0.0 || ctx->profSplit) ticksPerNs = 1.0;
    pthread_mutex_lock(&ctx->profMutex);
    for (int t = 0; t < MM_PROF_TAGS; t++) {
        out[t * 2] = ctx->profTicks[t] / ticksPerNs / 1.0e6;
        out[t * 2 + 1] = ctx->profEncoders[t];
        ctx->profTicks[t] = 0.0;
        ctx->profEncoders[t] = 0;
    }
    out[MM_PROF_TAGS * 2] = ctx->profCbSeconds * 1000.0;
    out[MM_PROF_TAGS * 2 + 1] = ctx->profCbCount;
    out[MM_PROF_TAGS * 2 + 2] = ticksPerNs;
    ctx->profCbSeconds = 0.0;
    ctx->profCbCount = 0;
    pthread_mutex_unlock(&ctx->profMutex);
}

MM_EXPORT void mm_destroy_context(void *p) {
    if (p == NULL) return;
    MMContext *ctx = (__bridge_transfer MMContext *)p;
    if (ctx.render != nil) { [ctx.render endEncoding]; ctx.render = nil; }
    if (ctx.blit != nil) { [ctx.blit endEncoding]; ctx.blit = nil; }
    if (ctx.compute != nil) { [ctx.compute endEncoding]; ctx.compute = nil; }
    if (ctx.cmd != nil) { [ctx.cmd commit]; [ctx.cmd waitUntilCompleted]; ctx.cmd = nil; }
    ctx = nil;
}

/** out: [0]=maxBufferLength [1]=recommendedMaxWorkingSetSize [2]=hasUnifiedMemory [3]=registryID [4]=lowPower */
MM_EXPORT void mm_context_info(void *p, char *name, int nameLen, int64_t *out) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        mm_copy_error(name, nameLen, ctx.device.name);
        out[0] = (int64_t)ctx.device.maxBufferLength;
        out[1] = (int64_t)ctx.device.recommendedMaxWorkingSetSize;
        out[2] = ctx.device.hasUnifiedMemory ? 1 : 0;
        out[3] = (int64_t)ctx.device.registryID;
        out[4] = ctx.device.lowPower ? 1 : 0;
    }
}

MM_EXPORT void mm_release(void *obj) {
    if (obj != NULL) CFRelease(obj);
}

MM_EXPORT void mm_set_label(void *obj, const char *label) {
    if (obj == NULL || label == NULL) return;
    @autoreleasepool {
        id o = (__bridge id)obj;
        if ([o respondsToSelector:@selector(setLabel:)]) {
            [o setLabel:[NSString stringWithUTF8String:label]];
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Command buffer / encoder management
// ---------------------------------------------------------------------------------------------------------------------

static id<MTLCommandBuffer> mm_cmd(MMContext *ctx) {
    if (ctx.cmd == nil) {
        if (ctx->gpuDebug) {
            MTLCommandBufferDescriptor *d = [[MTLCommandBufferDescriptor alloc] init];
            d.errorOptions = MTLCommandBufferErrorOptionEncoderExecutionStatus;
            ctx.cmd = [ctx.queue commandBufferWithDescriptor:d];
        } else {
            ctx.cmd = [ctx.queue commandBuffer];
        }
        ctx.cmd.label = [NSString stringWithFormat:@"MineMetal submit %llu", ctx->submitIndex];
        if (ctx->profCursor > ctx->profSlots * 3 / 4) ctx->profCursor = 0;
        ctx->profCbStart = ctx->profCursor;
        ctx->profCbTag = ctx->profTag;
        ctx->profCbEncoders = 0;
    }
    return ctx.cmd;
}

/** Split-mode profiling: adds one command buffer's GPU time to a tag. */
static void mm_prof_add_cb(MMContext *ctx, int tag, uint32_t encoders, double seconds) {
    if (tag < 0 || tag >= MM_PROF_TAGS) tag = 0;
    pthread_mutex_lock(&ctx->profMutex);
    ctx->profTicks[tag] += seconds * 1.0e9;
    ctx->profEncoders[tag] += encoders;
    if (seconds > 0.0) { ctx->profCbSeconds += seconds; ctx->profCbCount++; }
    pthread_mutex_unlock(&ctx->profMutex);
}

/**
 * Split mode: when a new encoder starts in a different phase than the open command buffer, commits that command
 * buffer on its own so its GPU time can be attributed to its phase (only between encoders, never inside one).
 */
static void mm_end_encoders(MMContext *ctx);

static void mm_prof_maybe_split(MMContext *ctx) {
    if (!ctx->profEnabled || !ctx->profSplit || ctx.cmd == nil) return;
    if (ctx->profCbTag == ctx->profTag) return;
    // A lingering render encoder belongs to an already ended pass: close it so the phases split cleanly.
    if (ctx.render != nil && ctx->renderLingering) mm_end_encoders(ctx);
    if (ctx.render != nil || ctx.blit != nil || ctx.compute != nil) return;
    id<MTLCommandBuffer> cmd = ctx.cmd;
    int tag = ctx->profCbTag;
    uint32_t encoders = ctx->profCbEncoders;
    MMContext *weakCtx = ctx;
    [cmd addCompletedHandler:^(id<MTLCommandBuffer> cb) {
        CFTimeInterval gs = cb.GPUStartTime, ge = cb.GPUEndTime;
        mm_prof_add_cb(weakCtx, tag, encoders, ge > gs && gs > 0.0 ? ge - gs : 0.0);
    }];
    [cmd commit];
    // Apple GPUs overlap consecutive command buffers, which makes their start/end times add up to more than the
    // frame. Waiting serialises them so each phase's time is exact (costs fps while profiling).
    [cmd waitUntilCompleted];
    ctx.cmd = nil;
}

/** Reserves a timestamp slot for the next encoder (or NSNotFound when profiling is off / the ring is full). */
static NSUInteger mm_prof_slot(MMContext *ctx, BOOL blit) {
    if (!ctx->profEnabled || ctx->profBuffer == nil) return NSNotFound;
    mm_cmd(ctx);
    if (ctx->profCursor >= ctx->profSlots) return NSNotFound;
    NSUInteger s = ctx->profCursor++;
    ctx->profTagOf[s] = (uint8_t)((ctx->profTag & 0x7F) | (blit ? MM_PROF_BLIT : 0));
    return s;
}

static void mm_prof_attach_render(MMContext *ctx, MTLRenderPassDescriptor *rp) {
    NSUInteger s = mm_prof_slot(ctx, NO);
    if (s == NSNotFound) return;
    MTLRenderPassSampleBufferAttachmentDescriptor *a = rp.sampleBufferAttachments[0];
    a.sampleBuffer = ctx->profBuffer;
    a.startOfVertexSampleIndex = s * 4;
    a.endOfVertexSampleIndex = s * 4 + 1;
    a.startOfFragmentSampleIndex = s * 4 + 2;
    a.endOfFragmentSampleIndex = s * 4 + 3;
}

// ---------------------------------------------------------------------------------------------------------------------
// Deferred clears (glClear from the GL emulation, e.g. Iris clearing its colortex buffers every frame).
// Instead of a separate pass that writes the whole image, the clear is folded into the load action of the next render
// pass that attaches the image (Apple GPUs clear in tile memory for free). Any other GPU work first flushes the
// remaining clears as plain clear passes, so sampling, copies, compute and readback always see cleared contents.
// ---------------------------------------------------------------------------------------------------------------------

#define MM_MAX_PENDING_CLEARS 32
static id<MTLTexture> g_pcTex[MM_MAX_PENDING_CLEARS];
static BOOL g_pcIsDepth[MM_MAX_PENDING_CLEARS];
static MTLClearColor g_pcColor[MM_MAX_PENDING_CLEARS];
static double g_pcDepth[MM_MAX_PENDING_CLEARS];
static int g_pcCount;
static int g_pcFlushing;
static int64_t g_pcFolded, g_pcFlushed;

static BOOL mm_same_image(id<MTLTexture> a, id<MTLTexture> b) {
    if (a == b) return YES;
    if (a == nil || b == nil) return NO;
    id<MTLTexture> ba = a.parentTexture ?: a, bb = b.parentTexture ?: b;
    if (ba != bb) return NO;
    NSUInteger la = a.parentTexture ? a.parentRelativeLevel : 0, lb = b.parentTexture ? b.parentRelativeLevel : 0;
    NSUInteger sa = a.parentTexture ? a.parentRelativeSlice : 0, sb = b.parentTexture ? b.parentRelativeSlice : 0;
    return la == lb && sa == sb;
}

static void mm_pc_remove(int i) {
    g_pcCount--;
    g_pcTex[i] = g_pcTex[g_pcCount];
    g_pcIsDepth[i] = g_pcIsDepth[g_pcCount];
    g_pcColor[i] = g_pcColor[g_pcCount];
    g_pcDepth[i] = g_pcDepth[g_pcCount];
    g_pcTex[g_pcCount] = nil;
}

/** Turns pending clears of images this pass attaches into its load actions. */
static void mm_pc_fold(MTLRenderPassDescriptor *rp) {
    for (int a = 0; a < 8 && g_pcCount > 0; a++) {
        MTLRenderPassColorAttachmentDescriptor *ca = rp.colorAttachments[a];
        if (ca.texture == nil || ca.level != 0 || ca.slice != 0) continue;
        for (int i = 0; i < g_pcCount; i++) {
            if (g_pcIsDepth[i] || !mm_same_image(ca.texture, g_pcTex[i])) continue;
            if (ca.loadAction == MTLLoadActionLoad) {
                ca.loadAction = MTLLoadActionClear;
                ca.clearColor = g_pcColor[i];
            }
            mm_pc_remove(i);
            g_pcFolded++;
            break;
        }
    }
    MTLRenderPassDepthAttachmentDescriptor *da = rp.depthAttachment;
    if (da.texture != nil && g_pcCount > 0 && da.level == 0 && da.slice == 0) {
        for (int i = 0; i < g_pcCount; i++) {
            if (!g_pcIsDepth[i] || !mm_same_image(da.texture, g_pcTex[i])) continue;
            if (da.loadAction == MTLLoadActionLoad) {
                da.loadAction = MTLLoadActionClear;
                da.clearDepth = g_pcDepth[i];
            }
            mm_pc_remove(i);
            g_pcFolded++;
            break;
        }
    }
}

static id<MTLRenderCommandEncoder> mm_render_encoder(MMContext *ctx, MTLRenderPassDescriptor *rp);

/** Encodes all remaining pending clears as clear passes. Callers must not have an encoder open. */
static void mm_pc_flush(MMContext *ctx) {
    if (g_pcCount == 0 || g_pcFlushing) return;
    g_pcFlushing = 1;
    for (int i = 0; i < g_pcCount; i++) {
        id<MTLTexture> t = g_pcTex[i];
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        if (g_pcIsDepth[i]) {
            rp.depthAttachment.texture = t;
            rp.depthAttachment.loadAction = MTLLoadActionClear;
            rp.depthAttachment.clearDepth = g_pcDepth[i];
            rp.depthAttachment.storeAction = MTLStoreActionStore;
            if (t.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) {
                rp.stencilAttachment.texture = t;
                rp.stencilAttachment.loadAction = MTLLoadActionLoad;
                rp.stencilAttachment.storeAction = MTLStoreActionStore;
            }
        } else {
            rp.colorAttachments[0].texture = t;
            rp.colorAttachments[0].loadAction = MTLLoadActionClear;
            rp.colorAttachments[0].clearColor = g_pcColor[i];
            rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        }
        id<MTLRenderCommandEncoder> enc = mm_render_encoder(ctx, rp);
        enc.label = @"MineMetal deferred clear";
        [enc endEncoding];
        g_pcTex[i] = nil;
        g_pcFlushed++;
    }
    g_pcCount = 0;
    g_pcFlushing = 0;
}

static void mm_pc_add(id<MTLTexture> t, BOOL depth, MTLClearColor color, double depthValue) {
    for (int i = 0; i < g_pcCount; i++) {
        if (g_pcIsDepth[i] == depth && mm_same_image(g_pcTex[i], t)) {
            g_pcColor[i] = color;
            g_pcDepth[i] = depthValue;
            return;
        }
    }
    g_pcTex[g_pcCount] = t;
    g_pcIsDepth[g_pcCount] = depth;
    g_pcColor[g_pcCount] = color;
    g_pcDepth[g_pcCount] = depthValue;
    g_pcCount++;
}

/**
 * glClear without scissor/stencil: records the clears instead of encoding a pass. Always succeeds (returns 1).
 * colors: count * 4 floats; NULL views are skipped; depthView may be NULL.
 */
MM_EXPORT int mm_clear_deferred(void *p, void **views, int count, const float *colors, void *depthView, double depth) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        int needed = (depthView != NULL ? 1 : 0);
        for (int i = 0; i < count; i++) if (views[i] != NULL) needed++;
        if (g_pcCount + needed > MM_MAX_PENDING_CLEARS) {
            mm_end_encoders(ctx);
            mm_pc_flush(ctx);
        }
        for (int i = 0; i < count; i++) {
            if (views[i] == NULL) continue;
            mm_pc_add((__bridge id<MTLTexture>)views[i], NO,
                      MTLClearColorMake(colors[i * 4], colors[i * 4 + 1], colors[i * 4 + 2], colors[i * 4 + 3]), 0.0);
        }
        if (depthView != NULL) mm_pc_add((__bridge id<MTLTexture>)depthView, YES, MTLClearColorMake(0, 0, 0, 0), depth);
        return 1;
    }
}

/** out: [0] folded into a pass, [1] flushed as separate clear passes (since start). */
MM_EXPORT void mm_clear_deferred_stats(int64_t *out) {
    out[0] = g_pcFolded;
    out[1] = g_pcFlushed;
}

static id<MTLRenderCommandEncoder> mm_render_encoder(MMContext *ctx, MTLRenderPassDescriptor *rp) {
    if (g_pcCount > 0 && !g_pcFlushing) {
        mm_pc_fold(rp);
        mm_pc_flush(ctx);
    }
    mm_prof_maybe_split(ctx);
    mm_prof_attach_render(ctx, rp);
    mm_cmd(ctx);
    ctx->profCbEncoders++;
    return [mm_cmd(ctx) renderCommandEncoderWithDescriptor:rp];
}

static id<MTLBlitCommandEncoder> mm_new_blit_encoder(MMContext *ctx) {
    mm_pc_flush(ctx);
    mm_prof_maybe_split(ctx);
    mm_cmd(ctx);
    ctx->profCbEncoders++;
    NSUInteger s = mm_prof_slot(ctx, YES);
    if (s == NSNotFound) return [mm_cmd(ctx) blitCommandEncoder];
    MTLBlitPassDescriptor *d = [MTLBlitPassDescriptor blitPassDescriptor];
    d.sampleBufferAttachments[0].sampleBuffer = ctx->profBuffer;
    d.sampleBufferAttachments[0].startOfEncoderSampleIndex = s * 4;
    d.sampleBufferAttachments[0].endOfEncoderSampleIndex = s * 4 + 1;
    return [mm_cmd(ctx) blitCommandEncoderWithDescriptor:d];
}

/** Completion-handler side: adds the encoder durations of one command buffer to the per-tag totals. */
static void mm_prof_collect(MMContext *ctx, id<MTLCounterSampleBuffer> buf, NSUInteger start, NSData *tags, double cbSeconds) {
    NSUInteger n = tags.length;
    NSData *data = n > 0 ? [buf resolveCounterRange:NSMakeRange(start * 4, n * 4)] : nil;
    const uint8_t *tg = tags.bytes;
    const MTLCounterResultTimestamp *ts = data != nil && data.length >= n * 4 * sizeof(MTLCounterResultTimestamp) ? data.bytes : NULL;
    pthread_mutex_lock(&ctx->profMutex);
    if (cbSeconds > 0.0) { ctx->profCbSeconds += cbSeconds; ctx->profCbCount++; }
    for (NSUInteger i = 0; ts != NULL && i < n; i++) {
        int tag = tg[i] & 0x7F;
        if (tag >= MM_PROF_TAGS) tag = 0;
        uint64_t lo = UINT64_MAX, hi = 0;
        int samples = (tg[i] & MM_PROF_BLIT) ? 2 : 4;
        for (int k = 0; k < samples; k++) {
            uint64_t v = ts[i * 4 + k].timestamp;
            if (v == MTLCounterErrorValue || v == 0) continue;
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        ctx->profEncoders[tag]++;
        if (hi > lo) ctx->profTicks[tag] += (double)(hi - lo);
    }
    pthread_mutex_unlock(&ctx->profMutex);
}

static void mm_end_encoders(MMContext *ctx) {
    ctx->renderLingering = 0;
    if (ctx.render != nil) { [ctx.render endEncoding]; ctx.render = nil; ctx.pipeline = nil; }
    if (ctx.blit != nil) { [ctx.blit endEncoding]; ctx.blit = nil; }
    if (ctx.compute != nil) { [ctx.compute endEncoding]; ctx.compute = nil; }
}

static id<MTLBlitCommandEncoder> mm_blit(MMContext *ctx) {
    if (ctx.render != nil) {
        // Copies are not allowed inside a render pass; the frontend guarantees this never happens (a lingering
        // encoder of an already ended pass is simply closed here).
        if (!ctx->renderLingering) NSLog(@"[MineMetal] blit requested while a render pass is open; ending the pass");
        [ctx.render endEncoding];
        ctx.render = nil;
        ctx.pipeline = nil;
        ctx->renderLingering = 0;
    }
    if (ctx.compute != nil) { [ctx.compute endEncoding]; ctx.compute = nil; }
    if (ctx.blit == nil) ctx.blit = mm_new_blit_encoder(ctx);
    return ctx.blit;
}

/** Ends encoding and commits the current command buffer. Returns the submit index that just got committed. */
MM_EXPORT uint64_t mm_submit(void *p) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        mm_end_encoders(ctx);
        mm_pc_flush(ctx);
        uint64_t index = ctx->submitIndex++;
        id<MTLCommandBuffer> cmd = mm_cmd(ctx);  // creates an empty one if nothing was recorded
        MMContext *weakCtx = ctx;  // the context outlives all command buffers (destroy waits)
        BOOL prof = ctx->profEnabled && ctx->profBuffer != nil;
        BOOL profSplit = ctx->profEnabled && ctx->profSplit;
        int profCbTag = ctx->profCbTag;
        uint32_t profCbEncoders = ctx->profCbEncoders;
        id<MTLCounterSampleBuffer> profBuf = ctx->profBuffer;
        NSUInteger profStart = ctx->profCbStart;
        NSData *profTags = prof && ctx->profCursor > profStart
            ? [NSData dataWithBytes:&ctx->profTagOf[profStart] length:ctx->profCursor - profStart] : [NSData data];
        [cmd addCompletedHandler:^(id<MTLCommandBuffer> cb) {
            if (cb.error != nil) {
                NSLog(@"[MineMetal] command buffer %@ failed: %@", cb.label, cb.error);
                id infos = cb.error.userInfo[MTLCommandBufferEncoderInfoErrorKey];
                if ([infos isKindOfClass:[NSArray class]]) {
                    for (id<MTLCommandBufferEncoderInfo> info in (NSArray *)infos) {
                        if (info.errorState == MTLCommandEncoderErrorStateFaulted) {
                            NSLog(@"[MineMetal]   faulted encoder: %@ (debug groups: %@)", info.label, info.debugSignposts);
                        }
                    }
                }
            }
            CFTimeInterval gs = cb.GPUStartTime, ge = cb.GPUEndTime;
            if (ge > gs && gs > 0.0 && ge - gs < 0.25) {
                double prevGpu = atomic_load(&weakCtx->gpuFrameTime);
                double cur = ge - gs;
                // React quickly to slower frames, slowly to faster ones (pacing must not under-estimate).
                atomic_store(&weakCtx->gpuFrameTime, cur > prevGpu ? prevGpu * 0.6 + cur * 0.4 : prevGpu * 0.95 + cur * 0.05);
            }
            if (profSplit) {
                mm_prof_add_cb(weakCtx, profCbTag, profCbEncoders, ge > gs && gs > 0.0 ? ge - gs : 0.0);
            }
            if (prof) {
                mm_prof_collect(weakCtx, profBuf, profStart, profTags, ge > gs && gs > 0.0 ? ge - gs : 0.0);
            }
            pthread_mutex_lock(&weakCtx->completionMutex);
            if (index > weakCtx->completedIndex) weakCtx->completedIndex = index;
            pthread_cond_broadcast(&weakCtx->completionCond);
            pthread_mutex_unlock(&weakCtx->completionMutex);
        }];
        [cmd commit];
        if (profSplit) [cmd waitUntilCompleted]; // see mm_prof_maybe_split
        ctx.cmd = nil;
        return index;
    }
}

MM_EXPORT uint64_t mm_current_submit_index(void *p) { return CTX(p)->submitIndex; }

/** Waits until submit `index` has completed on the GPU. timeoutNs < 0 waits forever. Returns 1 when completed. */
MM_EXPORT int mm_wait(void *p, uint64_t index, int64_t timeoutNs) {
    MMContext *ctx = CTX(p);
    pthread_mutex_lock(&ctx->completionMutex);
    int done = ctx->completedIndex >= index;
    if (!done && timeoutNs != 0) {
        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        int64_t ns = timeoutNs < 0 ? (int64_t)3600 * 1000000000LL : timeoutNs;
        deadline.tv_sec += ns / 1000000000LL;
        deadline.tv_nsec += ns % 1000000000LL;
        if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec += 1; deadline.tv_nsec -= 1000000000L; }
        while (ctx->completedIndex < index) {
            if (pthread_cond_timedwait(&ctx->completionCond, &ctx->completionMutex, &deadline) != 0) break;
        }
        done = ctx->completedIndex >= index;
    }
    pthread_mutex_unlock(&ctx->completionMutex);
    return done;
}

MM_EXPORT uint64_t mm_completed_index(void *p) {
    MMContext *ctx = CTX(p);
    pthread_mutex_lock(&ctx->completionMutex);
    uint64_t v = ctx->completedIndex;
    pthread_mutex_unlock(&ctx->completionMutex);
    return v;
}

MM_EXPORT void mm_wait_idle(void *p) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        uint64_t last = mm_submit(p);
        mm_wait(p, last, -1);
        (void)ctx;
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Buffers
// ---------------------------------------------------------------------------------------------------------------------

MM_EXPORT void *mm_buffer_create(void *p, uint64_t size) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        // Apple Silicon has unified memory: shared storage is both CPU-mappable and full speed for the GPU.
        // Round up to 16 bytes: MSL pads structs to their 16-byte alignment, so a 40-byte std140 block is a 48-byte
        // MSL struct. Without the padding the validation layer (correctly) reports reads past the end of the buffer.
        NSUInteger padded = (NSUInteger)(((size > 0 ? size : 4) + 15) & ~(uint64_t)15);
        id<MTLBuffer> buf = [ctx.device newBufferWithLength:padded
                                                    options:MTLResourceStorageModeShared | MTLResourceHazardTrackingModeTracked];
        return buf != nil ? (__bridge_retained void *)buf : NULL;
    }
}

MM_EXPORT void *mm_buffer_contents(void *buf) {
    return [(__bridge id<MTLBuffer>)buf contents];
}

// ---------------------------------------------------------------------------------------------------------------------
// Textures and samplers
// ---------------------------------------------------------------------------------------------------------------------

#define MM_TEX_USAGE_COPY_DST 1
#define MM_TEX_USAGE_COPY_SRC 2
#define MM_TEX_USAGE_TEXTURE_BINDING 4
#define MM_TEX_USAGE_RENDER_ATTACHMENT 8
#define MM_TEX_USAGE_CUBEMAP_COMPATIBLE 16
// MineMetal-private usage bits (above renderpearl's): GL image load/store and 1D/3D textures for the GL emulation.
#define MM_TEX_USAGE_SHADER_WRITE (1 << 20)
#define MM_TEX_USAGE_TYPE_1D (1 << 21)
#define MM_TEX_USAGE_TYPE_3D (1 << 22)

MM_EXPORT void *mm_texture_create(void *p, int64_t pixelFormat, int width, int height, int layers, int mips, int usage) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        MTLTextureDescriptor *d = [[MTLTextureDescriptor alloc] init];
        d.pixelFormat = (MTLPixelFormat)pixelFormat;
        d.width = (NSUInteger)MAX(width, 1);
        d.height = (NSUInteger)MAX(height, 1);
        d.mipmapLevelCount = (NSUInteger)MAX(mips, 1);
        if (usage & MM_TEX_USAGE_TYPE_3D) {
            d.textureType = MTLTextureType3D;
            d.depth = (NSUInteger)MAX(layers, 1);
        } else if (usage & MM_TEX_USAGE_TYPE_1D) {
            d.textureType = MTLTextureType1D;
            d.height = 1;
        } else if ((usage & MM_TEX_USAGE_CUBEMAP_COMPATIBLE) && layers == 6) {
            d.textureType = MTLTextureTypeCube;
        } else if (layers > 1) {
            d.textureType = MTLTextureType2DArray;
            d.arrayLength = (NSUInteger)layers;
        } else {
            d.textureType = MTLTextureType2D;
        }
        d.storageMode = MTLStorageModePrivate;
        MTLTextureUsage u = MTLTextureUsageShaderRead;
        if ((usage & MM_TEX_USAGE_RENDER_ATTACHMENT) && !(usage & MM_TEX_USAGE_TYPE_1D)) u |= MTLTextureUsageRenderTarget;
        if (usage & MM_TEX_USAGE_SHADER_WRITE) {
            u |= MTLTextureUsageShaderWrite;
            // GLSL imageAtomic* on r32ui/r32i images: native texture atomics (MSL 3.1) need this usage.
            if (@available(macOS 14.0, *)) {
                MTLPixelFormat f = (MTLPixelFormat)pixelFormat;
                if (f == MTLPixelFormatR32Uint || f == MTLPixelFormatR32Sint || f == MTLPixelFormatRG32Uint) u |= MTLTextureUsageShaderAtomic;
            }
        }
        d.usage = u;
        id<MTLTexture> tex = [ctx.device newTextureWithDescriptor:d];
        return tex != nil ? (__bridge_retained void *)tex : NULL;
    }
}

MM_EXPORT void *mm_texture_view(void *texPtr, int baseMip, int mips) {
    @autoreleasepool {
        id<MTLTexture> tex = (__bridge id<MTLTexture>)texPtr;
        NSUInteger slices = tex.textureType == MTLTextureTypeCube ? 6 : tex.arrayLength;
        id<MTLTexture> view = [tex newTextureViewWithPixelFormat:tex.pixelFormat
                                                     textureType:tex.textureType
                                                          levels:NSMakeRange((NSUInteger)baseMip, (NSUInteger)MAX(mips, 1))
                                                          slices:NSMakeRange(0, MAX(slices, (NSUInteger)1))];
        return view != nil ? (__bridge_retained void *)view : NULL;
    }
}

/** Generates the full mip chain of a texture (blit encoder). */
MM_EXPORT void mm_generate_mipmaps(void *p, void *texPtr) {
    @autoreleasepool {
        id<MTLTexture> tex = (__bridge id<MTLTexture>)texPtr;
        if (tex.mipmapLevelCount < 2) return;
        [mm_blit(CTX(p)) generateMipmapsForTexture:tex];
    }
}

/**
 * Sampler with extended GL-style state: addressing 0=repeat 1=clamp-to-edge 2=mirrored-repeat 3=clamp-to-border(black),
 * mipMode 0=none 1=nearest 2=linear, compareFunc = MTLCompareFunction or -1 for none (sampler2DShadow).
 */
MM_EXPORT void *mm_sampler_create_ex(void *p, int addrU, int addrV, int minLinear, int magLinear, int mipMode, int maxAnisotropy,
                                     float lodMin, float lodMax, int64_t compareFunc) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        MTLSamplerAddressMode modes[4] = {MTLSamplerAddressModeRepeat, MTLSamplerAddressModeClampToEdge,
                                          MTLSamplerAddressModeMirrorRepeat, MTLSamplerAddressModeClampToBorderColor};
        MTLSamplerDescriptor *d = [[MTLSamplerDescriptor alloc] init];
        d.sAddressMode = modes[addrU & 3];
        d.tAddressMode = modes[addrV & 3];
        d.rAddressMode = MTLSamplerAddressModeClampToEdge;
        d.borderColor = MTLSamplerBorderColorOpaqueBlack;
        d.minFilter = minLinear ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        d.magFilter = magLinear ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        d.mipFilter = mipMode == 2 ? MTLSamplerMipFilterLinear : (mipMode == 1 ? MTLSamplerMipFilterNearest : MTLSamplerMipFilterNotMipmapped);
        d.maxAnisotropy = (NSUInteger)MAX(1, MIN(maxAnisotropy, 16));
        d.lodMinClamp = lodMin;
        d.lodMaxClamp = lodMax;
        if (compareFunc >= 0) d.compareFunction = (MTLCompareFunction)compareFunc;
        id<MTLSamplerState> s = [ctx.device newSamplerStateWithDescriptor:d];
        return s != nil ? (__bridge_retained void *)s : NULL;
    }
}

MM_EXPORT void *mm_sampler_create(void *p, int repeatU, int repeatV, int minLinear, int magLinear, int mipLinear,
                                  int maxAnisotropy, float lodMax) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        MTLSamplerDescriptor *d = [[MTLSamplerDescriptor alloc] init];
        d.sAddressMode = repeatU ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
        d.tAddressMode = repeatV ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
        d.rAddressMode = MTLSamplerAddressModeClampToEdge;
        d.minFilter = minLinear ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        d.magFilter = magLinear ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        d.mipFilter = mipLinear ? MTLSamplerMipFilterLinear : MTLSamplerMipFilterNearest;
        d.maxAnisotropy = (NSUInteger)MAX(1, MIN(maxAnisotropy, 16));
        d.lodMinClamp = 0.0f;
        d.lodMaxClamp = lodMax;
        id<MTLSamplerState> s = [ctx.device newSamplerStateWithDescriptor:d];
        return s != nil ? (__bridge_retained void *)s : NULL;
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Copies
// ---------------------------------------------------------------------------------------------------------------------

MM_EXPORT void mm_copy_buffer(void *p, void *src, uint64_t srcOffset, void *dst, uint64_t dstOffset, uint64_t size) {
    if (size == 0) return;
    @autoreleasepool {
        [mm_blit(CTX(p)) copyFromBuffer:(__bridge id<MTLBuffer>)src sourceOffset:srcOffset
                               toBuffer:(__bridge id<MTLBuffer>)dst destinationOffset:dstOffset size:size];
    }
}

MM_EXPORT void mm_copy_buffer_to_texture(void *p, void *buf, uint64_t offset, uint64_t bytesPerRow, uint64_t bytesPerImage,
                                         void *tex, int slice, int mip, int x, int y, int w, int h) {
    if (w <= 0 || h <= 0) return;
    @autoreleasepool {
        id<MTLTexture> t = (__bridge id<MTLTexture>)tex;
        // 3D textures have one slice; the "layer" is the depth plane.
        BOOL is3D = t.textureType == MTLTextureType3D;
        [mm_blit(CTX(p)) copyFromBuffer:(__bridge id<MTLBuffer>)buf sourceOffset:offset sourceBytesPerRow:bytesPerRow
                      sourceBytesPerImage:bytesPerImage sourceSize:MTLSizeMake((NSUInteger)w, (NSUInteger)h, 1)
                                toTexture:t destinationSlice:is3D ? 0 : (NSUInteger)slice
                         destinationLevel:(NSUInteger)mip destinationOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, is3D ? (NSUInteger)slice : 0)];
    }
}

MM_EXPORT void mm_copy_texture_to_buffer(void *p, void *tex, int slice, int mip, int x, int y, int w, int h,
                                         void *buf, uint64_t offset, uint64_t bytesPerRow, uint64_t bytesPerImage) {
    if (w <= 0 || h <= 0) return;
    @autoreleasepool {
        [mm_blit(CTX(p)) copyFromTexture:(__bridge id<MTLTexture>)tex sourceSlice:(NSUInteger)slice sourceLevel:(NSUInteger)mip
                            sourceOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0) sourceSize:MTLSizeMake((NSUInteger)w, (NSUInteger)h, 1)
                                toBuffer:(__bridge id<MTLBuffer>)buf destinationOffset:offset
                  destinationBytesPerRow:bytesPerRow destinationBytesPerImage:bytesPerImage];
    }
}

MM_EXPORT void mm_copy_texture_to_texture(void *p, void *src, void *dst, int mip, int srcX, int srcY, int dstX, int dstY, int w, int h) {
    if (w <= 0 || h <= 0) return;
    @autoreleasepool {
        [mm_blit(CTX(p)) copyFromTexture:(__bridge id<MTLTexture>)src sourceSlice:0 sourceLevel:(NSUInteger)mip
                            sourceOrigin:MTLOriginMake((NSUInteger)srcX, (NSUInteger)srcY, 0) sourceSize:MTLSizeMake((NSUInteger)w, (NSUInteger)h, 1)
                               toTexture:(__bridge id<MTLTexture>)dst destinationSlice:0 destinationLevel:(NSUInteger)mip
                       destinationOrigin:MTLOriginMake((NSUInteger)dstX, (NSUInteger)dstY, 0)];
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Clears
// ---------------------------------------------------------------------------------------------------------------------

static BOOL mm_is_depth_format(MTLPixelFormat f) {
    return f == MTLPixelFormatDepth32Float || f == MTLPixelFormatDepth16Unorm || f == MTLPixelFormatDepth32Float_Stencil8 ||
           f == MTLPixelFormatStencil8;
}

static NSUInteger mm_slices(id<MTLTexture> t) {
    return t.textureType == MTLTextureTypeCube ? 6 : MAX(t.arrayLength, (NSUInteger)1);
}

/** Clears every mip level and slice of a color texture. */
MM_EXPORT void mm_clear_color_texture(void *p, void *texPtr, float r, float g, float b, float a) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        mm_end_encoders(ctx);
        id<MTLTexture> tex = (__bridge id<MTLTexture>)texPtr;
        for (NSUInteger level = 0; level < tex.mipmapLevelCount; level++) {
            for (NSUInteger slice = 0; slice < mm_slices(tex); slice++) {
                MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
                rp.colorAttachments[0].texture = tex;
                rp.colorAttachments[0].level = level;
                rp.colorAttachments[0].slice = slice;
                rp.colorAttachments[0].loadAction = MTLLoadActionClear;
                rp.colorAttachments[0].storeAction = MTLStoreActionStore;
                rp.colorAttachments[0].clearColor = MTLClearColorMake(r, g, b, a);
                id<MTLRenderCommandEncoder> enc = mm_render_encoder(ctx, rp);
                enc.label = @"MineMetal clear color";
                [enc endEncoding];
            }
        }
    }
}

MM_EXPORT void mm_clear_depth_texture(void *p, void *texPtr, double depth) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        mm_end_encoders(ctx);
        id<MTLTexture> tex = (__bridge id<MTLTexture>)texPtr;
        BOOL hasStencil = tex.pixelFormat == MTLPixelFormatDepth32Float_Stencil8;
        for (NSUInteger level = 0; level < tex.mipmapLevelCount; level++) {
            for (NSUInteger slice = 0; slice < mm_slices(tex); slice++) {
                MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
                rp.depthAttachment.texture = tex;
                rp.depthAttachment.level = level;
                rp.depthAttachment.slice = slice;
                rp.depthAttachment.loadAction = MTLLoadActionClear;
                rp.depthAttachment.storeAction = MTLStoreActionStore;
                rp.depthAttachment.clearDepth = depth;
                if (hasStencil) {
                    rp.stencilAttachment.texture = tex;
                    rp.stencilAttachment.level = level;
                    rp.stencilAttachment.slice = slice;
                    rp.stencilAttachment.loadAction = MTLLoadActionClear;
                    rp.stencilAttachment.storeAction = MTLStoreActionStore;
                }
                id<MTLRenderCommandEncoder> enc = mm_render_encoder(ctx, rp);
                enc.label = @"MineMetal clear depth";
                [enc endEncoding];
            }
        }
    }
}

static id<MTLRenderPipelineState> mm_helper_pipeline(MMContext *ctx, NSString *vs, NSString *fs, MTLPixelFormat color,
                                                     MTLPixelFormat depth, BOOL writeColor) {
    NSString *key = [NSString stringWithFormat:@"%@/%@/%lu/%lu/%d", vs, fs, (unsigned long)color, (unsigned long)depth, writeColor];
    id<MTLRenderPipelineState> pso = ctx.helperPipelines[key];
    if (pso != nil) return pso;
    MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor alloc] init];
    d.label = key;
    d.vertexFunction = [ctx.helperLibrary newFunctionWithName:vs];
    d.fragmentFunction = [ctx.helperLibrary newFunctionWithName:fs];
    d.colorAttachments[0].pixelFormat = color;
    d.colorAttachments[0].writeMask = writeColor ? MTLColorWriteMaskAll : MTLColorWriteMaskNone;
    d.depthAttachmentPixelFormat = depth;
    NSError *error = nil;
    pso = [ctx.device newRenderPipelineStateWithDescriptor:d error:&error];
    if (pso == nil) {
        NSLog(@"[MineMetal] helper pipeline %@ failed: %@", key, error);
        return nil;
    }
    ctx.helperPipelines[key] = pso;
    return pso;
}

/** Clears a rectangle (framebuffer coordinates, top-left origin) of a color + depth target pair at one mip level. */
MM_EXPORT void mm_clear_region(void *p, void *colorTex, void *depthTex, int mip, float r, float g, float b, float a, double depth,
                               int x, int y, int w, int h) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        mm_end_encoders(ctx);
        id<MTLTexture> color = (__bridge id<MTLTexture>)colorTex;
        id<MTLTexture> depthT = (__bridge id<MTLTexture>)depthTex;
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = color;
        rp.colorAttachments[0].level = (NSUInteger)mip;
        rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        if (depthT != nil) {
            rp.depthAttachment.texture = depthT;
            rp.depthAttachment.level = (NSUInteger)mip;
            rp.depthAttachment.loadAction = MTLLoadActionLoad;
            rp.depthAttachment.storeAction = MTLStoreActionStore;
        }
        NSUInteger tw = MAX(color.width >> mip, (NSUInteger)1), th = MAX(color.height >> mip, (NSUInteger)1);
        int x0 = MAX(x, 0), y0 = MAX(y, 0);
        int x1 = MIN(x + w, (int)tw), y1 = MIN(y + h, (int)th);
        if (x1 <= x0 || y1 <= y0) return;
        id<MTLRenderPipelineState> pso = mm_helper_pipeline(ctx, @"mm_clear_vs", @"mm_clear_fs", color.pixelFormat,
                                                            depthT != nil ? depthT.pixelFormat : MTLPixelFormatInvalid, YES);
        if (pso == nil) return;
        id<MTLRenderCommandEncoder> enc = mm_render_encoder(ctx, rp);
        enc.label = @"MineMetal clear region";
        [enc setRenderPipelineState:pso];
        [enc setDepthStencilState:depthT != nil ? ctx.depthAlwaysWrite : ctx.depthDisabled];
        [enc setScissorRect:(MTLScissorRect){(NSUInteger)x0, (NSUInteger)y0, (NSUInteger)(x1 - x0), (NSUInteger)(y1 - y0)}];
        float d = (float)depth;
        float c[4] = {r, g, b, a};
        [enc setVertexBytes:&d length:sizeof(d) atIndex:0];
        [enc setFragmentBytes:c length:sizeof(c) atIndex:0];
        [enc drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
        [enc endEncoding];
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Render passes
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Begins a render pass.
 * colorViews: array of `count` texture pointers (NULL = unused slot).
 * clearMask: bit i set = clear color attachment i with clearColors[i*4..i*4+3]; bit 31 = clear depth.
 */
MM_EXPORT int mm_pass_begin(void *p, void **colorViews, int count, int clearMask, const float *clearColors,
                            void *depthView, double clearDepth, int ax, int ay, int aw, int ah, const char *label) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        int dontCare = ctx->nextDontCare;
        ctx->nextDontCare = 0;
        if (ctx.render != nil && ctx->renderLingering && !ctx->renderStorage && clearMask == 0 && count == ctx->rpCount && count <= 8
            && depthView == ctx->rpDepth && !g_noCoalesce && g_pcCount == 0) {
            BOOL same = YES;
            for (int i = 0; i < count; i++) {
                if (colorViews[i] != ctx->rpColor[i]) { same = NO; break; }
            }
            if (same) {
                // Same attachments, nothing to clear: keep drawing into the open encoder.
                ctx->renderLingering = 0;
                id<MTLRenderCommandEncoder> enc = ctx.render;
                ctx.pipeline = nil;
                ctx.indexBuffer = nil;
                // The redundant-state caches still describe this encoder exactly, so they stay valid.
                NSUInteger width = ctx.passWidth, height = ctx.passHeight;
                [enc setViewport:(MTLViewport){0.0, 0.0, (double)width, (double)height, 0.0, 1.0}];
                int x0 = MAX(ax, 0), y0 = MAX(ay, 0);
                int x1 = MIN(ax + aw, (int)width), y1 = MIN(ay + ah, (int)height);
                if (x1 < x0) x1 = x0;
                if (y1 < y0) y1 = y0;
                [enc setScissorRect:(MTLScissorRect){(NSUInteger)x0, (NSUInteger)y0, (NSUInteger)(x1 - x0), (NSUInteger)(y1 - y0)}];
                ctx->scissor[0] = x0; ctx->scissor[1] = y0; ctx->scissor[2] = x1 - x0; ctx->scissor[3] = y1 - y0;
                return 1;
            }
        }
        mm_end_encoders(ctx);
        ctx->renderStorage = 0;
        ctx->rpCount = count <= 8 ? count : -1;
        for (int i = 0; i < count && i < 8; i++) ctx->rpColor[i] = colorViews[i];
        ctx->rpDepth = depthView;
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        NSUInteger width = 0, height = 0;
        for (int i = 0; i < count; i++) {
            if (colorViews[i] == NULL) continue;
            id<MTLTexture> t = (__bridge id<MTLTexture>)colorViews[i];
            rp.colorAttachments[i].texture = t;
            rp.colorAttachments[i].storeAction = MTLStoreActionStore;
            if (clearMask & (1 << i)) {
                rp.colorAttachments[i].loadAction = MTLLoadActionClear;
                rp.colorAttachments[i].clearColor =
                    MTLClearColorMake(clearColors[i * 4], clearColors[i * 4 + 1], clearColors[i * 4 + 2], clearColors[i * 4 + 3]);
            } else {
                rp.colorAttachments[i].loadAction = (dontCare & (1 << i)) ? MTLLoadActionDontCare : MTLLoadActionLoad;
            }
            width = t.width;
            height = t.height;
        }
        if (depthView != NULL) {
            id<MTLTexture> t = (__bridge id<MTLTexture>)depthView;
            rp.depthAttachment.texture = t;
            rp.depthAttachment.storeAction = MTLStoreActionStore;
            if (clearMask & (1 << 31)) {
                rp.depthAttachment.loadAction = MTLLoadActionClear;
                rp.depthAttachment.clearDepth = clearDepth;
            } else {
                rp.depthAttachment.loadAction = MTLLoadActionLoad;
            }
            if (t.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) {
                rp.stencilAttachment.texture = t;
                rp.stencilAttachment.loadAction = MTLLoadActionLoad;
                rp.stencilAttachment.storeAction = MTLStoreActionStore;
            }
            if (width == 0) { width = t.width; height = t.height; }
        }
        if (width == 0 || height == 0) return 0;
        id<MTLRenderCommandEncoder> enc = mm_render_encoder(ctx, rp);
        if (enc == nil) return 0;
        if (label != NULL) enc.label = [NSString stringWithUTF8String:label];
        ctx.render = enc;
        ctx.pipeline = nil;
        mm_reset_bound_state(ctx);
        ctx.passHasDepth = depthView != NULL;
        ctx.passWidth = width;
        ctx.passHeight = height;
        [enc setViewport:(MTLViewport){0.0, 0.0, (double)width, (double)height, 0.0, 1.0}];
        int x0 = MAX(ax, 0), y0 = MAX(ay, 0);
        int x1 = MIN(ax + aw, (int)width), y1 = MIN(ay + ah, (int)height);
        if (x1 < x0) x1 = x0;
        if (y1 < y0) y1 = y0;
        [enc setScissorRect:(MTLScissorRect){(NSUInteger)x0, (NSUInteger)y0, (NSUInteger)(x1 - x0), (NSUInteger)(y1 - y0)}];
        ctx->scissor[0] = x0; ctx->scissor[1] = y0; ctx->scissor[2] = x1 - x0; ctx->scissor[3] = y1 - y0;
        return 1;
    }
}

/** Viewport in framebuffer pixels (top-left origin = memory row 0, same convention as scissors). */
MM_EXPORT void mm_pass_set_viewport(void *p, double x, double y, double w, double h, double znear, double zfar) {
    MMContext *ctx = CTX(p);
    if (ctx.render == nil) return;
    [ctx.render setViewport:(MTLViewport){x, y, w, h, znear, zfar}];
}

MM_EXPORT void mm_pass_end(void *p) {
    MMContext *ctx = CTX(p);
    if (ctx.render != nil) {
        if (g_noCoalesce) {
            [ctx.render endEncoding];
            ctx.render = nil;
        } else {
            ctx->renderLingering = 1; // closed by the next encoder / submit, or continued by an identical pass
        }
    }
    ctx.pipeline = nil;
    ctx.indexBuffer = nil;
}

MM_EXPORT void mm_pass_push_debug(void *p, const char *label) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        if (ctx.render != nil && label != NULL) [ctx.render pushDebugGroup:[NSString stringWithUTF8String:label]];
    }
}

MM_EXPORT void mm_pass_pop_debug(void *p) {
    MMContext *ctx = CTX(p);
    if (ctx.render != nil) [ctx.render popDebugGroup];
}

MM_EXPORT void mm_pass_set_scissor(void *p, int x, int y, int w, int h) {
    MMContext *ctx = CTX(p);
    if (ctx.render == nil) return;
    int x0 = MAX(x, 0), y0 = MAX(y, 0);
    int x1 = MIN(x + w, (int)ctx.passWidth), y1 = MIN(y + h, (int)ctx.passHeight);
    if (x1 < x0) x1 = x0;
    if (y1 < y0) y1 = y0;
    if (ctx->scissor[0] == x0 && ctx->scissor[1] == y0 && ctx->scissor[2] == x1 - x0 && ctx->scissor[3] == y1 - y0) return;
    ctx->scissor[0] = x0; ctx->scissor[1] = y0; ctx->scissor[2] = x1 - x0; ctx->scissor[3] = y1 - y0;
    [ctx.render setScissorRect:(MTLScissorRect){(NSUInteger)x0, (NSUInteger)y0, (NSUInteger)(x1 - x0), (NSUInteger)(y1 - y0)}];
}

static id<MTLRenderPipelineState> mm_pipeline_variant(MMContext *ctx, MMPipeline *pl, BOOL depth) {
    id<MTLRenderPipelineState> pso = depth ? pl.withDepth : pl.withoutDepth;
    if (pso != nil) return pso;
    MTLRenderPipelineDescriptor *d = [pl.descriptor copy];
    d.depthAttachmentPixelFormat = depth ? MTLPixelFormatDepth32Float : MTLPixelFormatInvalid;
    NSError *error = nil;
    int64_t t0 = mm_now_ns();
    pso = [ctx.device newRenderPipelineStateWithDescriptor:d error:&error];
    NSLog(@"[MineMetal] compiled %s-depth variant of %@ on the render thread in %.1f ms", depth ? "with" : "without", pl.name,
          (mm_now_ns() - t0) / 1e6);
    if (pso == nil) {
        NSLog(@"[MineMetal] pipeline %@ (depth=%d) failed: %@", pl.name, depth, error);
        return nil;
    }
    if (depth) pl.withDepth = pso; else pl.withoutDepth = pso;
    return pso;
}

/** Returns 0 when the pipeline cannot be used in this pass (draws will be skipped). */
MM_EXPORT int mm_pass_set_pipeline(void *p, void *pipelinePtr) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        if (ctx.render == nil) return 0;
        MMPipeline *pl = (__bridge MMPipeline *)pipelinePtr;
        if (ctx.pipeline == pl) return 1;
        id<MTLRenderPipelineState> pso = mm_pipeline_variant(ctx, pl, ctx.passHasDepth);
        if (pso == nil) { ctx.pipeline = nil; return 0; }
        id<MTLRenderCommandEncoder> enc = ctx.render;
        [enc setRenderPipelineState:pso];
        id<MTLDepthStencilState> dss = (ctx.passHasDepth && pl.depthState != nil)
            ? (ctx->depthFlip && pl.depthStateFlipped != nil ? pl.depthStateFlipped : pl.depthState) : ctx.depthDisabled;
        void *dssRaw = (__bridge void *)dss;
        // Metal's default depth state is "always, no write", identical to depthDisabled: skip the call in that case.
        if (ctx->boundDepthState != dssRaw && !(ctx->boundDepthState == NULL && dss == ctx.depthDisabled)) {
            [enc setDepthStencilState:dss];
        }
        ctx->boundDepthState = dssRaw;
        if (ctx->boundCull != (int)pl.cullMode && !(ctx->boundCull == -1 && pl.cullMode == MTLCullModeNone)) {
            [enc setCullMode:pl.cullMode];
        }
        ctx->boundCull = (int)pl.cullMode;
        if (ctx->boundWinding != (int)pl.winding && !(ctx->boundWinding == -1 && pl.winding == MTLWindingClockwise)) {
            [enc setFrontFacingWinding:pl.winding];
        }
        ctx->boundWinding = (int)pl.winding;
        if (ctx->boundFill != (int)pl.fillMode && !(ctx->boundFill == -1 && pl.fillMode == MTLTriangleFillModeFill)) {
            [enc setTriangleFillMode:pl.fillMode];
        }
        ctx->boundFill = (int)pl.fillMode;
        float sign = ctx->depthFlip ? -1.0f : 1.0f; // bias always pulls toward the camera
        float biasC = pl.depthBiasConstant * sign, biasS = pl.depthBiasSlope * sign;
        BOOL biasDefault = isnan(ctx->boundBias[0]) && biasC == 0.0f && biasS == 0.0f;
        if (!biasDefault && (ctx->boundBias[0] != biasC || ctx->boundBias[1] != biasS)) {
            [enc setDepthBias:biasC slopeScale:biasS clamp:0.0f];
        }
        ctx->boundBias[0] = biasC;
        ctx->boundBias[1] = biasS;
        ctx.pipeline = pl;
        return 1;
    }
}

static inline void mm_bind_buffer(MMContext *ctx, int stage, id<MTLBuffer> b, void *raw, uint64_t offset, int index) {
    if (index < 0 || index >= 31) return;
    if (ctx->boundBuffer[stage][index] == raw) {
        if (ctx->boundOffset[stage][index] == offset) return;
        if (stage == 0) [ctx.render setVertexBufferOffset:offset atIndex:(NSUInteger)index];
        else [ctx.render setFragmentBufferOffset:offset atIndex:(NSUInteger)index];
    } else {
        if (stage == 0) [ctx.render setVertexBuffer:b offset:offset atIndex:(NSUInteger)index];
        else [ctx.render setFragmentBuffer:b offset:offset atIndex:(NSUInteger)index];
        ctx->boundBuffer[stage][index] = raw;
    }
    ctx->boundOffset[stage][index] = offset;
}

MM_EXPORT void mm_pass_set_vertex_buffer(void *p, void *buf, uint64_t offset, int index) {
    MMContext *ctx = CTX(p);
    if (ctx.render == nil) return;
    mm_bind_buffer(ctx, 0, (__bridge id<MTLBuffer>)buf, buf, offset, index);
}

MM_EXPORT void mm_pass_set_buffer(void *p, int stages, void *buf, uint64_t offset, int index) {
    MMContext *ctx = CTX(p);
    if (ctx.render == nil) return;
    id<MTLBuffer> b = (__bridge id<MTLBuffer>)buf;
    if (stages & MM_STAGE_VERTEX) mm_bind_buffer(ctx, 0, b, buf, offset, index);
    if (stages & MM_STAGE_FRAGMENT) mm_bind_buffer(ctx, 1, b, buf, offset, index);
}

MM_EXPORT void mm_pass_set_bytes(void *p, int stages, const void *data, uint64_t length, int index) {
    MMContext *ctx = CTX(p);
    if (ctx.render == nil || length == 0) return;
    if (index >= 0 && index < 31) { ctx->boundBuffer[0][index] = NULL; ctx->boundBuffer[1][index] = NULL; }
    if (stages & MM_STAGE_VERTEX) [ctx.render setVertexBytes:data length:length atIndex:(NSUInteger)index];
    if (stages & MM_STAGE_FRAGMENT) [ctx.render setFragmentBytes:data length:length atIndex:(NSUInteger)index];
}

static inline void mm_bind_texture(MMContext *ctx, int stage, id<MTLTexture> t, void *raw, int index) {
    if (index < 0 || index >= 64 || ctx->boundTexture[stage][index] == raw) return;
    ctx->boundTexture[stage][index] = raw;
    if (stage == 0) [ctx.render setVertexTexture:t atIndex:(NSUInteger)index];
    else [ctx.render setFragmentTexture:t atIndex:(NSUInteger)index];
}

MM_EXPORT void mm_pass_set_texture(void *p, int stages, void *tex, int index) {
    MMContext *ctx = CTX(p);
    if (ctx.render == nil) return;
    id<MTLTexture> t = (__bridge id<MTLTexture>)tex;
    if (stages & MM_STAGE_VERTEX) mm_bind_texture(ctx, 0, t, tex, index);
    if (stages & MM_STAGE_FRAGMENT) mm_bind_texture(ctx, 1, t, tex, index);
}

MM_EXPORT void mm_pass_set_sampler(void *p, int stages, void *sampler, int index) {
    MMContext *ctx = CTX(p);
    if (ctx.render == nil || index < 0 || index >= 16) return;
    id<MTLSamplerState> s = (__bridge id<MTLSamplerState>)sampler;
    if ((stages & MM_STAGE_VERTEX) && ctx->boundSampler[0][index] != sampler) {
        ctx->boundSampler[0][index] = sampler;
        [ctx.render setVertexSamplerState:s atIndex:(NSUInteger)index];
    }
    if ((stages & MM_STAGE_FRAGMENT) && ctx->boundSampler[1][index] != sampler) {
        ctx->boundSampler[1][index] = sampler;
        [ctx.render setFragmentSamplerState:s atIndex:(NSUInteger)index];
    }
}

/** Binds a typed view over a buffer range (GLSL samplerBuffer / MSL texture_buffer). */
MM_EXPORT void mm_pass_set_texel_buffer(void *p, int stages, void *buf, uint64_t offset, uint64_t length, int64_t pixelFormat,
                                        int bytesPerTexel, int index) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        if (ctx.render == nil || bytesPerTexel <= 0) return;
        id<MTLBuffer> b = (__bridge id<MTLBuffer>)buf;
        MTLPixelFormat fmt = (MTLPixelFormat)pixelFormat;
        NSUInteger align = [ctx.device minimumTextureBufferAlignmentForPixelFormat:fmt];
        if (align > 1 && (offset % align) != 0) {
            static int warned = 0;
            if (warned++ < 5) NSLog(@"[MineMetal] texel buffer offset %llu not aligned to %lu", offset, (unsigned long)align);
            return;
        }
        NSUInteger texels = (NSUInteger)(length / (uint64_t)bytesPerTexel);
        if (texels == 0) return;
        MTLTextureDescriptor *d = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:fmt
                                                                                          width:texels
                                                                                resourceOptions:b.resourceOptions
                                                                                          usage:MTLTextureUsageShaderRead];
        NSUInteger bytesPerRow = texels * (NSUInteger)bytesPerTexel;
        NSUInteger rowAlign = [ctx.device minimumLinearTextureAlignmentForPixelFormat:fmt];
        if (rowAlign > 1) bytesPerRow = (bytesPerRow + rowAlign - 1) / rowAlign * rowAlign;
        if (offset + bytesPerRow > b.length) {
            // Not enough room for the padded row: shrink the view to what fits.
            NSUInteger room = (NSUInteger)(b.length - offset);
            bytesPerRow = room / rowAlign * rowAlign;
            texels = MIN(texels, bytesPerRow / (NSUInteger)bytesPerTexel);
            if (texels == 0) return;
            d.width = texels;
        }
        id<MTLTexture> t = [b newTextureWithDescriptor:d offset:offset bytesPerRow:bytesPerRow];
        if (t == nil) return;
        void *raw = (__bridge void *)t;  // the encoder retains it for the rest of the pass
        if (stages & MM_STAGE_VERTEX) mm_bind_texture(ctx, 0, t, raw, index);
        if (stages & MM_STAGE_FRAGMENT) mm_bind_texture(ctx, 1, t, raw, index);
    }
}

MM_EXPORT void mm_pass_set_index_buffer(void *p, void *buf, int is32) {
    MMContext *ctx = CTX(p);
    ctx.indexBuffer = (__bridge id<MTLBuffer>)buf;
    ctx.indexType = is32 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16;
    ctx.indexSize = is32 ? 4 : 2;
}

static inline BOOL mm_can_draw(MMContext *ctx) {
    return ctx.render != nil && ctx.pipeline != nil && !ctx.pipeline.skipDraws;
}

MM_EXPORT void mm_pass_draw(void *p, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
    MMContext *ctx = CTX(p);
    if (!mm_can_draw(ctx) || vertexCount <= 0 || instanceCount <= 0) return;
    [ctx.render drawPrimitives:ctx.pipeline.primitiveType vertexStart:(NSUInteger)firstVertex vertexCount:(NSUInteger)vertexCount
                 instanceCount:(NSUInteger)instanceCount baseInstance:(NSUInteger)firstInstance];
}

MM_EXPORT void mm_pass_draw_indexed(void *p, int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
    MMContext *ctx = CTX(p);
    if (!mm_can_draw(ctx) || ctx.indexBuffer == nil || indexCount <= 0 || instanceCount <= 0) return;
    [ctx.render drawIndexedPrimitives:ctx.pipeline.primitiveType indexCount:(NSUInteger)indexCount indexType:ctx.indexType
                          indexBuffer:ctx.indexBuffer indexBufferOffset:(NSUInteger)firstIndex * ctx.indexSize
                        instanceCount:(NSUInteger)instanceCount baseVertex:baseVertex baseInstance:(NSUInteger)firstInstance];
}

/** params: drawCount pairs of (firstVertex, vertexCount) — VkMultiDrawInfoEXT layout. */
MM_EXPORT void mm_pass_multi_draw(void *p, const int32_t *params, int instanceCount, int firstInstance, int drawCount) {
    MMContext *ctx = CTX(p);
    if (!mm_can_draw(ctx) || instanceCount <= 0) return;
    for (int i = 0; i < drawCount; i++) {
        int32_t first = params[i * 2], count = params[i * 2 + 1];
        if (count <= 0) continue;
        [ctx.render drawPrimitives:ctx.pipeline.primitiveType vertexStart:(NSUInteger)first vertexCount:(NSUInteger)count
                     instanceCount:(NSUInteger)instanceCount baseInstance:(NSUInteger)firstInstance];
    }
}

/** params: drawCount triples of (firstIndex, indexCount, vertexOffset) — VkMultiDrawIndexedInfoEXT layout. */
MM_EXPORT void mm_pass_multi_draw_indexed(void *p, const int32_t *params, int instanceCount, int firstInstance, int drawCount) {
    MMContext *ctx = CTX(p);
    if (!mm_can_draw(ctx) || ctx.indexBuffer == nil || instanceCount <= 0) return;
    for (int i = 0; i < drawCount; i++) {
        int32_t firstIndex = params[i * 3], indexCount = params[i * 3 + 1], vertexOffset = params[i * 3 + 2];
        if (indexCount <= 0) continue;
        [ctx.render drawIndexedPrimitives:ctx.pipeline.primitiveType indexCount:(NSUInteger)indexCount indexType:ctx.indexType
                              indexBuffer:ctx.indexBuffer indexBufferOffset:(NSUInteger)firstIndex * ctx.indexSize
                            instanceCount:(NSUInteger)instanceCount baseVertex:vertexOffset baseInstance:(NSUInteger)firstInstance];
    }
}

/** VkDrawIndirectCommand == MTLDrawPrimitivesIndirectArguments (16 bytes). */
MM_EXPORT void mm_pass_draw_indirect(void *p, void *buf, uint64_t offset, int drawCount) {
    MMContext *ctx = CTX(p);
    if (!mm_can_draw(ctx)) return;
    id<MTLBuffer> b = (__bridge id<MTLBuffer>)buf;
    for (int i = 0; i < drawCount; i++) {
        [ctx.render drawPrimitives:ctx.pipeline.primitiveType indirectBuffer:b indirectBufferOffset:offset + (uint64_t)i * 16];
    }
}

/** VkDrawIndexedIndirectCommand == MTLDrawIndexedPrimitivesIndirectArguments (20 bytes). */
MM_EXPORT void mm_pass_draw_indexed_indirect(void *p, void *buf, uint64_t offset, int drawCount) {
    MMContext *ctx = CTX(p);
    if (!mm_can_draw(ctx) || ctx.indexBuffer == nil) return;
    id<MTLBuffer> b = (__bridge id<MTLBuffer>)buf;
    for (int i = 0; i < drawCount; i++) {
        [ctx.render drawIndexedPrimitives:ctx.pipeline.primitiveType indexType:ctx.indexType indexBuffer:ctx.indexBuffer
                        indexBufferOffset:0 indirectBuffer:b indirectBufferOffset:offset + (uint64_t)i * 20];
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Pipelines
// ---------------------------------------------------------------------------------------------------------------------

static int g_fastMath = 1;

/** 1 = fast math (default), 0 = IEEE-safe math. Takes effect for pipelines compiled afterwards. */
MM_EXPORT void mm_set_fast_math(int enabled) { g_fastMath = enabled; }

static id<MTLFunction> mm_compile_function(MMContext *ctx, const char *src, const char *entry, NSString *what, char *err, int errLen) {
    NSError *error = nil;
    MTLCompileOptions *options = [[MTLCompileOptions alloc] init];
    options.languageVersion = MTLLanguageVersion2_4;
    // Shaders with image load/store or compute kernels are generated for MSL 3.1 (native texture atomics).
    if (strstr(src, "// mm:msl31") != NULL) {
        if (@available(macOS 14.0, *)) options.languageVersion = MTLLanguageVersion3_1;
    }
    // Multi-pass rendering (e.g. translucency over terrain) relies on bit-identical positions between pipelines.
    options.preserveInvariance = YES;
    if (@available(macOS 15.0, *)) {
        options.mathMode = g_fastMath ? MTLMathModeFast : MTLMathModeSafe;
    } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        options.fastMathEnabled = g_fastMath ? YES : NO;
#pragma clang diagnostic pop
    }
    id<MTLLibrary> lib = [ctx.device newLibraryWithSource:[NSString stringWithUTF8String:src] options:options error:&error];
    if (lib == nil) {
        mm_copy_error(err, errLen, [NSString stringWithFormat:@"%@ shader: %@", what, error.localizedDescription]);
        return nil;
    }
    id<MTLFunction> fn = [lib newFunctionWithName:[NSString stringWithUTF8String:entry]];
    if (fn == nil) {
        mm_copy_error(err, errLen, [NSString stringWithFormat:@"%@ shader: entry point %s not found", what, entry]);
    }
    return fn;
}

/**
 * Creates a pipeline.
 *  layouts:  layoutCount * {metalBufferIndex, stride, stepRate}
 *  attribs:  attribCount * {location, metalBufferIndex, offset, mtlVertexFormat}
 *  targets:  targetCount * {mtlPixelFormat (0 = unused), writeMask(RGBA bits 1,2,4,8), blendEnabled,
 *                           srcRGB, dstRGB, opRGB, srcA, dstA, opA}
 *  raster:   {topology(PrimitiveTopology ordinal), cull, wireframe, frontFaceCCW, hasDepthState, depthCompare, depthWrite}
 *  bias:     {constant, slope}
 */
MM_EXPORT void *mm_pipeline_create(void *p, const char *name, const char *vsSrc, const char *vsEntry, const char *fsSrc,
                                   const char *fsEntry, const int32_t *layouts, int layoutCount, const int32_t *attribs,
                                   int attribCount, const int64_t *targets, int targetCount, const int32_t *raster,
                                   const float *bias, int eagerDepthVariant, char *err, int errLen) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        NSString *nm = [NSString stringWithUTF8String:name];
        MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor alloc] init];
        d.label = nm;
        d.vertexFunction = mm_compile_function(ctx, vsSrc, vsEntry, @"vertex", err, errLen);
        if (d.vertexFunction == nil) return NULL;
        d.fragmentFunction = mm_compile_function(ctx, fsSrc, fsEntry, @"fragment", err, errLen);
        if (d.fragmentFunction == nil) return NULL;

        if (attribCount > 0) {
            MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
            for (int i = 0; i < layoutCount; i++) {
                int idx = layouts[i * 3], stride = layouts[i * 3 + 1], step = layouts[i * 3 + 2];
                vd.layouts[idx].stride = (NSUInteger)stride;
                if (step < 0) {
                    // one value for every vertex (default values for attributes a GL program reads but the format lacks)
                    vd.layouts[idx].stepFunction = MTLVertexStepFunctionConstant;
                    vd.layouts[idx].stepRate = 0;
                } else if (step > 0) {
                    vd.layouts[idx].stepFunction = MTLVertexStepFunctionPerInstance;
                    vd.layouts[idx].stepRate = (NSUInteger)step;
                } else {
                    vd.layouts[idx].stepFunction = MTLVertexStepFunctionPerVertex;
                    vd.layouts[idx].stepRate = 1;
                }
            }
            for (int i = 0; i < attribCount; i++) {
                int loc = attribs[i * 4];
                vd.attributes[loc].bufferIndex = (NSUInteger)attribs[i * 4 + 1];
                vd.attributes[loc].offset = (NSUInteger)attribs[i * 4 + 2];
                vd.attributes[loc].format = (MTLVertexFormat)attribs[i * 4 + 3];
            }
            d.vertexDescriptor = vd;
        }

        for (int i = 0; i < targetCount; i++) {
            const int64_t *t = &targets[i * 9];
            MTLRenderPipelineColorAttachmentDescriptor *ca = d.colorAttachments[i];
            ca.pixelFormat = (MTLPixelFormat)t[0];
            if (t[0] == 0) continue;
            MTLColorWriteMask mask = MTLColorWriteMaskNone;
            if (t[1] & 1) mask |= MTLColorWriteMaskRed;
            if (t[1] & 2) mask |= MTLColorWriteMaskGreen;
            if (t[1] & 4) mask |= MTLColorWriteMaskBlue;
            if (t[1] & 8) mask |= MTLColorWriteMaskAlpha;
            ca.writeMask = mask;
            ca.blendingEnabled = t[2] != 0;
            if (t[2]) {
                ca.sourceRGBBlendFactor = (MTLBlendFactor)t[3];
                ca.destinationRGBBlendFactor = (MTLBlendFactor)t[4];
                ca.rgbBlendOperation = (MTLBlendOperation)t[5];
                ca.sourceAlphaBlendFactor = (MTLBlendFactor)t[6];
                ca.destinationAlphaBlendFactor = (MTLBlendFactor)t[7];
                ca.alphaBlendOperation = (MTLBlendOperation)t[8];
            }
        }

        MMPipeline *pl = [[MMPipeline alloc] init];
        pl.name = nm;
        pl.descriptor = d;
        int topology = raster[0];
        // PrimitiveTopology: LINES, DEBUG_LINES, DEBUG_LINE_STRIP, POINTS, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS
        switch (topology) {
            case 1: pl.primitiveType = MTLPrimitiveTypeLine; d.inputPrimitiveTopology = MTLPrimitiveTopologyClassLine; break;
            case 2: pl.primitiveType = MTLPrimitiveTypeLineStrip; d.inputPrimitiveTopology = MTLPrimitiveTopologyClassLine; break;
            case 3: pl.primitiveType = MTLPrimitiveTypePoint; d.inputPrimitiveTopology = MTLPrimitiveTopologyClassPoint; break;
            case 5: pl.primitiveType = MTLPrimitiveTypeTriangleStrip; d.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle; break;
            case 6: pl.primitiveType = MTLPrimitiveTypeTriangle; pl.skipDraws = YES; break;  // no fans in Metal
            default: pl.primitiveType = MTLPrimitiveTypeTriangle; d.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle; break;
        }
        pl.cullMode = raster[1] ? MTLCullModeBack : MTLCullModeNone;
        pl.fillMode = raster[2] ? MTLTriangleFillModeLines : MTLTriangleFillModeFill;
        pl.winding = raster[3] ? MTLWindingCounterClockwise : MTLWindingClockwise;
        if (raster[4]) {
            MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor alloc] init];
            dd.depthCompareFunction = (MTLCompareFunction)raster[5];
            dd.depthWriteEnabled = raster[6] != 0;
            pl.depthState = [ctx.device newDepthStencilStateWithDescriptor:dd];
            MTLCompareFunction f = (MTLCompareFunction)raster[5];
            switch (f) {
                case MTLCompareFunctionLess: f = MTLCompareFunctionGreater; break;
                case MTLCompareFunctionLessEqual: f = MTLCompareFunctionGreaterEqual; break;
                case MTLCompareFunctionGreater: f = MTLCompareFunctionLess; break;
                case MTLCompareFunctionGreaterEqual: f = MTLCompareFunctionLessEqual; break;
                default: break;
            }
            dd.depthCompareFunction = f;
            pl.depthStateFlipped = [ctx.device newDepthStencilStateWithDescriptor:dd];
        }
        pl.depthBiasConstant = bias[0];
        pl.depthBiasSlope = bias[1];

        // Compile one variant now so shader/pipeline errors surface at load time, not mid-frame.
        NSError *error = nil;
        MTLRenderPipelineDescriptor *eager = [d copy];
        eager.depthAttachmentPixelFormat = eagerDepthVariant ? MTLPixelFormatDepth32Float : MTLPixelFormatInvalid;
        id<MTLRenderPipelineState> pso = [ctx.device newRenderPipelineStateWithDescriptor:eager error:&error];
        if (pso == nil) {
            mm_copy_error(err, errLen, [NSString stringWithFormat:@"pipeline state: %@", error.localizedDescription]);
            return NULL;
        }
        if (eagerDepthVariant) pl.withDepth = pso; else pl.withoutDepth = pso;
        return (__bridge_retained void *)pl;
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Surface (CAMetalLayer)
// ---------------------------------------------------------------------------------------------------------------------

@interface MMSurface : NSObject {
@public
    _Atomic int drawablesInFlight;   // acquired and not yet presented (or dropped) by the compositor
    int skippedFrames;
    // statistics (read by mm_surface_stats)
    int64_t statPresented;
    int64_t statSkipped;
    int64_t statForcedAcquires;
    int64_t statAcquireNs;
    int64_t statAcquireMaxNs;
    // frame pacing (MM_PRESENT_PACED)
    _Atomic double lastPresentedTime;   // CACurrentMediaTime() timebase, from presented handlers
    _Atomic double refreshInterval;     // measured display refresh interval (s)
    double lastBlitTime;
    double avgFrameTime;
    double lastTargetVsync;
    int64_t statPacedSkips;
    BOOL smooth;                        // paced + even frame spacing (presentDrawable:afterMinimumDuration:)
}
@property(nonatomic, strong) CAMetalLayer *layer;
@property(nonatomic, strong) id<CAMetalDrawable> drawable;
@property(nonatomic) BOOL vsync;
@property(nonatomic) int mode;              // MM_PRESENT_*
@end

@implementation MMSurface
@end

#define MM_PRESENT_FIFO 0      // VSync on: block for a drawable every frame
#define MM_PRESENT_PACED 1     // VSync off: render unlimited, present the newest frame once per refresh
#define MM_PRESENT_IMMEDIATE 2 // VSync off, unpaced: present whenever a drawable is free (old behaviour)
#define MM_PRESENT_SMOOTH 3    // VSync off, paced, frames spaced evenly by the measured frame time (ProMotion-friendly)
#define MM_GPU_MARGIN 0.0025   // time from commit to "on glass" budget (GPU work + compositor)

MM_EXPORT void *mm_surface_create(void *p, void *layerPtr) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        CAMetalLayer *layer = (__bridge CAMetalLayer *)layerPtr;
        if (layer == nil) return NULL;
        layer.device = ctx.device;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        layer.framebufferOnly = YES;
        layer.maximumDrawableCount = 3;
        // Tag output as sRGB so macOS colour-manages it like the OpenGL path (avoids oversaturation on P3 panels).
        CGColorSpaceRef srgb = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
        layer.colorspace = srgb;
        CGColorSpaceRelease(srgb);
        MMSurface *s = [[MMSurface alloc] init];
        s.layer = layer;
        s.vsync = YES;
        s.mode = MM_PRESENT_FIFO;
        atomic_store(&s->refreshInterval, 1.0 / 60.0);
        atomic_store(&s->lastPresentedTime, 0.0);
        s->avgFrameTime = 1.0 / 60.0;
        return (__bridge_retained void *)s;
    }
}

MM_EXPORT void mm_surface_configure(void *surfacePtr, int width, int height, int mode) {
    MMSurface *s = (__bridge MMSurface *)surfacePtr;
    s.layer.drawableSize = CGSizeMake(MAX(width, 1), MAX(height, 1));
    s->smooth = mode == MM_PRESENT_SMOOTH;
    if (mode == MM_PRESENT_SMOOTH) mode = MM_PRESENT_PACED;
    s.mode = mode;
    s.vsync = mode == MM_PRESENT_FIFO;
    // Paced mode presents at most once per refresh, so it can keep display sync on (no tearing) without blocking.
    s.layer.displaySyncEnabled = mode != MM_PRESENT_IMMEDIATE;
}

static BOOL mm_surface_take_drawable(MMSurface *s) {
    int64_t t0 = mm_now_ns();
    id<CAMetalDrawable> d = [s.layer nextDrawable];
    int64_t dt = mm_now_ns() - t0;
    s->statAcquireNs += dt;
    if (dt > s->statAcquireMaxNs) s->statAcquireMaxNs = dt;
    if (d == nil) return NO;
    atomic_fetch_add(&s->drawablesInFlight, 1);
    __weak MMSurface *weakSurface = s;
    [d addPresentedHandler:^(id<MTLDrawable> drawable) {
        MMSurface *strong = weakSurface;
        if (strong == nil) return;
        atomic_fetch_sub(&strong->drawablesInFlight, 1);
        CFTimeInterval t = drawable.presentedTime;
        if (t > 0.0) {
            double prev = atomic_load(&strong->lastPresentedTime);
            double iv = atomic_load(&strong->refreshInterval);
            double delta = t - prev;
            // Consecutive refreshes only (skipped refreshes show up as multiples): 4 ms .. 1.5x current estimate.
            if (prev > 0.0 && delta > 0.004 && delta < iv * 1.5) {
                atomic_store(&strong->refreshInterval, iv * 0.9 + delta * 0.1);
            }
            atomic_store(&strong->lastPresentedTime, t);
        }
    }];
    s.drawable = d;
    return YES;
}

/**
 * VSync on: blocks for the next drawable (classic FIFO pacing).
 * VSync off: does nothing here; the drawable is taken late, in mm_surface_blit, and only if one is free,
 * so rendering is never throttled by the compositor (frames that find no free drawable are not shown).
 * Returns 1 on success.
 */
MM_EXPORT int mm_surface_acquire(void *surfacePtr) {
    @autoreleasepool {
        MMSurface *s = (__bridge MMSurface *)surfacePtr;
        if (s.mode != MM_PRESENT_FIFO) return 1;
        return mm_surface_take_drawable(s) ? 1 : 0;
    }
}

/** Copies (1:1, vertically flipped) srcView into a drawable and schedules presentation on the current submit. */
MM_EXPORT void mm_surface_blit(void *p, void *surfacePtr, void *srcView, int copyWidth, int copyHeight) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        MMSurface *s = (__bridge MMSurface *)surfacePtr;
        if (s.drawable == nil && s.mode == MM_PRESENT_PACED) {
            double now = CACurrentMediaTime();
            double dt = now - s->lastBlitTime;
            s->lastBlitTime = now;
            if (dt > 0.0 && dt < 0.1) s->avgFrameTime = s->avgFrameTime * 0.9 + dt * 0.1;
            double lp = atomic_load(&s->lastPresentedTime);
            double iv = atomic_load(&s->refreshInterval);
            BOOL present = YES;
            if (lp > 0.0 && iv > 0.0) {
                // First refresh this frame could make once its GPU work completes: GPU time is measured
                // (shader packs make it far larger than the old fixed margin) plus a compositor margin.
                double gpu = atomic_load(&ctx->gpuFrameTime);
                double ready = now + gpu + MM_GPU_MARGIN;
                double k = ceil((ready - lp) / iv);
                double target = lp + MAX(k, 1.0) * iv;
                // One frame per refresh: if the previous frame already took this refresh, aim for the next one
                // instead of dropping this frame (at low frame rates every frame must reach the screen).
                if (target < s->lastTargetVsync + iv * 0.5) {
                    target = s->lastTargetVsync + iv;
                }
                if (ready + s->avgFrameTime < target - 0.001) {
                    present = NO;            // the next frame will still make that refresh and is newer
                } else {
                    s->lastTargetVsync = target;
                }
            }
            // Only drop for lack of drawables when all of them are queued (nextDrawable would block).
            if (present && atomic_load(&s->drawablesInFlight) >= (int)s.layer.maximumDrawableCount) {
                present = NO;
            }
            if (present) {
                mm_surface_take_drawable(s);
            } else {
                s->statPacedSkips++;
                s->statSkipped++;
            }
        } else if (s.drawable == nil && s.mode == MM_PRESENT_IMMEDIATE) {
            int limit = (int)s.layer.maximumDrawableCount - 1;
            // Safety valve: if the in-flight counter ever gets stuck, fall back to a blocking acquire.
            if (atomic_load(&s->drawablesInFlight) < limit || s->skippedFrames > 120) {
                if (s->skippedFrames > 120) s->statForcedAcquires++;
                s->skippedFrames = 0;
                mm_surface_take_drawable(s);
            } else {
                s->skippedFrames++;
                s->statSkipped++;
            }
        }
        if (s.drawable == nil) return;
        mm_end_encoders(ctx);
        id<MTLRenderPipelineState> pso = mm_helper_pipeline(ctx, @"mm_present_vs", @"mm_present_fs", s.layer.pixelFormat,
                                                            MTLPixelFormatInvalid, YES);
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = s.drawable.texture;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> enc = mm_render_encoder(ctx, rp);
        enc.label = @"MineMetal present";
        if (pso != nil) {
            uint32_t size[2] = {(uint32_t)MAX(copyWidth, 0), (uint32_t)MAX(copyHeight, 0)};
            [enc setRenderPipelineState:pso];
            [enc setFragmentTexture:(__bridge id<MTLTexture>)srcView atIndex:0];
            [enc setFragmentBytes:size length:sizeof(size) atIndex:0];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        }
        [enc endEncoding];
        if (s->smooth && s.mode == MM_PRESENT_PACED) {
            // Even frame spacing: never show a frame sooner than the average frame time after the previous one.
            // At e.g. 34 fps on a 120 Hz panel this avoids the 3-3-4-3 refresh cadence that reads as stutter; on
            // ProMotion in fullscreen the display adapts its refresh to this spacing.
            double iv = atomic_load(&s->refreshInterval);
            double dur = s->avgFrameTime * 0.97;
            if (dur < iv * 0.9) dur = 0.0;           // fast frame rates: plain once-per-refresh pacing
            if (dur > 0.1) dur = 0.1;
            if (dur > 0.0) [mm_cmd(ctx) presentDrawable:s.drawable afterMinimumDuration:dur];
            else [mm_cmd(ctx) presentDrawable:s.drawable];
        } else {
            [mm_cmd(ctx) presentDrawable:s.drawable];
        }
        s->statPresented++;
        s.drawable = nil;
    }
}

/** out: [0]=presented [1]=skipped [2]=forcedBlockingAcquires [3]=acquireNsTotal [4]=acquireNsMax [5]=drawablesInFlight. Resets max. */
MM_EXPORT void mm_surface_stats(void *surfacePtr, int64_t *out) {
    MMSurface *s = (__bridge MMSurface *)surfacePtr;
    out[0] = s->statPresented;
    out[1] = s->statSkipped;
    out[2] = s->statForcedAcquires;
    out[3] = s->statAcquireNs;
    out[4] = s->statAcquireMaxNs;
    out[5] = atomic_load(&s->drawablesInFlight);
    out[6] = (int64_t)(atomic_load(&s->refreshInterval) * 1e6);
    out[7] = s.mode;
    s->statAcquireMaxNs = 0;
}

MM_EXPORT void mm_surface_drop(void *surfacePtr) {
    MMSurface *s = (__bridge MMSurface *)surfacePtr;
    s.drawable = nil;
}


// ---------------------------------------------------------------------------------------------------------------------
// Compute, storage resources, clears (stage 4: GL compute shaders, image load/store, SSBOs)
// ---------------------------------------------------------------------------------------------------------------------

/** Compiles an MSL kernel into a compute pipeline (retained; free with mm_release). */
MM_EXPORT void *mm_compute_pipeline_create(void *p, const char *name, const char *src, const char *entry, char *err, int errLen) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        id<MTLFunction> fn = mm_compile_function(ctx, src, entry, @"compute", err, errLen);
        if (fn == nil) return NULL;
        MTLComputePipelineDescriptor *d = [[MTLComputePipelineDescriptor alloc] init];
        d.computeFunction = fn;
        if (name != NULL) d.label = [NSString stringWithUTF8String:name];
        NSError *error = nil;
        id<MTLComputePipelineState> pso = [ctx.device newComputePipelineStateWithDescriptor:d options:MTLPipelineOptionNone
                                                                                reflection:nil error:&error];
        if (pso == nil) {
            mm_copy_error(err, errLen, [NSString stringWithFormat:@"compute pipeline: %@", error.localizedDescription]);
            return NULL;
        }
        return (__bridge_retained void *)pso;
    }
}

MM_EXPORT int mm_compute_max_threads(void *psoPtr) {
    return (int)((__bridge id<MTLComputePipelineState>)psoPtr).maxTotalThreadsPerThreadgroup;
}

static id<MTLComputeCommandEncoder> mm_compute_encoder(MMContext *ctx) {
    if (ctx.compute == nil) {
        mm_end_encoders(ctx);
        mm_pc_flush(ctx);
        ctx.compute = [mm_cmd(ctx) computeCommandEncoder];
    }
    return ctx.compute;
}

/** Opens (or continues) the compute encoder and sets the pipeline. */
MM_EXPORT void mm_compute_set_pipeline(void *p, void *psoPtr) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        [mm_compute_encoder(ctx) setComputePipelineState:(__bridge id<MTLComputePipelineState>)psoPtr];
    }
}

MM_EXPORT void mm_compute_set_buffer(void *p, void *buf, uint64_t offset, int index) {
    MMContext *ctx = CTX(p);
    if (ctx.compute == nil || index < 0 || index > 30) return;
    [ctx.compute setBuffer:(__bridge id<MTLBuffer>)buf offset:offset atIndex:(NSUInteger)index];
}

MM_EXPORT void mm_compute_set_bytes(void *p, const void *data, uint64_t length, int index) {
    MMContext *ctx = CTX(p);
    if (ctx.compute == nil || length == 0 || index < 0 || index > 30) return;
    [ctx.compute setBytes:data length:length atIndex:(NSUInteger)index];
}

MM_EXPORT void mm_compute_set_texture(void *p, void *tex, int index) {
    MMContext *ctx = CTX(p);
    if (ctx.compute == nil || index < 0 || index > 127) return;
    [ctx.compute setTexture:(__bridge id<MTLTexture>)tex atIndex:(NSUInteger)index];
}

MM_EXPORT void mm_compute_set_sampler(void *p, void *sampler, int index) {
    MMContext *ctx = CTX(p);
    if (ctx.compute == nil || index < 0 || index > 15) return;
    [ctx.compute setSamplerState:(__bridge id<MTLSamplerState>)sampler atIndex:(NSUInteger)index];
}

MM_EXPORT void mm_compute_dispatch(void *p, int gx, int gy, int gz, int tx, int ty, int tz) {
    MMContext *ctx = CTX(p);
    if (ctx.compute == nil || gx <= 0 || gy <= 0 || gz <= 0) return;
    [ctx.compute dispatchThreadgroups:MTLSizeMake((NSUInteger)gx, (NSUInteger)gy, (NSUInteger)gz)
                threadsPerThreadgroup:MTLSizeMake((NSUInteger)MAX(tx, 1), (NSUInteger)MAX(ty, 1), (NSUInteger)MAX(tz, 1))];
}

MM_EXPORT void mm_compute_dispatch_indirect(void *p, void *buf, uint64_t offset, int tx, int ty, int tz) {
    MMContext *ctx = CTX(p);
    if (ctx.compute == nil || buf == NULL) return;
    [ctx.compute dispatchThreadgroupsWithIndirectBuffer:(__bridge id<MTLBuffer>)buf indirectBufferOffset:offset
                                  threadsPerThreadgroup:MTLSizeMake((NSUInteger)MAX(tx, 1), (NSUInteger)MAX(ty, 1), (NSUInteger)MAX(tz, 1))];
}

/** glMemoryBarrier: orders storage writes before later reads inside the open encoder (across encoders Metal tracks hazards). */
MM_EXPORT void mm_memory_barrier(void *p) {
    MMContext *ctx = CTX(p);
    if (ctx.compute != nil) {
        [ctx.compute memoryBarrierWithScope:MTLBarrierScopeBuffers | MTLBarrierScopeTextures];
    } else if (ctx.render != nil && !ctx->renderLingering) {
        [ctx.render memoryBarrierWithScope:MTLBarrierScopeBuffers | MTLBarrierScopeTextures
                               afterStages:MTLRenderStageFragment
                              beforeStages:MTLRenderStageVertex | MTLRenderStageFragment];
    }
}

/** The open render pass binds writable storage: it must not be continued by the next pass (see mm_pass_begin). */
MM_EXPORT void mm_pass_mark_storage(void *p) {
    CTX(p)->renderStorage = 1;
}

/** Fills a byte range of a buffer (glClearBufferSubData with a single repeated byte). */
MM_EXPORT void mm_fill_buffer(void *p, void *buf, uint64_t offset, uint64_t size, int value) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        if (buf == NULL || size == 0) return;
        [mm_blit(ctx) fillBuffer:(__bridge id<MTLBuffer>)buf range:NSMakeRange((NSUInteger)offset, (NSUInteger)size) value:(uint8_t)value];
    }
}

/** Clears one mip level of a color texture (any type: 1D/2D/array/cube/3D) to zero by copying from a zero buffer. */
MM_EXPORT void mm_texture_zero(void *p, void *texPtr, int level, int bytesPerPixel) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        id<MTLTexture> tex = (__bridge id<MTLTexture>)texPtr;
        if (tex == nil || bytesPerPixel <= 0 || level < 0 || (NSUInteger)level >= tex.mipmapLevelCount) return;
        NSUInteger w = MAX(tex.width >> level, (NSUInteger)1);
        NSUInteger h = tex.textureType == MTLTextureType1D ? 1 : MAX(tex.height >> level, (NSUInteger)1);
        NSUInteger d = tex.textureType == MTLTextureType3D ? MAX(tex.depth >> level, (NSUInteger)1) : 1;
        NSUInteger slices = tex.textureType == MTLTextureTypeCube ? 6 : MAX(tex.arrayLength, (NSUInteger)1);
        NSUInteger row = w * (NSUInteger)bytesPerPixel;
        NSUInteger image = row * h;
        if (ctx.zeroBuffer == nil || ctx.zeroBuffer.length < image) {
            ctx.zeroBuffer = [ctx.device newBufferWithLength:MAX(image, (NSUInteger)4096) options:MTLResourceStorageModeShared];
            memset(ctx.zeroBuffer.contents, 0, ctx.zeroBuffer.length);
        }
        id<MTLBlitCommandEncoder> blit = mm_blit(ctx);
        for (NSUInteger s = 0; s < slices; s++) {
            for (NSUInteger z = 0; z < d; z++) {
                [blit copyFromBuffer:ctx.zeroBuffer sourceOffset:0 sourceBytesPerRow:row sourceBytesPerImage:image
                          sourceSize:MTLSizeMake(w, h, 1) toTexture:tex destinationSlice:s destinationLevel:(NSUInteger)level
                   destinationOrigin:MTLOriginMake(0, 0, z)];
            }
        }
    }
}

/** Copies all of level 0..n-1 of one texture into another of identical size/format (used to swap in a writable texture). */
MM_EXPORT void mm_copy_texture_all(void *p, void *src, void *dst) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        id<MTLTexture> s = (__bridge id<MTLTexture>)src;
        id<MTLTexture> d = (__bridge id<MTLTexture>)dst;
        if (s == nil || d == nil) return;
        [mm_blit(ctx) copyFromTexture:s toTexture:d];
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Upscaling: the world is rendered into a smaller target and scaled up to the window before the GUI is drawn.
// Modes: 1 = bilinear, 2 = AMD FSR 1 (EASU + RCAS), 3 = MetalFX spatial (+ optional RCAS sharpening).
// ---------------------------------------------------------------------------------------------------------------------

static NSString *const kMMUpscaleCommonMSL =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct MMFsOut { float4 pos [[position]]; };\n"
     "vertex MMFsOut mm_fs_vs(uint vid [[vertex_id]]) {\n"
     "    float2 p = float2(float((vid << 1) & 2), float(vid & 2));\n"
     "    MMFsOut o;\n"
     "    o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0);\n"
     "    return o;\n"
     "}\n"
     "struct MMUpConsts { float4 sizes; float4 extra; };\n"
     "struct MMMotionConsts { float4x4 invCur; float4x4 prevVP; float4 camDelta; float4 jitter; float4 misc; };\n"
     "struct MMMotionOut { float2 mv [[color(0)]]; float reactive [[color(1)]]; };\n"
     "fragment MMMotionOut mm_motion(float4 pos [[position]], depth2d<float> depth [[texture(0)]], constant MMMotionConsts &c [[buffer(0)]]) {\n"
     "    float2 size = float2(depth.get_width(), depth.get_height());\n"
     "    float z = depth.read(uint2(pos.xy));\n"
     "    if (c.misc.x > 0.5) z = 1.0 - z;  // standard depth -> Minecraft's reversed Z\n"
     "    MMMotionOut o;\n"
     "    o.reactive = z <= 1e-7 ? c.misc.z : 0.0;  // sky / shader clouds: no depth, lean on the current frame\n"
     "    float2 uv = pos.xy / size;\n"
     "    float4 p = c.invCur * float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, z, 1.0);\n"
     "    p /= p.w;\n"
     "    float4 q = c.prevVP * float4(p.xyz + c.camDelta.xyz, 1.0);\n"
     "    if (q.w <= 1e-6) { o.mv = float2(0.0); return o; }\n"
     "    float2 pn = q.xy / q.w;\n"
     "    float2 prevUv = float2(pn.x * 0.5 + 0.5, 0.5 - pn.y * 0.5);\n"
     "    o.mv = (prevUv - uv) * c.misc.y;\n"
     "    return o;\n"
     "}\n"
     "fragment float4 mm_fs_bilinear(float4 pos [[position]], texture2d<float> t [[texture(0)]], sampler s [[sampler(0)]],\n"
     "                               constant MMUpConsts &c [[buffer(0)]]) {\n"
     "    return float4(t.sample(s, pos.xy / c.sizes.zw).rgb, 1.0);\n"
     "}\n";

static id<MTLLibrary> g_upCommonLib, g_upEasuLib, g_upRcasLib;
static NSMutableDictionary<NSString *, id<MTLRenderPipelineState>> *g_upPipelines;
static id<MTLSamplerState> g_upLinear;
static id<MTLTexture> g_upTmp;          // EASU output / MetalFX output (full size)
static id<MTLFXSpatialScaler> g_upFx;
static int g_upFailed;                  // bit per mode that failed to initialise (falls back to bilinear)

static id<MTLLibrary> mm_up_library(MMContext *ctx, const char *src) {
    NSError *err = nil;
    MTLCompileOptions *o = [[MTLCompileOptions alloc] init];
    o.languageVersion = MTLLanguageVersion2_3;
    id<MTLLibrary> lib = [ctx.device newLibraryWithSource:[NSString stringWithUTF8String:src] options:o error:&err];
    if (lib == nil) NSLog(@"[MineMetal] upscaler shader compile failed: %@", err);
    return lib;
}

static id<MTLRenderPipelineState> mm_up_pipeline(MMContext *ctx, int which, MTLPixelFormat fmt) {
    if (g_upPipelines == nil) g_upPipelines = [NSMutableDictionary dictionary];
    NSString *key = [NSString stringWithFormat:@"%d/%lu", which, (unsigned long)fmt];
    id<MTLRenderPipelineState> pso = g_upPipelines[key];
    if (pso != nil) return pso;
    if (g_upCommonLib == nil) g_upCommonLib = mm_up_library(ctx, kMMUpscaleCommonMSL.UTF8String);
    id<MTLLibrary> fragLib = nil;
    NSString *fragName = nil;
    if (which == 0) { fragLib = g_upCommonLib; fragName = @"mm_fs_bilinear"; }
    else if (which == 1) { if (g_upEasuLib == nil) g_upEasuLib = mm_up_library(ctx, MM_FSR_EASU_MSL); fragLib = g_upEasuLib; fragName = @"main_easu"; }
    else { if (g_upRcasLib == nil) g_upRcasLib = mm_up_library(ctx, MM_FSR_RCAS_MSL); fragLib = g_upRcasLib; fragName = @"main_rcas"; }
    if (g_upCommonLib == nil || fragLib == nil) return nil;
    MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor alloc] init];
    d.label = [NSString stringWithFormat:@"MineMetal upscale %@", fragName];
    d.vertexFunction = [g_upCommonLib newFunctionWithName:@"mm_fs_vs"];
    d.fragmentFunction = [fragLib newFunctionWithName:fragName];
    d.colorAttachments[0].pixelFormat = fmt;
    NSError *err = nil;
    pso = [ctx.device newRenderPipelineStateWithDescriptor:d error:&err];
    if (pso == nil) { NSLog(@"[MineMetal] upscaler pipeline %@ failed: %@", fragName, err); return nil; }
    g_upPipelines[key] = pso;
    return pso;
}

/** One fullscreen-triangle pass: dst = shader(src). Overwrites every pixel of dst. */
static BOOL mm_up_draw(MMContext *ctx, int which, id<MTLTexture> src, id<MTLTexture> dst, float sharpStops) {
    id<MTLRenderPipelineState> pso = mm_up_pipeline(ctx, which, dst.pixelFormat);
    if (pso == nil) return NO;
    if (g_upLinear == nil) {
        MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor alloc] init];
        sd.minFilter = MTLSamplerMinMagFilterLinear;
        sd.magFilter = MTLSamplerMinMagFilterLinear;
        sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
        sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
        g_upLinear = [ctx.device newSamplerStateWithDescriptor:sd];
    }
    float consts[8] = { (float)src.width, (float)src.height, (float)dst.width, (float)dst.height, sharpStops, 0, 0, 0 };
    MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = dst;
    rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    id<MTLRenderCommandEncoder> enc = mm_render_encoder(ctx, rp);
    enc.label = @"MineMetal upscale";
    [enc setRenderPipelineState:pso];
    [enc setFragmentTexture:src atIndex:0];
    [enc setFragmentSamplerState:g_upLinear atIndex:0];
    [enc setFragmentBytes:consts length:sizeof(consts) atIndex:0];
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [enc endEncoding];
    return YES;
}

static id<MTLTexture> mm_up_tmp(MMContext *ctx, NSUInteger w, NSUInteger h, MTLPixelFormat fmt, MTLTextureUsage usage) {
    if (g_upTmp != nil && g_upTmp.width == w && g_upTmp.height == h && g_upTmp.pixelFormat == fmt && (g_upTmp.usage & usage) == usage) return g_upTmp;
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:fmt width:w height:h mipmapped:NO];
    td.usage = usage;
    td.storageMode = MTLStorageModePrivate;
    g_upTmp = [ctx.device newTextureWithDescriptor:td];
    g_upTmp.label = @"MineMetal upscale intermediate";
    return g_upTmp;
}

/**
 * Scales src (the low-resolution world image) up into dst (the window-sized main color target).
 * sharpness: 0..1 (1 = strongest RCAS sharpening, 0 = none). Returns the mode actually used (falls back to bilinear).
 */
MM_EXPORT int mm_upscale(void *p, void *srcPtr, void *dstPtr, int mode, float sharpness) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        id<MTLTexture> src = (__bridge id<MTLTexture>)srcPtr;
        id<MTLTexture> dst = (__bridge id<MTLTexture>)dstPtr;
        if (src == nil || dst == nil) return -1;
        mm_end_encoders(ctx);
        mm_pc_flush(ctx);
        if (sharpness < 0.0f) sharpness = 0.0f;
        if (sharpness > 1.0f) sharpness = 1.0f;
        float stops = (1.0f - sharpness) * 2.0f;  // RCAS: 0 stops = maximum sharpening
        BOOL sharpen = sharpness > 0.001f;
        if (mode == 3 && !(g_upFailed & 8)) {
            if (g_upFx == nil || g_upFx.inputWidth != src.width || g_upFx.inputHeight != src.height
                    || g_upFx.outputWidth != dst.width || g_upFx.outputHeight != dst.height
                    || g_upFx.colorTextureFormat != src.pixelFormat || g_upFx.outputTextureFormat != dst.pixelFormat) {
                g_upFx = nil;
                if ([MTLFXSpatialScalerDescriptor supportsDevice:ctx.device]) {
                    MTLFXSpatialScalerDescriptor *d = [[MTLFXSpatialScalerDescriptor alloc] init];
                    d.inputWidth = src.width;
                    d.inputHeight = src.height;
                    d.outputWidth = dst.width;
                    d.outputHeight = dst.height;
                    d.colorTextureFormat = src.pixelFormat;
                    d.outputTextureFormat = dst.pixelFormat;
                    d.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
                    g_upFx = [d newSpatialScalerWithDevice:ctx.device];
                }
                if (g_upFx == nil) { NSLog(@"[MineMetal] MetalFX spatial scaler unavailable; using FSR"); g_upFailed |= 8; }
            }
            if (g_upFx != nil) {
                id<MTLTexture> out = mm_up_tmp(ctx, dst.width, dst.height, dst.pixelFormat,
                                               g_upFx.outputTextureUsage | MTLTextureUsageShaderRead);
                g_upFx.colorTexture = src;
                g_upFx.outputTexture = out;
                g_upFx.inputContentWidth = src.width;
                g_upFx.inputContentHeight = src.height;
                mm_prof_maybe_split(ctx);
                [g_upFx encodeToCommandBuffer:mm_cmd(ctx)];
                if (sharpen && mm_up_draw(ctx, 2, out, dst, stops)) return 3;
                [mm_blit(ctx) copyFromTexture:out toTexture:dst];
                mm_end_encoders(ctx);
                return 3;
            }
            mode = 2;
        }
        if (mode == 2 || mode == 3) {
            id<MTLTexture> tmp = mm_up_tmp(ctx, dst.width, dst.height, MTLPixelFormatRGBA16Float,
                                           MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
            if (sharpen) {
                if (mm_up_draw(ctx, 1, src, tmp, stops) && mm_up_draw(ctx, 2, tmp, dst, stops)) return 2;
            } else if (mm_up_draw(ctx, 1, src, dst, stops)) {
                return 2;
            }
        }
        return mm_up_draw(ctx, 0, src, dst, 0.0f) ? 1 : -1;
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// MetalFX Temporal: jittered low-resolution color + scene depth + camera motion vectors -> anti-aliased, upscaled image.
// ---------------------------------------------------------------------------------------------------------------------

static id<MTLTexture> g_taDepth, g_taMotion, g_taReactive;
static BOOL g_taReactiveOn;
static id<MTLFXTemporalScaler> g_taFx;
static id<MTLRenderPipelineState> g_taMotionPso;
static BOOL g_taUnsupported;

/** Copies the world depth (before the hand pass) for the temporal upscaler. */
MM_EXPORT void mm_upscale_capture_depth(void *p, void *depthPtr) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        id<MTLTexture> d = (__bridge id<MTLTexture>)depthPtr;
        if (d == nil) return;
        mm_end_encoders(ctx);
        if (g_taDepth == nil || g_taDepth.width != d.width || g_taDepth.height != d.height || g_taDepth.pixelFormat != d.pixelFormat) {
            MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:d.pixelFormat width:d.width height:d.height mipmapped:NO];
            td.usage = MTLTextureUsageShaderRead;
            td.storageMode = MTLStorageModePrivate;
            g_taDepth = [ctx.device newTextureWithDescriptor:td];
            g_taDepth.label = @"MineMetal temporal depth";
        }
        [mm_blit(ctx) copyFromTexture:d sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                           sourceSize:MTLSizeMake(d.width, d.height, 1) toTexture:g_taDepth destinationSlice:0
                     destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
        mm_end_encoders(ctx);
    }
}

/**
 * params (44 floats): invCur[16], prevVP[16], camDelta[4], jitterX, jitterY, standardDepth, motionSign, skyReactive, (3 unused).
 * Returns 4 on success, -1 if MetalFX Temporal can't run this frame (the caller falls back to the spatial scaler).
 */
MM_EXPORT int mm_upscale_temporal(void *p, void *colorPtr, void *dstPtr, const float *params, int reset, float sharpness) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        id<MTLTexture> color = (__bridge id<MTLTexture>)colorPtr;
        id<MTLTexture> dst = (__bridge id<MTLTexture>)dstPtr;
        if (g_taUnsupported || color == nil || dst == nil || params == NULL || g_taDepth == nil
                || g_taDepth.width != color.width || g_taDepth.height != color.height) return -1;
        mm_end_encoders(ctx);
        mm_pc_flush(ctx);
        NSUInteger w = color.width, h = color.height;

        // 1. Motion vectors (camera reprojection of the scene depth).
        if (g_taMotion == nil || g_taMotion.width != w || g_taMotion.height != h) {
            MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRG16Float width:w height:h mipmapped:NO];
            td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
            td.storageMode = MTLStorageModePrivate;
            g_taMotion = [ctx.device newTextureWithDescriptor:td];
            g_taMotion.label = @"MineMetal motion vectors";
            MTLTextureDescriptor *rd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR8Unorm width:w height:h mipmapped:NO];
            rd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
            rd.storageMode = MTLStorageModePrivate;
            g_taReactive = [ctx.device newTextureWithDescriptor:rd];
            g_taReactive.label = @"MineMetal reactive mask";
        }
        if (g_taMotionPso == nil) {
            if (g_upCommonLib == nil) g_upCommonLib = mm_up_library(ctx, kMMUpscaleCommonMSL.UTF8String);
            if (g_upCommonLib == nil) { g_taUnsupported = YES; return -1; }
            MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor alloc] init];
            d.label = @"MineMetal motion vectors";
            d.vertexFunction = [g_upCommonLib newFunctionWithName:@"mm_fs_vs"];
            d.fragmentFunction = [g_upCommonLib newFunctionWithName:@"mm_motion"];
            d.colorAttachments[0].pixelFormat = MTLPixelFormatRG16Float;
            d.colorAttachments[1].pixelFormat = MTLPixelFormatR8Unorm;
            NSError *err = nil;
            g_taMotionPso = [ctx.device newRenderPipelineStateWithDescriptor:d error:&err];
            if (g_taMotionPso == nil) { NSLog(@"[MineMetal] motion vector pipeline failed: %@", err); g_taUnsupported = YES; return -1; }
        }
        float consts[48];
        memcpy(consts, params, 36 * sizeof(float));                 // invCur, prevVP, camDelta
        consts[36] = params[36]; consts[37] = params[37]; consts[38] = 0; consts[39] = 0;  // jitter
        consts[40] = params[38]; consts[41] = params[39]; consts[42] = params[40]; consts[43] = 0;  // misc: depth, sign, sky reactive
        {
            MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
            rp.colorAttachments[0].texture = g_taMotion;
            rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
            rp.colorAttachments[0].storeAction = MTLStoreActionStore;
            rp.colorAttachments[1].texture = g_taReactive;
            rp.colorAttachments[1].loadAction = MTLLoadActionDontCare;
            rp.colorAttachments[1].storeAction = MTLStoreActionStore;
            id<MTLRenderCommandEncoder> enc = mm_render_encoder(ctx, rp);
            enc.label = @"MineMetal motion vectors";
            [enc setRenderPipelineState:g_taMotionPso];
            [enc setFragmentTexture:g_taDepth atIndex:0];
            [enc setFragmentBytes:consts length:44 * sizeof(float) atIndex:0];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
        }

        // 2. The temporal scaler (recreated when sizes or formats change).
        BOOL fresh = NO;
        if (g_taFx == nil || g_taFx.inputWidth != w || g_taFx.inputHeight != h || g_taFx.outputWidth != dst.width
                || g_taFx.outputHeight != dst.height || g_taFx.colorTextureFormat != color.pixelFormat
                || g_taFx.depthTextureFormat != g_taDepth.pixelFormat || g_taFx.outputTextureFormat != dst.pixelFormat) {
            g_taFx = nil;
            if (![MTLFXTemporalScalerDescriptor supportsDevice:ctx.device]) {
                NSLog(@"[MineMetal] MetalFX Temporal is not supported on this GPU; using MetalFX Spatial");
                g_taUnsupported = YES;
                return -1;
            }
            MTLFXTemporalScalerDescriptor *d = [[MTLFXTemporalScalerDescriptor alloc] init];
            d.colorTextureFormat = color.pixelFormat;
            d.depthTextureFormat = g_taDepth.pixelFormat;
            d.motionTextureFormat = MTLPixelFormatRG16Float;
            d.outputTextureFormat = dst.pixelFormat;
            d.inputWidth = w;
            d.inputHeight = h;
            d.outputWidth = dst.width;
            d.outputHeight = dst.height;
            d.autoExposureEnabled = NO;
            // Reactive mask (newer MetalFX): set through KVC so older SDKs still compile; skipped where unsupported.
            g_taReactiveOn = NO;
            if (params[40] > 0.0f && [d respondsToSelector:NSSelectorFromString(@"setReactiveMaskTextureEnabled:")]) {
                @try {
                    [d setValue:@YES forKey:@"reactiveMaskTextureEnabled"];
                    [d setValue:@(MTLPixelFormatR8Unorm) forKey:@"reactiveMaskTextureFormat"];
                    g_taReactiveOn = YES;
                } @catch (NSException *e) {
                    NSLog(@"[MineMetal] MetalFX reactive mask unavailable: %@", e.reason);
                }
            }
            g_taFx = [d newTemporalScalerWithDevice:ctx.device];
            if (g_taFx == nil) {
                NSLog(@"[MineMetal] MetalFX Temporal scaler creation failed (%lux%lu -> %lux%lu); using MetalFX Spatial",
                      (unsigned long)w, (unsigned long)h, (unsigned long)dst.width, (unsigned long)dst.height);
                g_taUnsupported = YES;
                return -1;
            }
            g_taFx.motionVectorScaleX = (float)w;
            g_taFx.motionVectorScaleY = (float)h;
            fresh = YES;
        }
        id<MTLTexture> out = mm_up_tmp(ctx, dst.width, dst.height, dst.pixelFormat, g_taFx.outputTextureUsage | MTLTextureUsageShaderRead);
        g_taFx.colorTexture = color;
        g_taFx.depthTexture = g_taDepth;
        g_taFx.motionTexture = g_taMotion;
        if (g_taReactiveOn) {
            @try { [(NSObject *)g_taFx setValue:g_taReactive forKey:@"reactiveMaskTexture"]; }
            @catch (NSException *e) { g_taReactiveOn = NO; }
        }
        g_taFx.outputTexture = out;
        g_taFx.inputContentWidth = w;
        g_taFx.inputContentHeight = h;
        g_taFx.jitterOffsetX = params[36];
        g_taFx.jitterOffsetY = params[37];
        g_taFx.depthReversed = params[38] < 0.5f;
        g_taFx.reset = (reset != 0) || fresh;
        mm_prof_maybe_split(ctx);
        [g_taFx encodeToCommandBuffer:mm_cmd(ctx)];

        // 3. Optional RCAS sharpening into the main target, else a straight copy.
        if (sharpness > 0.001f && mm_up_draw(ctx, 2, out, dst, (1.0f - fminf(sharpness, 1.0f)) * 2.0f)) return 4;
        [mm_blit(ctx) copyFromTexture:out toTexture:dst];
        mm_end_encoders(ctx);
        return 4;
    }
}

/** Debug: out = [width, height, pixelFormat, usage, storageMode, mipmapLevelCount, arrayLength, textureType]. */
MM_EXPORT void mm_texture_describe(void *texPtr, int64_t *out) {
    id<MTLTexture> t = (__bridge id<MTLTexture>)texPtr;
    if (t == nil || out == NULL) return;
    out[0] = (int64_t)t.width; out[1] = (int64_t)t.height; out[2] = (int64_t)t.pixelFormat; out[3] = (int64_t)t.usage;
    out[4] = (int64_t)t.storageMode; out[5] = (int64_t)t.mipmapLevelCount; out[6] = (int64_t)t.arrayLength; out[7] = (int64_t)t.textureType;
}

// --- NeoForge 1.21.1 port: GLFW NO_API window + CAMetalLayer ---------------------------------------------------------
#include <objc/message.h>

/** Attaches a new CAMetalLayer to an NSWindow's content view (GLFW window created with GLFW_NO_API). Main thread. */
MM_EXPORT void *mm_layer_for_nswindow(void *nswindow) {
    @autoreleasepool {
        id win = (__bridge id)nswindow;
        if (win == nil) return NULL;
        id view = ((id (*)(id, SEL))objc_msgSend)(win, sel_registerName("contentView"));
        if (view == nil) return NULL;
        CGFloat scale = ((CGFloat (*)(id, SEL))objc_msgSend)(win, sel_registerName("backingScaleFactor"));
        CAMetalLayer *layer = [CAMetalLayer layer];
        layer.contentsScale = scale > 0 ? scale : 1.0;
        ((void (*)(id, SEL, id))objc_msgSend)(view, sel_registerName("setLayer:"), layer);
        ((void (*)(id, SEL, BOOL))objc_msgSend)(view, sel_registerName("setWantsLayer:"), YES);
        return (__bridge_retained void *)layer;
    }
}

/** Milestone-1 present: clears a drawable to the given colour and presents it. */
MM_EXPORT void mm_neo_present_clear(void *p, void *surfacePtr, float r, float g, float b, float a) {
    @autoreleasepool {
        MMContext *ctx = CTX(p);
        MMSurface *s = (__bridge MMSurface *)surfacePtr;
        if (s.drawable == nil && !mm_surface_take_drawable(s)) return;
        mm_end_encoders(ctx);
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = s.drawable.texture;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].clearColor = MTLClearColorMake(r, g, b, a);
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> enc = mm_render_encoder(ctx, rp);
        enc.label = @"MineMetal neo clear";
        [enc endEncoding];
        [mm_cmd(ctx) presentDrawable:s.drawable];
        s->statPresented++;
        s.drawable = nil;
    }
    mm_submit(p);
}
