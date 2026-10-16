package dev.minemetal.neo.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.minemetal.neo.metal.MetalBootstrap;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Creates the game window without an OpenGL context and boots MineMetal in place of GL.createCapabilities. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow @Final private long window;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V", remap = false))
    private void minemetal$windowHint(int hint, int value) {
        if (hint == GLFW.GLFW_CLIENT_API) value = GLFW.GLFW_NO_API;
        GLFW.glfwWindowHint(hint, value);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V", remap = false))
    private void minemetal$noContext(long handle) {
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;", remap = false))
    private GLCapabilities minemetal$bootstrap() {
        return MetalBootstrap.init(this.window);
    }

    @Redirect(method = "updateVsync", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapInterval(I)V", remap = false))
    private void minemetal$vsync(int interval) {
        MetalBootstrap.setVsync(interval != 0);
    }
}
