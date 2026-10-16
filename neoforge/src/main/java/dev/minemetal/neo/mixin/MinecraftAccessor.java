package dev.minemetal.neo.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("mainRenderTarget")
    RenderTarget minemetal$getMainRenderTarget();

    @Mutable
    @Accessor("mainRenderTarget")
    void minemetal$setMainRenderTarget(RenderTarget target);
}
