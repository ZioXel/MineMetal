package dev.minemetal.client.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import dev.minemetal.client.MineMetalClient;
import dev.minemetal.client.metal.MetalBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts Metal in front of the backends Minecraft tries. If Metal fails to initialise, Minecraft falls through to the
 * remaining (vanilla) backends exactly as before.
 * <p>
 * Escape hatches: choosing "OpenGL" explicitly in Video Settings (or Minecraft's own crash recovery, which forces OpenGL
 * after an unclean start) skips Metal, as does {@code -Dminemetal.disable=true}.
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {
    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void minemetal$preferMetal(final CallbackInfoReturnable<GpuBackend[]> cir) {
        if ((Object) this == PreferredGraphicsApi.OPENGL || !MineMetalClient.isMetalEnabled()) {
            return;
        }
        GpuBackend[] vanilla = cir.getReturnValue();
        GpuBackend[] withMetal = new GpuBackend[vanilla.length + 1];
        withMetal[0] = new MetalBackend();
        System.arraycopy(vanilla, 0, withMetal, 1, vanilla.length);
        cir.setReturnValue(withMetal);
    }
}
