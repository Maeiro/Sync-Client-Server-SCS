package com.scs.fabric;

import com.scs.core.Config;
import com.scs.core.RegisterCommands;
import com.scs.server.ServerEventHandlers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class SCSFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Config.load();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                RegisterCommands.register(dispatcher)
        );

        ServerLifecycleEvents.SERVER_STARTED.register(ServerEventHandlers::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerEventHandlers::onServerStopping);
    }
}
