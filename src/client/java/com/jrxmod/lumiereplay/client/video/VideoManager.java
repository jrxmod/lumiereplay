package com.jrxmod.lumiereplay.client.video;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.client.render.ProjectorRenderer;
import com.jrxmod.lumiereplay.client.render.ScreenTexture;
import com.jrxmod.lumiereplay.client.ytdlp.UrlResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks one VideoPlayer per projector BlockPos.
 * Quality parameter is passed through to UrlResolver for platform streams.
 */
public class VideoManager {

    private static final Map<BlockPos, VideoPlayer> players = new HashMap<>();
    private static final Set<BlockPos>              pending = new HashSet<>();

    public static void update(BlockPos posIn, String url, boolean playing,
                              int volume, UrlResolver.Quality quality) {
        final BlockPos pos = posIn.toImmutable();
        VideoPlayer existing = players.get(pos);

        if (!playing || url.isEmpty()) {
            pending.remove(pos);
            if (existing != null) {
                existing.close();
                players.remove(pos);
            }
            ScreenTexture tex = ProjectorRenderer.getTextures().get(pos);
            if (tex != null && !tex.isClosed()) tex.fillBlack();
            return;
        }

        if (existing != null && existing.getState() != PlayerState.ERROR) {
            if (existing.getState() == PlayerState.PAUSED) existing.resume();
            existing.setVolume(volume);
            return;
        }

        if (pending.contains(pos)) return;

        ProjectorRenderer.trackProjector(pos);

        if (UrlResolver.needsResolution(url)) {
            pending.add(pos);
            ScreenTexture tex = ProjectorRenderer.getTextures().get(pos);
            if (tex != null && !tex.isClosed()) tex.fillStatus(PlayerState.RESOLVING);

            LumierePlay.LOGGER.info("Resolving [{}] URL for projector at {}",
                quality.label, pos);

            UrlResolver.resolveAsync(url, quality, resolved -> {
                MinecraftClient.getInstance().execute(() -> {
                    pending.remove(pos);
                    if (resolved == null) {
                        LumierePlay.LOGGER.error("Could not resolve: {}", url);
                        ScreenTexture t = ProjectorRenderer.getTextures().get(pos);
                        if (t != null && !t.isClosed()) t.fillStatus(PlayerState.ERROR);
                        return;
                    }
                    startPlayer(pos, resolved, volume);
                });
            });
        } else {
            startPlayer(pos, url, volume);
        }
    }

    // Overload for calls without quality (uses BEST by default)
    public static void update(BlockPos posIn, String url, boolean playing, int volume) {
        update(posIn, url, playing, volume, UrlResolver.Quality.BEST);
    }

    public static void pause(BlockPos posIn) {
        VideoPlayer p = players.get(posIn.toImmutable());
        if (p != null) p.pause();
    }

    public static void resume(BlockPos posIn) {
        VideoPlayer p = players.get(posIn.toImmutable());
        if (p != null) p.resume();
    }

    public static void updateVolume(BlockPos posIn, int volume) {
        VideoPlayer p = players.get(posIn.toImmutable());
        if (p != null) p.setVolume(volume);
    }

    public static PlayerState getState(BlockPos posIn) {
        BlockPos pos = posIn.toImmutable();
        if (pending.contains(pos)) return PlayerState.RESOLVING;
        VideoPlayer p = players.get(pos);
        return p != null ? p.getState() : PlayerState.IDLE;
    }

    private static void startPlayer(BlockPos pos, String url, int volume) {
        VideoPlayer old = players.remove(pos);
        if (old != null) old.close();

        ProjectorRenderer.trackProjector(pos);

        ScreenTexture tex = ProjectorRenderer.getTextures().get(pos);
        if (tex == null || tex.isClosed()) {
            LumierePlay.LOGGER.warn("No screen texture at {}", pos);
            return;
        }

        tex.fillStatus(PlayerState.LOADING);

        VideoPlayer player = new VideoPlayer(url, tex, pos);
        player.setVolume(volume);
        player.play();
        players.put(pos, player);
    }

    public static void clearAll() {
        pending.clear();
        players.values().forEach(VideoPlayer::close);
        players.clear();
    }

    public static Map<BlockPos, VideoPlayer> getPlayers() { return players; }
}
