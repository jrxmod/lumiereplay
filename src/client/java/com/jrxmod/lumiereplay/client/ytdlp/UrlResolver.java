package com.jrxmod.lumiereplay.client.ytdlp;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.client.LumiereConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Resolves platform URLs to playable URLs via yt-dlp.
 *
 * Three resolution strategies, chosen by content kind:
 *
 *   FILE (YouTube and other on demand). yt-dlp downloads the media into a
 *             completed temporary file that VLC reads end to end. YouTube may
 *             serve only split video and audio streams (e.g. on a throttled
 *             network), so a muxed file is the portable way to get one file VLC
 *             can play; a VOD stays seekable to the end and no signed CDN
 *             headers are needed. Until the download finishes a buffering phase
 *             precedes playback.
 *
 *   STREAM (live broadcasts). yt-dlp --get-url returns a single stream URL that
 *             VLC fetches directly over the network. A network stream has no
 *             finite end, so a live broadcast keeps playing instead of freezing
 *             at the end of a still-growing temp file. Works the same on Windows
 *             and Linux.
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

        /**
         * Maps the quality key travelling in projector packets back to an entry.
         * Unknown or absent keys resolve to the default the GUI opens with, so a
         * server storing a key from a newer or older client never breaks playback.
         */
        public static Quality fromKey(String key) {
            if (key != null) {
                for (Quality q : values()) if (q.key.equals(key)) return q;
            }
            return P720;
        }

        /** Key form used on the wire and in the block entity. */
        public String key() { return key; }
    }

    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final String[] BROWSERS = {
        "firefox", "chrome", "chromium", "brave", "opera"
    };

    /**
     * Browser user agent used when a resolved stream URL has to be fetched by
     * VLC itself. Some CDNs (VK/OK use the okcdn.net origin) reject requests
     * that do not look like a browser, so VLC is told to send this header.
     */
    private static final String CHROME_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";

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

    /**
     * True for the sources handled by the temp-file download path. YouTube is
     * the only supported host that may require yt-dlp to merge split video and
     * audio into one file, so it must be downloaded to a completed file. Other
     * platforms serve a single muxed stream that VLC can play directly over the
     * network (see {@link #resolveViaDirect}), which also streams live sources
     * instead of freezing at the end of a still-growing file.
     */
    private static boolean isYouTube(String url) {
        String lower = url.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be");
    }

    private static boolean needsCookies(String url) {
        String lower = url.toLowerCase();
        for (String p : COOKIE_REQUIRED)
            if (lower.contains(p)) return true;
        return false;
    }

    /**
     * Extra libvlc arguments to send when VLC plays a resolved stream URL
     * directly. Live sources on the VK/OK origin serve a signed HLS stream that
     * is rejected unless the request carries a browser Referer and User-Agent,
     * which VLC does not send by default. Added only for those hosts; every
     * other source returns an empty array so existing playback is unchanged.
     *
     * @return libvlc arguments (each a single "flag=value" string), or an empty
     *         array when no extra headers are required.
     */
    public static String[] vlcHttpArgs(String origin) {
        String lower = origin == null ? "" : origin.toLowerCase();
        boolean vkOrOk = lower.contains("vk.com")
            || lower.contains("vkvideo.ru")
            || lower.contains("ok.ru");
        if (!vkOrOk || !isLive(origin)) return new String[0];
        // Colon-prefixed so the values are applied as media options to the
        // player that opens this stream, rather than as factory arguments.
        return new String[]{
            ":http-referrer=" + origin,
            ":http-user-agent=" + CHROME_USER_AGENT
        };
    }

    /**
     * True for live broadcasts. A live source never ends, so it cannot be
     * downloaded to a finite local file (VLC would read up to the current end of
     * the file and stop). It is resolved to a stream URL that VLC plays over the
     * network instead. Everything else is treated as on demand and is downloaded
     * to a completed file, which sidesteps the signed CDN URLs some hosts serve
     * (VLC would need Referer/User-Agent headers it does not send).
     */
    private static boolean isLive(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("live.vkvideo.ru")) return true;
        if (lower.contains("twitch.tv")) {
            // Twitch channels are live; VODs and clips are on demand. This keeps
            // the common channel case on the streaming path while allowing a
            // past broadcast to be downloaded and seeked like any other file.
            return !lower.contains("/videos/")
                && !lower.contains("/vods/")
                && !lower.contains("/v/")
                && !lower.contains("/clip");
        }
        return false;
    }

    public static Runnable resolveAsync(String url, Consumer<String> onResolved) {
        return resolveAsync(url, Quality.BEST, onResolved);
    }

    public static Runnable resolveAsync(String url, Quality quality, Consumer<String> onResolved) {
        return resolveAsync(url, quality, 0L, onResolved);
    }

    /**
     * Resolves a source, optionally asking for playback to begin at startMs.
     *
     * The offset is only meaningful for the piped path, where yt-dlp is told to
     * skip ahead with --download-sections. A resolved stream URL cannot be
     * offset here: seeking is done through VLC once the media is open, so the
     * direct path ignores startMs and the caller seeks after the first frame.
     *
     * Returns a cancel action. A download to a temp file can take long (a whole
     * movie at a throttled bandwidth), and the caller must be able to abandon it
     * when the source is stopped or changed; otherwise yt-dlp keeps downloading
     * in the background long after the user cleared the source. The cancel action
     * stops the underlying download and is safe to invoke at any time.
     */
    public static Runnable resolveAsync(String url, Quality quality, long startMs,
                                        Consumer<String> onResolved) {
        if (!YtDlpManager.isReady()) {
            LumierePlay.LOGGER.warn("yt-dlp not ready, cannot resolve: {}", url);
            onResolved.accept(null);
            return () -> {};
        }

        AtomicReference<Runnable> cancelRef = new AtomicReference<>(() -> {});
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Runnable cancel = () -> {
            if (cancelled.compareAndSet(false, true)) {
                cancelRef.get().run();
            }
        };

        CompletableFuture.runAsync(() -> {
            if (cancelled.get()) {
                onResolved.accept(null);
                return;
            }
            if (isYouTube(url) || !isLive(url)) {
                // YouTube and on demand sources are downloaded to a completed
                // temp file and played from disk, so VLC never needs to send
                // signed headers and a VOD stays seekable to the end.
                resolveViaProxy(url, quality, startMs, onResolved, cancelRef, cancelled);
            } else {
                // Live sources are handed to VLC as a network stream, which it
                // treats as unrestricted in length and never stops at a file end.
                resolveViaDirect(url, quality, onResolved);
            }
        });
        return cancel;
    }

    // --- Temp-file download path (YouTube and other on demand sources) ---

    /**
     * Builds the yt-dlp command and starts a PipeProxy that downloads into a
     * completed temporary file. Returns that file path to the caller.
     *
     * Cookie import and IPv4 pinning are intentionally not injected here. Both
     * change extraction outcome in ways that depend on the host (a Chromium
     * browser locks its cookie database while running, so --cookies-from-browser
     * fails; a network that prefers IPv6 breaks under --force-ipv4). They remain
     * available on demand through network.ytdlp_args, which is appended last and
     * therefore wins over the mod's own flags.
     */
    private static void resolveViaProxy(String url, Quality quality, long startMs,
                                        Consumer<String> onResolved,
                                        AtomicReference<Runnable> cancelRef,
                                        AtomicBoolean cancelled) {
        List<String> cmd = new ArrayList<>();
        cmd.add(YtDlpManager.getBinaryPath().toString());
        cmd.add("--no-playlist");
        cmd.add("--no-warnings");
        cmd.add("--no-part");
        cmd.add("-f");
        cmd.add(buildProxyFormatArg(url, quality));
        cmd.add("--hls-use-mpegts");

        // Skipping ahead is done by the extractor so the pipe only ever carries
        // media from the requested position on. Open ended ranges use "inf" as the
        // end marker. This path depends on yt-dlp being able to cut while piping
        // and is therefore opt in; a failure is reported as a null resolution and
        // the caller retries without an offset.
        if (startMs > 0) {
            cmd.add("--download-sections");
            cmd.add("*" + (startMs / 1000L) + "-inf");
        }

        String ytdlpProxy = configuredProxy();
        if (ytdlpProxy != null) cmd.add("--proxy=" + ytdlpProxy);

        cmd.add("-o");
        cmd.add("-");

        appendConfiguredArgs(cmd);
        cmd.add(url);

        // Expose the base path as the cancellation key before the blocking
        // warm-up starts, so a stop or source change can abandon the download.
        Path base = PipeProxy.newTempBase();
        if (base == null) {
            LumierePlay.LOGGER.error("No temp base path for: {}", url);
            onResolved.accept(null);
            return;
        }
        cancelRef.set(() -> PipeProxy.release(base.toString()));
        if (cancelled.get()) {
            onResolved.accept(null);
            return;
        }

        PipeProxy proxy = PipeProxy.start(base, cmd);
        if (proxy != null) {
            LumierePlay.LOGGER.info("PipeProxy [{}] started for: {}", quality.label, url);
            onResolved.accept(proxy.getPath());
        } else {
            LumierePlay.LOGGER.error("PipeProxy start failed for: {}", url);
            onResolved.accept(null);
        }
    }

    // --- Direct path (non-YouTube platforms) ---

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
     * Appends the operator supplied yt-dlp arguments from the config file, skipping
     * empty entries and never more than a sane handful. Applied last before the URL,
     * so a repeated option here overrides the one the mod passed.
     */
    private static void appendConfiguredArgs(List<String> cmd) {
        String[] extra = LumiereConfig.get().network.ytdlp_args;
        if (extra == null) return;
        int added = 0;
        for (String arg : extra) {
            if (arg == null || added >= 16) continue;
            if (arg.isBlank()) continue;
            cmd.add(arg);
            added++;
        }
    }

    /**
     * Proxy from config/lumiereplay.json, or null when unset. Surrounding
     * whitespace and bare host values are tolerated because hand edited configs
     * often carry neither a scheme nor a consistent quoting.
     */
    private static String configuredProxy() {
        String proxy = LumiereConfig.get().network.proxy;
        if (proxy == null) return null;
        proxy = proxy.trim();
        return proxy.isEmpty() ? null : proxy;
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

            String proxy = configuredProxy();
            if (proxy != null) cmd.add("--proxy=" + proxy);

            if (browser != null) {
                cmd.add("--cookies-from-browser");
                cmd.add(browser);
            }

            if (url.toLowerCase().contains("twitch.tv")) {
                cmd.add("--no-check-certificates");
            }

            appendConfiguredArgs(cmd);
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
     * Format selector for the temp-file download path. YouTube is requested as
     * split video and audio because a throttled network may serve only those,
     * and yt-dlp merges them into a single completed file. Every other on
     * demand source is a single muxed stream, so a combined format is chosen
     * and tied to the requested height cap.
     */
    private static String buildProxyFormatArg(String url, Quality quality) {
        if (isYouTube(url)) {
            return switch (quality) {
                case P1080 -> "bv*[height<=1080]+ba/b[height<=1080]/b";
                case P720  -> "bv*[height<=720]+ba/b[height<=720]/b";
                case P480  -> "bv*[height<=480]+ba/b[height<=480]/b";
                case P360  -> "bv*[height<=360]+ba/b[height<=360]/b";
                default    -> "bv*+ba/b";
            };
        }
        return switch (quality) {
            case P1080 -> "best[height<=1080]/best";
            case P720  -> "best[height<=720]/best";
            case P480  -> "best[height<=480]/best";
            case P360  -> "best[height<=360]/best";
            default    -> "best";
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
        // A single muxed format is required because --get-url can only print
        // one URL. Split video+audio needs an ffmpeg merge into a file, which a
        // direct stream cannot do, so a combined format is requested instead and
        // the height is capped to control size. The bare /best tail covers hosts
        // that expose only one combined stream.
        return switch (quality) {
            case P1080 -> "best[height<=1080]/best";
            case P720  -> "best[height<=720]/best";
            case P480  -> "best[height<=480]/best";
            case P360  -> "best[height<=360]/best";
            default    -> "best";
        };
    }
}
