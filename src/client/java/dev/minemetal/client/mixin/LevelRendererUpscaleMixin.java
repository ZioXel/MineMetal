package dev.minemetal.client.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.minemetal.client.upscale.MetalUpscaler;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MetalFX Temporal: records the view matrix exactly as the level is rendered with it (with Iris, view bobbing has been
 * moved into it at this point), so the motion vectors include bobbing.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererUpscaleMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void minemetal$captureView(final GraphicsResourceAllocator allocator, final boolean renderOutline, final CameraRenderState cameraState,
                                       final GpuBufferSlice terrainFog, final Vector4f fogColor, final boolean shouldRenderSky,
                                       final boolean consistentDepthRequired, final CallbackInfo ci) {
        MetalUpscaler.captureView(cameraState.viewRotationMatrix);
    }
}
