package com.jrxmod.lumiereplay.client.video;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.client.render.ProjectorRenderer;
import com.jrxmod.lumiereplay.client.render.ScreenTexture;
import com.jrxmod.lumiereplay.client.ytdlp.UrlResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks one VideoPlayer per projector BlockPos.
 * Quality is part of the projector state, so it arrives with every sync packet
 * and is passed through to UrlResolver for platform streams.
 *
 * The requested quality is remembered before resolution starts, not only once a
 * player exists: without that, the echo of the sender's own update would compare
 * against an empty slot and rebuild a perfectly healthy pipeline.
 */
public class VideoManager {

    private static final Map<BlockPos, VideoPlayer>         players       = new ConcurrentHashMap<>();
    private static final Set<BlockPos>                      pending       = ConcurrentHashMap.newKeySet();
    private static final Map<BlockPos, UrlResolver.Quality> activeQuality = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer>             retryCount    = new ConcurrentHashMap<>();
    private static final Map<BlockPos, String>              retryOrigin   = new ConcurrentHashMap<>();
    private static final Map<BlockPos, UrlResolver.Quality> retryQuality  = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer>             retryVolume   = new ConcurrentHashMap<>();
    private static final Set<BlockPos>                      lazyPaused    = ConcurrentHashMap.newKeySet();

    private static final int[]  RETRY_DELAYS_SEC = {2, 5, 15};
    private static final int    MAX_RETRIES      = 3;

    public static void update(BlockPos posIn, String url, boolean playing,
                              int volume, UrlResolver.Quality quality) {
        final BlockPos pos = posIn.toImmutable();
        VideoPlayer existing = players.get(pos);

        if (!playing || url.isEmpty()) {
            pending.remove(pos);
            activeQuality.remove(pos);
            if (existing != null) {
                existing.close();
                players.remove(pos);
            }
            ScreenTexture tex = ProjectorRenderer.getTextures().get(pos);
            if (tex != null && !tex.isClosed()) tex.fillBlack();
            // A cleared source ends the shared timeline, a pause only suspends it.
            if (url.isEmpty()) SyncClock.reset(pos); else SyncClock.clearPendingSeek(pos);
            return;
        }

        String  previous    = retryOrigin.get(pos);
        boolean freshSource = existing == null || previous == null || !previous.equals(url);
        if (freshSource) retryCount.remove(pos);

        retryOrigin.put(pos, url);
        retryQuality.put(pos, quality);
        retryVolume.put(pos, volume);

        if (existing != null && existing.getState() != PlayerState.ERROR) {
            UrlResolver.Quality current = activeQuality.get(pos);
            if (current == null || current == quality) {
                if (existing.getState() == PlayerState.PAUSED) existing.resume();
                existing.setVolume(volume);
                return;
            }
            // Quality changed: stop old player and fall through to create a new one
            LumierePlay.LOGGER.info("Quality changed {} -> {} at {}", current.label, quality.label, pos);
            existing.close();
            players.remove(pos);
        }

        if (pending.contains(pos)) return;

        activeQuality.put(pos, quality);
        ProjectorRenderer.trackProjector(pos);

        boolean piped = UrlResolver.needsResolution(url);
        long    target = SyncClock.startTargetMs(pos);

        if (piped) {
            // A pipe has no seek, so catching up to the shared timeline means
            // asking yt-dlp to start further in. Only a viewer that is opening
            // this source for the first time does that; a viewer that merely came
            // back to an existing player is left alone.
            long startMs = freshSource ? SyncClock.startOffsetMs(pos) : 0L;
            SyncClock.clearPendingSeek(pos);
            resolveAndStart(pos, url, volume, quality, startMs);
            return;
        }

        // Local files and direct stream URLs are seekable once open, so a new
        // viewer starts immediately and is aligned on the first playing frame.
        if (target > 0) SyncClock.queueStartSeek(pos, target);
        startPlayer(pos, url, volume, quality, 0L);
    }

    public static void update(BlockPos posIn, String url, boolean playing, int volume) {
        // No quality in the packet (legacy server): keep whatever this client or a
        // previous packet already chose, so an echo never forces a re-resolve.
        UrlResolver.Quality current = activeQuality.get(posIn.toImmutable());
        update(posIn, url, playing, volume, current != null ? current : UrlResolver.Quality.BEST);
    }

