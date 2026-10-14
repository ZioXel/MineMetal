package dev.minemetal.client.mixin;

import com.mojang.serialization.Codec;
import dev.minemetal.client.metal.MetalDevice;
import dev.minemetal.client.upscale.MetalUpscaler;
import dev.minemetal.client.upscale.UpscaleMode;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/** Adds a "MineMetal" section (upscaler, render scale, sharpness) to Video Settings while Metal is running. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    private VideoSettingsScreenMixin(final Screen lastScreen, final Options options, final Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void minemetal$addOptions(final CallbackInfo ci) {
        if (MetalDevice.current() == null || this.list == null) {
            return;
        }
        OptionInstance<UpscaleMode> upscaler = new OptionInstance<>(
            "minemetal.options.upscaler",
            value -> Tooltip.create(Component.translatable("minemetal.options.upscaler.tooltip")),
            (caption, value) -> Component.translatable("options.generic_value", caption, Component.literal(value.label)),
            new OptionInstance.Enum<>(Arrays.asList(UpscaleMode.values()), Codec.STRING.xmap(UpscaleMode::parse, m -> m.name().toLowerCase())),
            MetalUpscaler.mode(),
            MetalUpscaler::setMode);
        OptionInstance<Integer> scale = new OptionInstance<>(
            "minemetal.options.renderScale",
            value -> Tooltip.create(Component.translatable("minemetal.options.renderScale.tooltip")),
            (caption, value) -> Component.translatable("options.generic_value", caption, Component.literal(value + "%")),
            new OptionInstance.IntRange(MetalUpscaler.MIN_SCALE, MetalUpscaler.MAX_SCALE, false),
            MetalUpscaler.scalePercent(),
            MetalUpscaler::setScalePercent);
        OptionInstance<Integer> sharpness = new OptionInstance<>(
            "minemetal.options.sharpness",
            value -> Tooltip.create(Component.translatable("minemetal.options.sharpness.tooltip")),
            (caption, value) -> Component.translatable("options.generic_value", caption, Component.literal(value + "%")),
            new OptionInstance.IntRange(0, 100, false),
            MetalUpscaler.sharpnessPercent(),
            MetalUpscaler::setSharpnessPercent);
        this.list.addHeader(Component.translatable("minemetal.options.header"));
        this.list.addSmall(upscaler, scale, sharpness);
    }
}
