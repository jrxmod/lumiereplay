package com.jrxmod.lumiereplay.client.ytdlp;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.client.LumiereConfig;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Downloads a platform stream into a temporary file that VLC opens directly.
 *
 * A FIFO (named pipe) works for HLS/MPEG-TS but not for the split streams
 * yt-dlp often has to merge (progressive MP4's moov atom sits at the end of the
 * file, and a pipe is not seekable, so a demuxer cannot read it). Writing to a
 * seekable temporary file lets VLC read both a muxed MP4 and MPEG-TS.
 *
 * The output path uses a %(ext)s placeholder because yt-dlp chooses the
 * container extension itself: a hard-coded ".ts" target is overridden by yt-dlp
 * (for a split video+audio it writes ".mp4"/".mkv"), which would leave VLC
 * looking for a file that never existed. The real file is located after the
 * download starts and the located path is what VLC receives. yt-dlp's stderr is
 * logged so a network or format failure is visible in the game log instead of
 * being swallowed.
 */
public class PipeProxy {

    private static final Map<String, PipeProxy> ACTIVE = new ConcurrentHashMap<>();

    /**
     * Longest wait for the download to produce a playable file. For split
     * streams (bestvideo+bestaudio) yt-dlp downloads both tracks and merges them
     * into one file only at the end, so no data is visible until the download
     * completes. A generous timeout covers that case; progress is logged so the
     * wait does not look like a hang.
     */
    private static final long WARM_START_TIMEOUT_MS = 30L * 60L * 1000L;

    /** Intervals at which download progress is logged, in millis. */
    private static final long PROGRESS_LOG_INTERVAL_MS = 15_000L;

    /**
     * How long a download may show no byte growth before it is treated as
     * stalled. A throttled network still increases the file size slowly, so a
     * grown file is kept buffering; a total stall (no growth) is failed early
     * instead of blocking the whole timeout.
     */
    private static final long STALL_TIMEOUT_MS = 90_000L;

    private volatile Path       pipePath;
    private volatile Process     ytdlpProc;
    private volatile boolean     stopped = false;

    private PipeProxy(Path pipePath) {
        this.pipePath = pipePath;
    }

    /**
     * Builds a unique temp base path, starts yt-dlp writing to it, waits for a
     * playable file to exist, and returns the path VLC should open. Returns
     * null on failure.
     *
     * The base path is supplied by the caller (see {@link #newTempBase()}) so it
     * can be used as the cancellation key before this method blocks on warm-up.
     */
    public static PipeProxy start(Path pipe, List<String> ytdlpCmd) {
        if (pipe == null) return null;
        try {
            PipeProxy proxy = new PipeProxy(pipe);
            ACTIVE.put(pipe.toString(), proxy);

            proxy.startDownload(ytdlpCmd);
            if (!proxy.awaitWarmup()) {
                ACTIVE.remove(pipe.toString());
                proxy.stop();
                LumierePlay.LOGGER.error(
                    "Warm start failed for {}: no data from yt-dlp", pipe);
                return null;
            }

            // Re-key under the located file path. getPath() returns this path,
            // and the caller releases with it, so the entry must match.
            ACTIVE.remove(pipe.toString());
            String active = proxy.getPath();
            ACTIVE.put(active, proxy);
            LumierePlay.LOGGER.info("PipeProxy ready: {}", active);
            return proxy;

        } catch (Exception e) {
            LumierePlay.LOGGER.error("PipeProxy start failed: {}", e.getMessage());
            return null;
        }
    }

    public String getPath() { return pipePath.toString(); }

    public static void release(String path) {
        PipeProxy p = ACTIVE.remove(path);
        if (p != null) p.stop();
    }

    private void stop() {
        stopped = true;
        Process yt = ytdlpProc;
        if (yt != null) yt.destroyForcibly();
        deleteOutputs();
        LumierePlay.LOGGER.info("PipeProxy stopped: {}", pipePath);
    }

