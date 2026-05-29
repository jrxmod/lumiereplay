package com.jrxmod.lumiereplay.client.render;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.client.video.PlayerState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;

/**
 * Wraps NativeImageBackedTexture for per-frame pixel updates.
 * setPixelsBgra() accepts VLC's RV32 BGRA ByteBuffer and converts to ABGR
 * directly in native memory via MemoryUtil — zero Java object allocation per frame.
 * fillStatus() renders translated text via AWT Graphics2D into the texture.
 */
public class ScreenTexture implements AutoCloseable {

    public static final int RENDER_W     = 1280;
    public static final int RENDER_H     = 720;
    private static final int PIXEL_COUNT = RENDER_W * RENDER_H;

    private static final int FONT_SIZE_MAIN = RENDER_H / 10;
    private static final int FONT_SIZE_SUB  = RENDER_H / 18;

    private static Field pointerField;

    static {
        try {
            pointerField = NativeImage.class.getDeclaredField("pointer");
            pointerField.setAccessible(true);
            LumierePlay.LOGGER.info("NativeImage fast-path enabled via MemoryUtil");
        } catch (Exception e) {
            LumierePlay.LOGGER.warn("NativeImage fast-path unavailable: {}", e.getMessage());
        }
    }

    private static int idCounter = 0;

    private final Identifier               textureId;
    private       NativeImageBackedTexture texture;
    private       NativeImage              image;
    private       boolean                  registered    = false;
    private volatile boolean               closed        = false;
    private       long                     nativePointer = 0L;

    public ScreenTexture() {
        this.textureId = Identifier.of(LumierePlay.MOD_ID, "screen_" + (idCounter++));
        allocate();
    }

    private void allocate() {
        if (image != null) image.close();
        image         = new NativeImage(RENDER_W, RENDER_H, false);
        texture       = new NativeImageBackedTexture(image);
        nativePointer = resolvePointer(image);
        fillBlack();
    }

    private static long resolvePointer(NativeImage img) {
        if (pointerField == null) return 0L;
        try {
            return pointerField.getLong(img);
        } catch (Exception e) {
            return 0L;
        }
    }

    public Identifier register() {
        if (!registered && !closed) {
            MinecraftClient.getInstance().getTextureManager()
                .registerTexture(textureId, texture);
            registered = true;
        }
        return textureId;
    }

    public void fillBlack() {
        if (closed || image == null) return;
        if (nativePointer != 0L) {
            for (int i = 0; i < PIXEL_COUNT; i++)
                MemoryUtil.memPutInt(nativePointer + (long) i * 4, 0xFF000000);
        } else {
            for (int y = 0; y < RENDER_H; y++)
                for (int x = 0; x < RENDER_W; x++)
                    image.setColor(x, y, 0xFF000000);
        }
        scheduleUpload();
    }

