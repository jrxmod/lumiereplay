package com.jrxmod.lumiereplay;

import com.jrxmod.lumiereplay.AccessMode;
import com.jrxmod.lumiereplay.network.ProjectorClockPayload;
import com.jrxmod.lumiereplay.network.ProjectorSyncPayload;
import com.jrxmod.lumiereplay.network.ProjectorSyncV2Payload;
import com.jrxmod.lumiereplay.network.ProjectorUpdatePayload;
import com.jrxmod.lumiereplay.network.ProjectorUpdateV2Payload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class ModNetworking {

    /** Upper bound for a client requested seek: 8 hours of media. */
    private static final long MAX_SEEK_MS = 8L * 60 * 60 * 1000;

    /** Interaction range of 8 blocks, squared. Same limit older versions used. */
    private static final double MAX_INTERACTION_DISTANCE_SQ = 64.0;

    private ModNetworking() {}

    /**
     * Registers all payload types and server-side packet handlers.
     * Must be called from the common initializer before any packet is sent.
     *
     * Two wire formats of the projector command exist on purpose: the v2 packet
     * carries quality and an optional seek, the legacy packet keeps the pre-0.7.0
     * layout so a mixed version pair degrades to older behaviour instead of
     * failing to decode. The matching rule is the same in both directions and is
     * resolved with canSend, per player.
     */
    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(ProjectorUpdatePayload.ID,   ProjectorUpdatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ProjectorUpdateV2Payload.ID, ProjectorUpdateV2Payload.CODEC);
        PayloadTypeRegistry.playS2C().register(ProjectorSyncPayload.ID,      ProjectorSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ProjectorSyncV2Payload.ID,   ProjectorSyncV2Payload.CODEC);
        PayloadTypeRegistry.playS2C().register(ProjectorClockPayload.ID,     ProjectorClockPayload.CODEC);

        // Legacy client command: state without quality and without a seek request.
        ServerPlayNetworking.registerGlobalReceiver(ProjectorUpdatePayload.ID, (payload, context) ->
            context.server().execute(() -> {
                ProjectorBlockEntity projector = resolveTarget(context.player(), payload.pos());
                if (projector == null) return;

                applyState(projector, context.player(),
                    payload.videoUrl(), payload.isPlaying(), payload.volume(),
                    payload.screenWidth(), payload.screenHeight(), payload.accessMode());

                broadcastState(projector, context.player().getServerWorld());
            }));

        // Current client command: same state plus quality and an optional jump.
        ServerPlayNetworking.registerGlobalReceiver(ProjectorUpdateV2Payload.ID, (payload, context) ->
            context.server().execute(() -> {
                ProjectorBlockEntity projector = resolveTarget(context.player(), payload.pos());
                if (projector == null) return;

                applyState(projector, context.player(),
                    payload.videoUrl(), payload.isPlaying(), payload.volume(),
                    payload.screenWidth(), payload.screenHeight(), payload.accessMode());

                projector.setQualityKey(payload.qualityKey());

                if (payload.seekMs() != ProjectorUpdateV2Payload.NO_SEEK) {
                    projector.seekTo(Math.min(Math.max(0L, payload.seekMs()), MAX_SEEK_MS));
                }

                broadcastState(projector, context.player().getServerWorld());
            }));

        LumierePlay.LOGGER.info("Registering Lumiere Play networking...");
    }

    /**
     * Looks up the projector addressed by a command and rejects the command when
     * the sender is out of interaction range or not allowed to control it.
     * Rejected packets are dropped silently apart from a warning on access denial.
     */
    private static ProjectorBlockEntity resolveTarget(ServerPlayerEntity player, BlockPos pos) {
        ServerWorld world = player.getServerWorld();
        if (!(world.getBlockEntity(pos) instanceof ProjectorBlockEntity projector)) return null;

        if (player.squaredDistanceTo(pos.toCenterPos()) > MAX_INTERACTION_DISTANCE_SQ) return null;

        if (!projector.canInteract(player)) {
            LumierePlay.LOGGER.warn("Player {} denied access to projector at {}",
                player.getName().getString(), pos);
            return null;
        }
        return projector;
    }

    /**
     * Applies one client command to the block entity. Order matters: the source
     * is written first because a changed source resets the shared timeline, and
     * play state follows so the timeline is anchored around the new source.
     */
    private static void applyState(ProjectorBlockEntity projector, ServerPlayerEntity player,
                                   String url, boolean playing, int volume,
                                   int width, int height, AccessMode access) {
        projector.setVideoUrl(url);
        projector.setPlaying(playing);
        projector.setVolume(volume);
        projector.setScreenSize(width, height);

        // Only the player who placed the block may change who else controls it.
        if (projector.isOwner(player)) projector.setAccessMode(access);
    }

    /**
     * Sends the full projector state to one player, choosing the wire format the
     * receiving client declared. A client without the v2 channel gets the legacy
     * layout, which it can decode and act on exactly as in older releases.
     */
    public static void sendState(ProjectorBlockEntity projector, ServerPlayerEntity player) {
        BlockPos pos = projector.getPos();

        if (ServerPlayNetworking.canSend(player, ProjectorSyncV2Payload.ID)) {
            ServerPlayNetworking.send(player, new ProjectorSyncV2Payload(
                pos,
                projector.getVideoUrl(),
                projector.isPlaying(),
                projector.getVolume(),
                projector.getScreenWidth(),
                projector.getScreenHeight(),
                projector.getAccessMode(),
                projector.getQualityKey(),
                projector.nominalPositionMs()
            ));
        } else {
            ServerPlayNetworking.send(player, new ProjectorSyncPayload(
                pos,
                projector.getVideoUrl(),
                projector.isPlaying(),
                projector.getVolume(),
                projector.getScreenWidth(),
                projector.getScreenHeight(),
                projector.getAccessMode()
            ));
        }
    }

    /** Sends the current state to everyone tracking the block. */
    public static void broadcastState(ProjectorBlockEntity projector, ServerWorld world) {
        for (var player : PlayerLookup.tracking(world, projector.getPos())) {
            sendState(projector, player);
        }
    }

    /**
     * Clears the projector state for everyone tracking the position. Used when
     * the block is removed or replaced, so no client keeps rendering a screen
     * whose block is gone.
     */
    public static void sendClear(ServerWorld world, BlockPos pos) {
        for (var player : PlayerLookup.tracking(world, pos)) {
            if (ServerPlayNetworking.canSend(player, ProjectorSyncV2Payload.ID)) {
                ServerPlayNetworking.send(player, new ProjectorSyncV2Payload(
                    pos, "", false, 0, 16, 9, AccessMode.ALL,
                    LumierePlay.DEFAULT_QUALITY_KEY, 0L));
            } else {
                ServerPlayNetworking.send(player, new ProjectorSyncPayload(
                    pos, "", false, 0, 16, 9, AccessMode.ALL));
            }
        }
    }

    /**
     * Pushes the shared timeline to viewers that declared the clock channel.
     * Viewers decide themselves whether the gap is worth seeking over.
     */
    public static void sendClock(ProjectorBlockEntity projector, ServerWorld world) {
        BlockPos pos = projector.getPos();
        for (var player : PlayerLookup.tracking(world, pos)) {
            if (ServerPlayNetworking.canSend(player, ProjectorClockPayload.ID)) {
                ServerPlayNetworking.send(player,
                    new ProjectorClockPayload(pos, projector.nominalPositionMs(), projector.isPlaying()));
            }
        }
    }
}
