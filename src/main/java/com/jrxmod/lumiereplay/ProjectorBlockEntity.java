package com.jrxmod.lumiereplay;

import com.jrxmod.lumiereplay.AccessMode;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public class ProjectorBlockEntity extends BlockEntity {

    public static final String NBT_URL     = "VideoUrl";
    public static final String NBT_PLAYING = "IsPlaying";
    public static final String NBT_VOLUME  = "Volume";
    public static final String NBT_WIDTH   = "ScreenWidth";
    public static final String NBT_HEIGHT  = "ScreenHeight";
    public static final String NBT_OWNER   = "OwnerUuid";
    public static final String NBT_ACCESS   = "AccessMode";
    public static final String NBT_REDSTONE = "RedstonePowered";
    public static final String NBT_QUALITY    = "QualityKey";
    public static final String NBT_POSITION   = "PositionMs";

    /**
     * How far the nominal timeline may run ahead of the last anchor. The clock
     * service folds the elapsed part back in on every push, so this only bounds a
     * single interval, for example the moment between a resume and the first tick.
     */
    public static final int CLOCK_MAX_AHEAD_TICKS = 6000;

    private String     videoUrl     = "";
    private boolean    isPlaying    = false;
    private int        volume       = 100;
    private int        screenWidth  = 16;
    private int        screenHeight = 9;
    private String     ownerUuid       = "";
    private AccessMode accessMode      = AccessMode.ALL;
    private boolean    redstonePowered = false;
    private String     qualityKey   = LumierePlay.DEFAULT_QUALITY_KEY;

    /** Media position in milliseconds at anchorTick. Advances only while playing. */
    private long positionMs = 0L;
    /** World time the stored position was measured at, server ticks. */
    private long anchorTick = 0L;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PROJECTOR, pos, state);
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup r) {
        super.writeNbt(nbt, r);
        nbt.putString(NBT_URL,     videoUrl);
        nbt.putBoolean(NBT_PLAYING, isPlaying);
        nbt.putInt(NBT_VOLUME,     volume);
        nbt.putInt(NBT_WIDTH,      screenWidth);
        nbt.putInt(NBT_HEIGHT,     screenHeight);
        nbt.putString(NBT_OWNER,   ownerUuid);
        nbt.putString(NBT_ACCESS,  accessMode.id);
        nbt.putBoolean(NBT_REDSTONE, redstonePowered);
        nbt.putString(NBT_QUALITY, qualityKey);
        // The timeline is stored frozen at its nominal value. The anchor itself is
        // memory only, so a reload cannot invent playback time for the interval
        // during which the chunk was unloaded.
        nbt.putLong(NBT_POSITION, nominalPositionMs());
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup r) {
        super.readNbt(nbt, r);

        String raw = nbt.getString(NBT_URL);
        // Accept only real URLs or local absolute paths, discard anything corrupted
        videoUrl = isValidUrl(raw) ? raw : "";

        isPlaying    = nbt.getBoolean(NBT_PLAYING);
        volume       = nbt.contains(NBT_VOLUME) ? nbt.getInt(NBT_VOLUME) : 100;
        screenWidth  = nbt.contains(NBT_WIDTH)  ? nbt.getInt(NBT_WIDTH)  : 16;
        screenHeight = nbt.contains(NBT_HEIGHT) ? nbt.getInt(NBT_HEIGHT) : 9;

        // Guard against out-of-range values
        volume       = Math.max(0, Math.min(100, volume));
        screenWidth  = Math.max(1, Math.min(64, screenWidth));
        screenHeight = Math.max(1, Math.min(64, screenHeight));

        // ownerUuid — empty string for pre-v0.3.0 blocks (migrates to ALL access)
        ownerUuid       = nbt.contains(NBT_OWNER)    ? nbt.getString(NBT_OWNER)           : "";
        accessMode      = nbt.contains(NBT_ACCESS)   ? AccessMode.fromId(nbt.getString(NBT_ACCESS)) : AccessMode.ALL;
        redstonePowered = nbt.contains(NBT_REDSTONE) ? nbt.getBoolean(NBT_REDSTONE)        : false;

        // Quality and clock fields were introduced in 0.7.0: absent keys mean
        // "default quality, timeline starts from the beginning".
        qualityKey = nbt.contains(NBT_QUALITY)
            ? clampQualityKey(nbt.getString(NBT_QUALITY)) : LumierePlay.DEFAULT_QUALITY_KEY;
        positionMs = nbt.contains(NBT_POSITION) ? Math.max(0L, nbt.getLong(NBT_POSITION)) : 0L;

        // Re-anchor a restored timeline to the present so that the frozen
        // position stays where it is instead of resuming the extrapolation that
        // saved it. Anchor 0 means "not extrapolating" (see nominalPositionMs).
        anchorTick = (isPlaying && world != null) ? world.getTime() : 0L;
    }

    // Returns true for http(s) URLs, absolute Unix paths, Windows paths (C:\, D:\),
    // UNC paths (\\server\share) and file:// URIs.
    private static boolean isValidUrl(String s) {
        if (s == null || s.isEmpty()) return false;
        String lower = s.toLowerCase();
        return lower.startsWith("http://")
            || lower.startsWith("https://")
            || lower.startsWith("file://")
            || lower.startsWith("/")
            || (s.length() >= 3 && Character.isLetter(s.charAt(0))
                              && s.charAt(1) == ':'
                              && (s.charAt(2) == '\\' || s.charAt(2) == '/'))
            || s.startsWith("\\\\");
    }

    /** Keeps a malformed or oversized quality key from reaching clients. */
    private static String clampQualityKey(String key) {
        if (key == null || key.isEmpty()) return LumierePlay.DEFAULT_QUALITY_KEY;
        return key.length() <= 16 ? key : key.substring(0, 16);
    }

    /** Convert a local file path to a VLC-friendly URI. */
    public static String toVlcUri(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
            || lower.startsWith("file://")) return s;
        if (s.startsWith("/") || s.startsWith("\\\\")
            || (s.length() >= 3 && Character.isLetter(s.charAt(0))
                              && s.charAt(1) == ':')) {
            return "file:///" + s.replace("\\", "/");
        }
        return s;
    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup r) {
        return createNbt(r);
    }

    private void sync() {
        markDirty();
        if (world != null && !world.isClient()) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
            ProjectorClockService.arm(this);
        }
    }

    /**
     * Media position every viewer should be at right now. Frozen while paused,
     * extrapolated from the anchor tick while playing, and never extrapolated by
     * more than {@link #CLOCK_MAX_AHEAD_TICKS}.
     */
    public long nominalPositionMs() {
        long base = Math.max(0L, positionMs);
        if (!isPlaying || world == null || anchorTick <= 0L) return base;
        long elapsed = world.getTime() - anchorTick;
        if (elapsed <= 0) return base;
        if (elapsed > CLOCK_MAX_AHEAD_TICKS) elapsed = CLOCK_MAX_AHEAD_TICKS;
        return base + elapsed * 50L;
    }

    /**
     * Folds the extrapolated part of the timeline into the stored position and
     * restarts the anchor. Without this the elapsed term keeps growing from the
     * last state change, hits the cap, and the timeline silently stops tracking
     * the media on any video longer than the cap.
     */
    public void commitTimeline() {
        if (!isPlaying || world == null) return;
        long nominal = nominalPositionMs();
        if (nominal == positionMs && anchorTick > 0L && world.getTime() - anchorTick <= 1L) return;
        positionMs  = nominal;
        anchorTick  = world.getTime();
    }

    /**
     * Moves the shared timeline. The anchor is reset so the new position is the
     * one viewers align to, not the old position plus elapsed time.
     */
    public void seekTo(long ms) {
        positionMs  = Math.max(0L, ms);
        anchorTick  = world != null ? world.getTime() : 0L;
        sync();
    }

    public String  getVideoUrl()    { return videoUrl; }
    public boolean isPlaying()      { return isPlaying; }
    public int     getVolume()      { return volume; }
    public int     getScreenWidth() { return screenWidth; }
    public int     getScreenHeight(){ return screenHeight; }

    public String     getOwnerUuid()  { return ownerUuid; }
    public AccessMode getAccessMode() { return accessMode; }
    public String     getQualityKey() { return qualityKey; }

    public void setOwnerUuid(String uuid) {
        this.ownerUuid = uuid != null ? uuid : "";
        sync();
    }

    public void setAccessMode(AccessMode mode) {
        this.accessMode = mode != null ? mode : AccessMode.ALL;
        sync();
    }

    public boolean isRedstonePowered() { return redstonePowered; }

    public void setRedstonePowered(boolean powered) {
        if (this.redstonePowered == powered) return;
        this.redstonePowered = powered;
        sync();
    }

    /**
     * Returns true if the given player is allowed to control this projector.
     * Blocks with no recorded owner (pre-v0.3.0) are always accessible.
     */
    public boolean canInteract(PlayerEntity player) {
        if (ownerUuid.isEmpty()) return true;
        boolean isOwner = ownerUuid.equals(player.getUuidAsString());
        return switch (accessMode) {
            case ALL   -> true;
            case OWNER -> isOwner;
            case OPS   -> isOwner || player.hasPermissionLevel(2);
        };
    }

    /** True for the player who placed the block, and for anybody when unowned. */
    public boolean isOwner(PlayerEntity player) {
        return ownerUuid.isEmpty() || ownerUuid.equals(player.getUuidAsString());
    }

    public void setVideoUrl(String url) {
        String clean = (url != null && isValidUrl(url)) ? url : "";
        if (clean.equals(this.videoUrl)) return;
        this.videoUrl = clean;
        // A different source is a different timeline, so it always restarts from
        // zero; otherwise viewers would be told to catch up to a position the
        // fresh stream never had.
        this.positionMs = 0L;
        this.anchorTick = world != null ? world.getTime() : 0L;
        sync();
    }

    public void setPlaying(boolean v) {
        if (v != this.isPlaying) {
            if (this.isPlaying) {
                // Pause: keep the timeline where it stands instead of advancing it.
                this.positionMs = nominalPositionMs();
            } else {
                this.anchorTick = world != null ? world.getTime() : 0L;
            }
            this.isPlaying = v;
            // A paused or idle screen must not keep the hall lit, so the level is
            // rewritten on every actual change of the playback state.
            ProjectorBlock.setLevel(world, pos, v ? ProjectorBlock.LEVEL_PLAYING : 0);
        }
        sync();
    }

    public void setVolume(int v)               { this.volume = Math.max(0, Math.min(100, v)); sync(); }

    /** Size is clamped to the same 1..64 block range the save format accepts. */
    public void setScreenSize(int w, int h) {
        this.screenWidth  = Math.max(1, Math.min(64, w));
        this.screenHeight = Math.max(1, Math.min(64, h));
        sync();
    }

    public void setQualityKey(String key) {
        String clean = clampQualityKey(key);
        if (clean.equals(this.qualityKey)) return;
        this.qualityKey = clean;
        sync();
    }
}