    /**
     * Renders a status screen with translated text via AWT Graphics2D.
     * Text is centered and large enough to be readable from several blocks away.
     */
    public void fillStatus(PlayerState state) {
        if (closed || image == null) return;
        if (state == PlayerState.IDLE || state == PlayerState.PLAYING || state == PlayerState.PAUSED) {
            fillBlack();
            return;
        }

        String textKey = switch (state) {
            case RESOLVING -> "screen.lumiereplay.resolving";
            case LOADING   -> "screen.lumiereplay.loading";
            case ERROR     -> "screen.lumiereplay.error";
            default        -> null;
        };
        if (textKey == null) { fillBlack(); return; }

        // Fetch translated string — respects the language selected in Minecraft options
        String label = Language.getInstance().get(textKey, textKey);

        Color bgColor   = switch (state) {
            case RESOLVING -> new Color(8,  0,  20);
            case LOADING   -> new Color(4,  10, 22);
            case ERROR     -> new Color(20, 4,  4);
            default        -> Color.BLACK;
        };
        Color textColor = switch (state) {
            case RESOLVING -> new Color(160, 100, 255);
            case LOADING   -> new Color(80,  160, 255);
            case ERROR     -> new Color(255, 80,  80);
            default        -> Color.WHITE;
        };
        // Subtle accent line color below the text
        Color lineColor = textColor.darker();

        BufferedImage buf = new BufferedImage(RENDER_W, RENDER_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buf.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);

        // Background
        g.setColor(bgColor);
        g.fillRect(0, 0, RENDER_W, RENDER_H);

        // Main label — bold, centered
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE_MAIN));
        g.setColor(textColor);
        FontMetrics fm   = g.getFontMetrics();
        int labelW       = fm.stringWidth(label);
        int labelX       = (RENDER_W - labelW) / 2;
        int labelY       = RENDER_H / 2 + fm.getAscent() / 2 - fm.getDescent();
        g.drawString(label, labelX, labelY);

        // Thin accent line under the text
        int lineY = labelY + fm.getDescent() + 6;
        int lineW = Math.min(labelW + 40, RENDER_W - 80);
        int lineX = (RENDER_W - lineW) / 2;
        g.setColor(lineColor);
        g.fillRect(lineX, lineY, lineW, 3);

        // Subtle mod watermark — bottom right, small
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE_SUB));
        g.setColor(new Color(80, 80, 80));
        String watermark = "Lumière Play";
        int wmW = g.getFontMetrics().stringWidth(watermark);
        g.drawString(watermark, RENDER_W - wmW - 20, RENDER_H - 20);

        g.dispose();

        // Copy all pixels at once — getRGB bulk is far faster than per-pixel calls
        int[] pixels = buf.getRGB(0, 0, RENDER_W, RENDER_H, null, 0, RENDER_W);
        if (nativePointer != 0L) {
            for (int i = 0; i < PIXEL_COUNT; i++)
                MemoryUtil.memPutInt(nativePointer + (long) i * 4, toAbgr(pixels[i]));
        } else {
            for (int row = 0; row < RENDER_H; row++)
                for (int col = 0; col < RENDER_W; col++)
                    image.setColor(col, row, toAbgr(pixels[row * RENDER_W + col]));
        }

        scheduleUpload();
    }

    /**
     * Writes VLC's RV32 BGRA ByteBuffer directly to NativeImage native memory.
     * BGRA bytes [B,G,R,A] as little-endian int = 0xAARRGGBB (= ARGB as int).
     * NativeImage expects ABGR = (a<<24)|(b<<16)|(g<<8)|r.
     * Conversion: swap R and B channels.
     */
    public void setPixelsBgra(java.nio.ByteBuffer bgra) {
        if (closed || image == null || bgra == null) return;

        int count = Math.min(bgra.remaining() / 4, PIXEL_COUNT);

        if (nativePointer != 0L && bgra.isDirect()) {
            long srcPtr = MemoryUtil.memAddress(bgra);
            for (int i = 0; i < count; i++) {
                int argb = MemoryUtil.memGetInt(srcPtr + (long) i * 4);
                MemoryUtil.memPutInt(nativePointer + (long) i * 4, toAbgr(argb));
            }
        } else if (nativePointer != 0L) {
            for (int i = 0; i < count; i++) {
                int argb = bgra.getInt(i * 4);
                MemoryUtil.memPutInt(nativePointer + (long) i * 4, toAbgr(argb));
            }
        } else {
            for (int y = 0; y < RENDER_H; y++)
                for (int x = 0; x < RENDER_W; x++) {
                    int idx = (y * RENDER_W + x) * 4;
                    if (idx + 3 >= bgra.limit()) break;
                    int argb = bgra.getInt(idx);
                    image.setColor(x, y, toAbgr(argb));
                }
        }
    }

    // ARGB int -> ABGR int (swap R and B)
    private static int toAbgr(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8)  & 0xFF;
        int b =  argb        & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    public void upload() { scheduleUpload(); }

    private void scheduleUpload() {
        if (closed || texture == null) return;
        NativeImageBackedTexture ref = texture;
        RenderSystem.recordRenderCall(() -> {
            try {
                if (!closed) ref.upload();
            } catch (Exception ignored) {}
        });
    }

    private void unregister() {
        if (registered) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(textureId);
            registered = false;
        }
    }

    public Identifier getId()  { return textureId; }
    public boolean isClosed()  { return closed; }
    public int getWidth()      { return RENDER_W; }
    public int getHeight()     { return RENDER_H; }

    @Override
    public void close() {
        closed        = true;
        nativePointer = 0L;
        unregister();
        if (image != null) { image.close(); image = null; }
    }
}
