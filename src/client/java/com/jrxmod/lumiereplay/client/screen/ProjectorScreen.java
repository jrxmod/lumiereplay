/*
 * Copyright 2026 jrxmod — Lumière Play GUI
 * Sectioned layout with history, hotkeys and sounds. Playback commands carry
 * the quality choice and seek requests, so the panel controls every viewer.
 */
package com.jrxmod.lumiereplay.client.screen;

import com.jrxmod.lumiereplay.AccessMode;
import com.jrxmod.lumiereplay.ProjectorBlockEntity;
import com.jrxmod.lumiereplay.client.LumiereConfig;
import com.jrxmod.lumiereplay.client.net.ProjectorClientNet;
import com.jrxmod.lumiereplay.client.video.PlayerState;
import com.jrxmod.lumiereplay.client.video.SyncClock;
import com.jrxmod.lumiereplay.client.video.VideoManager;
import com.jrxmod.lumiereplay.client.ytdlp.UrlResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

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

    // History state
    private int     historyIndex = -1;  // -1 = current URL, 0+ = history position
    private String[] historyUrls;

    private TextFieldWidget urlField;
    private ButtonWidget    playPauseBtn;
    private VolumeSlider    volumeSlider;
    private SeekSlider      seekSlider;

    // ===== Layout constants =====
    private static final int PW    = 440;
    private static final int PAD   = 14;
    private static final int GAP   = 6;
    private static final int ROW   = 24;
    private static final int LHGT  = 10;
    private static final int TITLE_H = 18;
    private static final int SEC_LABEL_H = 10;
    private static final int SEC_GAP = 8;

    // Y positions (computed in init for clarity)
    private int ySep1, ySrcLabel, ySrcField, ySrcHist;
    private int ySep2, yPbLabel, yPbBtns, yPbSeek, yPbVol;
    private int ySep3, yDqLabel, yDqBtns;
    private int ySep4, yFooter;
    private int panelH;

    // Colors
    private static final int COL_PANEL_BG    = 0xF0181828;
    private static final int COL_BORDER      = 0xFF2A2A3A;
    private static final int COL_SEPARATOR   = 0xFF2A2A3A;
    private static final int COL_TITLE       = 0xFFFFD700;
    private static final int COL_SECTION     = 0xFF7777AA;
    private static final int COL_PRIMARY     = 0xFFFFFFFF;
    private static final int COL_SECONDARY   = 0xFF999999;
    private static final int COL_HIGHLIGHT_Q = 0x66FFD700;
    private static final int COL_HIGHLIGHT_S = 0x6600FF88;

    private static final int[][] PRESETS        = {{16,9},{32,18},{32,14},{16,12}};
    private static final String[] PRESET_LABELS = {"16:9","32:18","21:9","4:3"};

    /** Size of one ← / → jump, in milliseconds. */
    private static final long SEEK_STEP_MS = 5_000L;

    public ProjectorScreen(BlockPos pos, String url, boolean playing,
                           int volume, int width, int height,
                           AccessMode accessMode, String ownerUuid,
                           String qualityKey) {
        super(Text.translatable("gui.lumiereplay.projector.title"));
        this.pos          = pos;
        this.currentUrl   = url;
        this.isPlaying    = playing;
        this.volume       = volume;
        this.screenWidth  = width;
        this.screenHeight = height;
        this.accessMode   = accessMode != null ? accessMode : AccessMode.ALL;

        this.selectedQuality = UrlResolver.Quality.fromKey(qualityKey);

        // A projector nobody has played yet still holds the built in defaults, so
        // the configured start volume applies to it. Anything already in use keeps
        // its stored value untouched.
        if (url.isEmpty() && !playing && volume == 100) {
            this.volume = LumiereConfig.get().audio.volume;
        }

        String selfUuid = MinecraftClient.getInstance().player != null
            ? MinecraftClient.getInstance().player.getUuidAsString() : "";
        this.isOwner = ownerUuid.isEmpty() || ownerUuid.equals(selfUuid);

        this.historyUrls = LumiereConfig.getHistoryUrls();

        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i][0] == width && PRESETS[i][1] == height) {
                selectedPreset = i;
                break;
            }
        }
    }

    private int px() { return this.width  / 2 - PW / 2; }
    private int py() { return this.height / 2 - panelH / 2; }
    private int innerW() { return PW - PAD * 2; }

    @Override
    protected void init() {
        int iw = innerW();
        int x = px();

        // Compute Y positions
        int y = PAD;
        y += TITLE_H;
        ySep1 = y;
        y += SEC_GAP;
        ySrcLabel = y;
        y += SEC_LABEL_H + 4;
        ySrcField = y;
        y += ROW + 4;
        ySrcHist = y;
        y += ROW + 4;
        ySep2 = y;
        y += SEC_GAP;
        yPbLabel = y;
        y += SEC_LABEL_H + 4;
        yPbBtns = y;
        y += ROW + 4;
        yPbSeek = y;
        y += ROW + 4;
        yPbVol = y;
        y += ROW + 4;
        ySep3 = y;
        y += SEC_GAP;
        yDqLabel = y;
        y += SEC_LABEL_H + 4;
        yDqBtns = y;
        y += ROW + 4;
        ySep4 = y;
        y += SEC_GAP;
        yFooter = y;
        y += ROW + PAD;
        panelH = y;

        int py = py();

        // === Source section ===
        int playW = 60;
        int urlW = iw - playW - GAP;
        urlField = new TextFieldWidget(this.textRenderer,
            x + PAD, py + ySrcField, urlW, ROW,
            Text.translatable("gui.lumiereplay.projector.url_placeholder"));
        urlField.setMaxLength(512);
        urlField.setText(currentUrl);
        urlField.setPlaceholder(Text.translatable("gui.lumiereplay.projector.url_placeholder"));
        this.addDrawableChild(urlField);

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.lumiereplay.projector.play"),
            btn -> { playClick(); applyAndPlay(); })
            .dimensions(x + PAD + urlW + GAP, py + ySrcField, playW, ROW)
            .build());

        // History navigation
        int histBtnW = 24;
        int histGap = 4;
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("◀"),
            btn -> { playClick(); cycleHistory(-1); })
            .dimensions(x + PAD, py + ySrcHist, histBtnW, ROW)
            .build());

        int histLabelW = 50;
        int histLabelX = x + PAD + histBtnW + histGap;

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("▶"),
            btn -> { playClick(); cycleHistory(1); })
            .dimensions(histLabelX + histLabelW + histGap, py + ySrcHist, histBtnW, ROW)
            .build());

        // Copy button (right-aligned)
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("⧉"),
            btn -> {
                playClick();
                if (!currentUrl.isEmpty()) {
                    MinecraftClient.getInstance().keyboard.setClipboard(currentUrl);
                }
            })
            .dimensions(x + PAD + iw - histBtnW, py + ySrcHist, histBtnW, ROW)
            .build());

        // === Playback buttons ===
        int bw = 28;
        int bg = 6;
        playPauseBtn = ButtonWidget.builder(
            Text.translatable(isPlaying
                ? "gui.lumiereplay.projector.pause"
                : "gui.lumiereplay.projector.resume"),
            btn -> { playClick(); togglePause(); })
            .dimensions(x + PAD, py + yPbBtns, bw, ROW)
            .build();
        this.addDrawableChild(playPauseBtn);

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.lumiereplay.projector.stop"),
            btn -> {
                playClick();
                isPlaying  = false;
                currentUrl = "";
                urlField.setText("");
                historyIndex = -1;
                playPauseBtn.setMessage(Text.translatable("gui.lumiereplay.projector.resume"));
                VideoManager.update(pos, "", false, volume);
                SyncClock.reset(pos);
                sendStateOnly();
            })
            .dimensions(x + PAD + (bw + bg), py + yPbBtns, bw, ROW)
            .build());

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("↻"),
            btn -> {
                playClick();
                VideoManager.restart(pos);
                // A restart is a new start for the group too: leaving the shared
                // timeline where it was would drag every viewer back to it.
                SyncClock.reanchor(pos, 0L);
            })
            .dimensions(x + PAD + (bw + bg) * 2, py + yPbBtns, bw, ROW)
            .build());

        // Push this viewer's position onto the shared timeline for everyone else.
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("⏱"),
            btn -> {
                playClick();
                long cur = localPositionMs();
                if (cur < 0) { showSeekError("gui.lumiereplay.message.position_unknown"); return; }
                if (VideoManager.getLengthMs(pos) <= 0) {
                    showSeekError("gui.lumiereplay.message.seek_no_timeline"); return;
                }
                SyncClock.reanchor(pos, cur);
            })
            .dimensions(x + PAD + (bw + bg) * 3, py + yPbBtns, bw, ROW)
            .build());

        // === Seek slider ===
        seekSlider = new SeekSlider(x + PAD, py + yPbSeek, iw, ROW);
        this.addDrawableChild(seekSlider);

        // === Volume slider ===
        volumeSlider = new VolumeSlider(x + PAD, py + yPbVol, iw, ROW);
        this.addDrawableChild(volumeSlider);

        // === Display presets (left half) ===
        int halfW = (iw - 20) / 2;
        int sw = (halfW - (PRESETS.length - 1) * 4) / PRESETS.length;
        for (int i = 0; i < PRESETS.length; i++) {
            final int idx = i;
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal(PRESET_LABELS[i]),
                btn -> {
                    playClick();
                    selectedPreset = idx;
                    screenWidth    = PRESETS[idx][0];
                    screenHeight   = PRESETS[idx][1];
                    sendStateOnly();
                })
                .dimensions(x + PAD + i * (sw + 4), py + yDqBtns, sw, ROW)
                .build());
        }

        // === Quality buttons (right half) ===
        UrlResolver.Quality[] qs = UrlResolver.Quality.values();
        int qStart = x + PAD + halfW + 20;
        int qArea = iw - halfW - 20;
        int qgap = 3;
        int qw = (qArea - (qs.length - 1) * qgap) / qs.length;
        for (int i = 0; i < qs.length; i++) {
            final UrlResolver.Quality q = qs[i];
            this.addDrawableChild(ButtonWidget.builder(
                Text.literal(q.label),
                btn -> {
                    playClick();
                    if (selectedQuality == q) return;
                    selectedQuality = q;
                    // Only the command re-resolves the stream. Submitting state here
                    // used to restart the local player through the sync echo, because
                    // quality was not part of the packet at all.
                    if (isPlaying && !currentUrl.isEmpty()) {
                        VideoManager.update(pos, currentUrl, true, volume, q);
                    }
                    sendStateOnly();
                })
                .dimensions(qStart + i * (qw + qgap), py + yDqBtns, qw, ROW)
                .build());
        }

        // === Footer: Access + Done ===
        int doneW = 80;
        if (isOwner) {
            this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.lumiereplay.projector.access", accessMode.id),
                btn -> {
                    playClick();
                    AccessMode[] modes = AccessMode.values();
                    accessMode = modes[(accessMode.ordinal() + 1) % modes.length];
                    btn.setMessage(Text.translatable("gui.lumiereplay.projector.access", accessMode.id));
                    sendStateOnly();
                })
                .dimensions(x + PAD, py + yFooter, 140, ROW)
                .build());
        }

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.done"),
            btn -> { playClick(); this.close(); })
            .dimensions(x + PW - PAD - doneW, py + yFooter, doneW, ROW)
            .build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        seekSlider.refresh();
        super.render(ctx, mouseX, mouseY, delta);

        int x = px();
        int y = py();
        int iw = innerW();

        // Panel background
        ctx.fill(x, y, x + PW, y + panelH, COL_PANEL_BG);
        // Border
        ctx.fill(x, y, x + PW, y + 1, COL_BORDER);
        ctx.fill(x, y + panelH - 1, x + PW, y + panelH, COL_BORDER);
        ctx.fill(x, y, x + 1, y + panelH, COL_BORDER);
        ctx.fill(x + PW - 1, y, x + PW, y + panelH, COL_BORDER);

        // Title
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.title"),
            x + PAD, y + PAD + 4, COL_TITLE);

        // Status with pulsing dot
        PlayerState pstate = VideoManager.getState(pos);
        String stateKey = "gui.lumiereplay.status." + pstate.name().toLowerCase();
        Text stTxt = Text.translatable(stateKey);
        int stCol = getStatusColor(pstate);
        int stW = textRenderer.getWidth(stTxt);
        int stX = x + PW - PAD - stW;
        int stY = y + PAD + 4;
        ctx.drawTextWithShadow(this.textRenderer, stTxt, stX, stY, stCol);

        int badgeRight = stX - 14;

        // Shared timeline gap, shown only when this client can actually align to
        // it: without a known media duration the shared value cannot be reached,
        // so displaying it would only show a number nobody can act on.
        if (SyncClock.hasClock(pos) && VideoManager.getLengthMs(pos) > 0) {
            long drift = SyncClock.driftMs(pos) / 1000L;
            String driftText = Math.abs(drift) < 1
                ? Text.translatable("gui.lumiereplay.projector.sync_ok").getString()
                : Text.translatable("gui.lumiereplay.projector.sync",
                        (drift > 0 ? "+" : "") + drift + "s").getString();
            int driftColor = Math.abs(drift) < 2 ? COL_SECONDARY : 0xFFFFAA44;
            int driftW = textRenderer.getWidth(driftText);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(driftText),
                badgeRight - driftW, stY, driftColor);
            badgeRight -= driftW + 10;
        }

        // Redstone indicator, read live from the block entity
        if (isRedstonePowered()) {
            Text redstone = Text.translatable("gui.lumiereplay.projector.redstone_active");
            int rW = textRenderer.getWidth(redstone);
            ctx.drawTextWithShadow(this.textRenderer, redstone, badgeRight - rW, stY, 0xFFFF6666);
        }

        // Pulsing dot for PLAYING
        if (pstate == PlayerState.PLAYING) {
            float pulse = (float)((Math.sin(System.currentTimeMillis() / 300.0) + 1.0) / 2.0);
            int alpha = (int)(100 + pulse * 155);
            int dotCol = (alpha << 24) | (stCol & 0x00FFFFFF);
            ctx.fill(stX - 10, stY + 3, stX - 4, stY + 9, dotCol);
        }

        // Separators
        ctx.fill(x + PAD, y + ySep1, x + PW - PAD, y + ySep1 + 1, COL_SEPARATOR);
        ctx.fill(x + PAD, y + ySep2, x + PW - PAD, y + ySep2 + 1, COL_SEPARATOR);
        ctx.fill(x + PAD, y + ySep3, x + PW - PAD, y + ySep3 + 1, COL_SEPARATOR);
        ctx.fill(x + PAD, y + ySep4, x + PW - PAD, y + ySep4 + 1, COL_SEPARATOR);

        // Section labels
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.section_source"),
            x + PAD, y + ySrcLabel, COL_SECTION);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.section_playback"),
            x + PAD, y + yPbLabel, COL_SECTION);

        int halfW = (iw - 20) / 2;
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("gui.lumiereplay.projector.section_screen"),
            x + PAD, y + yDqLabel, COL_SECTION);
        Text qLabel = Text.translatable("gui.lumiereplay.projector.section_quality");
        ctx.drawTextWithShadow(this.textRenderer, qLabel, x + PAD + halfW + 20, y + yDqLabel, COL_SECTION);

        // What the source really delivers, next to the requested cap. A platform
        // stream fed through a pipe can only be a single muxed file, so the cap is
        // frequently not reached; showing the delivered size keeps the row honest.
        int[] src = VideoManager.getSourceResolution(pos);
        if (src != null) {
            int cap = requestedHeight();
            int srcCol = (cap > 0 && src[1] * 4 < cap * 3) ? 0xFFFFAA44 : COL_SECONDARY;
            ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("gui.lumiereplay.projector.source", src[0] + "×" + src[1]),
                x + PAD + halfW + 28 + textRenderer.getWidth(qLabel), y + yDqLabel, srcCol);
        }

        // History counter
        String histLabel;
        if (historyUrls.length == 0) {
            histLabel = "—";
        } else if (historyIndex < 0) {
            histLabel = "— / " + historyUrls.length;
        } else {
            histLabel = (historyIndex + 1) + " / " + historyUrls.length;
        }
        int histBtnW = 24;
        int histGap = 4;
        int histLabelW = 50;
        int histLabelX = x + PAD + histBtnW + histGap;
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal(histLabel),
            histLabelX + histLabelW / 2, y + ySrcHist + (ROW - LHGT) / 2 + 1, COL_SECONDARY);

        // Quality highlight
        UrlResolver.Quality[] qs = UrlResolver.Quality.values();
        int qStart = x + PAD + halfW + 20;
        int qArea = iw - halfW - 20;
        int qgap = 3;
        int qw = (qArea - (qs.length - 1) * qgap) / qs.length;
        for (int i = 0; i < qs.length; i++) {
            if (qs[i] == selectedQuality) {
                ctx.fill(qStart + i * (qw + qgap),      y + yDqBtns,
                         qStart + i * (qw + qgap) + qw, y + yDqBtns + ROW,
                         COL_HIGHLIGHT_Q);
            }
        }

        // Size preset highlight
        int sw = (halfW - (PRESETS.length - 1) * 4) / PRESETS.length;
        ctx.fill(x + PAD + selectedPreset * (sw + 4),      y + yDqBtns,
                 x + PAD + selectedPreset * (sw + 4) + sw, y + yDqBtns + ROW,
                 COL_HIGHLIGHT_S);

        // Size text
        String sizeTxt = screenWidth + "×" + screenHeight;
        int sizeTxtX = x + PAD + halfW - textRenderer.getWidth(sizeTxt);
        if (sizeTxtX > x + PAD + PRESETS.length * (sw + 4)) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(sizeTxt),
                sizeTxtX, y + yDqBtns + (ROW - LHGT) / 2 + 1, 0xFF44FF88);
        }

        // Access locked warning
        if (!isOwner) {
            ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("gui.lumiereplay.projector.access_locked"),
                x + PAD, y + yFooter + (ROW - LHGT) / 2 + 1, COL_SECONDARY);
        }
    }

    /** Redstone state comes from the block entity, which the server keeps updated. */
    private boolean isRedstonePowered() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;
        return mc.world.getBlockEntity(pos) instanceof ProjectorBlockEntity projector
            && projector.isRedstonePowered();
    }

    private static int getStatusColor(PlayerState state) {
        return switch (state) {
            case PLAYING   -> 0xFF44FF88;
            case PAUSED    -> 0xFFFFAA44;
            case LOADING   -> 0xFF4488FF;
            case RESOLVING -> 0xFF9966FF;
            case ERROR     -> 0xFFFF4444;
            default        -> 0xFF888888;
        };
    }

    // === Actions ===

    private void togglePause() {
        isPlaying = !isPlaying;
        playPauseBtn.setMessage(Text.translatable(isPlaying
            ? "gui.lumiereplay.projector.pause"
            : "gui.lumiereplay.projector.resume"));
        if (isPlaying) VideoManager.resume(pos); else VideoManager.pause(pos);
        sendStateOnly();
    }

    private void applyAndPlay() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        boolean newSource = !url.equals(currentUrl);
        currentUrl = url;
        isPlaying  = true;
        playPauseBtn.setMessage(Text.translatable("gui.lumiereplay.projector.pause"));
        LumiereConfig.addHistoryUrl(url);
        historyUrls = LumiereConfig.getHistoryUrls();
        historyIndex = -1;
        // A different source is a new timeline: drop any remembered position,
        // otherwise the panel would try to resume the old media's timestamp.
        if (newSource) SyncClock.reset(pos);
        VideoManager.update(pos, url, true, volume, selectedQuality);
        sendStateOnly();
    }

    private void cycleHistory(int direction) {
        if (historyUrls.length == 0) return;
        if (historyIndex < 0) {
            historyIndex = direction > 0 ? 0 : historyUrls.length - 1;
        } else {
            historyIndex += direction;
            if (historyIndex >= historyUrls.length) historyIndex = 0;
            if (historyIndex < 0) historyIndex = historyUrls.length - 1;
        }
        String url = historyUrls[historyIndex];
        currentUrl = url;
        urlField.setText(url);
    }

    /** Submits the current panel state without moving the shared timeline. */
    private void sendStateOnly() {
        ProjectorClientNet.send(pos, currentUrl, isPlaying, volume,
            screenWidth, screenHeight, accessMode, selectedQuality.key);
    }

    /**
     * Submits state and asks every viewer to move to the given media position.
     * The local player is seeked first so the panel does not snap back while the
     * command round trips.
     */
    /**
     * Seeks locally and publishes the new position as the shared timeline.
     * Media that reports no duration (live streams and sources fed through a
     * pipe) is refused here: a seek request on such a source lands wherever the
     * buffer happens to be, which looks like a random jump rather than a seek.
     */
    private void seekToAndShare(long targetMs) {
        long len = VideoManager.getLengthMs(pos);
        if (len <= 0) {
            showSeekError("gui.lumiereplay.message.seek_no_timeline");
            return;
        }
        long clamped = Math.max(0L, Math.min(len, targetMs));
        if (!VideoManager.seekTo(pos, clamped)) {
            showSeekError("gui.lumiereplay.message.seek_unsupported");
            return;
        }
        SyncClock.reanchor(pos, clamped);
    }

    /** Current local playback position, or -1 when the media does not report one. */
    private long localPositionMs() {
        return VideoManager.getPositionMs(pos);
    }

    private void playClick() {
        MinecraftClient.getInstance().getSoundManager()
            .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    // === Hotkeys ===

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (urlField.isFocused()) return super.keyPressed(keyCode, scanCode, modifiers);

        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            togglePause();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            long cur = localPositionMs();
            if (cur < 0) {
                showSeekError("gui.lumiereplay.message.position_unknown");
                return true;
            }
            seekToAndShare(cur - SEEK_STEP_MS);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            long cur = localPositionMs();
            if (cur < 0) {
                showSeekError("gui.lumiereplay.message.position_unknown");
                return true;
            }
            seekToAndShare(cur + SEEK_STEP_MS);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Short advice line in the action bar, translated like the rest of the panel. */
    /** Height the selected quality caps the source at, 0 when unconstrained. */
    private int requestedHeight() {
        return switch (selectedQuality) {
            case P1080 -> 1080;
            case P720  -> 720;
            case P480  -> 480;
            case P360  -> 360;
            default    -> 0;
        };
    }

    private void showSeekError(String translationKey) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(
                Text.translatable(translationKey).formatted(Formatting.YELLOW), true);
        }
    }

    // === Sliders ===

    /** Seek slider — disables itself on live streams, shows buffering %. */
    private class SeekSlider extends SliderWidget {
        private boolean seekable = true;
        SeekSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.literal("0:00 / 0:00"), 0.0);
        }
        public void refresh() {
            updateMessage();
        }
        @Override protected void updateMessage() {
            long posMs = VideoManager.getPositionMs(ProjectorScreen.this.pos);
            long lenMs = VideoManager.getLengthMs(ProjectorScreen.this.pos);
            int buf = VideoManager.getBufferPercent(ProjectorScreen.this.pos);
            PlayerState pstate = VideoManager.getState(ProjectorScreen.this.pos);
            boolean isLoading = pstate == PlayerState.LOADING || pstate == PlayerState.RESOLVING;

            // A live stream reports a short sliding length (the HLS window), so
            // the local position quickly leaves it behind. Treat a zero or a
            // position that has clearly passed the reported length as live: the
            // slider disables and is never pushed past the widget.
            boolean live = lenMs <= 0 || posMs > lenMs + 1000L;
            if (live) {
                seekable = false;
                Text liveLabel = Text.translatable("gui.lumiereplay.projector.live");
                if (isLoading || (buf > 0 && buf < 100)) {
                    setMessage(Text.literal(liveLabel.getString() + "  •  ")
                        .append(bufferingText(buf)));
                } else {
                    setMessage(liveLabel);
                }
                return;
            }
            seekable = true;
            this.value = Math.max(0.0, Math.min(1.0, (double) posMs / (double) lenMs));

            String timeLabel = formatTime(posMs) + " / " + formatTime(lenMs);
            if (isLoading || (buf > 0 && buf < 100)) {
                setMessage(Text.literal(timeLabel + "     ").append(bufferingText(buf)));
            } else {
                setMessage(Text.literal(timeLabel));
            }
        }
        @Override protected void applyValue() {
            if (!seekable) return;
            long lenMs = VideoManager.getLengthMs(ProjectorScreen.this.pos);
            if (lenMs > 0) ProjectorScreen.this.seekToAndShare((long)(value * lenMs));
        }
    }

    /** Volume slider — replaces +/- buttons. */
    private class VolumeSlider extends SliderWidget {
        VolumeSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.literal(""), volume / 100.0);
            updateMessage();
        }
        @Override protected void updateMessage() {
            setMessage(Text.literal("🔊  ").append(
                Text.translatable("gui.lumiereplay.projector.volume", ProjectorScreen.this.volume)));
        }
        @Override protected void applyValue() {
            ProjectorScreen.this.volume = (int)(value * 100);
            VideoManager.updateVolume(ProjectorScreen.this.pos, ProjectorScreen.this.volume);
            ProjectorScreen.this.sendStateOnly();
        }
    }

    // === Helpers ===

    /** "Buffering 43%" or "Buffering..." depending on what VLC reported. */
    private static Text bufferingText(int percent) {
        return percent > 0
            ? Text.translatable("gui.lumiereplay.projector.buffering", percent + "%")
            : Text.translatable("gui.lumiereplay.projector.buffering_short");
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
