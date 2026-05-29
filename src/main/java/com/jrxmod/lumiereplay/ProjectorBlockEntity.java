package com.jrxmod.lumiereplay;

import net.minecraft.block.BlockState;
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

    private String  videoUrl     = "";
    private boolean isPlaying    = false;
    private int     volume       = 100;
    private int     screenWidth  = 16;
    private int     screenHeight = 9;

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
    }

    // Returns true only for http(s) URLs or absolute local paths
    private static boolean isValidUrl(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.startsWith("http://")
            || s.startsWith("https://")
            || s.startsWith("/");
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

    public void setVideoUrl(String url) {
        this.videoUrl = (url != null && isValidUrl(url)) ? url : "";
        sync();
    }

    public void setPlaying(boolean v)          { this.isPlaying = v; sync(); }
    public void setVolume(int v)               { this.volume = Math.max(0, Math.min(100, v)); sync(); }
    public void setScreenSize(int w, int h)    { this.screenWidth = w; this.screenHeight = h; sync(); }
}
