package dev.minemetal.neo.gl.compile;

import java.nio.file.Path;

/**
 * The game directory on either loader: this GL layer is compiled into the NeoForge 1.21.1 jar and the Fabric 26.3 jar,
 * so the loader API is looked up reflectively.
 */
public final class GameDir {
    private static Path dir;

    private GameDir() {
    }

    public static synchronized Path get() {
        if (dir == null) dir = find();
        return dir;
    }

    private static Path find() {
        try { // Fabric
            Class<?> c = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = c.getMethod("getInstance").invoke(null);
            return (Path) c.getMethod("getGameDir").invoke(loader);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        try { // NeoForge
            Class<?> c = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Object gameDir = c.getField("GAMEDIR").get(null);
            return (Path) c.getMethod("get").invoke(gameDir);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return Path.of("").toAbsolutePath();
    }
}
