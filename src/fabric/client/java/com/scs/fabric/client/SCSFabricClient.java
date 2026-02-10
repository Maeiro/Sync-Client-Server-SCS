package com.scs.fabric.client;

import com.scs.client.ClientEventHandlers;
import net.fabricmc.api.ClientModInitializer;

public final class SCSFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientEventHandlers.registerClientEvents();
    }
}
