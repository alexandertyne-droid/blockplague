package com.blockplague;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockPlagueMod implements ModInitializer {
    public static final String MOD_ID = "blockplague";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Block Plague initializing...");

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            PlagueCommands.register(dispatcher, registryAccess);
        });

        // Register server tick event for spreading
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            PlagueManager.getInstance().tick(server);
        });

        LOGGER.info("Block Plague initialized!");
    }
}
