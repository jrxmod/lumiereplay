package com.jrxmod.lumiereplay.network;

import com.jrxmod.lumiereplay.LumierePlay;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Sent from client to server when a player submits changes in the projector GUI.
 * Carries the full state: URL, play flag, volume, and screen dimensions.
 */
public record ProjectorUpdatePayload(
    BlockPos pos,
    String   videoUrl,
    boolean  isPlaying,
    int      volume,
    int      screenWidth,
    int      screenHeight
) implements CustomPayload {

    public static final CustomPayload.Id<ProjectorUpdatePayload> ID =
        new CustomPayload.Id<>(Identifier.of(LumierePlay.MOD_ID, "projector_update"));

    public static final PacketCodec<RegistryByteBuf, ProjectorUpdatePayload> CODEC =
        PacketCodec.tuple(
            BlockPos.PACKET_CODEC,    ProjectorUpdatePayload::pos,
            PacketCodecs.STRING,      ProjectorUpdatePayload::videoUrl,
            PacketCodecs.BOOL,        ProjectorUpdatePayload::isPlaying,
            PacketCodecs.INTEGER,     ProjectorUpdatePayload::volume,
            PacketCodecs.INTEGER,     ProjectorUpdatePayload::screenWidth,
            PacketCodecs.INTEGER,     ProjectorUpdatePayload::screenHeight,
            ProjectorUpdatePayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
