/*
 * Copyright 2026 jrxmod
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jrxmod.lumiereplay.client.screen;

import com.jrxmod.lumiereplay.client.video.PlayerState;
import com.jrxmod.lumiereplay.client.video.VideoManager;
import com.jrxmod.lumiereplay.client.ytdlp.UrlResolver;
import com.jrxmod.lumiereplay.network.ProjectorUpdatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public class ProjectorScreen extends Screen {

    private final BlockPos pos;

    private String  currentUrl;
    private boolean isPlaying;
    private int     volume;
    private int     screenWidth;
    private int     screenHeight;

    private UrlResolver.Quality selectedQuality = UrlResolver.Quality.BEST;
    private int                 selectedPreset  = 0;

    private TextFieldWidget urlField;
    private ButtonWidget    playPauseBtn;

    private static final int PAD  = 10;
    private static final int GAP  = 6;
    private static final int LHGT = 9;
    private static final int BHGT = 20;
    private static final int FHGT = 20;
    private static final int SECT = 8;

    private static final int PW = 370;

    private static final int Y_TITLE   = PAD;
    private static final int Y_SRC_LBL = Y_TITLE  + LHGT + SECT;
    private static final int Y_SRC_FLD = Y_SRC_LBL + LHGT + GAP;
    private static final int Y_PB_LBL  = Y_SRC_FLD + FHGT + SECT;
    private static final int Y_PB_BTN  = Y_PB_LBL  + LHGT + GAP;
    private static final int Y_QL_LBL  = Y_PB_BTN  + BHGT + SECT;
    private static final int Y_QL_BTN  = Y_QL_LBL  + LHGT + GAP;
    private static final int Y_SZ_LBL  = Y_QL_BTN  + BHGT + SECT;
    private static final int Y_SZ_BTN  = Y_SZ_LBL  + LHGT + GAP;
    private static final int Y_STATUS  = Y_SZ_BTN  + BHGT + SECT;
    private static final int Y_DONE    = Y_STATUS  + LHGT + GAP;
    private static final int PH        = Y_DONE    + BHGT + PAD;

    private static final int[][] PRESETS        = {{16,9},{32,18},{32,14},{16,12}};
    private static final String[] PRESET_LABELS = {"16:9","32:18","21:9","4:3"};

    public ProjectorScreen(BlockPos pos, String url, boolean playing,
                           int volume, int width, int height) {
        super(Text.translatable("gui.lumiereplay.projector.title"));
        this.pos          = pos;
        this.currentUrl   = url;
        this.isPlaying    = playing;
        this.volume       = volume;
        this.screenWidth  = width;
        this.screenHeight = height;

        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i][0] == width && PRESETS[i][1] == height) {
                selectedPreset = i;
                break;
            }
        }
    }

    private int px() { return this.width  / 2 - PW / 2; }
    private int py() { return this.height / 2 - PH / 2; }

    @Override
    protected void init() {
        int x = px();
        int y = py();

        urlField = new TextFieldWidget(this.textRenderer,
            x + PAD, y + Y_SRC_FLD, PW - PAD * 2 - 76, FHGT,
            Text.translatable("gui.lumiereplay.projector.url_placeholder"));
        urlField.setMaxLength(512);
        urlField.setText(currentUrl);
        urlField.setPlaceholder(Text.translatable("gui.lumiereplay.projector.url_placeholder"));
        this.addDrawableChild(urlField);

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.lumiereplay.projector.play"),
            btn -> applyAndPlay())
            .dimensions(x + PW - PAD - 70, y + Y_SRC_FLD, 70, FHGT)
            .build()
        );

        playPauseBtn = ButtonWidget.builder(
            Text.translatable(isPlaying
                ? "gui.lumiereplay.projector.pause"
                : "gui.lumiereplay.projector.resume"),
            btn -> {
                isPlaying = !isPlaying;
                btn.setMessage(Text.translatable(isPlaying
                    ? "gui.lumiereplay.projector.pause"
                    : "gui.lumiereplay.projector.resume"));
                if (isPlaying) VideoManager.resume(pos);
                else           VideoManager.pause(pos);
                sendStateOnly();
            })
            .dimensions(x + PAD, y + Y_PB_BTN, 30, BHGT)
            .build();
        this.addDrawableChild(playPauseBtn);

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.lumiereplay.projector.stop"),
            btn -> {
                isPlaying  = false;
                currentUrl = "";
                urlField.setText("");
                playPauseBtn.setMessage(Text.translatable("gui.lumiereplay.projector.resume"));
                VideoManager.update(pos, "", false, volume);
                sendStateOnly();
            })
            .dimensions(x + PAD + 34, y + Y_PB_BTN, 30, BHGT)
            .build()
        );

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("–"),
            btn -> {
                volume = Math.max(0, volume - 10);
                VideoManager.updateVolume(pos, volume);
                sendStateOnly();
            })
            .dimensions(x + PAD + 80, y + Y_PB_BTN, 26, BHGT)
            .build()
        );

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("+"),
            btn -> {
                volume = Math.min(100, volume + 10);
                VideoManager.updateVolume(pos, volume);
                sendStateOnly();
            })
            .dimensions(x + PAD + 140, y + Y_PB_BTN, 26, BHGT)
            .build()
        );

        // Quality buttons — clicking stops current video, next Play uses new quality
        UrlResolver.Quality[] qs = UrlResolver.Quality.values();
        int qw = (PW - PAD * 2 - (qs.length - 1) * 4) / qs.length;
        for (int i = 0; i < qs.length; i++) {
            final UrlResolver.Quality q = qs[i];
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal(q.label),
                btn -> {
                    if (selectedQuality != q) {
                        selectedQuality = q;
                        if (isPlaying && !currentUrl.isEmpty()) {
                            VideoManager.update(pos, "", false, volume);
                            applyAndPlay();
                        }
                    }
                })
                .dimensions(x + PAD + i * (qw + 4), y + Y_QL_BTN, qw, BHGT)
                .build()
            );
        }

        int sw = 56;
        for (int i = 0; i < PRESETS.length; i++) {
            final int idx = i;
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal(PRESET_LABELS[i]),
                btn -> {
                    selectedPreset = idx;
                    screenWidth    = PRESETS[idx][0];
                    screenHeight   = PRESETS[idx][1];
                    sendStateOnly();
                })
                .dimensions(x + PAD + i * (sw + 4), y + Y_SZ_BTN, sw, BHGT)
                .build()
            );
        }

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.done"),
            btn -> this.close())
            .dimensions(x + PW / 2 - 40, y + Y_DONE, 80, BHGT)
            .build()
        );
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x = px();
        int y = py();

        ctx.fill(x, y, x + PW, y + PH, 0xF0101018);
        ctx.fill(x, y, x + PW, y + 2, 0xFFFFD700);
        ctx.fill(x,         y + 2, x + 1,      y + PH, 0xFF444455);
        ctx.fill(x + PW -1, y + 2, x + PW,     y + PH, 0xFF444455);
        ctx.fill(x,         y + PH - 1, x + PW, y + PH, 0xFF444455);

        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.title"),
            x + PAD, y + Y_TITLE, 0xFFFFD700);

        // Status indicator — top right corner
        PlayerState pstate = VideoManager.getState(pos);
        String stateKey = "gui.lumiereplay.status." + pstate.name().toLowerCase();
        Text stTxt = Text.translatable(stateKey);
        int stCol = switch (pstate) {
            case PLAYING   -> 0xFF44FF88;
            case PAUSED    -> 0xFFFFD700;
            case LOADING   -> 0xFF4488FF;
            case RESOLVING -> 0xFF9966FF;
            case ERROR     -> 0xFFFF4444;
            default        -> 0xFF888888;
        };
        ctx.drawTextWithShadow(this.textRenderer,
            stTxt,
            x + PW - PAD - textRenderer.getWidth(stTxt),
            y + Y_TITLE, stCol);

        ctx.fill(x + PAD, y + Y_TITLE + LHGT + 3,
                 x + PW - PAD, y + Y_TITLE + LHGT + 4, 0xFF333344);

        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.section_source"),
            x + PAD, y + Y_SRC_LBL, 0xFF888888);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.section_playback"),
            x + PAD, y + Y_PB_LBL, 0xFF888888);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.section_quality"),
            x + PAD, y + Y_QL_LBL, 0xFF888888);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.section_screen"),
            x + PAD, y + Y_SZ_LBL, 0xFF888888);

        // Volume label
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.volume", volume),
            x + PAD + 80 + 26 + (140 - 80 - 26) / 2,
            y + Y_PB_BTN + (BHGT - LHGT) / 2 + 1, 0xFFFFFFFF);

        // Quality highlight
        UrlResolver.Quality[] qs = UrlResolver.Quality.values();
        int qw = (PW - PAD * 2 - (qs.length - 1) * 4) / qs.length;
        for (int i = 0; i < qs.length; i++) {
            if (qs[i] == selectedQuality) {
                ctx.fill(x + PAD + i * (qw + 4),      y + Y_QL_BTN,
                         x + PAD + i * (qw + 4) + qw, y + Y_QL_BTN + BHGT,
                         0x99FFD700);
            }
        }

        // Preset highlight
        int sw = 56;
        ctx.fill(x + PAD + selectedPreset * (sw + 4),      y + Y_SZ_BTN,
                 x + PAD + selectedPreset * (sw + 4) + sw, y + Y_SZ_BTN + BHGT,
                 0x9900FF88);

        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal(screenWidth + " × " + screenHeight + " blocks"),
            x + PAD + PRESETS.length * (sw + 4) + 8,
            y + Y_SZ_BTN + (BHGT - LHGT) / 2 + 1, 0xFF44FF88);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void applyAndPlay() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http") && !url.startsWith("/")) return;
        currentUrl = url;
        isPlaying  = true;
        playPauseBtn.setMessage(Text.translatable("gui.lumiereplay.projector.pause"));
        VideoManager.update(pos, url, true, volume, selectedQuality);
        sendFullUpdate(url);
    }

    private void sendFullUpdate(String url) {
        ClientPlayNetworking.send(new ProjectorUpdatePayload(
            pos, url, isPlaying, volume, screenWidth, screenHeight));
    }

    private void sendStateOnly() {
        ClientPlayNetworking.send(new ProjectorUpdatePayload(
            pos, currentUrl, isPlaying, volume, screenWidth, screenHeight));
    }

    @Override
    public boolean shouldPause() { return false; }
}
