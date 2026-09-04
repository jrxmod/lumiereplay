package com.jrxmod.lumiereplay.client.video;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.client.LumiereConfig;
import com.jrxmod.lumiereplay.client.render.ScreenTexture;
import com.jrxmod.lumiereplay.client.sound.ProjectorSound;
import com.jrxmod.lumiereplay.client.ytdlp.PipeProxy;
import net.minecraft.util.math.BlockPos;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Video player backed by VLCJ (libvlc).
 * Uses an explicit pauseRequested flag to survive VLC's async buffering/playing events.
 * When pauseRequested is true any spurious playing event re-applies the pause immediately.
 * For YouTube, the source is a localhost proxy URL (StreamProxy) — released on close().
 *
 * 0.5.5: added a 20s first-frame timeout and a "playing-after-frame" gate so the
 * state machine doesn't claim PLAYING before VLC actually produced a decodable
 * frame. Previously this caused users to see a frozen LOADING screen (or, when
 * the texture was a black default, a flash of black) while VLC was still
 * waiting for the pipe/HLS source to start producing data.
 */
public class VideoPlayer implements AutoCloseable {

    /**
     * How long the first decodable frame may be delayed before the watchdog
     * considers the source, and the window of data silence that makes it give up.
     * A source that keeps feeding VLC (slow cold start, throttled download)
     * re-arms the watchdog and is never failed on a deadline; only a source that
     * stops delivering data is declared dead after this much time without any.
     */
    private static final long FIRST_FRAME_TIMEOUT_MS = 60_000L;
    private static final long FIRST_FRAME_TIMEOUT_NS = FIRST_FRAME_TIMEOUT_MS * 1_000_000L;

    private final String        source;
    private final ScreenTexture screenTexture;
    private final BlockPos      projectorPos;

    /** Extra libvlc arguments (e.g. HTTP Referer/User-Agent) VLC must send. */
    private final String[]       httpArgs;

    private MediaPlayerFactory  factory;
    private EmbeddedMediaPlayer mediaPlayer;

    private volatile PlayerState state          = PlayerState.IDLE;
    private volatile boolean     pauseRequested = false;
    private volatile int         targetVolume    = 100;
    private          int         frameCounter    = 0;
    private volatile boolean     firstFrameReceived  = false;
    private volatile int         bufferPercent       = 0;
    private volatile long        lastDataEventNanos  = 0L;
    private volatile Runnable     onRetry             = null;
    private          Timer       firstFrameTimer     = null;

    /**
     * Media time the local stream is behind the shared projector timeline.
     * Set when the source was opened at an offset (a piped stream that had to be
     * re-resolved with --download-sections) so that reported and requested
     * positions are always expressed in timeline terms, not in stream terms.
     */
    private volatile long        positionOffsetMs    = 0L;

    /**
     * Size the media itself reports, captured from the first buffer format call.
     * The render target is fixed, so this is the only place where a viewer can see
     * whether a quality request was actually served.
     */
    private volatile int         sourceWidth         = 0;
    private volatile int         sourceHeight        = 0;

    public VideoPlayer(String source, ScreenTexture screenTexture, BlockPos projectorPos) {
        this(source, screenTexture, projectorPos, new String[0]);
    }

    public VideoPlayer(String source, ScreenTexture screenTexture, BlockPos projectorPos,
                       String[] httpArgs) {
        this.source        = source;
        this.screenTexture = screenTexture;
        this.projectorPos  = projectorPos;
        this.httpArgs      = httpArgs != null ? httpArgs : new String[0];
    }

    public String getSource() { return source; }

    /** Delivered source size, or 0 when the media has not reported one yet. */
    public int getSourceWidth()  { return sourceWidth; }
    public int getSourceHeight() { return sourceHeight; }

