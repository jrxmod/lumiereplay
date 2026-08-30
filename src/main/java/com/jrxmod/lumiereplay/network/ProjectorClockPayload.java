package com.jrxmod.lumiereplay.network;

import com.jrxmod.lumiereplay.LumierePlay;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Server to client update of the shared media timeline for one projector.
 *
 * Carries nothing but the clock so that receiving it cannot restart a stream or
 * touch the screen texture. Viewers compare it against their own playback
 * position and only act when the gap is larger than their local threshold.
 *
 * Sent only to clients that declared this channel, which keeps the packet away
 * from older versions of the mod.
 */
public record ProjectorClockPayload(
    BlockPos pos,
    long     positionMs,
    boolean  isPlaying
) implements CustomPayload {

    public static final CustomPayload.Id<ProjectorClockPayload> ID =
        new CustomPayload.Id<>(Identifier.of(LumierePlay.MOD_ID, "projector_clock"));

    public static final PacketCodec<RegistryByteBuf, ProjectorClockPayload> CODEC =
        PacketCodec.of(
            (value, buf) -> {
                BlockPos.PACKET_CODEC.encode(buf, value.pos());
                buf.writeLong(value.positionMs());
                buf.writeBoolean(value.isPlaying());
            },
            buf -> new ProjectorClockPayload(
                BlockPos.PACKET_CODEC.decode(buf),
                Math.max(0L, buf.readLong()),
                buf.readBoolean()
            )
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
