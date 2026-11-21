# Evade Clips

Record, store and edit gameplay clips. Evade Clips is a standalone desktop app for Windows, macOS and Linux. It
works with any game and has extra integration with Evade Client.

![Library](docs/library.png)

## Why it's different

- **Saving costs nothing.** Capture runs once into one-second segments that are already encoded, so saving a
  replay is a file copy that finishes instantly. While you play, saves never re-encode or stutter.
- **Replay and recording at the same time.** Starting a recording never restarts capture, so the replay buffer
  keeps running and a replay can be saved in the middle of a recording.
- **Recordings survive crashes.** A recording is written as you play and is playable at every moment. On stop
  it's converted to MP4.
- **Post-roll.** "Save the last 30 s **+5 s after**", so the clip shows what happened after you pressed the key.
- **Highlights in any game.** Fights are loud, so Evade Clips scans the audio for moments that stand out
  (shots, hits, explosions, your shouting) and marks them. One button (**H** in the editor) cuts a long
  recording down to just the action. New recordings are scanned automatically.
- **Auto-clips in Minecraft.** The **Clips** mod in Evade Client saves clips by itself on kills, totem pops,
  deaths and wins. Kills close together become one clip titled "Double kill", "Triple kill" and so on, tagged
  and named with the server.
- **Game and mic on separate tracks.** You can turn your voice down or mute it after the fact, in the editor,
  without losing the game sound.
- **A real editor.** Trim, split, delete parts, slow-mo or speed-up any part (0.25×–4×), vertical/square
  framing with crop or blurred background, zoom, fades, volume, a title card, markers, frame grabs, and
  undo/redo for everything. Edits are non-destructive: the original is never touched.
- **Exports for where it's going.** Discord 10 MB and 50 MB (two-pass, guaranteed to fit), YouTube 1080p60,
  TikTok/Shorts/Reels 1080×1920, GIF, source quality, or an instant lossless trim.
- **Montages.** Select clips, right-click → *Make montage*. Each clip's edits are kept, and different sizes and
  frame rates are fitted to one canvas.
- **Local and private.** No account, no ads, no overlay injected into your game, no uploads. Your clips are
  plain MP4 files in a folder you choose, and the library index moves with the folder.
- **Fast to share.** Copy a clip (Ctrl+C) and paste it straight into Discord, or drag it out of the library.

## Using it

| Action | Default |
|---|---|
| Save the last 30 s | **F8** (anywhere, even in fullscreen games) |
| Start / stop recording | **F9** |
| Drop a marker | **F7** |

The editor is keyboard-first: **Space** play/pause, **←/→** frame step (Shift: 5 s), **J/L** ±2 s, **S** split,
**Delete** cut the part, **I/O** trim start/end, **H** highlight reel, **M** marker, **P** save the frame,
**Ctrl+Z/Y** undo/redo, **Ctrl+E** export, **Esc** back. Right-click the timeline to change a part's speed or
bring back a cut part. Ctrl+scroll zooms the timeline.

Library: search by title, game or tag; filter favorites, replays, recordings and exports; hover a clip to scrub
through it. Favorites are never auto-deleted when the storage limit is reached (the oldest replays go first).

## Local API

Evade Client and your own tools (scripts, stream decks) can drive Evade Clips on `http://127.0.0.1:47821`.
Requests from web pages are refused.

```
GET  /status
POST /clip?seconds=30&after=5&title=Ace&game=Valorant&tag=ace
POST /record/start   POST /record/stop   POST /record/toggle
POST /marker
```

## Requirements

Java 17+ (the Minecraft Launcher's Java works) and **ffmpeg**:

- **Windows:** download a build from gyan.dev and put its `ffmpeg` folder next to Evade Clips (or on PATH).
  To record game audio, enable *Stereo Mix* or install VB-Cable. NVIDIA, AMD and Intel GPU encoders are
  picked up automatically.
- **macOS:** `brew install ffmpeg`. Install BlackHole to record game audio.
- **Linux:** install ffmpeg from your package manager. Desktop audio comes from PulseAudio/PipeWire.

## Building and testing

```bash
./gradlew :clips:jar                 # clips/build/libs/EvadeClips.jar
./gradlew :clips:createExe           # Evade-Clips.exe (Launch4j)
./gradlew :clips:selfTest            # headless end-to-end test, see below
xvfb-run java -jar clips/build/libs/EvadeClips.jar --screenshots OUT   # render every page to PNG
```

`selfTest` drives the real capture engine with ffmpeg's test pattern and two tones (game + mic). It checks
replays, markers, post-roll, recording during replay, the local API, every export preset (including the 10 MB
size limit and 1080×1920 vertical output), mic-track mixing, highlight detection on a clip with known loud
moments, and a montage of differently sized clips.

## Layout

```
net.evadeclient.clips
├── ClipsApp / SelfTest
├── core/   CaptureEngine (segments → replays + recordings), CaptureSource (per-OS inputs), Library,
│           Clip, EditSpec, Exporter (presets, montage), Highlights, Hotkeys, ControlServer, Ffmpeg, Settings
└── ui/     MainWindow, LibraryPanel, RecordPanel, EditorPanel, Timeline, VideoPlayer, SettingsPanel, Toast
```
