package com.scs.core;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(SCS.MODID)
public class SCS {

    public static final String MODID = "scs";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SCS() {
        LOGGER.info("Initializing SCS...");

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        LOGGER.info("SCS initialized.");

        MinecraftForge.EVENT_BUS.addListener(RegisterCommands::onRegisterCommands);
    }
}
