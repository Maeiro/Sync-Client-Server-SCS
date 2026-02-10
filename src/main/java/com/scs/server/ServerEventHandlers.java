package com.scs.server;

import com.scs.core.Config;
import com.scs.core.SCS;
import net.minecraft.server.MinecraftServer;

public final class ServerEventHandlers {

    private ServerEventHandlers() {
    }

    public static void onServerStarted(MinecraftServer server) {
        if (server == null || !server.isDedicatedServer()) {
            return;
        }

        Config.reload();
        try {
            SCS.LOGGER.info("Starting SCS file hosting server for dedicated server lifecycle.");
            FileHostingServer.start();
        } catch (Exception e) {
            SCS.LOGGER.error("Failed to start file hosting server", e);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        if (server == null || !server.isDedicatedServer()) {
            return;
        }

        FileHostingServer.stop();
    }
}
