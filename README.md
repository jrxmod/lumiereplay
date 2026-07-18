# Lumière Play

In-game video projector and cinema experience for Minecraft (Fabric 1.21.1).

Place a projector block, choose your screen size, and watch videos together with friends — YouTube, Twitch, VK, local files, network streams.

## Features

- Play any video URL supported by VLC (YouTube, Twitch, VK, Vimeo, HLS, DASH, RTMP, RTSP)
- Local files (Windows paths, UNC paths, macOS/Linux paths)
- 16:9 screen with configurable dimensions
- Volume control
- Access control (All / Owner / Ops)
- Redstone support (rising edge plays, falling edge pauses)
- Lazy pause — projector pauses when you're far away, resumes when you come back
- Status screens (Resolving / Loading / Error)
- Auto-retry on stream drops
- JSON config at `config/lumiereplay.json`

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16.14+
- Fabric API 0.116.12+
- Java 21
- VLC 3.0+ installed (`libvlc` must be in `PATH` or installed system-wide)
- `yt-dlp` and `ffmpeg` are bundled — the mod downloads them automatically on first run
  (Linux: `~/.minecraft/lumiereplay/bin/`, Windows: `%APPDATA%\.minecraft\lumiereplay\bin\`)
  if they are not already on your `PATH`

## Installation

1. Install Fabric Loader for 1.21.1
2. Drop the mod jar into `mods/`
3. Install VLC (see "Requirements" above)
4. Launch the game — yt-dlp and ffmpeg will be downloaded on first play
5. Craft a projector: 3 glass + 1 diamond + 1 redstone + 3 iron (GGG/IDI/IRI)
6. Place it, right-click to open GUI, paste a video URL

## Build from source

```bash
chmod +x build.sh install.sh gradlew
./build.sh
```

Output: `build/libs/lumiereplay-0.5.5.jar` (or whatever `mod_version` is set to)

## License

Apache-2.0
