package com.jrxmod.lumiereplay.network;

import com.jrxmod.lumiereplay.AccessMode;
import com.jrxmod.lumiereplay.LumierePlay;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Client to server projector command, second wire format.
 *
 * Extends the legacy {@link ProjectorUpdatePayload} with the stream quality key
 * and an optional seek request. Both fields are carried in one packet so the
 * server stays the only place where projector state is decided.
 *
 * qualityKey is a free-form string matched against the client side quality
 * list; the server never interprets it, it only stores and relays it.
 * seekMs is an absolute media position in milliseconds, or
 * {@link #NO_SEEK} when the sender does not ask for a jump.
 */
public record ProjectorUpdateV2Payload(
    BlockPos   pos,
    String     videoUrl,
    boolean    isPlaying,
    int        volume,
    int        screenWidth,
    int        screenHeight,
    AccessMode accessMode,
    String     qualityKey,
    long       seekMs
) implements CustomPayload {

    /** Sentinel meaning "no seek requested", avoids overloading position 0. */
    public static final long NO_SEEK = -1L;

    public static final CustomPayload.Id<ProjectorUpdateV2Payload> ID =
        new CustomPayload.Id<>(Identifier.of(LumierePlay.MOD_ID, "projector_update_v2"));

    /** Bound for the free-form quality key so a malformed client cannot pad the packet. */
    private static final int MAX_QUALITY_KEY_LEN = 16;

    public static final PacketCodec<RegistryByteBuf, ProjectorUpdateV2Payload> CODEC =
        PacketCodec.of(
            (value, buf) -> {
                BlockPos.PACKET_CODEC.encode(buf, value.pos());
                buf.writeString(value.videoUrl());
                buf.writeBoolean(value.isPlaying());
                buf.writeInt(value.volume());
                buf.writeInt(value.screenWidth());
                buf.writeInt(value.screenHeight());
                buf.writeInt(value.accessMode().ordinal());
                buf.writeString(clampKey(value.qualityKey()));
                buf.writeLong(value.seekMs());
            },
            buf -> new ProjectorUpdateV2Payload(
                BlockPos.PACKET_CODEC.decode(buf),
                buf.readString(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                AccessMode.values()[Math.min(buf.readInt(), AccessMode.values().length - 1)],
                clampKey(buf.readString()),
                buf.readLong()
            )
        );

    private static String clampKey(String key) {
        if (key == null || key.isEmpty()) return LumierePlay.DEFAULT_QUALITY_KEY;
        return key.length() <= MAX_QUALITY_KEY_LEN ? key : key.substring(0, MAX_QUALITY_KEY_LEN);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