    /**
     * Removes every file this download created in the temp directory: the merged
     * output yt-dlp produced plus the intermediate per-track files it used while
     * merging. Matching on the unique base prefix keeps the deletion scoped to
     * this download.
     */
    private void deleteOutputs() {
        Path base = pipePath.getParent();
        if (base == null) return;
        String prefix = pipePath.getFileName().toString();
        try (var stream = Files.list(base)) {
            stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    /**
     * Builds a unique base path in a disk backed temp directory. No file is
     * created here: yt-dlp appends {@code %(ext)s} and resolves the real
     * container extension itself, so the base name must not carry an extension
     * that yt-dlp would misinterpret or override. The random suffix keeps the
     * path unique per projector and per download.
     *
     * The system temp directory is avoided because on Linux it is commonly a
     * tmpfs mount (RAM), so buffering a whole video there would consume main
     * memory. A folder inside the mod's own directory is on the real disk.
     */
    /**
     * Builds a unique base path in a disk backed temp directory. No file is
     * created here: yt-dlp appends {@code %(ext)s} and resolves the real
     * container extension itself, so the base name must not carry an extension
     * that yt-dlp would misinterpret or override. The random suffix keeps the
     * path unique per projector and per download.
     *
     * The system temp directory is avoided because on Linux it is commonly a
     * tmpfs mount (RAM), so buffering a whole video there would consume main
     * memory. A folder inside the mod's own directory is on the real disk.
     */
    public static Path newTempBase() {
        try {
            Path gameDir = MinecraftClient.getInstance().runDirectory.toPath();
            Path tempDir = gameDir.resolve("lumiereplay").resolve("temp").normalize();
            Files.createDirectories(tempDir);
            return tempDir.resolve("lumiereplay_" + UUID.randomUUID());
        } catch (Exception e) {
            LumierePlay.LOGGER.error("newTempBase error: {}", e.getMessage());
            return null;
        }
    }

    private void startDownload(List<String> ytdlpCmd) {
        Thread t = new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>(ytdlpCmd);
                int dashO = cmd.indexOf("-o");
                if (dashO != -1 && dashO + 1 < cmd.size()) {
                    cmd.set(dashO + 1, pipePath + ".%(ext)s");
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                applyProxy(pb);
                Process proc = pb.start();
                ytdlpProc = proc;

                drainStderr(proc.getErrorStream(), "yt-dlp-stderr");
                proc.getInputStream().transferTo(OutputStream.nullOutputStream());
                proc.waitFor();
                LumierePlay.LOGGER.info("PipeProxy download finished: {}", pipePath);

            } catch (Exception e) {
                if (!stopped) LumierePlay.LOGGER.warn("PipeProxy download error: {}", e.getMessage());
            }
        }, "lumiereplay-pipe-download");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Blocks until yt-dlp has finished writing a complete file that VLC can read
     * end to end, or until the timeout elapses. A local file is read up to its
     * end and then stops, so handing VLC a still growing file would freeze it at
     * the current end. Only a finished download is therefore accepted; progress
     * is logged periodically and a stall (no byte growth) fails early instead of
     * blocking the whole timeout.
     */
    private boolean awaitWarmup() {
        long deadline = System.currentTimeMillis() + WARM_START_TIMEOUT_MS;
        long lastLog = System.currentTimeMillis();
        long lastBytes = -1L;
        long lastGrowth = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            if (stopped) return false;
            Process p = ytdlpProc;
            if (p == null) {
                try { Thread.sleep(100); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                continue;
            }
            if (!p.isAlive()) {
                return acceptCompleted();
            }

            long bytes = totalBytes();
            if (bytes != lastBytes) {
                lastBytes = bytes;
                lastGrowth = System.currentTimeMillis();
            }

            long now = System.currentTimeMillis();
            if (now - lastLog >= PROGRESS_LOG_INTERVAL_MS) {
                lastLog = now;
                LumierePlay.LOGGER.info(
                    "PipeProxy downloading {} ({} bytes, yt-dlp running)", pipePath, bytes);
            }
            if (bytes > 0 && now - lastGrowth > STALL_TIMEOUT_MS) {
                LumierePlay.LOGGER.warn(
                    "PipeProxy download stalled (no growth for {} ms): {}",
                    now - lastGrowth, pipePath);
                return false;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        LumierePlay.LOGGER.warn("PipeProxy warm-up timed out for: {}", pipePath);
        return acceptCompleted();
    }

    /**
     * Total bytes present under this download's base name so far, summing the
     * merged output and the intermediate per-track files yt-dlp writes. This is
     * the best proxy for "is the download making progress" because the merged
     * file does not exist until the very end.
     */
    private long totalBytes() {
        Path base = pipePath.getParent();
        if (base == null) return 0L;
        String prefix = pipePath.getFileName().toString();
        long total = 0L;
        try (var stream = Files.list(base)) {
            java.util.Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (!p.getFileName().toString().startsWith(prefix)) continue;
                try { total += Files.size(p); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            return 0L;
        }
        return total;
    }

    /**
     * Called when yt-dlp has exited. Accepts the download when a merged output
     * file exists and is non-empty; rejects it (with a logged reason) when it
     * wrote nothing. The exit code is reported to make the failure diagnosable.
     */
    private boolean acceptCompleted() {
        Process yt = ytdlpProc;
        int exit = (yt != null) ? yt.exitValue() : -1;
        Path located = locateOutput();
        if (located == null || !Files.exists(located)) {
            LumierePlay.LOGGER.warn(
                "yt-dlp exited (exit={}) without writing a file: {}", exit, pipePath);
            return false;
        }
        long size = -1L;
        try { size = Files.size(located); } catch (Exception ignored) {}
        if (size <= 0L) {
            LumierePlay.LOGGER.warn(
                "yt-dlp exited (exit={}) with an empty file: {}", exit, located);
            return false;
        }
        pipePath = located;
        LumierePlay.LOGGER.info("PipeProxy download complete (exit={}, {} bytes): {}",
            exit, size, pipePath);
        return true;
    }

    /**
     * Finds the file yt-dlp actually produced for this download. yt-dlp writes
     * to {@code <base>.<ext>} (e.g. .mp4, .mkv, .webm), so the bare base path
     * may not exist. The unique base name is matched as a prefix, which scopes
     * this search to this download. Intermediate track files used while merging
     * (named {@code <base>.f<id>.<ext>}) and {@code .part} files are skipped so
     * VLC never receives a partial or audio-less track.
     */
    private Path locateOutput() {
        Path base = pipePath.getParent();
        if (base == null) return null;
        String prefix = pipePath.getFileName().toString();
        try (var stream = Files.list(base)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .filter(p -> !p.getFileName().toString().endsWith(".part"))
                .filter(p -> !p.getFileName().toString().matches(
                    Pattern.quote(prefix) + "\\.f\\d+\\..*"))
                .max(java.util.Comparator.comparingLong(
                    p -> { try { return Files.size(p); } catch (Exception e) { return 0L; } }))
                .orElse(pipePath);
        } catch (Exception e) {
            return pipePath;
        }
    }

    /**
     * Points yt-dlp at the proxy from config/lumiereplay.json.
     * Both tools read these variables from the environment, so the setting also
     * covers requests the tool makes on its own. Without a configured proxy the
     * inherited environment is left untouched.
     */
    private static void applyProxy(ProcessBuilder pb) {
        String proxy = LumiereConfig.get().network.proxy;
        if (proxy == null) return;
        proxy = proxy.trim();
        if (proxy.isEmpty()) return;

        Map<String, String> env = pb.environment();
        env.put("HTTP_PROXY", proxy);
        env.put("http_proxy", proxy);
        env.put("HTTPS_PROXY", proxy);
        env.put("https_proxy", proxy);
        env.put("ALL_PROXY", proxy);
        env.put("all_proxy", proxy);
    }

    /**
     * Captures yt-dlp stderr and forwards it to the mod logger. Failure
     * messages from yt-dlp were previously dropped, which made any network or
     * format problem look like a silent "no data" failure.
     */
    private static void drainStderr(InputStream err, String threadName) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(err))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("ERROR:")) {
                        LumierePlay.LOGGER.error("[yt-dlp] {}", line);
                    } else if (line.startsWith("WARNING:")) {
                        LumierePlay.LOGGER.warn("[yt-dlp] {}", line);
                    }
                }
            } catch (Exception ignored) {}
        }, "lumiereplay-" + threadName);
        t.setDaemon(true);
        t.start();
    }
}