    /** Variant used by the sync receiver, where quality travels as a string key. */
    public static void update(BlockPos posIn, String url, boolean playing, int volume,
                              String qualityKey) {
        update(posIn, url, playing, volume, UrlResolver.Quality.fromKey(qualityKey));
    }

    // Only pauses if currently playing, prevents double-toggle from GUI + sync
    public static void pause(BlockPos posIn) {
        VideoPlayer p = players.get(posIn.toImmutable());
        if (p != null && p.getState() != PlayerState.IDLE && p.getState() != PlayerState.ERROR) p.pause();
    }

    // Only resumes if currently paused, prevents double-toggle from GUI + sync
    public static void resume(BlockPos posIn) {
        VideoPlayer p = players.get(posIn.toImmutable());
        if (p != null && p.getState() == PlayerState.PAUSED) p.resume();
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

    // Stops and releases the player, called when the projector block is broken
    public static void stopAt(BlockPos posIn) {
        BlockPos pos = posIn.toImmutable();
        pending.remove(pos);
        activeQuality.remove(pos);
        lazyPaused.remove(pos);
        retryVolume.remove(pos);
        SyncClock.reset(pos);
        VideoPlayer p = players.remove(pos);
        if (p != null) p.close();
    }

    /**
     * Resolves a platform URL and starts playback. A non zero startMs asks
     * yt-dlp to skip ahead, which is best effort: a failed attempt falls back
     * to a plain resolve so the viewer still gets the stream, only from zero.
     */
    private static void resolveAndStart(BlockPos pos, String url, int volume,
                                        UrlResolver.Quality quality, long startMs) {
        pending.add(pos);
        ScreenTexture tex = ProjectorRenderer.getTextures().get(pos);
        if (tex != null && !tex.isClosed()) tex.fillStatus(PlayerState.RESOLVING);

        LumierePlay.LOGGER.info("Resolving [{}] URL for projector at {}{}",
            quality.label, pos, startMs > 0 ? " from " + (startMs / 1000L) + "s" : "");

        UrlResolver.resolveAsync(url, quality, startMs, resolved ->
            MinecraftClient.getInstance().execute(() -> {
                pending.remove(pos);
                if (resolved == null) {
                    if (startMs > 0) {
                        LumierePlay.LOGGER.warn(
                            "Offset resolve failed at {}, retrying from the beginning", pos);
                        resolveAndStart(pos, url, volume, quality, 0L);
                        return;
                    }
                    LumierePlay.LOGGER.error("Could not resolve: {}", url);
                    activeQuality.remove(pos);
                    ScreenTexture t = ProjectorRenderer.getTextures().get(pos);
                    if (t != null && !t.isClosed()) t.fillStatus(PlayerState.ERROR);
                    return;
                }
                startPlayer(pos, resolved, volume, quality, startMs);
            }));
    }

    /**
     * Opens a player. startMs declares that the media itself begins that far into
     * the shared timeline, which keeps reported positions comparable.
     */
    private static void startPlayer(BlockPos pos, String url, int volume,
                                    UrlResolver.Quality quality, long startMs) {
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
        player.setOnRetry(buildRetryCallback(pos));
        if (startMs > 0) player.setPositionOffsetMs(startMs);
        player.play();
        players.put(pos, player);
        activeQuality.put(pos, quality);
    }

    public static void clearAll() {
        pending.clear();
        activeQuality.clear();
        retryCount.clear();
        retryOrigin.clear();
        retryQuality.clear();
        retryVolume.clear();
        lazyPaused.clear();
        SyncClock.resetAll();
        players.values().forEach(VideoPlayer::close);
        players.clear();
    }

    public static void lazyPause(BlockPos posIn) {
        BlockPos pos = posIn.toImmutable();
        VideoPlayer p = players.get(pos);
        if (p == null) return;
        if (p.getState() == PlayerState.PLAYING) {
            p.pause();
            lazyPaused.add(pos);
            LumierePlay.LOGGER.debug("Lazy-paused projector at {}", pos);
        }
    }

    public static void lazyResume(BlockPos posIn) {
        BlockPos pos = posIn.toImmutable();
        if (!lazyPaused.contains(pos)) return;
        VideoPlayer p = players.get(pos);
        if (p != null && p.getState() == PlayerState.PAUSED) {
            // Coming back after being far away usually means this viewer fell
            // behind, so the shared timeline is applied instead of resuming blind.
            long target = p.getLengthMs() > 0 ? SyncClock.startTargetMs(pos) : 0L;
            p.resume();
            if (target > 0) SyncClock.queueStartSeek(pos, target);
            LumierePlay.LOGGER.debug("Lazy-resumed projector at {}", pos);
        }
        lazyPaused.remove(pos);
    }

    public static boolean isLazyPaused(BlockPos posIn) {
        return lazyPaused.contains(posIn.toImmutable());
    }

    private static Runnable buildRetryCallback(BlockPos pos) {
        return () -> {
            VideoPlayer current = players.get(pos);
            if (current == null || current.getState() != PlayerState.ERROR) return;

            int attempt = retryCount.getOrDefault(pos, 0);
            if (attempt >= MAX_RETRIES) {
                LumierePlay.LOGGER.error("Projector at {} failed after {} retries — giving up", pos, MAX_RETRIES);
                return;
            }

            int     delaySec = RETRY_DELAYS_SEC[attempt];
            retryCount.put(pos, attempt + 1);
            String  url      = retryOrigin.getOrDefault(pos, "");
            UrlResolver.Quality quality = retryQuality.getOrDefault(pos, UrlResolver.Quality.BEST);
            int     keepVol  = retryVolume.getOrDefault(pos, 100);

            LumierePlay.LOGGER.info("Projector at {} — retry {}/{} in {}s",
                pos, attempt + 1, MAX_RETRIES, delaySec);

            ScreenTexture tex = ProjectorRenderer.getTextures().get(pos);
            if (tex != null && !tex.isClosed()) tex.fillStatus(PlayerState.LOADING);

            current.close();
            players.remove(pos);
            activeQuality.remove(pos);

            Thread t = new Thread(() -> {
                try { Thread.sleep(delaySec * 1000L); } catch (InterruptedException ignored) {}
                MinecraftClient.getInstance().execute(() -> {
                    if (url.isEmpty()) return;
                    LumierePlay.LOGGER.info("Projector at {} — firing retry resolve", pos);
                    pending.remove(pos);
                    resolveAndStart(pos, url, keepVol, quality, 0L);
                });
            }, "lumiereplay-retry-" + pos);
            t.setDaemon(true);
            t.start();
        };
    }

    public static Map<BlockPos, VideoPlayer> getPlayers() { return players; }

    /** Full restart: re-resolves URL (for HLS where setPosition fails) and rebuilds pipeline. */
    public static void restart(BlockPos pos) {
        final BlockPos p = pos.toImmutable();
        VideoPlayer player = players.get(p);
        if (player == null) return;
        LumierePlay.LOGGER.info("Restart requested at {}", p);
        // Try VLC-level restart first (fast for files / direct URLs)
        try {
            String src1 = player.getSource();
            if (src1 != null && !src1.startsWith("http")) {
                player.restart();
                return;
            }
        } catch (Exception ignored) {}
        // For HTTP/HLS: full re-resolve via UrlResolver
        String origin = retryOrigin.get(p);
        UrlResolver.Quality q = retryQuality.getOrDefault(p, UrlResolver.Quality.P720);
        if (origin == null || origin.isEmpty()) return;
        player.close();
        players.remove(p);
        SyncClock.clearPendingSeek(p);
        int keepVol = retryVolume.getOrDefault(p, 100);
        resolveAndStart(p, origin, keepVol, q, 0L);
    }

    public static long getPositionMs(BlockPos pos) {
        VideoPlayer p = players.get(pos);
        return p == null ? -1 : p.getPositionMs();
    }

    public static long getLengthMs(BlockPos pos) {
        VideoPlayer p = players.get(pos);
        return p == null ? -1 : p.getLengthMs();
    }

    /**
     * Size the media reports for a projector, or null while nothing is known.
     * The render target inside the mod is fixed, so this is the only number that
     * tells a viewer whether a quality request was actually served.
     */
    public static int[] getSourceResolution(BlockPos pos) {
        VideoPlayer p = players.get(pos);
        if (p == null || p.getSourceWidth() <= 0 || p.getSourceHeight() <= 0) return null;
        return new int[]{ p.getSourceWidth(), p.getSourceHeight() };
    }

    public static int getBufferPercent(BlockPos pos) {
        VideoPlayer p = players.get(pos);
        return p == null ? 0 : p.getBufferPercent();
    }

    public static boolean seekTo(BlockPos pos, long ms) {
        VideoPlayer p = players.get(pos);
        return p != null && p.setPositionMs(ms);
    }
}
