package com.jrxmod.lumiereplay.client;

import com.jrxmod.lumiereplay.LumierePlay;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks for a usable libvlc installation at startup.
 * On Windows and macOS, manually locates the VLC directory and injects it
 * into jna.library.path before handing off to NativeDiscovery.
 * Result is cached after the first call.
 */
public final class VlcChecker {

    private static Boolean result = null;

    private VlcChecker() {}

    public static boolean isAvailable() {
        if (result != null) return result;
        try {
            injectVlcPathHint();
            result = new NativeDiscovery().discover();
        } catch (Throwable t) {
            LumierePlay.LOGGER.error("VLC discovery threw an exception: {}", t.getMessage());
            result = false;
        }
        if (result) {
            LumierePlay.LOGGER.info("[Lumiere Play] libvlc detected successfully.");
        } else {
            LumierePlay.LOGGER.error("[Lumiere Play] libvlc NOT found — video playback unavailable.");
        }
        return result;
    }

    public static String getInstallHintKey() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "message.lumiereplay.vlc_missing_windows";
        if (os.contains("mac")) return "message.lumiereplay.vlc_missing_mac";
        return "message.lumiereplay.vlc_missing_linux";
    }

    /**
     * Finds the VLC native library directory for the current OS and appends it
     * to jna.library.path so NativeDiscovery can locate libvlc without guessing.
     * On Linux, VLC is on the system library path — no hint needed.
     */
    private static void injectVlcPathHint() {
        String dir = findVlcDir();
        if (dir == null) return;

        String current = System.getProperty("jna.library.path", "");
        String updated = current.isEmpty() ? dir : current + File.pathSeparator + dir;
        System.setProperty("jna.library.path", updated);
        LumierePlay.LOGGER.info("VLC path hint injected: {}", dir);
    }

    private static String findVlcDir() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
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

        if (os.contains("mac")) {
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

        // Linux — system ldconfig handles it
        return null;
    }
}
