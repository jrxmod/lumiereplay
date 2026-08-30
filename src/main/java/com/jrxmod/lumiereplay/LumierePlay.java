package com.jrxmod.lumiereplay;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LumierePlay implements ModInitializer {

    public static final String MOD_ID = "lumiereplay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Quality key stored with a projector that has never been configured.
     * The server only relays this string, the client resolves it against its own
     * quality list, so an unknown or absent key falls back to the same default.
     */
    public static final String DEFAULT_QUALITY_KEY = "720";

    @Override
    public void onInitialize() {
        LOGGER.info("Lumiere Play initializing...");

        ModBlocks.initialize();
        ModBlockEntities.initialize();
        ModItemGroups.initialize();
        ModNetworking.initialize();
        ProjectorClockService.register();
    }
}
