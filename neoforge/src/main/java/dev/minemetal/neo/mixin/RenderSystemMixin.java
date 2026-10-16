package dev.minemetal.neo.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.minemetal.neo.metal.MetalBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Presents through the CAMetalLayer instead of glfwSwapBuffers. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false))
    private static void minemetal$present(long window) {
        MetalBootstrap.present(window);
    }
}
