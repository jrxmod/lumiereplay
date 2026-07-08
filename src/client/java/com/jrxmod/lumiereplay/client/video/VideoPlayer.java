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

/**
 * Video player backed by VLCJ (libvlc).
 * Uses an explicit pauseRequested flag to survive VLC's async buffering/playing events.
 * When pauseRequested is true any spurious playing event re-applies the pause immediately.
 * For YouTube, the source is a localhost proxy URL (StreamProxy) — released on close().
 */
public class VideoPlayer implements AutoCloseable {

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
    private volatile Runnable     onRetry             = null;

    public VideoPlayer(String source, ScreenTexture screenTexture, BlockPos projectorPos) {
        this.source        = source;
        this.screenTexture = screenTexture;
        this.projectorPos  = projectorPos;
    }

    public String getSource() { return source; }

    public void play() {
        state = PlayerState.LOADING;
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
                    } else {
                        state = PlayerState.PLAYING;
                        LumierePlay.LOGGER.info("VLC playing: {}", source);
                    }
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
            state = PlayerState.PLAYING;
            mediaPlayer.controls().setPause(false);
        }
    }

    public void stop() {
        pauseRequested = false;
        firstFrameReceived = false;
        if (mediaPlayer != null) mediaPlayer.controls().stop();
        state = PlayerState.IDLE;
    }

    public void setVolume(int vol) {
        this.targetVolume = Math.max(0, Math.min(100, vol));
        if (mediaPlayer != null) {
            float spatial = ProjectorSound.getEffectiveVolume(projectorPos);
            mediaPlayer.audio().setVolume((int)(targetVolume * spatial));
        }
    }

    public PlayerState getState() { return state; }

    public void setOnRetry(Runnable r) { this.onRetry = r; }

    private static boolean isPipePath(String s) {
        return s != null && s.contains("lumiereplay_");
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
                // Check if this buffer has any non-zero bytes (real content)
                buf.mark();
                boolean hasContent = false;
                byte[] probe = new byte[Math.min(1024, buf.remaining())];
                buf.get(probe);
                for (byte b : probe) {
                    if (b != 0) { hasContent = true; break; }
                }
                buf.reset();
                if (!hasContent) return;
                firstFrameReceived = true;
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
        try { mediaPlayer.controls().setTime(ms); return true; } catch (Exception e) { return false; }
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
}
