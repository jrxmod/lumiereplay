package com.jrxmod.lumiereplay.client.ytdlp;

import com.jrxmod.lumiereplay.LumierePlay;
import com.jrxmod.lumiereplay.client.ytdlp.FfmpegManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-platform pipe proxy: yt-dlp stdout -> ffmpeg -> FIFO -> VLC.
 *
 * YouTube HLS streams contain discontinuous PCR timestamps at segment
 * boundaries. VLC cannot recover from these jumps and freezes video while
 * audio continues. Piping through ffmpeg with +genpts regenerates monotonic
 * PTS/PCR values so VLC receives a clean, continuous TS stream.
 *
 * Pipeline:
 *   yt-dlp -f m3u8_native --hls-use-mpegts -o - URL
 *     | ffmpeg -fflags +genpts+discardcorrupt -i pipe:0 -c copy -f mpegts pipe:1
 *     > FIFO
 *   VLC opens FIFO as a local file
 *
 * Linux / macOS: POSIX named pipe (mkfifo) — zero-copy kernel pipe.
 * Windows: temp file — yt-dlp downloads fully before VLC opens it.
 */
public class PipeProxy {

    private static final Map<String, PipeProxy> ACTIVE = new ConcurrentHashMap<>();

    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    private final Path           pipePath;
    private volatile Process     ytdlpProc;
    private volatile Process     ffmpegProc;
    private volatile boolean     stopped = false;

    private PipeProxy(Path pipePath) {
        this.pipePath = pipePath;
    }

