package com.jrxmod.lumiereplay;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LumierePlay implements ModInitializer {

    public static final String MOD_ID = "lumiereplay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Lumiere Play initializing...");

        ModBlocks.initialize();
        ModBlockEntities.initialize();
        ModItemGroups.initialize();
        ModNetworking.initialize();
    }
}
