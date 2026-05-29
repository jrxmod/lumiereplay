package com.jrxmod.lumiereplay.client.sound;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Computes effective volume for a projector based on player distance.
 * Used by VideoPlayer to set VLC audio volume with spatial attenuation.
 */
public class ProjectorSound {

    private static final float FULL_VOLUME_RADIUS = 8f;
    private static final float MAX_RADIUS         = 32f;

    private static final Map<BlockPos, Integer> baseVolumes = new HashMap<>();

    public static void setBaseVolume(BlockPos pos, int volume) {
        baseVolumes.put(pos.toImmutable(), volume);
    }

    public static void remove(BlockPos pos) {
        baseVolumes.remove(pos.toImmutable());
    }

    public static float getEffectiveVolume(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0f;

        Integer base = baseVolumes.get(pos);
        if (base == null || base == 0) return 0f;

        Vec3d center  = Vec3d.ofCenter(pos);
        double dist   = client.player.getPos().distanceTo(center);

        float spatial;
        if (dist <= FULL_VOLUME_RADIUS) {
            spatial = 1.0f;
        } else if (dist >= MAX_RADIUS) {
            spatial = 0.0f;
        } else {
            spatial = 1.0f - (float)(dist - FULL_VOLUME_RADIUS) / (MAX_RADIUS - FULL_VOLUME_RADIUS);
        }

        return (base / 100f) * spatial;
    }

    public static void clearAll() {
        baseVolumes.clear();
    }
}
