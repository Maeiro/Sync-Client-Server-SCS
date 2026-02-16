package com.scs.core;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod.EventBusSubscriber(modid = SCS.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.ConfigValue<Integer> FILE_SERVER_PORT = BUILDER
            .comment(
                    "Port number for the file server to run on",
                    "Default: 25566"
            )
            .define("fileServerPort", 25566);

    private static final ForgeConfigSpec.ConfigValue<Boolean> UPDATE_CONFIG = BUILDER
            .comment(
                    "If true, the client will also update the config folder when pressing the update button.",
                    "This downloads config.zip from the server and extracts it into /config.",
                    "Default: true"
            )
            .define("updateConfig", true);

    private static final ForgeConfigSpec.ConfigValue<Boolean> MIRROR_MODS = BUILDER
            .comment(
                    "If true, the client mods folder will be mirrored to mods.zip.",
                    "Any mod jar not present in mods.zip will be removed during update.",
                    "Default: false"
            )
            .define("mirrorMods", false);

    private static final ForgeConfigSpec.ConfigValue<Boolean> MIRROR_CONFIG = BUILDER
            .comment(
                    "If true, the client config folder will be mirrored to config.zip.",
                    "Any config file not present in config.zip will be removed during update.",
                    "Default: false"
            )
            .define("mirrorConfig", false);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int fileServerPort = 25566;
    public static boolean updateConfig = true;
    public static boolean mirrorMods = false;
    public static boolean mirrorConfig = false;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (!SCS.MODID.equals(event.getConfig().getModId())) {
            return;
        }

        fileServerPort = FILE_SERVER_PORT.get();
        updateConfig = UPDATE_CONFIG.get();
        mirrorMods = MIRROR_MODS.get();
        mirrorConfig = MIRROR_CONFIG.get();

        SCS.LOGGER.info("Configuration loaded:");
        SCS.LOGGER.info("File Server Port: {}", fileServerPort);
        SCS.LOGGER.info("Update Config: {}", updateConfig);
        SCS.LOGGER.info("Mirror Mods: {}", mirrorMods);
        SCS.LOGGER.info("Mirror Config: {}", mirrorConfig);

        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            try {
                com.scs.server.FileHostingServer.restartIfPortChanged();
            } catch (Exception e) {
                SCS.LOGGER.error("Failed to apply file server config changes.", e);
            }
        }
    }
}
