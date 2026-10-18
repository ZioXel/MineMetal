package dev.minemetal.client.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import dev.minemetal.client.MineMetalClient;
import dev.minemetal.client.gltranslate.MetalGl;
import dev.minemetal.client.gltranslate.MetalGlBackend;
import dev.minemetal.client.metal.MetalBackend;
import dev.minemetal.client.metal.MetalDevice;
import net.minecraft.network.chat.Component;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts Metal in front of the backends Minecraft tries. If Metal fails to initialise, Minecraft falls through to the
 * remaining (vanilla) backends exactly as before.
 * <p>
 * Only the "Default" Graphics API uses Metal (shown as "Default (Metal)"); choosing OpenGL or Vulkan explicitly in Video
 * Settings (or Minecraft's own crash recovery, which forces OpenGL after an unclean start) skips Metal, as does
 * {@code -Dminemetal.disable=true}.
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {
    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void minemetal$preferMetal(final CallbackInfoReturnable<GpuBackend[]> cir) {
        // "Default" = Metal; choosing OpenGL or Vulkan explicitly gives exactly that backend.
        if (!MineMetalClient.isMetalEnabled()) {
            return;
        }
        if ((Object) this != PreferredGraphicsApi.DEFAULT) {
            // e.g. Minecraft forced OpenGL after a crashed startup: say so, otherwise this looks like a MineMetal run
            MineMetalClient.LOG.warn("[MineMetal] Graphics API is set to {}, not Default: MineMetal is NOT used this run"
                + " (Video Settings -> Graphics API -> Default)", this);
            return;
        }
        GpuBackend[] vanilla = cir.getReturnValue();
        GpuBackend[] withMetal = new GpuBackend[vanilla.length + 1];
        // -Dminemetal.backend=gl: Minecraft's OpenGL backend on MineMetal's OpenGL-on-Metal layer (A/B comparison)
        withMetal[0] = MineMetalClient.useGlTranslation() ? new MetalGlBackend() : new MetalBackend();
        System.arraycopy(vanilla, 0, withMetal, 1, vanilla.length);
        cir.setReturnValue(withMetal);
    }

    /** Video Settings shows "Default (Metal)" when Default is running MineMetal's Metal backend. */
    @Inject(method = "caption", at = @At("RETURN"), cancellable = true)
    private void minemetal$caption(final CallbackInfoReturnable<Component> cir) {
        // en_us also renames "Default" to "Default (Metal)" (that is what Sodium's settings screen shows); don't add it twice.
        if ((Object) this == PreferredGraphicsApi.DEFAULT && (MetalDevice.current() != null || MetalGl.isActive())
                && !cir.getReturnValue().getString().contains("Metal")) {
            cir.setReturnValue(Component.empty().append(cir.getReturnValue()).append(" (Metal)"));
        }
    }
}
