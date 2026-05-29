package com.jrxmod.lumiereplay;

import com.jrxmod.lumiereplay.network.ProjectorSyncPayload;
import com.jrxmod.lumiereplay.network.ProjectorUpdatePayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class ModNetworking {

    /**
     * Registers all payload types and server-side packet handlers.
     * Must be called from the common initializer before any packet is sent.
     */
    public static void initialize() {
        // Register payload types for both directions
        PayloadTypeRegistry.playC2S().register(ProjectorUpdatePayload.ID, ProjectorUpdatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ProjectorSyncPayload.ID,   ProjectorSyncPayload.CODEC);

        // Handle incoming update from a client player
        ServerPlayNetworking.registerGlobalReceiver(ProjectorUpdatePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                BlockPos pos = payload.pos();
                ServerWorld world = context.player().getServerWorld();

                if (!(world.getBlockEntity(pos) instanceof ProjectorBlockEntity projector)) return;

                // Validate that the player is within interaction range (8 blocks)
                if (context.player().squaredDistanceTo(pos.toCenterPos()) > 64.0) return;

                // Apply the state received from the player GUI
                projector.setVideoUrl(payload.videoUrl());
                projector.setPlaying(payload.isPlaying());
                projector.setVolume(payload.volume());
                projector.setScreenSize(payload.screenWidth(), payload.screenHeight());

                // Build sync packet and broadcast to all nearby clients
                ProjectorSyncPayload sync = new ProjectorSyncPayload(
                    pos,
                    payload.videoUrl(),
                    payload.isPlaying(),
                    payload.volume(),
                    payload.screenWidth(),
                    payload.screenHeight()
                );

                for (var player : PlayerLookup.tracking(world, pos)) {
                    ServerPlayNetworking.send(player, sync);
                }
            });
        });

        LumierePlay.LOGGER.info("Registering Lumiere Play networking...");
    }
}
