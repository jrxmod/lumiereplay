# Lumière Play

In-game video projector and cinema experience for Minecraft (Fabric 1.21.1).

Place a projector block, choose your screen size, and watch videos together with friends: YouTube, Twitch, VK, local files, network streams.

## Features

- Play any video URL supported by VLC (YouTube, Twitch, VK, Vimeo, HLS, DASH, RTMP, RTSP)
- Local files (Windows paths, UNC paths, macOS/Linux paths)
- One shared timeline: play, pause and seeking apply to everyone watching the same projector
- Quality is chosen once and applies to every viewer
- Players who arrive late or walk back into range resume at the shared position
- Screen presets: 16:9, 32:18, 21:9, 4:3 (up to 64x64 blocks)
- Volume control with distance falloff
- The projector lights the hall while a video runs, with a glow tinted by the picture
- Access control (All / Owner / Ops)
- Redstone support (rising edge plays, falling edge pauses)
- Lazy pause: the projector pauses when you're far away and resumes when you come back
- Status screens (Resolving / Loading / Error)
- Auto-retry on stream drops
- JSON config at `config/lumiereplay.json`

## Shared playback

The projector block remembers a media position and advances it while it plays,
and re-anchors that value every few seconds so it keeps tracking the media
instead of running ahead of it. Every viewer aligns to that position when the gap
grows past the configured threshold, so a viewer who buffered, lagged or walked
away catches up on its own. A single catch-up move is limited to
`sync.max_step_ms`, which turns a long absence into a short run of visible steps
rather than one teleport.

Sources that report no duration (live streams and platform streams fed through a
pipe) are left alone entirely: seeking them is not possible, so the panel refuses
the request and the shared timeline is neither corrected nor displayed for them.
For those sources alignment can only happen before playback starts.

## Quality and resolution

The quality row is a request, and the panel prints the size the source actually
delivered next to it, in orange when it clearly missed the cap. Two limits decide
what you can see:

- Platform streams fed through a pipe (YouTube, Twitch, VK) have to be one file
  that already contains audio: yt-dlp does not merge a separate video and audio
  stream into stdout. For YouTube the muxed choices are progressive mp4 (usually
  up to 360p) and HLS variants (up to 1080p), and the selector prefers HLS, so a
  video without HLS variants stays capped no matter which button you press.
- The screen renders into a fixed 1280x720 texture and the picture on your
  monitor is about `screenWidth * 16` pixels across, so a 16x9 screen shows
  roughly 256x144 of detail whatever was downloaded. A bigger screen is what
  makes a higher quality visible.

On Windows the media is written to a temporary file rather than a pipe, so yt-dlp
is free to mux a separate video and audio stream. There `network.ytdlp_args` can
already raise the ceiling without any change in the mod, for example:

```json
"network": { "ytdlp_args": ["-f", "bv*[height<=1080]+ba/b[height<=1080]/best"] }
```

On Linux and macOS the same list makes yt-dlp warn that the two streams will be
concatenated instead of muxed, because output goes to stdout: that path needs a
muxer in front of the pipe, which is not part of this release.

## Ambient light

While a video is running the projector block lights up the hall, and the light goes
away as soon as playback is paused or the block is broken. The level is fixed at 12
because it becomes world data that every viewer shares: it lives in the block state,
so a world saved by an older version keeps loading and a client without this mod
simply sees no extra light.

On top of that every client draws a soft glow around the frame, tinted by the
average colour of the picture currently on the screen, so a night scene spills blue
and a fireball spills orange. It fades out away from the frame instead of ending on
a line, it appears only while media is actually running, and it stays hidden where
the spot is already lit by the sky, which means nothing is drawn over an open air
screen at noon. That part is visual only and belongs to each viewer, which is why
`screen.glow_tint` can turn it off without changing what anyone else sees.

## Configuration

`config/lumiereplay.json` is read when playback starts, no game restart needed.

| Key | Default | Meaning |
|-----|---------|---------|
| `screen.bezel_size` | `0.06` | Black frame around the screen, in blocks |
| `screen.glow_tint` | `true` | Coloured spill of light around the frame, driven by the picture |
| `audio.volume` | `100` | Starting volume shown for a projector nobody has played yet |
| `audio.max_distance` | `64.0` | Distance where the sound fades to silence, in blocks |
| `lazy.pause_distance` | `96` | Distance where playback is paused locally, in blocks |
| `network.proxy` | `""` | Proxy handed to yt-dlp and ffmpeg, e.g. `http://127.0.0.1:7890` |
| `network.vlc_args` | `[]` | Extra libvlc arguments for every player |
| `network.ytdlp_args` | `[]` | Extra arguments for every yt-dlp call, applied after the mod's own so they win |
| `history.max_entries` | `5` | How many links the panel remembers |
| `sync.enabled` | `true` | Keep viewers on the shared timeline |
| `sync.threshold_ms` | `1500` | Smallest gap worth correcting, in milliseconds |
| `sync.check_interval_ms` | `5000` | How often a viewer compares itself with the timeline |
| `sync.grace_ms` | `4000` | Quiet period after a start or a seek |
| `sync.max_step_ms` | `30000` | Largest jump one correction makes; bigger gaps close in several steps |
| `sync.jump_on_join` | `false` | Reopen a piped stream (YouTube, Twitch, VK) at the shared position for a viewer who joins late. Costs one extra resolve step and is best effort |

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16.14+
- Fabric API 0.116.12+
- Java 21
- VLC 3.0+ installed (`libvlc` must be in `PATH` or installed system-wide)
- `yt-dlp` and `ffmpeg` are bundled, the mod downloads them automatically on first run
  (Linux: `~/.minecraft/lumiereplay/bin/`, Windows: `%APPDATA%\.minecraft\lumiereplay\bin\`)
  if they are not already on your `PATH`

## Installation

1. Install Fabric Loader for 1.21.1
2. Drop the mod jar into `mods/`
3. Install VLC (see "Requirements" above)
4. Launch the game: yt-dlp and ffmpeg will be downloaded on first play
5. Craft a projector: 3 glass + 1 diamond + 1 redstone + 3 iron (GGG/IDI/IRI)
6. Place it, right-click to open GUI, paste a video URL

## Build from source

```bash
chmod +x build.sh install.sh gradlew
./build.sh
```

Output: `build/libs/lumiereplay-0.7.0.jar` (or whatever `mod_version` is set to)

## Version compatibility

The projector command and state packets have two wire formats. A 0.7.0 client on
a server that still runs 0.6.x keeps working, but seeking and shared quality need
both sides on 0.7.0 or newer.

## License

Apache-2.0
