package com.scs.core;

import com.moandjiezana.toml.Toml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class Config {

    private static final Path CONFIG_FILE = Path.of("config", "scs-common.toml");

    private static final int DEFAULT_FILE_SERVER_PORT = 25566;
    private static final boolean DEFAULT_UPDATE_CONFIG = true;
    private static final boolean DEFAULT_MIRROR_MODS = false;
    private static final boolean DEFAULT_MIRROR_CONFIG = false;

    public static volatile int fileServerPort = DEFAULT_FILE_SERVER_PORT;
    public static volatile boolean updateConfig = DEFAULT_UPDATE_CONFIG;
    public static volatile boolean mirrorMods = DEFAULT_MIRROR_MODS;
    public static volatile boolean mirrorConfig = DEFAULT_MIRROR_CONFIG;

    private Config() {
    }

    public static synchronized void load() {
        try {
            ensureConfigFileExists();
            Toml toml = new Toml().read(CONFIG_FILE.toFile());

            fileServerPort = sanitizePort(readInt(toml, "fileServerPort", DEFAULT_FILE_SERVER_PORT));
            updateConfig = readBoolean(toml, "updateConfig", DEFAULT_UPDATE_CONFIG);
            mirrorMods = readBoolean(toml, "mirrorMods", DEFAULT_MIRROR_MODS);
            mirrorConfig = readBoolean(toml, "mirrorConfig", DEFAULT_MIRROR_CONFIG);

            SCS.LOGGER.info(
                    "Config loaded: fileServerPort={}, updateConfig={}, mirrorMods={}, mirrorConfig={}",
                    fileServerPort,
                    updateConfig,
                    mirrorMods,
                    mirrorConfig
            );
        } catch (Exception e) {
            SCS.LOGGER.error("Failed to load config from {}. Using defaults.", CONFIG_FILE, e);
            fileServerPort = DEFAULT_FILE_SERVER_PORT;
            updateConfig = DEFAULT_UPDATE_CONFIG;
            mirrorMods = DEFAULT_MIRROR_MODS;
            mirrorConfig = DEFAULT_MIRROR_CONFIG;
        }
    }

    public static synchronized void reload() {
        load();
    }

    private static int sanitizePort(int value) {
        return value > 0 && value <= 65535 ? value : DEFAULT_FILE_SERVER_PORT;
    }

    private static int readInt(Toml toml, String key, int fallback) {
        Long numeric = toml.getLong(key);
        if (numeric != null) {
            return numeric.intValue();
        }

        String text = toml.getString(key);
        if (text != null) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        return fallback;
    }

    private static boolean readBoolean(Toml toml, String key, boolean fallback) {
        Boolean bool = toml.getBoolean(key);
        if (bool != null) {
            return bool;
        }

        String text = toml.getString(key);
        if (text != null) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
        }

        return fallback;
    }

    private static void ensureConfigFileExists() throws IOException {
        Path parent = CONFIG_FILE.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (Files.exists(CONFIG_FILE)) {
            return;
        }

        String content = """
                # Sync Client Server (SCS) common config
                # Port used by the integrated file hosting server.
                fileServerPort = 25566

                # If true, client update also applies config.zip.
                updateConfig = true

                # If true, mirror client /mods with mods.zip exactly.
                mirrorMods = false

                # If true, mirror client /config with config.zip exactly.
                mirrorConfig = false
                """;

        Files.writeString(
                CONFIG_FILE,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        SCS.LOGGER.info("Created default config at {}", CONFIG_FILE);
    }
}