    /**
     * Creates the pipe, starts the yt-dlp | ffmpeg pipeline writing into it,
     * and returns the path VLC should open. Returns null on failure.
     */
    public static PipeProxy start(List<String> ytdlpCmd) {
        try {
            Path pipe = IS_WINDOWS ? createTempFile() : createFifo();
            if (pipe == null) return null;

            PipeProxy proxy = new PipeProxy(pipe);
            ACTIVE.put(pipe.toString(), proxy);

            if (IS_WINDOWS) {
                proxy.startDownload(ytdlpCmd);
            } else {
                proxy.startFifoPump(ytdlpCmd);
            }

            LumierePlay.LOGGER.info("PipeProxy ready: {}", pipe);
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
        Process ff = ffmpegProc;
        if (ff != null) ff.destroyForcibly();
        Process yt = ytdlpProc;
        if (yt != null) yt.destroyForcibly();
        try { Files.deleteIfExists(pipePath); } catch (Exception ignored) {}
        LumierePlay.LOGGER.info("PipeProxy stopped: {}", pipePath);
    }

    // --- Linux / macOS: FIFO ---

    private static Path createFifo() {
        try {
            String name = "lumiereplay_" + UUID.randomUUID().toString().replace("-", "") + ".ts";
            Path   path = Paths.get(System.getProperty("java.io.tmpdir"), name);

            Process mkfifo = new ProcessBuilder("mkfifo", path.toString())
                .redirectErrorStream(true).start();
            mkfifo.getInputStream().transferTo(OutputStream.nullOutputStream());

            if (mkfifo.waitFor() != 0 || !Files.exists(path)) {
                LumierePlay.LOGGER.error("mkfifo failed for {}", path);
                return null;
            }
            return path;
        } catch (Exception e) {
            LumierePlay.LOGGER.error("createFifo error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Runs yt-dlp piped through ffmpeg, writing the result into the FIFO.
     *
     * ffmpeg flags:
     *   -fflags +genpts          — regenerate PTS when missing or discontinuous
     *   -fflags +discardcorrupt  — skip corrupt packets instead of aborting
     *   -c copy                  — no re-encoding, stream copy only
     *   -f mpegts                — output MPEG-TS (required for streaming)
     *
     * This fixes Invalid PCR / TS discontinuity errors produced by YouTube
     * HLS streams at every segment boundary, which caused VLC to freeze video.
     */
    private void startFifoPump(List<String> ytdlpCmd) {
        Thread t = new Thread(() -> {
            try {
                // Start yt-dlp
                ProcessBuilder ytPb = new ProcessBuilder(ytdlpCmd);
                ytPb.redirectErrorStream(false);
                Process ytProc = ytPb.start();
                ytdlpProc = ytProc;

                drainStderr(ytProc.getErrorStream(), "yt-dlp-stderr");

                // Start ffmpeg reading from yt-dlp stdout
                List<String> ffCmd = new ArrayList<>();
                ffCmd.add(FfmpegManager.getBinaryPath().toString());
                ffCmd.add("-fflags");              ffCmd.add("+genpts+discardcorrupt+igndts");
                ffCmd.add("-avoid_negative_ts");   ffCmd.add("make_zero");
                ffCmd.add("-max_interleave_delta"); ffCmd.add("0");
                ffCmd.add("-i");                   ffCmd.add("pipe:0");
                ffCmd.add("-c");                   ffCmd.add("copy");
                ffCmd.add("-f");                   ffCmd.add("mpegts");
                ffCmd.add("-mpegts_flags");        ffCmd.add("+pat_pmt_at_frames");
                ffCmd.add("pipe:1");

                ProcessBuilder ffPb = new ProcessBuilder(ffCmd);
                ffPb.redirectErrorStream(false);
                Process ff = ffPb.start();
                ffmpegProc = ff;

                drainStderr(ff.getErrorStream(), "ffmpeg-stderr");

                // Pump yt-dlp stdout -> ffmpeg stdin in a dedicated thread
                Thread pump = new Thread(() -> {
                    try (InputStream ytOut  = ytProc.getInputStream();
                         OutputStream ffIn = ff.getOutputStream()) {
                        byte[] buf = new byte[65536];
                        int    n;
                        while (!stopped && (n = ytOut.read(buf)) != -1) {
                            try {
                                ffIn.write(buf, 0, n);
                            } catch (IOException broken) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        if (!stopped) LumierePlay.LOGGER.warn("yt-dlp->ffmpeg pump: {}", e.getMessage());
                    } finally {
                        // Signal EOF to ffmpeg
                        try { ff.getOutputStream().close(); } catch (Exception ignored) {}
                    }
                }, "lumiereplay-yt-pump");
                pump.setDaemon(true);
                pump.start();

                // ffmpeg stdout -> FIFO
                try (InputStream ffOut = ff.getInputStream();
                     OutputStream fifo = Files.newOutputStream(pipePath)) {
                    byte[] buf = new byte[65536];
                    int    n;
                    while (!stopped && (n = ffOut.read(buf)) != -1) {
                        try {
                            fifo.write(buf, 0, n);
                        } catch (IOException broken) {
                            break;
                        }
                    }
                }

                ff.waitFor();
                ytProc.waitFor();
                LumierePlay.LOGGER.info("PipeProxy pipeline finished: {}", pipePath);

            } catch (Exception e) {
                if (!stopped) LumierePlay.LOGGER.warn("PipeProxy pump error: {}", e.getMessage());
            }
        }, "lumiereplay-pipe-pump");
        t.setDaemon(true);
        t.start();
    }

    // --- Windows: temp file ---

    private static Path createTempFile() {
        try {
            return Files.createTempFile("lumiereplay_", ".ts");
        } catch (Exception e) {
            LumierePlay.LOGGER.error("createTempFile error: {}", e.getMessage());
            return null;
        }
    }

    private void startDownload(List<String> ytdlpCmd) {
        Thread t = new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>(ytdlpCmd);
                int dashO = cmd.indexOf("-o");
                if (dashO != -1 && dashO + 1 < cmd.size()) {
                    cmd.set(dashO + 1, pipePath.toString());
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
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

    private static void drainStderr(InputStream err, String threadName) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                while (err.read(buf) != -1) {}
            } catch (Exception ignored) {}
        }, "lumiereplay-" + threadName);
        t.setDaemon(true);
        t.start();
    }
}
