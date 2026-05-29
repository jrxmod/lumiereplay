/*
 * Copyright 2026 jrxmod
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jrxmod.lumiereplay.client;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.ProjectorBlockEntity;
import com.jrxmod.lumiereplay.client.render.ProjectorRenderer;
import com.jrxmod.lumiereplay.client.screen.ProjectorScreen;
import com.jrxmod.lumiereplay.client.sound.ProjectorSound;
import com.jrxmod.lumiereplay.client.video.VideoManager;
import com.jrxmod.lumiereplay.client.ytdlp.YtDlpManager;
import com.jrxmod.lumiereplay.network.ProjectorSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public class LumierePlayClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        LumierePlay.LOGGER.info("Lumiere Play client initializing...");

        // VLC check runs eagerly so the result is cached before world join
        VlcChecker.isAvailable();

        YtDlpManager.initialize();
        ProjectorRenderer.register();
        registerSyncReceiver();
        registerBlockUseCallback();
        registerJoinHandler();
        registerDisconnectCleanup();
    }

    private void registerSyncReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(ProjectorSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                BlockPos pos = payload.pos();
                if (context.client().world == null) return;

                BlockEntity be = context.client().world.getBlockEntity(pos);
                if (!(be instanceof ProjectorBlockEntity projector)) return;

                projector.setVideoUrl(payload.videoUrl());
                projector.setPlaying(payload.isPlaying());
                projector.setVolume(payload.volume());
                projector.setScreenSize(payload.screenWidth(), payload.screenHeight());

                ProjectorRenderer.trackProjector(pos);
                VideoManager.update(pos, payload.videoUrl(), payload.isPlaying(), payload.volume());
                ProjectorSound.setBaseVolume(pos, payload.volume());

                LumierePlay.LOGGER.debug("Projector synced at {} playing={}", pos, payload.isPlaying());
            });
        });
    }

    private void registerBlockUseCallback() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient()) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            BlockEntity be = world.getBlockEntity(pos);

            if (!(be instanceof ProjectorBlockEntity projector)) return ActionResult.PASS;

            // Block interaction is not allowed when VLC is missing
            if (!VlcChecker.isAvailable()) return ActionResult.PASS;

            ProjectorRenderer.trackProjector(pos);

            MinecraftClient.getInstance().setScreen(new ProjectorScreen(
                pos,
                projector.getVideoUrl(),
                projector.isPlaying(),
                projector.getVolume(),
                projector.getScreenWidth(),
                projector.getScreenHeight()
            ));

            return ActionResult.SUCCESS;
        });
    }

    /**
     * Shows a chat warning once per world join if libvlc is not found.
     * The hint line tells the player exactly how to install VLC for their OS.
     */
    private void registerJoinHandler() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (VlcChecker.isAvailable()) return;

            client.execute(() -> {
                if (client.player == null) return;

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
            });
        });
    }

    private void registerDisconnectCleanup() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            VideoManager.clearAll();
            ProjectorRenderer.clearAll();
            ProjectorSound.clearAll();
        });
    }
}
