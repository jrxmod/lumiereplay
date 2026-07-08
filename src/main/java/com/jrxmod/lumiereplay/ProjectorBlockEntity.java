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

    private String     videoUrl     = "";
    private boolean    isPlaying    = false;
    private int        volume       = 100;
    private int        screenWidth  = 16;
    private int        screenHeight = 9;
    private String     ownerUuid       = "";
    private AccessMode accessMode      = AccessMode.ALL;
    private boolean    redstonePowered = false;

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
        if (world != null && !world.isClient())
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    public String  getVideoUrl()    { return videoUrl; }
    public boolean isPlaying()      { return isPlaying; }
    public int     getVolume()      { return volume; }
    public int     getScreenWidth() { return screenWidth; }
    public int     getScreenHeight(){ return screenHeight; }

    public String     getOwnerUuid()  { return ownerUuid; }
    public AccessMode getAccessMode() { return accessMode; }

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
        this.redstonePowered = powered;
        markDirty();
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

    public void setVideoUrl(String url) {
        this.videoUrl = (url != null && isValidUrl(url)) ? url : "";
        sync();
    }

    public void setPlaying(boolean v)          { this.isPlaying = v; sync(); }
    public void setVolume(int v)               { this.volume = Math.max(0, Math.min(100, v)); sync(); }
    public void setScreenSize(int w, int h)    { this.screenWidth = w; this.screenHeight = h; sync(); }
}
