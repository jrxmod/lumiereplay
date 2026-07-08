package com.jrxmod.lumiereplay.client.ytdlp;

import com.jrxmod.lumiereplay.LumierePlay;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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

/**
 * Manages the yt-dlp binary across Linux, Windows and macOS.
 * Priority: system yt-dlp > bundled in .minecraft/lumiereplay/bin/.
 * Downloads the correct platform binary only if system yt-dlp is not found.
 */
public class YtDlpManager {

    // Detected once at class load — safe to query from any thread
    private static final OS CURRENT_OS = detectOs();

    private static Path    binaryPath;
    private static boolean ready = false;

    private enum OS { LINUX, WINDOWS, MACOS }

    private static OS detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("win"))  return OS.WINDOWS;
        if (name.contains("mac"))  return OS.MACOS;
        return OS.LINUX;
    }

    // Returns the correct yt-dlp release asset name for this platform
    private static String binaryName() {
        return switch (CURRENT_OS) {
            case WINDOWS -> "yt-dlp.exe";
            case MACOS   -> "yt-dlp_macos";
            default      -> "yt-dlp";
        };
    }

    private static String downloadUrl() {
        return "https://github.com/yt-dlp/yt-dlp/releases/latest/download/" + binaryName();
    }

    public static void initialize() {
        Path system = findSystemYtDlp();
        if (system != null) {
            binaryPath = system;
            ready      = true;
            LumierePlay.LOGGER.info("Using system yt-dlp: {}", binaryPath);
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
            LumierePlay.LOGGER.info("Using bundled yt-dlp: {}", binaryPath);
        } else {
            LumierePlay.LOGGER.info("Downloading yt-dlp for {} ...", CURRENT_OS);
            downloadAsync();
        }
    }

    private static Path findSystemYtDlp() {
        // On Windows use `where`, on Unix use `which`
        if (CURRENT_OS == OS.WINDOWS) {
            return findViaCommand("where", "yt-dlp.exe");
        }

        // macOS Homebrew installs to /usr/local/bin (Intel) or /opt/homebrew/bin (Apple Silicon)
        String[] candidates = CURRENT_OS == OS.MACOS
            ? new String[]{
                "/usr/local/bin/yt-dlp",
                "/opt/homebrew/bin/yt-dlp",
                System.getProperty("user.home") + "/bin/yt-dlp"
              }
            : new String[]{
                "/usr/bin/yt-dlp",
                "/usr/local/bin/yt-dlp",
                System.getProperty("user.home") + "/.local/bin/yt-dlp"
              };

        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.exists(p) && p.toFile().canExecute()) return p;
        }

        return findViaCommand("which", "yt-dlp");
    }

    // Runs `cmd binaryName` and returns the resolved path if successful
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

    private static void downloadAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl()))
                    .header("User-Agent", "lumiereplay-mod/0.1.0")
                    .GET()
                    .build();

                HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    LumierePlay.LOGGER.error("yt-dlp download failed HTTP {}",
                        response.statusCode());
                    return;
                }

                Files.copy(response.body(), binaryPath, StandardCopyOption.REPLACE_EXISTING);

                // POSIX permissions — Linux and macOS only
                // Windows executables do not need chmod
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
                        // Filesystem does not support POSIX — fall back to File.setExecutable
                        binaryPath.toFile().setExecutable(true, false);
                    }
                }

                ready = true;
                LumierePlay.LOGGER.info("yt-dlp downloaded to {}", binaryPath);

            } catch (Exception e) {
                LumierePlay.LOGGER.error("Failed to download yt-dlp: {}", e.getMessage());
            }
        });
    }

    public static boolean isReady()    { return ready; }
    public static Path getBinaryPath() { return binaryPath; }
    public static OS getCurrentOs()    { return CURRENT_OS; }
}
