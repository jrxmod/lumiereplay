package com.jrxmod.lumiereplay.client;

import com.jrxmod.lumiereplay.LumierePlay;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Checks for usable libvlc and ffmpeg installations at startup.
 * On Windows and macOS, manually locates the VLC directory and injects it
 * into jna.library.path before handing off to NativeDiscovery.
 * Results are cached after the first call.
 */
public final class VlcChecker {

    private static Boolean vlcResult    = null;
    private static Boolean ffmpegResult = null;

    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    private VlcChecker() {}

    public static boolean isAvailable() {
        if (vlcResult != null) return vlcResult;
        try {
            injectVlcPathHint();
            vlcResult = new NativeDiscovery().discover();
        } catch (Throwable t) {
            LumierePlay.LOGGER.error("VLC discovery threw an exception: {}", t.getMessage());
            vlcResult = false;
        }
        if (vlcResult) {
            LumierePlay.LOGGER.info("[Lumiere Play] libvlc detected successfully.");
        } else {
            LumierePlay.LOGGER.error("[Lumiere Play] libvlc NOT found — video playback unavailable.");
        }
        return vlcResult;
    }

    /**
     * Checks if ffmpeg is available either as a system binary or via FfmpegManager.
     * Called from registerJoinHandler to warn the user if download is still in progress.
     */
    public static boolean isFfmpegAvailable() {
        if (ffmpegResult != null) return ffmpegResult;
        ffmpegResult = isCommandAvailable("ffmpeg", "-version");
        if (ffmpegResult) {
            LumierePlay.LOGGER.info("[Lumiere Play] ffmpeg detected successfully.");
        } else {
            LumierePlay.LOGGER.warn("[Lumiere Play] ffmpeg not found in PATH — will use bundled binary.");
        }
        return ffmpegResult;
    }

    private static boolean isCommandAvailable(String cmd, String arg) {
        try {
            Process p = new ProcessBuilder(cmd, arg)
                .redirectErrorStream(true)
                .start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String getInstallHintKey() {
        if (OS.contains("win")) return "message.lumiereplay.vlc_missing_windows";
        if (OS.contains("mac")) return "message.lumiereplay.vlc_missing_mac";
        return "message.lumiereplay.vlc_missing_linux";
    }

    public static String getFfmpegInstallHintKey() {
        if (OS.contains("win")) return "message.lumiereplay.ffmpeg_missing_windows";
        if (OS.contains("mac")) return "message.lumiereplay.ffmpeg_missing_mac";
        return "message.lumiereplay.ffmpeg_missing_linux";
    }

    private static void injectVlcPathHint() {
        String dir = findVlcDir();
        if (dir == null) return;

        String current = System.getProperty("jna.library.path", "");
        String updated = current.isEmpty() ? dir : current + File.pathSeparator + dir;
        System.setProperty("jna.library.path", updated);
        LumierePlay.LOGGER.info("VLC path hint injected: {}", dir);
    }

    private static String findVlcDir() {
        if (OS.contains("win")) {
            String[] candidates = {
                "C:\\Program Files\\VideoLAN\\VLC",
                "C:\\Program Files (x86)\\VideoLAN\\VLC",
                System.getProperty("user.home") + "\\AppData\\Local\\Programs\\VLC"
            };
            for (String c : candidates) {
                if (new File(c, "libvlc.dll").exists()) return c;
            }
            return null;
        }

        if (OS.contains("mac")) {
            String[] candidates = {
                "/Applications/VLC.app/Contents/MacOS/lib",
                "/Applications/VLC.app/Contents/MacOS",
                "/usr/local/lib",
                "/opt/homebrew/lib"
            };
            for (String c : candidates) {
                if (Files.exists(Path.of(c, "libvlc.dylib"))) return c;
            }
            return null;
        }

        return null;
    }
}
