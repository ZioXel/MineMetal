package dev.minemetal.neo.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.minemetal.neo.upscale.NeoUpscaler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the level into a smaller target (see {@link NeoUpscaler}) and scales it up before the GUI.
 * The swap covers renderLevel, entity outlines and vanilla post effects; it ends right before the main target is
 * re-bound for the GUI.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererUpscaleMixin {
    @Shadow @Final Minecraft minecraft;
    @Unique private RenderTarget minemetal$full;
    @Unique private RenderTarget minemetal$low;

    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void minemetal$beginLowRes(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        MinecraftAccessor mc = (MinecraftAccessor) this.minecraft;
        RenderTarget main = mc.minemetal$getMainRenderTarget();
        RenderTarget low = NeoUpscaler.begin(main);
        if (low != null) {
            this.minemetal$full = main;
            this.minemetal$low = low;
            mc.minemetal$setMainRenderTarget(low);
        }
    }

    /**
     * After {@code getMainRenderTarget().bindWrite(true)}: that receiver was still the small target (evaluated before
     * any injection could run), so restore, upscale, and re-bind the real main target for the GUI.
     */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V", ordinal = 0,
        shift = At.Shift.AFTER))
    private void minemetal$upscale(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        RenderTarget full = this.minemetal$full;
        this.minemetal$restore(true);
        if (full != null) full.bindWrite(true);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void minemetal$endFrame(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        this.minemetal$restore(false);
    }

    @Unique
    private void minemetal$restore(boolean upscale) {
        RenderTarget full = this.minemetal$full, low = this.minemetal$low;
        if (full == null || low == null) return;
        NeoUpscaler.end();
        ((MinecraftAccessor) this.minecraft).minemetal$setMainRenderTarget(full);
        this.minemetal$full = null;
        this.minemetal$low = null;
        if (upscale) NeoUpscaler.finish(low, full);
    }
}
