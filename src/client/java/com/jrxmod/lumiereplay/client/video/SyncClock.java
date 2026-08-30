package com.jrxmod.lumiereplay.client.video;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.ProjectorBlockEntity;
import com.jrxmod.lumiereplay.client.LumiereConfig;
import com.jrxmod.lumiereplay.client.net.ProjectorClientNet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side view of the server owned playback timeline for each projector.
 *
 * The server keeps one nominal media position per projector: the position an
 * update was applied at, advanced by world time while it plays. Every viewer
 * receives that value and aligns its own VLC position to it when the gap is
 * larger than the configured threshold. Nothing here starts or stops streams;
 * it only decides when a running player has to seek.
 *
 * Alignment is skipped when sync is disabled in config, when the media has no
 * known length (live streams and piped sources), when the projector is not
 * PLAYING, or while inside the grace period after a start or a seek. Sources
 * that cannot be seeked are therefore never pushed around.
 */
public final class SyncClock {

    /** Position a starting or buffering player should be brought to next. */
    private static final Map<BlockPos, Long> pendingSeekMs = new ConcurrentHashMap<>();

    /** Most recent value received from the server, and when it was received. */
    private static final Map<BlockPos, Clock> clocks = new ConcurrentHashMap<>();

    /** Next moment a drift check may run for a projector, in monotonic nanos. */
    private static final Map<BlockPos, Long> nextCheckNanos = new ConcurrentHashMap<>();

    /** Guards the once per loop "restart for everyone" message. */
    private static final Map<BlockPos, Long> lastLoopNanos = new ConcurrentHashMap<>();

    private static final long LOOP_NOTICE_GAP_NS = 5_000_000_000L;

    /** Shorter gaps than this never move anyone, and a fresh timeline starts here. */
    private static final long IGNORE_BELOW_MS = 3_000L;

    private record Clock(long positionMs, long receivedNanos, boolean playing) {}

    private SyncClock() {}

    /**
     * Records the timeline carried by a state packet and queues the alignment a
     * viewer still starting up needs. A projector with no local player yet is
     * left alone: the value is read back while the player is created.
     */
    public static void acceptState(BlockPos pos, long positionMs, boolean playing) {
        BlockPos key = pos.toImmutable();
        clocks.put(key, new Clock(positionMs, System.nanoTime(), playing));

        if (!LumiereConfig.get().sync.enabled || !playing) return;
        if (positionMs <= IGNORE_BELOW_MS) return;
        if (VideoManager.getPlayers().get(key) != null) queueCorrection(key, positionMs);
    }

    /** Records the timeline from the periodic push, without touching the pipeline. */
    public static void acceptClock(BlockPos pos, long positionMs, boolean playing) {
        BlockPos key = pos.toImmutable();
        clocks.put(key, new Clock(positionMs, System.nanoTime(), playing));

        if (!LumiereConfig.get().sync.enabled || !playing) return;
        if (VideoManager.getPlayers().get(key) == null) return;
        queueCorrection(key, positionMs);
    }

    /** Where the shared timeline should be right now, extrapolated locally. */
    public static long expectedNowMs(BlockPos pos) {
        Clock clock = clocks.get(pos.toImmutable());
        if (clock == null) return 0L;
        long elapsed = clock.playing()
            ? Math.max(0L, (System.nanoTime() - clock.receivedNanos()) / 1_000_000L)
            : 0L;
        return clock.positionMs() + elapsed;
    }

    /**
     * Position a player that is about to start should reach, or 0 when there is
     * nothing to align to. Below {@link #IGNORE_BELOW_MS} a timeline counts as
     * freshly opened, so a new viewer must not chase it.
     */
    public static long startTargetMs(BlockPos pos) {
        if (!LumiereConfig.get().sync.enabled) return 0L;
        Clock clock = clocks.get(pos.toImmutable());
        if (clock == null || !clock.playing()) return 0L;
        long target = expectedNowMs(pos);
        return target > IGNORE_BELOW_MS ? target : 0L;
    }

