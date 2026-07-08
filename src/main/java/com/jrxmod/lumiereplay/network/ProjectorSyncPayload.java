package com.jrxmod.lumiereplay.network;

import com.jrxmod.lumiereplay.AccessMode;
import com.jrxmod.lumiereplay.LumierePlay;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Sent from server to all nearby clients when projector state changes.
 * Clients use this to start/stop/sync video playback on their side.
 */
public record ProjectorSyncPayload(
    BlockPos   pos,
    String     videoUrl,
    boolean    isPlaying,
    int        volume,
    int        screenWidth,
    int        screenHeight,
    AccessMode accessMode
) implements CustomPayload {

    public static final CustomPayload.Id<ProjectorSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of(LumierePlay.MOD_ID, "projector_sync"));

    public static final PacketCodec<RegistryByteBuf, ProjectorSyncPayload> CODEC =
        PacketCodec.of(
            (value, buf) -> {
                BlockPos.PACKET_CODEC.encode(buf, value.pos());
                buf.writeString(value.videoUrl());
                buf.writeBoolean(value.isPlaying());
                buf.writeInt(value.volume());
                buf.writeInt(value.screenWidth());
                buf.writeInt(value.screenHeight());
                buf.writeInt(value.accessMode().ordinal());
            },
            buf -> new ProjectorSyncPayload(
                BlockPos.PACKET_CODEC.decode(buf),
                buf.readString(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                AccessMode.values()[Math.min(buf.readInt(), AccessMode.values().length - 1)]
            )
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
