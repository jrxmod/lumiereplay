package com.jrxmod.lumiereplay.client.video;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.client.render.ScreenTexture;
import com.jrxmod.lumiereplay.client.sound.ProjectorSound;
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
 */
public class VideoPlayer implements AutoCloseable {

    private final String        source;
    private final ScreenTexture screenTexture;
    private final BlockPos      projectorPos;

    private MediaPlayerFactory  factory;
    private EmbeddedMediaPlayer mediaPlayer;

    private volatile PlayerState state          = PlayerState.IDLE;
    private volatile boolean     pauseRequested = false;
    private volatile int         targetVolume   = 100;
    private          int         frameCounter   = 0;

    public VideoPlayer(String source, ScreenTexture screenTexture, BlockPos projectorPos) {
        this.source        = source;
        this.screenTexture = screenTexture;
        this.projectorPos  = projectorPos;
    }

    public void play() {
        state = PlayerState.LOADING;
        try {
            factory = new MediaPlayerFactory(
                "--no-video-title-show",
                "--quiet",
                "--no-metadata-network-access"
            );

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
                    mp.controls().setPosition(0f);
                    mp.controls().play();
                }

                @Override
                public void error(MediaPlayer mp) {
                    state = PlayerState.ERROR;
                    LumierePlay.LOGGER.error("VLC error for: {}", source);
                }

                @Override
                public void buffering(MediaPlayer mp, float newCache) {
                    // Never overwrite PAUSED state — buffering events fire during pause too
                    if (state != PlayerState.PAUSED && newCache < 100f) {
                        state = PlayerState.LOADING;
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

    private class LumiereBufferFormatCallback implements BufferFormatCallback {
        @Override
        public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
            LumierePlay.LOGGER.info("VLC source resolution: {}x{}", sourceWidth, sourceHeight);
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

            screenTexture.setPixelsBgra(buf);
            screenTexture.upload();

            if (++frameCounter % 15 == 0) {
                float spatial = ProjectorSound.getEffectiveVolume(projectorPos);
                int   vlcVol  = Math.max(0, Math.min(200, (int)(targetVolume * spatial)));
                try { mp.audio().setVolume(vlcVol); } catch (Exception ignored) {}
            }
        }
    }

    @Override
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
    }
}
