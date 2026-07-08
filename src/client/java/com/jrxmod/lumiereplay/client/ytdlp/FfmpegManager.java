package com.jrxmod.lumiereplay.client.ytdlp;

import com.jrxmod.lumiereplay.LumierePlay;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages the ffmpeg binary across Linux, Windows and macOS.
 * Priority: system ffmpeg > bundled in .minecraft/lumiereplay/bin/.
 * Downloads a static build only if system ffmpeg is not found.
 *
 * Download sources:
 *   Linux   — yt-dlp/FFmpeg-Builds tar.xz, extracted via system `tar`
 *   Windows — yt-dlp/FFmpeg-Builds zip,    extracted via Java ZipInputStream
 *   macOS   — evermeet.cx static zip,       extracted via Java ZipInputStream
 *
 * ffmpeg is required for the yt-dlp | ffmpeg | FIFO pipeline that fixes
 * YouTube HLS PCR discontinuities (sefc=1 + IPv6 rotation issue, 2026).
 */
public class FfmpegManager {

    private static final OS CURRENT_OS = detectOs();

    private static Path    binaryPath;
    private static boolean ready = false;

    private enum OS { LINUX, WINDOWS, MACOS }

    private static OS detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("win")) return OS.WINDOWS;
        if (name.contains("mac")) return OS.MACOS;
        return OS.LINUX;
    }

    private static String binaryName() {
        return CURRENT_OS == OS.WINDOWS ? "ffmpeg.exe" : "ffmpeg";
    }

    // Archive name inside the bin directory — used to locate the binary after extraction
    private static String archiveName() {
        return switch (CURRENT_OS) {
            case WINDOWS -> "ffmpeg-win64.zip";
            case MACOS   -> "ffmpeg-macos.zip";
            default      -> "ffmpeg-linux64.tar.xz";
        };
    }

    private static String downloadUrl() {
        return switch (CURRENT_OS) {
            case WINDOWS ->
                "https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/" +
                "ffmpeg-master-latest-win64-gpl.zip";
            case MACOS ->
                "https://evermeet.cx/ffmpeg/getrelease/ffmpeg/zip";
            default ->
                "https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/" +
                "ffmpeg-master-latest-linux64-gpl.tar.xz";
        };
    }

    public static void initialize() {
        Path system = findSystemFfmpeg();
        if (system != null) {
            binaryPath = system;
            ready      = true;
            LumierePlay.LOGGER.info("Using system ffmpeg: {}", binaryPath);
            return;
        }

        Path gameDir = MinecraftClient.getInstance().runDirectory.toPath();
        Path binDir  = gameDir.resolve("lumiereplay").resolve("bin");

        try {
            Files.createDirectories(binDir);
        } catch (Exception e) {
            LumierePlay.LOGGER.error("Could not create lumiereplay/bin: {}", e.getMessage());
            return;
        }

        binaryPath = binDir.resolve(binaryName());

        if (Files.exists(binaryPath) && binaryPath.toFile().canExecute()) {
            ready = true;
            LumierePlay.LOGGER.info("Using bundled ffmpeg: {}", binaryPath);
        } else {
            LumierePlay.LOGGER.info("Downloading ffmpeg for {} ...", CURRENT_OS);
            downloadAsync(binDir);
        }
    }

    private static Path findSystemFfmpeg() {
        if (CURRENT_OS == OS.WINDOWS) {
            return findViaCommand("where", "ffmpeg.exe");
        }

        String[] candidates = CURRENT_OS == OS.MACOS
            ? new String[]{
                "/usr/local/bin/ffmpeg",
                "/opt/homebrew/bin/ffmpeg"
              }
            : new String[]{
                "/usr/bin/ffmpeg",
                "/usr/local/bin/ffmpeg"
              };

        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.exists(p) && p.toFile().canExecute()) return p;
        }

        return findViaCommand("which", "ffmpeg");
    }

    private static Path findViaCommand(String cmd, String target) {
        try {
            Process proc = new ProcessBuilder(cmd, target)
                .redirectErrorStream(true).start();
            String line;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                line = r.readLine();
            }
            if (proc.waitFor() == 0 && line != null && !line.isBlank()) {
                Path p = Path.of(line.trim());
                if (Files.exists(p)) return p;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void downloadAsync(Path binDir) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl()))
                    .header("User-Agent", "lumiereplay-mod/0.2.1")
                    .GET()
                    .build();

                LumierePlay.LOGGER.info("Downloading ffmpeg from: {}", downloadUrl());

                HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    LumierePlay.LOGGER.error("ffmpeg download failed HTTP {}",
                        response.statusCode());
                    return;
                }

                Path archivePath = binDir.resolve(archiveName());
                Files.copy(response.body(), archivePath, StandardCopyOption.REPLACE_EXISTING);

                // Extract the ffmpeg binary from the downloaded archive
                boolean extracted = switch (CURRENT_OS) {
                    case WINDOWS, MACOS -> extractFromZip(archivePath, binaryPath);
                    default             -> extractFromTarXz(archivePath, binaryPath, binDir);
                };

                if (!extracted) {
                    LumierePlay.LOGGER.error("ffmpeg extraction failed");
                    return;
                }

                Files.deleteIfExists(archivePath);

                if (CURRENT_OS != OS.WINDOWS) {
                    try {
                        Files.setPosixFilePermissions(binaryPath, Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE
                        ));
                    } catch (UnsupportedOperationException ignored) {
                        binaryPath.toFile().setExecutable(true, false);
                    }
                }

                ready = true;
                LumierePlay.LOGGER.info("ffmpeg ready at {}", binaryPath);

            } catch (Exception e) {
                LumierePlay.LOGGER.error("Failed to download ffmpeg: {}", e.getMessage());
            }
        });
    }

    /**
     * Extracts the ffmpeg binary from a zip archive.
     * Searches for an entry whose name ends with the binary name (ffmpeg or ffmpeg.exe).
     */
    private static boolean extractFromZip(Path archivePath, Path dest) {
        String target = binaryName();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archivePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = Path.of(entry.getName()).getFileName().toString();
                if (name.equals(target)) {
                    try (OutputStream out = Files.newOutputStream(dest)) {
                        zis.transferTo(out);
                    }
                    LumierePlay.LOGGER.info("Extracted {} from zip", target);
                    return true;
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            LumierePlay.LOGGER.error("Zip extraction error: {}", e.getMessage());
        }
        LumierePlay.LOGGER.error("ffmpeg binary not found inside zip archive");
        return false;
    }

    /**
     * Extracts the ffmpeg binary from a tar.xz archive using the system `tar` command.
     * Available on all Linux and macOS systems by default.
     * The yt-dlp/FFmpeg-Builds archive structure is:
     *   ffmpeg-master-latest-linux64-gpl/bin/ffmpeg
     */
    private static boolean extractFromTarXz(Path archivePath, Path dest, Path binDir) {
        try {
            // Extract only the ffmpeg binary, strip the leading directory components
            Process proc = new ProcessBuilder(
                "tar", "xf", archivePath.toString(),
                "--wildcards", "*/bin/ffmpeg",
                "--strip-components=2",
                "-C", binDir.toString()
            ).redirectErrorStream(true).start();

            proc.getInputStream().transferTo(OutputStream.nullOutputStream());
            int exit = proc.waitFor();

            if (exit == 0 && Files.exists(dest)) {
                LumierePlay.LOGGER.info("Extracted ffmpeg from tar.xz");
                return true;
            }

            LumierePlay.LOGGER.error("tar extraction failed (exit={})", exit);
            return false;

        } catch (Exception e) {
            LumierePlay.LOGGER.error("tar extraction error: {}", e.getMessage());
            return false;
        }
    }

    public static boolean isReady()    { return ready; }
    public static Path getBinaryPath() { return binaryPath; }
}