    /**
     * Start position for a source that cannot be seeked after it is open, such as
     * a pipe fed by yt-dlp. Returning it here instead of seeking keeps the
     * behaviour opt in: a piped stream has to be re-resolved to skip ahead.
     */
    public static long startOffsetMs(BlockPos pos) {
        if (!LumiereConfig.get().sync.jump_on_join) return 0L;
        return startTargetMs(pos);
    }

    /** Asks for one alignment seek as soon as the player at pos is actually playing. */
    public static void queueStartSeek(BlockPos pos, long targetMs) {
        pendingSeekMs.put(pos.toImmutable(), targetMs);
    }

    public static void clearPendingSeek(BlockPos pos) {
        pendingSeekMs.remove(pos.toImmutable());
    }

    /**
     * Signed difference between local playback and the shared timeline in
     * milliseconds, or 0 when nothing is known. Feeds the GUI indicator.
     */
    public static long driftMs(BlockPos pos) {
        Clock clock = clocks.get(pos.toImmutable());
        if (clock == null) return 0L;
        long local = VideoManager.getPositionMs(pos);
        if (local < 0) return 0L;
        return local - expectedNowMs(pos);
    }

    /** True once this client has heard a shared timeline for this projector. */
    public static boolean hasClock(BlockPos pos) {
        return clocks.containsKey(pos.toImmutable());
    }

    /**
     * Drops everything known about a projector. Called when playback is stopped
     * locally, when a new source is submitted, and when the block goes away, so a
     * stale timeline can never make a fresh stream jump ahead.
     */
    public static void reset(BlockPos pos) {
        BlockPos key = pos.toImmutable();
        clocks.remove(key);
        pendingSeekMs.remove(key);
        nextCheckNanos.remove(key);
        lastLoopNanos.remove(key);
    }

    /** Drops all timelines, used when the client leaves a world. */
    public static void resetAll() {
        clocks.clear();
        pendingSeekMs.clear();
        nextCheckNanos.clear();
        lastLoopNanos.clear();
    }

    /**
     * Drives deferred alignment and periodic drift checks. Safe to call every
     * client tick; real work happens only for projectors with a known timeline.
     */
    public static void tick(MinecraftClient client) {
        if (client.world == null || clocks.isEmpty()) return;
        if (!LumiereConfig.get().sync.enabled) return;

        LumiereConfig cfg = LumiereConfig.get();
        long now = System.nanoTime();

        for (Map.Entry<BlockPos, Clock> entry : clocks.entrySet()) {
            BlockPos key = entry.getKey();
            Clock    clk = entry.getValue();
            if (!clk.playing()) continue;

            VideoPlayer player = VideoManager.getPlayers().get(key);
            if (player == null) continue;

            Long pending = pendingSeekMs.get(key);
            if (pending != null) {
                // A player that was still resolving or buffering when the state
                // arrived is aligned once, as soon as it really plays.
                if (player.getState() != PlayerState.PLAYING) continue;
                pendingSeekMs.remove(key);
                // The queued target was computed when the player was still starting
                // up. Anything that already lines up needs no seek at all, and
                // issuing one would show up as an unexplained jump.
                if (Math.abs(player.getPositionMs() - pending) <= cfg.sync.threshold_ms) continue;
                if (applySeek(player, pending)) {
                    LumierePlay.LOGGER.info("Aligned new viewer at {} to {}ms", key, pending);
                    grace(key, cfg);
                }
                continue;
            }

            if (player.getState() != PlayerState.PLAYING) continue;
            if (now < nextCheckNanos.getOrDefault(key, 0L)) continue;
            nextCheckNanos.put(key, now + cfg.sync.check_interval_ms * 1_000_000L);

            correct(key, player, cfg);
        }
    }