    public void play() {
        state = PlayerState.LOADING;
        firstFrameReceived = false;
        cancelFirstFrameTimer();
        scheduleFirstFrameTimer();
        try {
            // FIFO paths need large file-caching — yt-dlp sleeps up to 15s before
            // YouTube allows download to start (rate-limiting on HLS streams).
            // Without this VLC stops waiting for data and freezes after ~1 second.
            String[] factoryArgs = isPipePath(source)
                ? new String[]{
                    "--no-video-title-show",
                    "--quiet",
                    "--file-caching=30000",
                    "--live-caching=30000",
                    "--disc-caching=30000",
                    "--no-drop-late-frames",
                    "--no-skip-frames",
                    "--no-ts-trust-pcr",
                    "--clock-synchro=0"
                  }
                : new String[]{
                    "--no-video-title-show",
                    "--quiet",
                    "--no-metadata-network-access"
                  };
            factory = new MediaPlayerFactory(withConfiguredArgs(factoryArgs));

            mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();

            CallbackVideoSurface surface = factory.videoSurfaces().newVideoSurface(
                new LumiereBufferFormatCallback(),
                new LumiereRenderCallback(),
                true
            );
            mediaPlayer.videoSurface().set(surface);

            mediaPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override
                public void playing(MediaPlayer mp) {
                    if (pauseRequested) {
                        // VLC fired playing after a pause request — re-apply pause immediately
                        mp.controls().setPause(true);
                        state = PlayerState.PAUSED;
                        return;
                    }
                    if (!firstFrameReceived) {
                        // VLC says "playing" but we have not yet decoded a frame.
                        // Stay in LOADING so the user keeps seeing the loading screen
                        // instead of a black screen / frozen screen. The first-frame
                        // timeout will trip if this never resolves.
                        LumierePlay.LOGGER.debug("VLC playing event before first frame for: {}", source);
                        return;
                    }
                    state = PlayerState.PLAYING;
                    LumierePlay.LOGGER.info("VLC playing: {}", source);
                }

                @Override
                public void paused(MediaPlayer mp) {
                    state = PlayerState.PAUSED;
                }

                @Override
                public void stopped(MediaPlayer mp) {
                    state = PlayerState.IDLE;
                }

                @Override
                public void finished(MediaPlayer mp) {
                    // FIFO sources cannot be replayed — the pipe is already closed
                    if (isPipePath(source)) {
                        state = PlayerState.IDLE;
                        return;
                    }
                    mp.controls().setPosition(0f);
                    mp.controls().play();
                }

                @Override
                public void error(MediaPlayer mp) {
                    cancelFirstFrameTimer();
                    state = PlayerState.ERROR;
                    LumierePlay.LOGGER.error("VLC error for: {}", source);
                    screenTexture.fillStatus(PlayerState.ERROR);
                    Runnable retry = onRetry;
                    if (retry != null && isPipePath(source)) {
                        net.minecraft.client.MinecraftClient.getInstance().execute(retry);
                    }
                }

                @Override
                public void buffering(MediaPlayer mp, float newCache) {
                    bufferPercent = (int) newCache;
                    // Any buffering callback means VLC is pulling data from the
                    // pipe or file. Remember when, so the first-frame watchdog can
                    // tell a slow source from a dead one: a source still feeding
                    // VLC must not be failed on the frame deadline.
                    lastDataEventNanos = System.nanoTime();
                    // Never overwrite PAUSED state — buffering events fire during pause too
                    if (state != PlayerState.PAUSED && newCache < 100f) {
                        state = PlayerState.LOADING;
                        // Only show status screen before real frames arrive —
                        // HLS fires buffering events at every segment boundary
                        // which would overwrite live video with a blue screen.
                        if (!firstFrameReceived) {
                            screenTexture.fillStatus(PlayerState.LOADING);
                        }
                    }
                }
            });

