package com.jrxmod.lumiereplay.client.ytdlp;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.client.ytdlp.YtDlpManager;
import com.jrxmod.lumiereplay.client.ytdlp.PipeProxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Resolves platform URLs to playable URLs via yt-dlp.
 *
 * Two resolution strategies:
 *
 *   PROXY  — YouTube / youtu.be: yt-dlp is piped through a local HTTP proxy
 *             (StreamProxy). VLC plays http://127.0.0.1:PORT/stream. YouTube
 *             never sees VLC's IP — eliminates sefc=1 / IPv6-rotation 403s.
 *
 *   DIRECT — All other platforms: classic --get-url approach returns a direct
 *             stream URL that VLC can fetch without session binding issues.
 */
public class UrlResolver {

    public enum Quality {
        BEST  ("best",  "Best"),
        P1080 ("1080",  "1080p"),
        P720  ("720",   "720p"),
        P480  ("480",   "480p"),
        P360  ("360",   "360p");

        public final String key;
        public final String label;
        Quality(String key, String label) { this.key = key; this.label = label; }
    }

    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final String[] BROWSERS = {
        "firefox", "chrome", "chromium", "brave", "opera"
    };

    private static final String[] SUPPORTED_PATTERNS = {
        "youtube.com", "youtu.be",
        "twitch.tv",
        "vimeo.com",
        "dailymotion.com",
        "soundcloud.com",
        "vk.com/video",
        "vkvideo.ru",
        "rutube.ru",
        "ok.ru/video"
    };

    private static final String[] PROXY_PATTERNS = {
        "youtube.com", "youtu.be",
        "twitch.tv",
        "vimeo.com",
        "dailymotion.com",
        "soundcloud.com",
        "vk.com/video",
        "vkvideo.ru",
        "rutube.ru",
        "ok.ru/video"
    };

    private static final String[] COOKIE_REQUIRED = {
        "youtube.com", "youtu.be"
    };

    public static boolean needsResolution(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        for (String p : SUPPORTED_PATTERNS)
            if (lower.contains(p)) return true;
        return false;
    }

    private static boolean needsProxy(String url) {
        String lower = url.toLowerCase();
        for (String p : PROXY_PATTERNS)
            if (lower.contains(p)) return true;
        return false;
    }

    private static boolean needsCookies(String url) {
        String lower = url.toLowerCase();
        for (String p : COOKIE_REQUIRED)
            if (lower.contains(p)) return true;
        return false;
    }

    public static void resolveAsync(String url, Consumer<String> onResolved) {
        resolveAsync(url, Quality.BEST, onResolved);
    }

    public static void resolveAsync(String url, Quality quality, Consumer<String> onResolved) {
        if (!YtDlpManager.isReady()) {
            LumierePlay.LOGGER.warn("yt-dlp not ready, cannot resolve: {}", url);
            onResolved.accept(null);
            return;
        }

        CompletableFuture.runAsync(() -> {
            if (needsProxy(url)) {
                resolveViaProxy(url, quality, onResolved);
            } else {
                resolveViaDirect(url, quality, onResolved);
            }
        });
    }

    // --- Proxy path (YouTube) ---

    /**
     * Builds the yt-dlp pipe command and starts a StreamProxy.
     * Returns the proxy's localhost URL to the caller.
     */
    private static void resolveViaProxy(String url, Quality quality,
                                        Consumer<String> onResolved) {
        String browser = detectBrowser();

        List<String> cmd = new ArrayList<>();
        cmd.add(YtDlpManager.getBinaryPath().toString());
        cmd.add("--no-playlist");
        cmd.add("--no-warnings");
        cmd.add("--force-ipv4");
        cmd.add("-f");
        cmd.add(buildProxyFormatArg(url, quality));
        cmd.add("--hls-use-mpegts");
        cmd.add("-o");
        cmd.add("-");

        if (browser != null) {
            cmd.add("--cookies-from-browser");
            cmd.add(browser);
        }

        cmd.add(url);

        PipeProxy proxy = PipeProxy.start(cmd);
        if (proxy != null) {
            LumierePlay.LOGGER.info("PipeProxy [{}] started for: {}", quality.label, url);
            onResolved.accept(proxy.getPath());
        } else {
            LumierePlay.LOGGER.error("PipeProxy start failed for: {}", url);
            onResolved.accept(null);
        }
    }

    // --- Direct path (all other platforms) ---

    private static void resolveViaDirect(String url, Quality quality,
                                         Consumer<String> onResolved) {
        String resolved;
        if (needsCookies(url)) {
            String browser = detectBrowser();
            resolved = tryResolve(url, quality, browser);
            if (resolved == null && browser != null) {
                LumierePlay.LOGGER.warn("Cookie resolve failed, retrying without...");
                resolved = tryResolve(url, quality, null);
            }
        } else {
            resolved = tryResolve(url, quality, null);
            if (resolved == null) {
                String browser = detectBrowser();
                if (browser != null) resolved = tryResolve(url, quality, browser);
            }
        }
        onResolved.accept(resolved);
    }

