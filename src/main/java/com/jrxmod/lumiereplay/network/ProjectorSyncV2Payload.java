package com.jrxmod.lumiereplay.network;

import com.jrxmod.lumiereplay.AccessMode;
import com.jrxmod.lumiereplay.LumierePlay;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Server to client projector state, second wire format.
 *
 * Extends the legacy {@link ProjectorSyncPayload} with the quality key chosen by
 * the controlling client and with the nominal media position of the shared
 * timeline. Clients that also understand this packet keep every viewer on the
 * same timestamp; clients that only know the legacy packet get the same state
 * minus the clock, which behaves like older releases.
 *
 * positionMs is the media position in milliseconds already advanced to the
 * moment the packet was sent. It is 0 for sources without a known timeline and
 * for live streams, where no viewer can be realigned anyway.
 */
public record ProjectorSyncV2Payload(
    BlockPos   pos,
    String     videoUrl,
    boolean    isPlaying,
    int        volume,
    int        screenWidth,
    int        screenHeight,
    AccessMode accessMode,
    String     qualityKey,
    long       positionMs
) implements CustomPayload {

    public static final CustomPayload.Id<ProjectorSyncV2Payload> ID =
        new CustomPayload.Id<>(Identifier.of(LumierePlay.MOD_ID, "projector_sync_v2"));

    public static final PacketCodec<RegistryByteBuf, ProjectorSyncV2Payload> CODEC =
        PacketCodec.of(
            (value, buf) -> {
                BlockPos.PACKET_CODEC.encode(buf, value.pos());
                buf.writeString(value.videoUrl());
                buf.writeBoolean(value.isPlaying());
                buf.writeInt(value.volume());
                buf.writeInt(value.screenWidth());
                buf.writeInt(value.screenHeight());
                buf.writeInt(value.accessMode().ordinal());
                buf.writeString(value.qualityKey());
                buf.writeLong(value.positionMs());
            },
            buf -> new ProjectorSyncV2Payload(
                BlockPos.PACKET_CODEC.decode(buf),
                buf.readString(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                AccessMode.values()[Math.min(buf.readInt(), AccessMode.values().length - 1)],
                buf.readString(),
                Math.max(0L, buf.readLong())
            )
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
