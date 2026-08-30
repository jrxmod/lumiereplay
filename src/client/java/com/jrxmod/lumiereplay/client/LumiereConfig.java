package com.jrxmod.lumiereplay.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent JSON config for the Lumiere Play mod.
 * Stored at config/lumiereplay.json — edits are loaded on next playback.
 */
public class LumiereConfig {
    public Screen screen = new Screen();
    public Audio   audio  = new Audio();
    public Lazy    lazy   = new Lazy();
    public Network network = new Network();
    public History history = new History();
    public Sync    sync    = new Sync();

    public static class Screen {
        public float bezel_size = 0.06f;
        /**
         * Coloured spill of light around the frame, tinted by the average colour of
         * the picture. Client side only, so a viewer may turn it off without
         * changing the light the projector gives to the hall for everybody else.
         */
        public boolean glow_tint = true;
    }
    public static class Audio {
        /** Volume a freshly opened projector starts with, before any stored value. */
        public int   volume = 100;
        /** Distance in blocks where spatial attenuation reaches silence. */
        public float max_distance = 64.0f;
    }
    public static class Lazy {
        /** Distance in blocks at which a far away projector is paused locally. */
        public int    pause_distance = 96;
        /** Deprecated alias of pause_distance, kept for configs written before 0.7.0. */
        public double pause_distance_sq = 96.0 * 96.0;

        /** Squared pause distance, derived from pause_distance when it was changed. */
        public double pauseDistSq() {
            if (pause_distance > 0 && pause_distance != 96) {
                return (double) pause_distance * pause_distance;
            }
            return pause_distance_sq > 0 ? pause_distance_sq : 96.0 * 96.0;
        }
    }
    public static class Network {
        /**
         * Proxy handed to yt-dlp and to the media pipeline as a child process
         * environment. Empty means the system environment is left untouched.
         */
        public String proxy = "";
        /** Extra libvlc arguments appended to every player instance. */
        public String[] vlc_args = new String[0];
        /**
         * Extra arguments for every yt-dlp call, inserted right before the URL so
         * they win over what the mod sets. Meant for retuning extraction without a
         * rebuild, for instance a format selector that lets yt-dlp mux video and
         * audio into a file on systems where the source is not a pipe.
         */
        public String[] ytdlp_args = new String[0];
    }
    public static class History {
        public String[] urls = new String[0];
        public int max_entries = 5;
    }
    public static class Sync {
        /** Master switch for keeping viewers on the shared media timeline. */
        public boolean enabled = true;
        /** Gaps smaller than this are ignored, so normal buffering never causes a seek. */
        public int  threshold_ms = 1500;
        /** How often a viewer compares its own position with the shared timeline. */
        public int  check_interval_ms = 5000;
        /** Quiet period after a start or a seek, in milliseconds. */
        public int  grace_ms = 4000;
        /**
         * Largest jump a single correction makes. A viewer that fell further
         * behind than this is walked back over several checks instead of being
         * teleported to the shared position in one seek.
         */
        public int  max_step_ms = 30000;
        /**
         * Reopens a piped stream at the shared position when a viewer starts
         * watching an already playing projector. Costs one extra resolve step.
         */
        public boolean jump_on_join = false;
    }

    private static LumiereConfig INSTANCE;

    public static LumiereConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    public static synchronized void load() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("lumiereplay.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        if (Files.exists(p)) {
            try {
                INSTANCE = gson.fromJson(Files.newBufferedReader(p), LumiereConfig.class);
                if (INSTANCE == null) INSTANCE = new LumiereConfig();
                sanitize();
                return;
            } catch (Exception e) {
                System.err.println("[LumierePlay] Failed to read config, using defaults: " + e.getMessage());
            }
        }
        INSTANCE = new LumiereConfig();
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, gson.toJson(INSTANCE));
        } catch (IOException e) {
            System.err.println("[LumierePlay] Failed to write default config: " + e.getMessage());
        }
    }

    /**
     * Fills in sections added after a config file was first written, and clamps
     * every value a hand edited file could carry into a usable range.
     */
    private static void sanitize() {
        LumiereConfig cfg = INSTANCE;
        if (cfg.screen  == null) cfg.screen  = new Screen();
        if (cfg.audio   == null) cfg.audio   = new Audio();
        if (cfg.lazy    == null) cfg.lazy    = new Lazy();
        if (cfg.network == null) cfg.network = new Network();
        if (cfg.history == null) cfg.history = new History();
        if (cfg.sync    == null) cfg.sync    = new Sync();

        cfg.screen.bezel_size = Math.max(0f, Math.min(1f, cfg.screen.bezel_size));
        cfg.audio.volume      = Math.max(0, Math.min(100, cfg.audio.volume));
        cfg.audio.max_distance = Math.max(8f, Math.min(256f, cfg.audio.max_distance));
        cfg.lazy.pause_distance = Math.max(1, Math.min(1024, cfg.lazy.pause_distance));
        cfg.history.max_entries = Math.max(0, Math.min(50, cfg.history.max_entries));
        if (cfg.history.urls == null) cfg.history.urls = new String[0];
        if (cfg.network.vlc_args == null) cfg.network.vlc_args = new String[0];
        if (cfg.network.ytdlp_args == null) cfg.network.ytdlp_args = new String[0];

        cfg.sync.threshold_ms      = Math.max(200, Math.min(60_000, cfg.sync.threshold_ms));
        cfg.sync.check_interval_ms = Math.max(1000, Math.min(300_000, cfg.sync.check_interval_ms));
        cfg.sync.grace_ms          = Math.max(0, Math.min(60_000, cfg.sync.grace_ms));
        cfg.sync.max_step_ms       = Math.max(1000, Math.min(3_600_000, cfg.sync.max_step_ms));
    }

    /**
     * Adds a URL to the front of the history, removes duplicates,
     * and trims to max_entries. Persists the config immediately.
     */
    public static synchronized void addHistoryUrl(String url) {
        if (url == null || url.isEmpty()) return;
        LumiereConfig cfg = get();
        List<String> list = new ArrayList<>();
        list.add(url);
        for (String existing : cfg.history.urls) {
            if (!existing.equals(url) && list.size() < cfg.history.max_entries) {
                list.add(existing);
            }
        }
        cfg.history.urls = list.toArray(new String[0]);
        save();
    }

    public static String[] getHistoryUrls() {
        return get().history.urls;
    }

    public static synchronized void save() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("lumiereplay.json");
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(INSTANCE));
        } catch (IOException e) {
            System.err.println("[LumierePlay] Failed to save config: " + e.getMessage());
        }
    }
}