    /**
     * Detects an installed browser by checking PATH.
     * Uses `where` on Windows and `which` on Linux/macOS.
     */
    private static String detectBrowser() {
        String cmd = IS_WINDOWS ? "where" : "which";
        for (String browser : BROWSERS) {
            String[] targets = IS_WINDOWS
                ? new String[]{ browser + ".exe", browser }
                : new String[]{ browser };

            for (String target : targets) {
                try {
                    Process p = new ProcessBuilder(cmd, target)
                        .redirectErrorStream(true).start();
                    p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                    if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) return browser;
                    p.destroyForcibly();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String tryResolve(String url, Quality quality, String browser) {
        Process process = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(YtDlpManager.getBinaryPath().toString());
            cmd.add("--no-playlist");
            cmd.add("--no-warnings");
            cmd.add("-f");
            cmd.add(buildFormatArg(url, quality));
            cmd.add("--get-url");

            if (browser != null) {
                cmd.add("--cookies-from-browser");
                cmd.add(browser);
            }

            if (url.toLowerCase().contains("twitch.tv")) {
                cmd.add("--no-check-certificates");
            }

            cmd.add(url);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            process = pb.start();

            final Process       proc  = process;
            final StringBuilder errSb = new StringBuilder();
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = r.readLine()) != null)
                        errSb.append(line).append("\n");
                } catch (Exception ignored) {}
            }, "yt-dlp-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            String resolved;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                resolved = reader.readLine();
                while (reader.readLine() != null) {}
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            stderrThread.join(3000);

            if (!finished) {
                LumierePlay.LOGGER.error("yt-dlp timed out for {}", url);
                process.destroyForcibly();
                return null;
            }

            int exit = process.exitValue();

            if (exit != 0 || resolved == null || resolved.isEmpty() || !resolved.startsWith("http")) {
                String err = errSb.toString().trim();
                LumierePlay.LOGGER.error("yt-dlp failed (exit={} browser={}):\n{}",
                    exit, browser, err.isEmpty() ? "(no stderr)" : err);
                return null;
            }

            LumierePlay.LOGGER.info("Resolved [{}] {} -> {}...",
                quality.label, url,
                resolved.substring(0, Math.min(60, resolved.length())));
            return resolved;

        } catch (Exception e) {
            LumierePlay.LOGGER.error("tryResolve error: {}", e.getMessage());
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    /**
     * Format selector for the proxy pipe path (all platforms).
     * All supported platforms go through yt-dlp | ffmpeg | FIFO | VLC.
     * HLS m3u8_native preferred — yt-dlp outputs continuous MPEG-TS via
     * --hls-use-mpegts which ffmpeg can re-timestamp cleanly.
     * Twitch uses named quality levels, not height filters.
     */
    private static String buildProxyFormatArg(String url, Quality quality) {
        String lower = url.toLowerCase();

        if (lower.contains("twitch.tv")) {
            return switch (quality) {
                case P720  -> "720p60/720p/best";
                case P480  -> "480p/best";
                case P360  -> "360p/best";
                default    -> "best";
            };
        }

        return switch (quality) {
            case P1080 -> "best[height<=1080][protocol=m3u8_native]/best[height<=1080]/best";
            case P720  -> "best[height<=720][protocol=m3u8_native]/best[height<=720]/best";
            case P480  -> "best[height<=480][protocol=m3u8_native]/best[height<=480]/best";
            case P360  -> "best[height<=360][protocol=m3u8_native]/best[height<=360]/best";
            default    -> "best[protocol=m3u8_native]/best";
        };
    }

    /**
     * Format selector for the direct --get-url path (non-YouTube platforms).
     * Twitch uses its own format names and does not support protocol filters.
     */
    private static String buildFormatArg(String url, Quality quality) {
        if (url.toLowerCase().contains("twitch.tv")) {
            return switch (quality) {
                case P720  -> "720p60/720p/best";
                case P480  -> "480p/best";
                case P360  -> "360p/best";
                default    -> "best";
            };
        }
        return switch (quality) {
            case P1080 -> "best[height<=1080][protocol=https]/best[height<=1080]/best";
            case P720  -> "best[height<=720][protocol=https]/best[height<=720]/best";
            case P480  -> "best[height<=480][protocol=https]/best[height<=480]/best";
            case P360  -> "best[height<=360][protocol=https]/best[height<=360]/best";
            default    -> "best[protocol=https]/best";
        };
    }
}
