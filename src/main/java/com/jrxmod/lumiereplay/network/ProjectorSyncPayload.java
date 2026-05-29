package com.jrxmod.lumiereplay.network;

import com.jrxmod.lumiereplay.LumierePlay;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Sent from server to all nearby clients when projector state changes.
 * Clients use this to start/stop/sync video playback on their side.
 */
public record ProjectorSyncPayload(
    BlockPos pos,
    String   videoUrl,
    boolean  isPlaying,
    int      volume,
    int      screenWidth,
    int      screenHeight
) implements CustomPayload {

    public static final CustomPayload.Id<ProjectorSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of(LumierePlay.MOD_ID, "projector_sync"));

    public static final PacketCodec<RegistryByteBuf, ProjectorSyncPayload> CODEC =
        PacketCodec.tuple(
            BlockPos.PACKET_CODEC,   ProjectorSyncPayload::pos,
            PacketCodecs.STRING,     ProjectorSyncPayload::videoUrl,
            PacketCodecs.BOOL,       ProjectorSyncPayload::isPlaying,
            PacketCodecs.INTEGER,    ProjectorSyncPayload::volume,
            PacketCodecs.INTEGER,    ProjectorSyncPayload::screenWidth,
            PacketCodecs.INTEGER,    ProjectorSyncPayload::screenHeight,
            ProjectorSyncPayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
