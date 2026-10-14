package dev.minemetal.client.compat;

import dev.minemetal.client.upscale.MetalUpscaler;
import dev.minemetal.client.upscale.UpscaleMode;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Sodium replaces Video Settings with its own screen; this adds a "MineMetal" page there (same settings as the vanilla
 * Video Settings section). Only loaded by Sodium through the {@code sodium:config_api_user} entrypoint.
 */
public final class MineMetalSodiumConfig implements ConfigEntryPoint {
    private static Identifier id(final String path) {
        return Identifier.fromNamespaceAndPath("minemetal", path);
    }

    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        builder.registerOwnModOptions()
            .setName("MineMetal")
            .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xFF8FB8DE))
            .addPage(builder.createOptionPage()
                .setName(Component.literal("Upscaling"))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createEnumOption(id("upscaler"), UpscaleMode.class)
                        .setName(Component.translatable("minemetal.options.upscaler"))
                        .setTooltip(Component.translatable("minemetal.options.upscaler.tooltip"))
                        .setElementNameProvider(m -> Component.literal(m.label))
                        .setImpact(OptionImpact.VARIES)
                        .setDefaultValue(UpscaleMode.OFF)
                        .setBinding(MetalUpscaler::setMode, MetalUpscaler::mode)
                        .setStorageHandler(() -> { }))
                    .addOption(builder.createIntegerOption(id("render_scale"))
                        .setName(Component.translatable("minemetal.options.renderScale"))
                        .setTooltip(Component.translatable("minemetal.options.renderScale.tooltip"))
                        .setImpact(OptionImpact.HIGH)
                        .setRange(MetalUpscaler.MIN_SCALE, MetalUpscaler.MAX_SCALE, 1)
                        .setValueFormatter(v -> Component.literal(v + "%"))
                        .setDefaultValue(75)
                        .setBinding(MetalUpscaler::setScalePercent, MetalUpscaler::scalePercent)
                        .setStorageHandler(() -> { }))
                    .addOption(builder.createIntegerOption(id("sharpness"))
                        .setName(Component.translatable("minemetal.options.sharpness"))
                        .setTooltip(Component.translatable("minemetal.options.sharpness.tooltip"))
                        .setImpact(OptionImpact.LOW)
                        .setRange(0, 100, 5)
                        .setValueFormatter(v -> Component.literal(v + "%"))
                        .setDefaultValue(80)
                        .setBinding(MetalUpscaler::setSharpnessPercent, MetalUpscaler::sharpnessPercent)
                        .setStorageHandler(() -> { }))));
    }
}
