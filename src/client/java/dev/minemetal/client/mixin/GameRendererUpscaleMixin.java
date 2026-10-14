package dev.minemetal.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.minemetal.client.upscale.MetalUpscaler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World upscaling: the main render target is swapped for a smaller one while the world (and its post effects) render,
 * then scaled back up into the real main target before the GUI draws. See {@link MetalUpscaler}.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererUpscaleMixin {
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;

    @Unique private RenderTarget minemetal$fullTarget;
    @Unique private RenderTarget minemetal$lowTarget;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void minemetal$beginLowRes(final CallbackInfo ci) {
        RenderTarget low = MetalUpscaler.beginWorld(this.mainRenderTarget);
        if (low != null) {
            this.minemetal$fullTarget = this.mainRenderTarget;
            this.minemetal$lowTarget = low;
            this.mainRenderTarget = low;
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
    private void minemetal$upscale(final CallbackInfo ci) {
        this.minemetal$restore(true);
    }

    /** Safety net: never leave the small target installed past the frame (e.g. if a world render threw). */
    @Inject(method = "render", at = @At("RETURN"))
    private void minemetal$endFrame(final CallbackInfo ci) {
        this.minemetal$restore(false);
    }

    @Unique
    private void minemetal$restore(final boolean upscale) {
        RenderTarget full = this.minemetal$fullTarget;
        RenderTarget low = this.minemetal$lowTarget;
        if (full == null || low == null) {
            return;
        }
        this.mainRenderTarget = full;
        this.minemetal$fullTarget = null;
        this.minemetal$lowTarget = null;
        if (upscale) {
            MetalUpscaler.finishWorld(low, full);
        }
    }
}
