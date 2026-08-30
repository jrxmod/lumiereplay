package com.jrxmod.lumiereplay;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side bookkeeping for the shared playback timeline.
 *
 * Two jobs, both driven from the server tick so no block entity ticker is
 * needed:
 *
 *   - A player who starts tracking a playing projector is sent the full state,
 *     so walking back into range or logging in mid film starts playback at the
 *     shared position instead of leaving a black screen.
 *   - While a projector plays, the nominal media position is pushed to every
 *     tracking client that understands the clock channel. Viewers use it to
 *     correct their own drift.
 *
 * Projectors arm themselves from their state setters and drop out of the
 * registry as soon as the tracked block is gone, paused or cleared, so stale
 * positions cannot linger after a block is broken or a chunk unloads.
 */
public final class ProjectorClockService {

    /** How often newly tracking players are looked for, in ticks. */
    private static final int NOTIFY_INTERVAL_TICKS = 40;

    /**
     * How often the nominal clock is pushed to viewers, in ticks. Kept close to
     * the client side check interval so a correction never works from a stale
     * value, and the anchor is folded back in on the same pass.
     */
    private static final int CLOCK_INTERVAL_TICKS = 100;

    /** Dimension and position of a playing projector, mapped to the viewers already told about it. */
    private static final Map<RegistryKey<World>, Map<BlockPos, Set<UUID>>> ARMED = new ConcurrentHashMap<>();

    private static int tickCounter = 0;

    private ProjectorClockService() {}

    /**
     * Hooks the service into the server lifecycle. Must run once from the
     * common initializer, after the payload types are registered.
     */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ProjectorClockService::onEndTick);
        LumierePlay.LOGGER.info("Registering Lumiere Play projector clock service...");
    }

    /**
     * Adds or removes a projector from the clock registry depending on whether
     * it currently has a source and is playing. Safe to call on every state
     * change; the operation is idempotent.
     */
    public static void arm(ProjectorBlockEntity projector) {
        World world = projector.getWorld();
        if (world == null || world.isClient()) return;

        RegistryKey<World> dim = world.getRegistryKey();
        BlockPos           pos = projector.getPos();

        if (projector.isPlaying() && !projector.getVideoUrl().isEmpty()) {
            ARMED.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                 .putIfAbsent(pos, ConcurrentHashMap.newKeySet());
        } else {
            Map<BlockPos, Set<UUID>> perDim = ARMED.get(dim);
            if (perDim != null) {
                perDim.remove(pos);
                if (perDim.isEmpty()) ARMED.remove(dim, perDim);
            }
        }
    }

    private static void onEndTick(MinecraftServer server) {
        tickCounter++;
        boolean notifyTick = tickCounter % NOTIFY_INTERVAL_TICKS == 0;
        boolean clockTick  = tickCounter % CLOCK_INTERVAL_TICKS == 0;
        if (!notifyTick && !clockTick) return;

        for (Map.Entry<RegistryKey<World>, Map<BlockPos, Set<UUID>>> dimEntry : ARMED.entrySet()) {
            ServerWorld world = server.getWorld(dimEntry.getKey());
            Map<BlockPos, Set<UUID>> projectors = dimEntry.getValue();

            if (world == null) {
                ARMED.remove(dimEntry.getKey());
                continue;
            }

            Iterator<Map.Entry<BlockPos, Set<UUID>>> it = projectors.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Set<UUID>> entry = it.next();
                BlockPos pos = entry.getKey();

                if (!(world.getBlockEntity(pos) instanceof ProjectorBlockEntity projector)
                        || !projector.isPlaying()
                        || projector.getVideoUrl().isEmpty()) {
                    it.remove();
                    continue;
                }

                Set<ServerPlayerEntity> viewers = new HashSet<>(PlayerLookup.tracking(world, pos));

                if (notifyTick) {
                    Set<UUID> tracked = new HashSet<>();
                    for (ServerPlayerEntity viewer : viewers) tracked.add(viewer.getUuid());

                    // A viewer that stopped tracking the chunk must be notified again on return.
                    entry.getValue().retainAll(tracked);

                    for (ServerPlayerEntity viewer : viewers) {
                        if (entry.getValue().add(viewer.getUuid())) {
                            ModNetworking.sendState(projector, viewer);
                        }
                    }
                }

                if (clockTick) {
                    // Re-anchor first: the pushed value then equals the stored one,
                    // and the extrapolation window starts over instead of growing
                    // until it is clamped.
                    projector.commitTimeline();
                    ModNetworking.sendClock(projector, world);
                }
            }

            if (projectors.isEmpty()) ARMED.remove(dimEntry.getKey(), projectors);
        }
    }
}
