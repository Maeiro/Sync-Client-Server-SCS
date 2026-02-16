package com.scs.server;

import com.scs.core.SCS;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(modid = SCS.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.DEDICATED_SERVER)
public class ServerEventHandlers {

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        try {
            SCS.LOGGER.info("Performing common setup tasks.");
            FileHostingServer.start();
        } catch (Exception e) {
            SCS.LOGGER.error("Failed to start file hosting server: ", e);
        }
    }
}
