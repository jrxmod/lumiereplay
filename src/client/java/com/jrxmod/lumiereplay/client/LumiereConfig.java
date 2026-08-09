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

    public static class Screen {
        public float bezel_size = 0.06f;
    }
    public static class Audio {
        public int   volume = 100;
        public float max_distance = 64.0f;
    }
    public static class Lazy {
        public int    pause_distance = 96;
        public double pause_distance_sq = 96.0 * 96.0;
    }
    public static class Network {
        public String proxy = "";
        public String[] vlc_args = new String[0];
    }
    public static class History {
        public String[] urls = new String[0];
        public int max_entries = 5;
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
