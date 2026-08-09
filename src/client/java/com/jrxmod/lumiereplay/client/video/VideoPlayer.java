package com.jrxmod.lumiereplay.client.video;

import com.jrxmod.lumiereplay.LumierePlay;
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

    /** How long to wait for the first decodable frame before failing the source. */
    private static final long FIRST_FRAME_TIMEOUT_MS = 20_000L;

    private final String        source;
    private final ScreenTexture screenTexture;
    private final BlockPos      projectorPos;

    private MediaPlayerFactory  factory;
    private EmbeddedMediaPlayer mediaPlayer;

    private volatile PlayerState state          = PlayerState.IDLE;
    private volatile boolean     pauseRequested = false;
    private volatile int         targetVolume    = 100;
    private          int         frameCounter    = 0;
    private volatile boolean     firstFrameReceived  = false;
    private volatile int         bufferPercent       = 0;
    private volatile Runnable     onRetry             = null;
    private          Timer       firstFrameTimer     = null;

    public VideoPlayer(String source, ScreenTexture screenTexture, BlockPos projectorPos) {
        this.source        = source;
        this.screenTexture = screenTexture;
        this.projectorPos  = projectorPos;
    }

    public String getSource() { return source; }

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
            factory = new MediaPlayerFactory(factoryArgs);

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

            mediaPlayer.audio().setVolume(targetVolume);
            mediaPlayer.media().play(source);

        } catch (Exception e) {
            state = PlayerState.ERROR;
            LumierePlay.LOGGER.error("VLC init failed: {}", e.getMessage());
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
        this.targetVolume = Math.max(0, Math.min(100, vol));
        if (mediaPlayer != null) {
            float spatial = ProjectorSound.getEffectiveVolume(projectorPos);
            // Apply square-root curve for perceptual loudness
            int curvedVol = (int)(Math.sqrt(targetVolume / 100.0) * 100);
            mediaPlayer.audio().setVolume((int)(curvedVol * spatial));
        }
    }

    public PlayerState getState() { return state; }
    public int getBufferPercent() { return bufferPercent; }

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

    private class LumiereBufferFormatCallback implements BufferFormatCallback {
        private boolean formatLocked = false;

        @Override
        public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
            if (!formatLocked) {
                LumierePlay.LOGGER.info("VLC source resolution: {}x{}", sourceWidth, sourceHeight);
                formatLocked = true;
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
                float spatial = ProjectorSound.getEffectiveVolume(projectorPos);
                int   vlcVol  = Math.max(0, Math.min(200, (int)(targetVolume * spatial)));
                try { mp.audio().setVolume(vlcVol); } catch (Exception ignored) {}
            }
        }
    }

    /** Returns current playback position in milliseconds, or -1 if unknown. */
    public long getPositionMs() {
        if (mediaPlayer == null) return -1;
        try { return mediaPlayer.status().time(); } catch (Exception e) { return -1; }
    }

    /** Returns total media length in milliseconds, or -1 if unknown (e.g. live stream). */
    public long getLengthMs() {
        if (mediaPlayer == null) return -1;
        try { return mediaPlayer.status().length(); } catch (Exception e) { return -1; }
    }

    /** Seeks to position in milliseconds. Returns false if not seekable (HLS live). */
    public boolean setPositionMs(long ms) {
        if (mediaPlayer == null) return false;
        try {
            long len = mediaPlayer.status().length();
            if (len > 0) {
                mediaPlayer.controls().setPosition((float) ms / len);
            } else {
                mediaPlayer.controls().setTime(ms);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    /** Full restart: stop + re-play the same source. Works for HLS where setPosition fails. */
    public void restart() {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.controls().stop();
            // brief delay to let VLC release the pipe, then re-play
            new Thread(() -> {
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                try {
                    mediaPlayer.media().play(source);
                } catch (Exception e) {
                    LumierePlay.LOGGER.warn("Restart failed for {}: {}", projectorPos, e.getMessage());
                }
            }, "lumiereplay-restart").start();
        } catch (Exception e) {
            LumierePlay.LOGGER.warn("Restart stop() failed for {}: {}", projectorPos, e.getMessage());
        }
    }

    public void close() {
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
        // Release the PipeProxy if this player was using one (FIFO or temp file)
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
