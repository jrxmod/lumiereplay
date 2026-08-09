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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks one VideoPlayer per projector BlockPos.
 * Quality parameter is passed through to UrlResolver for platform streams.
 */
public class VideoManager {

    private static final Map<BlockPos, VideoPlayer>        players       = new ConcurrentHashMap<>();
    private static final Set<BlockPos>                     pending       = ConcurrentHashMap.newKeySet();
    private static final Map<BlockPos, UrlResolver.Quality> activeQuality  = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer>             retryCount     = new ConcurrentHashMap<>();
    private static final Map<BlockPos, String>              retryOrigin    = new ConcurrentHashMap<>();
    private static final Map<BlockPos, UrlResolver.Quality> retryQuality   = new ConcurrentHashMap<>();
    private static final Set<BlockPos>                      lazyPaused     = ConcurrentHashMap.newKeySet();

    private static final int[]  RETRY_DELAYS_SEC = {2, 5, 15};
    private static final int    MAX_RETRIES      = 3;

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
            UrlResolver.Quality current = activeQuality.get(pos);
            if (current != null && current != quality) {
                // Quality changed — stop old player and fall through to create new one
                LumierePlay.LOGGER.info("Quality changed {} -> {} at {}", current.label, quality.label, pos);
                existing.close();
                players.remove(pos);
                activeQuality.remove(pos);
            } else {
                if (existing.getState() == PlayerState.PAUSED) existing.resume();
                existing.setVolume(volume);
                return;
            }
        }

        retryOrigin.put(pos, url);
        retryQuality.put(pos, quality);
        if (!url.equals(retryOrigin.getOrDefault(pos, "")) || existing == null) {
            retryCount.remove(pos);
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
                    startPlayer(pos, resolved, volume, quality);
                });
            });
        } else {
            startPlayer(pos, url, volume, quality);
        }
    }

    public static void update(BlockPos posIn, String url, boolean playing, int volume) {
        update(posIn, url, playing, volume, UrlResolver.Quality.BEST);
    }

    // Only pauses if currently playing — prevents double-toggle from GUI + sync
    public static void pause(BlockPos posIn) {
        VideoPlayer p = players.get(posIn.toImmutable());
        if (p != null && p.getState() != PlayerState.IDLE && p.getState() != PlayerState.ERROR) p.pause();
    }

    // Only resumes if currently paused — prevents double-toggle from GUI + sync
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

    // Stops and releases the player — called when the projector block is broken
    public static void stopAt(BlockPos posIn) {
        BlockPos pos = posIn.toImmutable();
        pending.remove(pos);
        activeQuality.remove(pos);
        lazyPaused.remove(pos);
        VideoPlayer p = players.remove(pos);
        if (p != null) p.close();
    }

    private static void startPlayer(BlockPos pos, String url, int volume, UrlResolver.Quality quality) {
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
        lazyPaused.clear();
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
            p.resume();
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

            int delaySec = RETRY_DELAYS_SEC[attempt];
            retryCount.put(pos, attempt + 1);
            String  url     = retryOrigin.getOrDefault(pos, "");
            UrlResolver.Quality quality = retryQuality.getOrDefault(pos, UrlResolver.Quality.BEST);

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
                    if (!url.isEmpty()) {
                        LumierePlay.LOGGER.info("Projector at {} — firing retry resolve", pos);
                        UrlResolver.resolveAsync(url, quality, resolved -> {
                            MinecraftClient.getInstance().execute(() -> {
                                if (resolved == null) {
                                    LumierePlay.LOGGER.error("Retry resolve failed at {}", pos);
                                    ScreenTexture t2 = ProjectorRenderer.getTextures().get(pos);
                                    if (t2 != null && !t2.isClosed()) t2.fillStatus(PlayerState.ERROR);
                                    return;
                                }
                                VideoPlayer old = players.remove(pos);
                                if (old != null) old.close();
                                startPlayer(pos, resolved, 100, quality);
                            });
                        });
                    }
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
        // For HTTP/HLS — full re-resolve via UrlResolver
        String origin = retryOrigin.get(p);
        UrlResolver.Quality q = retryQuality.getOrDefault(p, UrlResolver.Quality.P720);
        if (origin == null || origin.isEmpty()) return;
        player.close();
        players.remove(p);
        pending.add(p);
        ScreenTexture tex = ProjectorRenderer.getTextures().get(p);
        if (tex != null && !tex.isClosed()) tex.fillStatus(PlayerState.RESOLVING);
        UrlResolver.resolveAsync(origin, q, resolved -> {
            try {
                VideoPlayer np = new VideoPlayer(resolved, tex, p);
                np.setOnRetry(buildRetryCallback(p));
                players.put(p, np);
                np.play();
            } catch (Exception e) {
                LumierePlay.LOGGER.warn("Restart failed for {}: {}", p, e.getMessage());
            } finally {
                pending.remove(p);
            }
        });
    }

    public static long getPositionMs(BlockPos pos) {
        VideoPlayer p = players.get(pos);
        return p == null ? -1 : p.getPositionMs();
    }

    public static long getLengthMs(BlockPos pos) {
        VideoPlayer p = players.get(pos);
        return p == null ? -1 : p.getLengthMs();
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
