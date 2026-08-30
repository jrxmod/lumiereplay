package com.jrxmod.lumiereplay.client.net;

import com.jrxmod.lumiereplay.AccessMode;
import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.ProjectorBlockEntity;
import com.jrxmod.lumiereplay.network.ProjectorUpdatePayload;
import com.jrxmod.lumiereplay.network.ProjectorUpdateV2Payload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * Single place where the client submits projector commands.
 *
 * The v2 command carries the quality choice and an optional seek. It is only
 * sent when the connected server declared that channel, which is discovered at
 * runtime, so a 0.7.0 client joining a server that still runs an older version
 * keeps working through the legacy packet instead of having its commands
 * silently dropped by the decode mismatch.
 */
public final class ProjectorClientNet {

    private ProjectorClientNet() {}

    /**
     * Submits the full desired state.
     *
     * seekMs is an absolute media position in milliseconds, or
     * {@link ProjectorUpdateV2Payload#NO_SEEK} when the sender does not ask for a
     * jump. It is ignored on the legacy wire format.
     */
    public static void send(BlockPos pos, String url, boolean playing, int volume,
                            int width, int height, AccessMode access,
                            String qualityKey, long seekMs) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) return;

        AccessMode mode = access != null ? access : AccessMode.ALL;

        if (ClientPlayNetworking.canSend(ProjectorUpdateV2Payload.ID)) {
            ClientPlayNetworking.send(new ProjectorUpdateV2Payload(
                pos, url, playing, volume, width, height, mode, qualityKey, seekMs));
        } else {
            if (seekMs != ProjectorUpdateV2Payload.NO_SEEK) {
                LumierePlay.LOGGER.debug(
                    "Server does not accept seek commands, jumping locally only at {}", pos);
            }
            ClientPlayNetworking.send(new ProjectorUpdatePayload(
                pos, url, playing, volume, width, height, mode));
        }
    }

    /** Submits state without touching the shared timeline. */
    public static void send(BlockPos pos, String url, boolean playing, int volume,
                            int width, int height, AccessMode access, String qualityKey) {
        send(pos, url, playing, volume, width, height, access, qualityKey,
            ProjectorUpdateV2Payload.NO_SEEK);
    }

    /**
     * Submits a seek using the state a client already knows about a projector.
     * Used by paths that have no GUI open, such as realigning the shared timeline
     * when a loopable video reaches its end.
     */
    public static void sendSeek(BlockPos pos, long seekMs, ProjectorBlockEntity projector) {
        send(pos,
            projector.getVideoUrl(),
            projector.isPlaying(),
            projector.getVolume(),
            projector.getScreenWidth(),
            projector.getScreenHeight(),
            projector.getAccessMode(),
            projector.getQualityKey(),
            seekMs);
    }
}
