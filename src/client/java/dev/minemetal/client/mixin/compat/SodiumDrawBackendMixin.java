package dev.minemetal.client.mixin.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium picks its terrain draw path with {@code instanceof VulkanDevice}; anything else gets the OpenGL path.
 * On Metal we want its Vulkan "ext_multidraw" path: it only uses renderpearl's interleaved multi-draw, which the
 * Metal backend implements natively. Applied only when Sodium is installed (see MineMetalMixinPlugin).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend", remap = false)
public abstract class SodiumDrawBackendMixin {
    @Inject(method = "chooseBackend", at = @At("HEAD"), cancellable = true, remap = false)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void minemetal$useMultidrawOnMetal(final CallbackInfoReturnable<Object> cir) {
        if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName())) {
            return;
        }
        try {
            Class enumClass = Class.forName("net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend");
            cir.setReturnValue(Enum.valueOf(enumClass, "VK_MULTIDRAW"));
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            org.slf4j.LoggerFactory.getLogger("MineMetal").error("Could not select Sodium's multidraw path on Metal", e);
        }
    }
}
