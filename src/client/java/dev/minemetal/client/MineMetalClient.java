package dev.minemetal.client;

import dev.minemetal.client.glcompat.MetalGLSelfTest;
import dev.minemetal.client.metal.MetalBackend;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MineMetalClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("MineMetal");

    public static boolean isMetalEnabled() {
        return MetalBackend.isSupportedPlatform() && !Boolean.getBoolean("minemetal.disable");
    }

    @Override
    public void onInitializeClient() {
        LOG.info("MineMetal loaded (os={} {}, arch={}); Metal backend {}",
            System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
            isMetalEnabled() ? "enabled" : "disabled");
        if (Boolean.getBoolean("minemetal.glSelfTest")) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> MetalGLSelfTest.run());
        }
    }
}
