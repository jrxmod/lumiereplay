# Lumière Play

**A cinema inside Minecraft.** Place a projector, paste a URL, hit play — and watch YouTube, Twitch or any local video on a big virtual screen right in your world.

> Works in singleplayer and on multiplayer servers. Your friends see and hear the same thing.

---

## How it works

1. Craft a **Lumière Projector**
2. Place it — it faces the direction you are looking
3. Right-click → paste a URL or a local file path
4. Pick quality and screen size
5. Press Play

That is it.

---

## What you can watch

| Source | Notes |
|---|---|
| YouTube | Log in to YouTube in your browser first |
| Twitch | Live streams and VODs |
| Vimeo | Works out of the box |
| Dailymotion | Works out of the box |
| VK / vkvideo.ru | Works out of the box |
| Rutube | Works out of the box |
| ok.ru/video | Works out of the box |
| SoundCloud | Audio only |
| Local files | Paste the absolute path — /home/you/video.mp4 |

---

## Requirements

**On the client:**

| What | How to get it |
|---|---|
| VLC Media Player | pacman -S vlc / apt install vlc / videolan.org |
| Fabric Loader 0.16+ | fabricmc.net |
| Fabric API 0.116.12 | Modrinth |

yt-dlp is downloaded automatically — no action needed.

**On the server:**

Only Fabric API. No VLC, no yt-dlp, nothing else.

---

## Crafting

    G G G     G = Glass Pane
    I D I     I = Iron Ingot
    I R I     D = Diamond
              R = Redstone Dust

---

## Screen sizes

16:9 — 32:18 — 21:9 — 4:3

Quality selector: Best / 1080p / 720p / 480p / 360p

---

## Platform support

| OS | Status |
|---|---|
| Linux | Full support |
| Windows | Full support |
| macOS | Full support |

---

## Build from source

    git clone https://github.com/jrxmod/lumiereplay
    cd lumiereplay
    ./gradlew build

Output: build/libs/lumiereplay-*.jar

---

## Known issues

Яндекс.Видео is not supported — broken extractor in yt-dlp upstream.

---

## License

Apache 2.0 — 2026 jrxmod