    private static void queueCorrection(BlockPos pos, long positionMs) {
        LumiereConfig cfg = LumiereConfig.get();
        VideoPlayer player = VideoManager.getPlayers().get(pos);

        if (player == null) {
            queueStartSeek(pos, positionMs);
            return;
        }
        // Without a known length there is nothing to align to, and issuing a seek
        // on such a source (live streams, pipes) can interrupt a running picture.
        if (player.getLengthMs() <= 0) return;
        if (player.getPositionMs() < 0) return;
        if (player.getState() != PlayerState.PLAYING) {
            queueStartSeek(pos, positionMs);
            return;
        }
        // A timeline that ran past the end (paused at the last frame, or a chunk
        // reload) must restart for everyone rather than drop a viewer on the
        // final second of the movie.
        if (positionMs >= player.getLengthMs() - cfg.sync.threshold_ms) {
            announceLoop(pos);
            return;
        }
        if (Math.abs(player.getPositionMs() - positionMs) <= cfg.sync.threshold_ms) return;

        long target = clampStep(player.getPositionMs(), positionMs, player.getLengthMs(), cfg);
        if (applySeek(player, target)) grace(pos, cfg);
    }

    private static void correct(BlockPos pos, VideoPlayer player, LumiereConfig cfg) {
        long expected = expectedNowMs(pos);
        if (expected <= IGNORE_BELOW_MS) return;

        long length = player.getLengthMs();
        if (length <= 0) return;

        long local = player.getPositionMs();
        if (local < 0) return;
        if (Math.abs(local - expected) <= cfg.sync.threshold_ms) return;

        // The shared timeline ran past the end while this viewer had already
        // looped. Re-anchoring the timeline is the only way back together, so ask
        // for a restart for everyone instead of chasing a position that is gone.
        if (expected >= length - cfg.sync.threshold_ms) {
            announceLoop(pos);
            return;
        }

        long target = clampStep(local, expected, length, cfg);
        if (Math.abs(target - local) <= cfg.sync.threshold_ms) return;
        if (applySeek(player, target)) {
            LumierePlay.LOGGER.info("Synced projector at {} by {}ms", pos, local - expected);
            grace(pos, cfg);
        }
    }

    /**
     * Steps from a local position towards a shared one, moving no further than
     * the configured step. Big gaps are closed over several checks, so a viewer
     * who buffered for a minute is walked back to the group instead of being
     * teleported past the content in front of them.
     */
    private static long clampStep(long local, long shared, long lengthMs, LumiereConfig cfg) {
        long want  = Math.min(shared, Math.max(0L, lengthMs - 1_000L));
        long limit = cfg.sync.max_step_ms;
        long delta = want - local;
        if (limit > 0 && Math.abs(delta) > limit) delta = delta < 0 ? -limit : limit;
        return Math.max(0L, local + delta);
    }

    private static boolean applySeek(VideoPlayer player, long targetMs) {
        if (targetMs < 0) return false;
        return player.setPositionMs(targetMs);
    }

    private static void grace(BlockPos pos, LumiereConfig cfg) {
        nextCheckNanos.put(pos, System.nanoTime() + cfg.sync.grace_ms * 1_000_000L);
    }

    /**
     * Publishes a media position as the shared timeline for this projector, so the
     * other viewers move with it. Used for loops, restarts and manual seeks.
     */
    public static void reanchor(BlockPos pos, long positionMs) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        if (!(client.world.getBlockEntity(pos) instanceof ProjectorBlockEntity projector)) return;
        if (projector.getVideoUrl().isEmpty()) return;

        clearPendingSeek(pos);
        ProjectorClientNet.sendSeek(pos, positionMs, projector);
    }

    /**
     * Asks the server to move the shared timeline back to zero so that everyone
     * restarts together when a loopable video ends. Throttled per projector.
     */
    private static void announceLoop(BlockPos pos) {
        long now = System.nanoTime();
        Long last = lastLoopNanos.get(pos);
        if (last != null && now - last < LOOP_NOTICE_GAP_NS) return;
        lastLoopNanos.put(pos, now);

        reanchor(pos, 0L);
    }
}
