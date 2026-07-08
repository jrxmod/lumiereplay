/*
 * Copyright 2026 jrxmod — Lumière Play GUI
 * Clean 8-row layout, no overlapping elements.
 */
package com.jrxmod.lumiereplay.client.screen;

import com.jrxmod.lumiereplay.AccessMode;
import com.jrxmod.lumiereplay.client.video.PlayerState;
import com.jrxmod.lumiereplay.client.video.VideoManager;
import com.jrxmod.lumiereplay.client.ytdlp.UrlResolver;
import com.jrxmod.lumiereplay.network.ProjectorUpdatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
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
    private AccessMode accessMode;
    private boolean isOwner;
    private UrlResolver.Quality selectedQuality = UrlResolver.Quality.P720;
    private int     selectedPreset  = 0;

    private TextFieldWidget urlField;
    private ButtonWidget    playPauseBtn;
    private ButtonWidget    stopBtn;
    private ButtonWidget    restartBtn;
    private ButtonWidget    volDownBtn;
    private ButtonWidget    volUpBtn;
    private SeekSlider      seekSlider;
    private ButtonWidget    accessBtn;

    // ===== Grid constants =====
    private static final int PAD   = 10;   // outer panel padding
    private static final int GAP   = 6;    // gap between widgets in a row
    private static final int ROW   = 22;   // row height (button/field/slider)
    private static final int LHGT  = 11;   // label/text height
    private static final int TITLE_H = 16; // title row height
    private static final int SEC_GAP = 8;  // vertical gap between sections

    private static final int PW    = 340;  // panel width
    private static final int BW_S  = 24;   // small square button (play/pause/stop/restart)
    private static final int BW_V  = 26;   // volume - and + buttons
    private static final int BIG_W = 64;   // Play button width
    private static final int DONE_W = 80;
    private static final int ACC_W = 140;  // access button

    // Vertical Y offsets (top to bottom)
    private static final int Y_TITLE  = PAD;
    private static final int Y_SRC    = Y_TITLE + TITLE_H + SEC_GAP;     // row 0: URL + Play
    private static final int Y_PB     = Y_SRC   + ROW   + SEC_GAP;       // row 1: play/pause/stop/restart
    private static final int Y_SEEK   = Y_PB    + ROW   + SEC_GAP;       // row 2: seek slider
    private static final int Y_VOL    = Y_SEEK  + ROW   + SEC_GAP;       // row 3: volume - 100% +
    private static final int Y_QL     = Y_VOL   + ROW   + SEC_GAP;       // row 4: quality buttons
    private static final int Y_SZ     = Y_QL    + ROW   + SEC_GAP;       // row 5: size buttons
    private static final int Y_AC     = Y_SZ    + ROW   + SEC_GAP;       // row 6: access
    private static final int Y_DONE   = Y_AC    + ROW   + SEC_GAP;       // row 7: Done
    private static final int PH       = Y_DONE  + ROW   + PAD;           // panel height

    private static final int[][] PRESETS        = {{16,9},{32,18},{32,14},{16,12}};
    private static final String[] PRESET_LABELS = {"16:9","32:18","21:9","4:3"};

    public ProjectorScreen(BlockPos pos, String url, boolean playing,
                           int volume, int width, int height,
                           AccessMode accessMode, String ownerUuid) {
        super(Text.translatable("gui.lumiereplay.projector.title"));
        this.pos          = pos;
        this.currentUrl   = url;
        this.isPlaying    = playing;
        this.volume       = volume;
        this.screenWidth  = width;
        this.screenHeight = height;
        this.accessMode   = accessMode != null ? accessMode : AccessMode.ALL;

        String selfUuid = net.minecraft.client.MinecraftClient.getInstance().player != null
            ? net.minecraft.client.MinecraftClient.getInstance().player.getUuidAsString() : "";
        this.isOwner = ownerUuid.isEmpty() || ownerUuid.equals(selfUuid);

        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i][0] == width && PRESETS[i][1] == height) {
                selectedPreset = i;
                break;
            }
        }
    }

    private int px() { return this.width  / 2 - PW / 2; }
    private int py() { return this.height / 2 - PH / 2; }
    private int innerW() { return PW - PAD * 2; }

    @Override
    protected void init() {
        int x = px();
        int y = py();
        int iw = innerW();

        // === Row 0: URL field + Play button ===
        int playW = BIG_W;
        int urlW = iw - playW - GAP;
        urlField = new TextFieldWidget(this.textRenderer,
            x + PAD, y + Y_SRC, urlW, ROW,
            Text.translatable("gui.lumiereplay.projector.url_placeholder"));
        urlField.setMaxLength(512);
        urlField.setText(currentUrl);
        urlField.setPlaceholder(Text.translatable("gui.lumiereplay.projector.url_placeholder"));
        this.addDrawableChild(urlField);

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.lumiereplay.projector.play"),
            btn -> applyAndPlay())
            .dimensions(x + PAD + urlW + GAP, y + Y_SRC, playW, ROW)
            .build());

        // === Row 1: play/pause | stop | restart ===
        playPauseBtn = ButtonWidget.builder(
            Text.translatable(isPlaying
                ? "gui.lumiereplay.projector.pause"
                : "gui.lumiereplay.projector.resume"),
            btn -> {
                isPlaying = !isPlaying;
                btn.setMessage(Text.translatable(isPlaying
                    ? "gui.lumiereplay.projector.pause"
                    : "gui.lumiereplay.projector.resume"));
                if (isPlaying) VideoManager.resume(pos); else VideoManager.pause(pos);
                sendStateOnly();
            })
            .dimensions(x + PAD, y + Y_PB, BW_S, ROW)
            .build();
        this.addDrawableChild(playPauseBtn);

        stopBtn = ButtonWidget.builder(
            Text.translatable("gui.lumiereplay.projector.stop"),
            btn -> {
                isPlaying  = false;
                currentUrl = "";
                urlField.setText("");
                playPauseBtn.setMessage(Text.translatable("gui.lumiereplay.projector.resume"));
                VideoManager.update(pos, "", false, volume);
                sendStateOnly();
            })
            .dimensions(x + PAD + (BW_S + GAP), y + Y_PB, BW_S, ROW)
            .build();
        this.addDrawableChild(stopBtn);

        restartBtn = ButtonWidget.builder(
            Text.literal("\u21BB"),
            btn -> VideoManager.restart(pos))
            .dimensions(x + PAD + (BW_S + GAP) * 2, y + Y_PB, BW_S, ROW)
            .build();
        this.addDrawableChild(restartBtn);

        // === Row 2: Seek slider (full width) ===
        seekSlider = new SeekSlider(x + PAD, y + Y_SEEK, iw, ROW);
        this.addDrawableChild(seekSlider);

        // === Row 3: Volume (- 100% +) ===
        volDownBtn = ButtonWidget.builder(
            Text.literal("-"),
            btn -> { volume = Math.max(0, volume - 10);
                    VideoManager.updateVolume(pos, volume); sendStateOnly(); })
            .dimensions(x + PAD, y + Y_VOL, BW_V, ROW)
            .build();
        this.addDrawableChild(volDownBtn);

        volUpBtn = ButtonWidget.builder(
            Text.literal("+"),
            btn -> { volume = Math.min(100, volume + 10);
                    VideoManager.updateVolume(pos, volume); sendStateOnly(); })
            .dimensions(x + PAD + iw - BW_V, y + Y_VOL, BW_V, ROW)
            .build();
        this.addDrawableChild(volUpBtn);
        // "100%" label drawn in render() between the two buttons

        // === Row 4: Quality buttons (5 buttons) ===
        UrlResolver.Quality[] qs = UrlResolver.Quality.values();
        int qgap = 4;
        int qw = (iw - (qs.length - 1) * qgap) / qs.length;
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
                .dimensions(x + PAD + i * (qw + qgap), y + Y_QL, qw, ROW)
                .build());
        }

        // === Row 5: Size presets (4 buttons) ===
        int sw = (iw - (PRESETS.length - 1) * 4) / PRESETS.length;
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
                .dimensions(x + PAD + i * (sw + 4), y + Y_SZ, sw, ROW)
                .build());
        }

        // === Row 6: Access mode (only if owner) ===
        if (isOwner) {
            accessBtn = ButtonWidget.builder(
                Text.translatable("gui.lumiereplay.projector.access", accessMode.id),
                btn -> {
                    AccessMode[] modes = AccessMode.values();
                    accessMode = modes[(accessMode.ordinal() + 1) % modes.length];
                    btn.setMessage(Text.translatable("gui.lumiereplay.projector.access", accessMode.id));
                    sendStateOnly();
                })
                .dimensions(x + PAD, y + Y_AC, ACC_W, ROW)
                .build();
            this.addDrawableChild(accessBtn);
        }

        // === Row 7: Done button (centered) ===
        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.done"),
            btn -> this.close())
            .dimensions(x + PW / 2 - DONE_W / 2, y + Y_DONE, DONE_W, ROW)
            .build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x = px();
        int y = py();

        // Panel background + gold accent
        ctx.fill(x, y, x + PW, y + PH, 0xF0101018);
        ctx.fill(x, y, x + PW, y + 2, 0xFFFFD700);
        ctx.fill(x,         y + 2, x + 1,      y + PH, 0xFF444455);
        ctx.fill(x + PW - 1, y + 2, x + PW,     y + PH, 0xFF444455);
        ctx.fill(x,         y + PH - 1, x + PW, y + PH, 0xFF444455);

        // Title (top-left)
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.title"),
            x + PAD, y + Y_TITLE + 3, 0xFFFFD700);

        // Status (top-right, same row as title)
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
        ctx.drawTextWithShadow(this.textRenderer, stTxt,
            x + PW - PAD - textRenderer.getWidth(stTxt), y + Y_TITLE + 3, stCol);

        // Volume label — drawn in the GAP between - and + buttons (no overlap)
        int iw = innerW();
        int volLabelX = x + PAD + BW_V + (iw - BW_V * 2) / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.volume", volume),
            volLabelX, y + Y_VOL + (ROW - LHGT) / 2 + 1, 0xFFFFFFFF);

        // Quality highlight
        UrlResolver.Quality[] qs = UrlResolver.Quality.values();
        int qgap = 4;
        int qw = (iw - (qs.length - 1) * qgap) / qs.length;
        for (int i = 0; i < qs.length; i++) {
            if (qs[i] == selectedQuality) {
                ctx.fill(x + PAD + i * (qw + qgap),      y + Y_QL,
                         x + PAD + i * (qw + qgap) + qw, y + Y_QL + ROW,
                         0x99FFD700);
            }
        }

        // Size preset highlight
        int sw = (iw - (PRESETS.length - 1) * 4) / PRESETS.length;
        ctx.fill(x + PAD + selectedPreset * (sw + 4),      y + Y_SZ,
                 x + PAD + selectedPreset * (sw + 4) + sw, y + Y_SZ + ROW,
                 0x9900FF88);
        // Size text on the right (if width allows)
        String sizeTxt = screenWidth + "x" + screenHeight;
        if (textRenderer.getWidth(sizeTxt) < (PW - PAD - (x + PAD + 4 * (sw + 4) - x - PAD))) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(sizeTxt),
                x + PW - PAD - textRenderer.getWidth(sizeTxt),
                y + Y_SZ + (ROW - LHGT) / 2 + 1, 0xFF44FF88);
        }

        // Access locked warning (if not owner) — placed to the right of the access button
        if (!isOwner) {
            ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("gui.lumiereplay.projector.access_locked"),
                x + PAD + ACC_W + 8, y + Y_AC + (ROW - LHGT) / 2 + 1, 0xFF666666);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void applyAndPlay() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        currentUrl = url;
        isPlaying  = true;
        playPauseBtn.setMessage(Text.translatable("gui.lumiereplay.projector.pause"));
        VideoManager.update(pos, url, true, volume, selectedQuality);
        sendStateOnly();
    }

    private void sendStateOnly() {
        ClientPlayNetworking.send(new ProjectorUpdatePayload(
            pos, currentUrl, isPlaying, volume, screenWidth, screenHeight, accessMode));
    }

    /** Slider for seeking — disables itself on live streams. */
    private class SeekSlider extends SliderWidget {
        private boolean seekable = true;
        SeekSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.literal("0:00 / 0:00"), 0.0);
        }
        @Override protected void updateMessage() {
            long posMs = VideoManager.getPositionMs(ProjectorScreen.this.pos);
            long lenMs = VideoManager.getLengthMs(ProjectorScreen.this.pos);
            if (lenMs <= 0) {
                seekable = false;
                setMessage(Text.translatable("gui.lumiereplay.projector.live"));
                return;
            }
            seekable = true;
            this.value = (double) posMs / (double) lenMs;
            setMessage(Text.literal(formatTime(posMs) + " / " + formatTime(lenMs)));
        }
        @Override protected void applyValue() {
            if (!seekable) return;
            long lenMs = VideoManager.getLengthMs(ProjectorScreen.this.pos);
            if (lenMs > 0) VideoManager.seekTo(ProjectorScreen.this.pos, (long)(value * lenMs));
        }
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        s %= 60; m %= 60;
        if (h > 0) return h + ":" + pad(m) + ":" + pad(s);
        return m + ":" + pad(s);
    }
    private static String pad(long n) { return n < 10 ? "0" + n : "" + n; }
}