            applyVolume(mediaPlayer);
            playMedia();

        } catch (Exception e) {
            state = PlayerState.ERROR;
            LumierePlay.LOGGER.error("VLC init failed: {}", e.getMessage());
        }
    }

    /**
     * Starts playback of {@link #source}. When the source needs extra headers
     * (some live HTTP streams reject requests without a browser Referer or
     * User-Agent) they are passed as colon-prefixed media options, which is how
     * vlcj feeds per-media configuration to libvlc. Factory arguments affect the
     * whole player instance and are not suitable for per-source headers.
     */
    private void playMedia() {
        if (httpArgs.length == 0) {
            mediaPlayer.media().play(source);
        } else {
            mediaPlayer.media().play(source, httpArgs);
        }
    }

    public void pause() {
        if (mediaPlayer != null) {
            pauseRequested = true;
            state = PlayerState.PAUSED;
            mediaPlayer.controls().setPause(true);
        }
    }

    public void resume() {
        if (mediaPlayer != null) {
            pauseRequested = false;
            // PLAYING state is set by VLC's playing() event callback after
            // buffering completes. Setting it here would show PLAYING while
            // VLC is still buffering, misleading the user.
            mediaPlayer.controls().setPause(false);
        }
    }

    public void stop() {
        cancelFirstFrameTimer();
        pauseRequested = false;
        firstFrameReceived = false;
        if (mediaPlayer != null) mediaPlayer.controls().stop();
        state = PlayerState.IDLE;
    }

    public void setVolume(int vol) {
        int clamped = Math.max(0, Math.min(100, vol));
        this.targetVolume = clamped;
        // Keep the projector-wide base in sync so the spatial falloff is applied
        // against the current setting even before the state round trip comes back.
        ProjectorSound.setBaseVolume(projectorPos, clamped);
        applyVolume(mediaPlayer);
    }

    public PlayerState getState() { return state; }
    public int getBufferPercent() { return bufferPercent; }

    /**
     * Applies the current volume and the distance falloff to a media player.
     *
     * {@link ProjectorSound#getEffectiveVolume} already folds the user volume and
     * the spatial attenuation into a single 0..1 factor, so applying the volume
     * here must not multiply by the target volume again. The perceptual
     * square-root curve is applied once, from this method, and the render loop
     * calls the same method instead of a second, linear formula; two competing
     * paths made the level jump between them every frame group.
     */
    private void applyVolume(MediaPlayer mp) {
        if (mp == null) return;
        float effective = ProjectorSound.getEffectiveVolume(projectorPos);
        int vlcVol = (int)(Math.sqrt(Math.max(0f, effective)) * 100f);
        vlcVol = Math.max(0, Math.min(200, vlcVol));
        try { mp.audio().setVolume(vlcVol); } catch (Exception ignored) {}
    }

    public void setOnRetry(Runnable r) { this.onRetry = r; }

    /**
     * Identifies paths created by {@link PipeProxy}.
     * Matches /tmp/lumiereplay_UUID.ts (Linux/macOS) and temp dir equivalents
     * on Windows. Requires a path separator before the prefix to avoid false
     * positives from domain names or URL parameters containing the substring.
     */
    private static boolean isPipePath(String s) {
        if (s == null) return false;
        int idx = s.indexOf("lumiereplay_");
        if (idx < 0) return false;
        return idx == 0 || s.charAt(idx - 1) == '/' || s.charAt(idx - 1) == '\\';
    }

    /**
     * Appends the user supplied libvlc arguments from config/lumiereplay.json.
     * Blank entries are dropped and the count is capped, because the arguments
     * are passed straight to libvlc and an unbounded or half written list would
     * make every player instance fail to start.
     */
    private static String[] withConfiguredArgs(String[] base) {
        String[] extra = LumiereConfig.get().network.vlc_args;
        if (extra == null || extra.length == 0) return base;

        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String arg : extra) {
            if (arg == null) continue;
            arg = arg.trim();
            if (arg.isEmpty()) continue;
            kept.add(arg);
            if (kept.size() >= 16) break;
        }
        if (kept.isEmpty()) return base;

        String[] all = new String[base.length + kept.size()];
        System.arraycopy(base, 0, all, 0, base.length);
        for (int i = 0; i < kept.size(); i++) all[base.length + i] = kept.get(i);
        LumierePlay.LOGGER.info("Applying {} libvlc argument(s) from config", kept.size());
        return all;
    }

    private class LumiereBufferFormatCallback implements BufferFormatCallback {
        private boolean formatLocked = false;

        @Override
        public BufferFormat getBufferFormat(int srcW, int srcH) {
            if (!formatLocked) {
                formatLocked = true;
                sourceWidth  = srcW;
                sourceHeight = srcH;
                LumierePlay.LOGGER.info("VLC source resolution: {}x{}", srcW, srcH);
            }
            // Always return fixed render size — HLS streams report different resolutions
            // between fragments which triggers repeated getBufferFormat calls and
            // resets the render pipeline, causing video to freeze while audio continues.
            return new RV32BufferFormat(ScreenTexture.RENDER_W, ScreenTexture.RENDER_H);
        }

        @Override
        public void allocatedBuffers(ByteBuffer[] buffers) {}
    }

    private class LumiereRenderCallback implements RenderCallback {
        @Override
        public void display(MediaPlayer mp, ByteBuffer[] nativeBuffers, BufferFormat bufferFormat) {
            if (screenTexture.isClosed()) return;

            ByteBuffer buf = nativeBuffers[0];
            buf.rewind();

            // Skip frames until real content arrives — ffmpeg outputs black
            // frames during yt-dlp cold start (YouTube Sleeping N seconds).
            // Keeping LOADING texture until first non-empty frame prevents
            // the black-frame flash that looks like a freeze.
            if (!firstFrameReceived) {
                // Sample the frame instead of checking individual bytes.
                // BGRA means the first byte of each pixel is the blue channel.
                // A solid black frame has every B,G,R byte = 0, so we check
                // that the average brightness of a small sample is above a
                // threshold. This rejects both pitch-black frames and
                // frames with only stray metadata bytes.
                buf.mark();
                int probeLen = Math.min(1024, buf.remaining());
                byte[] probe = new byte[probeLen];
                buf.get(probe);
                buf.reset();

                int brightCount = 0;
                for (int i = 0; i < probeLen; i += 4) {
                    int b = probe[i]     & 0xff;
                    int g = probe[i + 1] & 0xff;
                    int r = probe[i + 2] & 0xff;
                    if (b + g + r > 24) { brightCount++; }
                }
                if (brightCount < 4) return;

                firstFrameReceived = true;
                cancelFirstFrameTimer();
                LumierePlay.LOGGER.info("First real frame received: {}", source);
            }
            screenTexture.setPixelsBgra(buf);
            screenTexture.upload();

            if (++frameCounter % 15 == 0) {
                applyVolume(mp);
            }
        }
    }

    /**
     * Returns current playback position in milliseconds of the shared projector
     * timeline, or -1 if unknown. A stream opened at an offset reports timeline
     * time, so every consumer of this value can compare it with the clock.
     */
    public long getPositionMs() {
        if (mediaPlayer == null) return -1;
        try { return mediaPlayer.status().time() + positionOffsetMs; } catch (Exception e) { return -1; }
    }

    /** Returns total media length in milliseconds, or -1 if unknown (e.g. live stream). */
    public long getLengthMs() {
        if (mediaPlayer == null) return -1;
        try { return mediaPlayer.status().length(); } catch (Exception e) { return -1; }
    }

    /**
     * Seeks to a position in milliseconds of the shared timeline.
     * Returns false if the media cannot be seeked, which is the normal answer
     * for a live stream or for a pipe that has no length.
     */
    public boolean setPositionMs(long ms) {
        if (mediaPlayer == null) return false;
        long local = Math.max(0L, ms - positionOffsetMs);
        try {
            long len = mediaPlayer.status().length();
            // A ratio only describes the open media, so it is unusable once the
            // stream was cut short by an offset. Those seek by absolute time.
            if (len > 0 && positionOffsetMs == 0L) {
                mediaPlayer.controls().setPosition((float) ms / (float) len);
            } else {
                mediaPlayer.controls().setTime(local);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    /**
     * Declares that this stream starts at offsetMs of the shared timeline.
     * Applied when a piped source was re-resolved with a section start.
     */
    public void setPositionOffsetMs(long offsetMs) {
        this.positionOffsetMs = Math.max(0L, offsetMs);
    }

    public long getPositionOffsetMs() { return positionOffsetMs; }

    /** Full restart: stop + re-play the same source. Works for HLS where setPosition fails. */
    public void restart() {
        sourceWidth  = 0;
        sourceHeight = 0;
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.controls().stop();
            // brief delay to let VLC release the pipe, then re-play
            new Thread(() -> {
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                try {
                    playMedia();
                } catch (Exception e) {
                    LumierePlay.LOGGER.warn("Restart failed for {}: {}", projectorPos, e.getMessage());
                }
            }, "lumiereplay-restart").start();
        } catch (Exception e) {
            LumierePlay.LOGGER.warn("Restart stop() failed for {}: {}", projectorPos, e.getMessage());
        }
    }

    public void close() {
        sourceWidth  = 0;
        sourceHeight = 0;
        cancelFirstFrameTimer();
        stop();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (factory != null) {
            factory.release();
            factory = null;
        }
        // Release the PipeProxy if this player was using a temp file or a pipe.
        if (source != null && (source.startsWith("/tmp/") || source.startsWith("/var/")
                || source.contains("lumiereplay_"))) {
            PipeProxy.release(source);
        }
    }

    /**
     * Schedule a watchdog that will fail the source if the first decodable
     * frame does not arrive within {@link #FIRST_FRAME_TIMEOUT_MS}. Without
     * this, broken pipes / dead HLS endpoints can leave the user staring at
     * a LOADING screen indefinitely.
     */
    private void scheduleFirstFrameTimer() {
        firstFrameTimer = new Timer("lumiereplay-first-frame-watchdog", true);
        firstFrameTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (firstFrameReceived) return;
                if (state == PlayerState.ERROR || state == PlayerState.IDLE) return;
                // A source that is still feeding VLC but has produced no decodable
                // frame yet is alive and merely slow (cold start, throttled
                // download). Re-arm instead of failing it. Only a source that has
                // stopped delivering data is declared dead after the silence window.
                if (lastDataEventNanos != 0L
                        && System.nanoTime() - lastDataEventNanos < FIRST_FRAME_TIMEOUT_NS) {
                    cancelFirstFrameTimer();
                    scheduleFirstFrameTimer();
                    return;
                }
                LumierePlay.LOGGER.error(
                    "No frame decoded within {}ms for: {}",
                    FIRST_FRAME_TIMEOUT_MS, source
                );
                state = PlayerState.ERROR;
                try {
                    screenTexture.fillStatus(PlayerState.ERROR);
                } catch (Exception ignored) {}
                if (mediaPlayer != null) {
                    try { mediaPlayer.controls().stop(); } catch (Exception ignored) {}
                }
            }
        }, FIRST_FRAME_TIMEOUT_MS);
    }

    private void cancelFirstFrameTimer() {
        if (firstFrameTimer != null) {
            firstFrameTimer.cancel();
            firstFrameTimer = null;
        }
    }
}
