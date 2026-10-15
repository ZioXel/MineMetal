package dev.minemetal.neo;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MineMetal for NeoForge 1.21.1 (early development). */
@Mod("minemetal")
public final class MineMetalNeo {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal");

    public MineMetalNeo() {
        LOGGER.info("[MineMetal] NeoForge 1.21.1 port loaded (Java {})", Runtime.version());
    }
}
