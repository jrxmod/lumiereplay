package com.jrxmod.lumiereplay.client;

import com.jrxmod.lumiereplay.AccessMode;
import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.ProjectorBlockEntity;
import com.jrxmod.lumiereplay.client.render.ProjectorRenderer;
import com.jrxmod.lumiereplay.client.screen.ProjectorScreen;
import com.jrxmod.lumiereplay.client.sound.ProjectorSound;
import com.jrxmod.lumiereplay.client.video.SyncClock;
import com.jrxmod.lumiereplay.client.video.VideoManager;
import com.jrxmod.lumiereplay.client.ytdlp.FfmpegManager;
import com.jrxmod.lumiereplay.client.ytdlp.YtDlpManager;
import com.jrxmod.lumiereplay.network.ProjectorClockPayload;
import com.jrxmod.lumiereplay.network.ProjectorSyncPayload;
import com.jrxmod.lumiereplay.network.ProjectorSyncV2Payload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class LumierePlayClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        LumierePlay.LOGGER.info("Lumiere Play client initializing...");

        VlcChecker.isAvailable();

        YtDlpManager.initialize();
        FfmpegManager.initialize();
        ProjectorRenderer.register();
        registerSyncReceivers();
        registerBlockUseCallback();
        registerJoinHandler();
        registerSyncTick();
        registerDisconnectCleanup();
    }

    /**
     * Receives projector state from the server. Both wire formats are handled: v2
     * additionally carries the quality choice and the shared media position,
     * while the legacy packet leaves both untouched locally.
     */
    private void registerSyncReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(ProjectorSyncV2Payload.ID, (payload, context) ->
            context.client().execute(() -> applyState(
                payload.pos(), payload.videoUrl(), payload.isPlaying(), payload.volume(),
                payload.screenWidth(), payload.screenHeight(), payload.accessMode(),
                payload.qualityKey(), payload.positionMs())));

        ClientPlayNetworking.registerGlobalReceiver(ProjectorSyncPayload.ID, (payload, context) ->
            context.client().execute(() -> applyState(
                payload.pos(), payload.videoUrl(), payload.isPlaying(), payload.volume(),
                payload.screenWidth(), payload.screenHeight(), payload.accessMode(),
                null, -1L)));

        ClientPlayNetworking.registerGlobalReceiver(ProjectorClockPayload.ID, (payload, context) ->
            context.client().execute(() ->
                SyncClock.acceptClock(payload.pos(), payload.positionMs(), payload.isPlaying())));
    }

    private void applyState(BlockPos pos, String url, boolean playing, int volume,
                            int width, int height, AccessMode accessMode,
                            String qualityKey, long positionMs) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        BlockEntity be = client.world.getBlockEntity(pos);
        if (!(be instanceof ProjectorBlockEntity projector)) return;

        projector.setVideoUrl(url);
        projector.setPlaying(playing);
        projector.setVolume(volume);
        projector.setScreenSize(width, height);
        projector.setAccessMode(accessMode);
        if (qualityKey != null) projector.setQualityKey(qualityKey);

        ProjectorRenderer.trackProjector(pos);
        ProjectorSound.setBaseVolume(pos, volume);

        // The timeline is stored before the pipeline is touched, so a viewer that
        // starts playback here can be opened at the shared position directly.
        if (positionMs >= 0) SyncClock.acceptState(pos, positionMs, playing);

        if (url.isEmpty()) {
            VideoManager.update(pos, url, false, volume);
        } else if (!playing) {
            VideoManager.pause(pos);
        } else if (qualityKey != null) {
            VideoManager.update(pos, url, true, volume, qualityKey);
        } else {
            VideoManager.update(pos, url, true, volume);
        }

        LumierePlay.LOGGER.debug("Projector synced at {} playing={}", pos, playing);
    }

    private void registerBlockUseCallback() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient()) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            BlockEntity be = world.getBlockEntity(pos);

            if (!(be instanceof ProjectorBlockEntity projector)) return ActionResult.PASS;

            if (!VlcChecker.isAvailable()) return ActionResult.PASS;

            if (!projector.canInteract(player)) {
                player.sendMessage(
                    Text.translatable("gui.lumiereplay.projector.access_locked")
                        .formatted(Formatting.RED),
                    true
                );
                return ActionResult.SUCCESS;
            }

            ProjectorRenderer.trackProjector(pos);

            MinecraftClient.getInstance().setScreen(new ProjectorScreen(
                pos,
                projector.getVideoUrl(),
                projector.isPlaying(),
                projector.getVolume(),
                projector.getScreenWidth(),
                projector.getScreenHeight(),
                projector.getAccessMode(),
                projector.getOwnerUuid(),
                projector.getQualityKey()
            ));

            return ActionResult.SUCCESS;
        });
    }

    private void registerJoinHandler() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                if (client.player == null) return;

                // VLC missing — nothing works without it
                if (!VlcChecker.isAvailable()) {
                    client.player.sendMessage(
                        Text.translatable("message.lumiereplay.vlc_missing")
                            .formatted(Formatting.RED),
                        false
                    );
                    client.player.sendMessage(
                        Text.translatable(VlcChecker.getInstallHintKey())
                            .formatted(Formatting.YELLOW),
                        false
                    );
                    return;
                }

                // ffmpeg missing — streaming unavailable, downloading in background
                if (!FfmpegManager.isReady()) {
                    client.player.sendMessage(
                        Text.translatable("message.lumiereplay.ffmpeg_missing")
                            .formatted(Formatting.YELLOW),
                        false
                    );
                    client.player.sendMessage(
                        Text.translatable(VlcChecker.getFfmpegInstallHintKey())
                            .formatted(Formatting.GOLD),
                        false
                    );
                }
            });
        });
    }

    /**
     * Applies queued timeline alignment and checks for drift. Runs on the client
     * thread and does nothing unless a shared timeline is known for a projector.
     */
    private void registerSyncTick() {
        ClientTickEvents.END_CLIENT_TICK.register(SyncClock::tick);
    }

    private void registerDisconnectCleanup() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            VideoManager.clearAll();
            ProjectorRenderer.clearAll();
            ProjectorSound.clearAll();
            cleanupTempVideo();
        });
    }

    /**
     * Removes every downloaded media file the previous session left in the temp
     * directory. A file still present here is orphaned because the download that
     * created it finished or was stopped earlier, so it can never be played
     * again; keeping it would only consume disk. Only paths with the mod's own
     * prefix are touched, never anything else under the game folder.
     */
    private void cleanupTempVideo() {
        try {
            Path gameDir = MinecraftClient.getInstance().runDirectory.toPath();
            Path tempDir = gameDir.resolve("lumiereplay").resolve("temp").normalize();
            if (!Files.isDirectory(tempDir)) return;
            try (Stream<Path> stream = Files.list(tempDir)) {
                stream.filter(p -> p.getFileName().toString().startsWith("lumiereplay_"))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
            LumierePlay.LOGGER.info("Cleared leftover media from {}", tempDir);
        } catch (IOException e) {
            LumierePlay.LOGGER.warn("Could not clear temp media: {}", e.getMessage());
        }
    }
}
