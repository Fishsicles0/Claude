package net.evadeclient.gametest;

import net.evadeclient.replay.ReplayFile;
import net.evadeclient.replay.ReplayPlayer;
import net.evadeclient.replay.ReplayProject;
import net.evadeclient.replay.ReplayRecorder;
import net.evadeclient.replay.ReplayRender;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 3D replays, end to end in the real game: record a scripted scene (blocks, a mob, the player moving and
 * swinging), save it, leave, open the replay from the title screen and check the world came back: blocks,
 * the mob, the recorded player where they were, a spectator camera. Then play, seek both ways, render a camera
 * path to MP4, and check green screen.
 */
final class ReplayTest {
    private ReplayTest() {}

    static void check(boolean ok, String what) {
        System.out.println("[gametest] " + (ok ? "PASS" : "FAIL") + ": " + what);
        if (!ok) throw new AssertionError("FAILED: " + what);
    }

    /** Mean loudness (dB) of the video's sound in a narrow band around {@code hz}, from {@code at} for {@code len} s. */
    static double bandLevel(Path video, double at, double len, int hz) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-ss", String.valueOf(Math.max(0, at)), "-t", String.valueOf(len),
                "-i", video.toString(), "-map", "0:a", "-af", "bandpass=f=" + hz + ":width_type=h:w=100,volumedetect", "-f", "null", "-")
                .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        var m = java.util.regex.Pattern.compile("mean_volume: (-?[0-9.]+|-inf) dB").matcher(log);
        return m.find() && !m.group(1).equals("-inf") ? Double.parseDouble(m.group(1)) : -120;
    }

    /** When the first loud sound (above -20 dB below 1.5 kHz, so the test's 3 kHz voice tone doesn't count) starts and ends, in seconds. */
    static double[] loudSpan(Path video) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-i", video.toString(), "-map", "0:a",
                "-af", "lowpass=f=1500,silencedetect=n=-20dB:d=0.15", "-f", "null", "-").redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        var on = java.util.regex.Pattern.compile("silence_end: ([0-9.]+)").matcher(log);
        double start = on.find() ? Double.parseDouble(on.group(1)) : -1;
        var off = java.util.regex.Pattern.compile("silence_start: ([0-9.]+)").matcher(log);
        double end = -1;
        while (off.find()) { double v = Double.parseDouble(off.group(1)); if (v > start) { end = v; break; } }
        if (end < 0) {
            var d = java.util.regex.Pattern.compile("Duration: (\\d+):(\\d+):([0-9.]+)").matcher(log);
            end = d.find() ? Integer.parseInt(d.group(1)) * 3600 + Integer.parseInt(d.group(2)) * 60 + Double.parseDouble(d.group(3)) : start;
        }
        return new double[]{start, end};
    }

    /**
     * Seconds from {@code from} until half the sound energy (below 1.5 kHz) of the next {@code len} seconds has
     * arrived. Slowing a sound to half speed stretches it in time, so this exactly doubles.
     */
    static double halfEnergyTime(Path video, double from, double len) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-ss", String.valueOf(from), "-t", String.valueOf(len),
                "-i", video.toString(), "-map", "0:a", "-af", "lowpass=f=1500,lowpass=f=1500", "-ac", "1", "-ar", "48000", "-f", "s16le", "-")
                .start();
        byte[] b = p.getInputStream().readAllBytes();
        p.waitFor();
        int n = b.length / 2;
        double total = 0;
        double[] e = new double[n];
        for (int i = 0; i < n; i++) { double v = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8)); e[i] = v * v; total += e[i]; }
        double acc = 0;
        for (int i = 0; i < n; i++) { acc += e[i]; if (acc >= total / 2) return i / 48000.0; }
        return len;
    }

    /** Holds W while the replay plays at {@code speed} and returns how fast the camera moved, in blocks per real second. */
    static double flySpeed(ClientGameTestContext ctx, double speed, int ticks) {
        ctx.runOnClient(c -> {
            var p = ReplayPlayer.current();
            p.setSpeed(speed);
            p.setPlaying(true);
            c.player.setYaw(0);
            c.player.setPitch(0);
        });
        ctx.waitTicks(2);
        // get up to speed first, then measure
        ctx.runOnClient(c -> c.options.forwardKey.setPressed(true));
        ctx.waitTicks(Math.max(1, ticks / 3));
        net.minecraft.util.math.Vec3d from = ctx.computeOnClient(c -> c.player.getEyePos());
        long t0 = System.nanoTime();
        ctx.waitTicks(ticks);
        net.minecraft.util.math.Vec3d to = ctx.computeOnClient(c -> c.player.getEyePos());
        double secs = (System.nanoTime() - t0) / 1e9;
        ctx.runOnClient(c -> { c.options.forwardKey.setPressed(false); var p = ReplayPlayer.current(); p.setSpeed(1); p.setPlaying(false); });
        ctx.waitTicks(10);
        return to.distanceTo(from) / secs;
    }

    static void run(ClientGameTestContext ctx) {
        Path saved;
        int soundAt, voiceAt;
        BlockPos a = new BlockPos(3, -60, 3), b = new BlockPos(-3, -60, 5);
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            var server = sp.getServer();
            server.runCommand("time set 1000");
            server.runCommand("gamerule doDaylightCycle false");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamemode creative @a");
            server.runCommand("kill @e[type=!player]");
            server.runCommand("tp @a 0 -60 0 0 0");
            ctx.waitTicks(20);
            check(ctx.computeOnClient(c -> ReplayRecorder.active()), "replay capture runs in the background from joining");
            // built before recording starts: the replay must still show it (it's rebuilt from the session since joining)
            server.runCommand("setblock 3 -60 3 minecraft:gold_block");
            server.runCommand("summon minecraft:zombie 0 -60 6 {NoAI:1b,PersistenceRequired:1b,CustomName:'\"Dummy\"'}");
            ctx.waitTicks(20);
            boolean started = ctx.computeOnClient(c -> ReplayRecorder.start());
            check(started && ctx.computeOnClient(c -> ReplayRecorder.recording()), "start recording mid-session");
            ctx.waitTicks(10);
            ctx.runOnClient(c -> c.player.swingHand(net.minecraft.util.Hand.MAIN_HAND));
            ctx.runOnClient(c -> ReplayRecorder.marker("Gold block"));
            ctx.waitTicks(10);
            // move, then build a second block: the replay should show the player moving between the two
            server.runCommand("tp @a 6 -60 -4 90 0");
            ctx.waitTicks(10);
            // a loud sound at a known moment: the render's audio has to start right there
            soundAt = ctx.computeOnClient(c -> ReplayRecorder.recordedTicks());
            server.runCommand("playsound minecraft:entity.generic.explode master @a 0 -59 0 4 1");
            ctx.waitTicks(20);
            server.runCommand("setblock -3 -60 5 minecraft:diamond_block");
            ctx.waitTicks(5);
            // voice chat: half a second of a 3 kHz tone "said" by the recording player, through Simple Voice Chat's
            // own Opus (the plugin is loaded by the real mod), 20 ms frames arriving 2-3 per tick as they do live
            check(ctx.computeOnClient(c -> net.evadeclient.replay.ReplayVoice.available()), "Simple Voice Chat plugin loaded: voice can be recorded");
            voiceAt = ctx.computeOnClient(c -> ReplayRecorder.recordedTicks());
            java.util.UUID me = ctx.computeOnClient(c -> c.player.getUuid());
            int[] sent = {0};
            for (int t = 0; t < 10; t++) {
                int n = t % 2 == 0 ? 3 : 2;
                ctx.runOnClient(c -> {
                    for (int i = 0; i < n; i++) {
                        short[] pcm = new short[960];
                        for (int k = 0; k < pcm.length; k++) pcm[k] = (short) (12000 * Math.sin(2 * Math.PI * 3000 * (sent[0] * 960 + k) / 48000.0));
                        sent[0]++;
                        net.evadeclient.replay.ReplayVoice.recordEntity(me, 48, false, pcm);
                    }
                });
                ctx.waitTick();
            }
            ctx.waitTicks(25);
            System.out.println("[gametest] voice frames recorded: " + net.evadeclient.replay.ReplayVoice.framesRecorded);
            saved = ctx.computeOnClient(c -> ReplayRecorder.save());
            check(saved != null, "save replay");
            for (int i = 0; i < 100 && !(Files.exists(saved)); i++) ctx.waitTicks(5);
            check(Files.exists(saved), "replay file written: " + saved.getFileName());
        }
        ctx.waitTicks(10);
        ReplayFile.Meta meta;
        try { meta = ReplayFile.readMeta(saved); } catch (Exception e) { throw new AssertionError(e); }
        long size = saved.toFile().length();
        check(meta.endTick - meta.startTick >= 90 && meta.markers.size() == 1 && meta.markers.get(0).label().equals("Gold block"),
                "replay meta: " + (meta.endTick - meta.startTick) + " ticks kept, marker \"" + (meta.markers.isEmpty() ? "-" : meta.markers.get(0).label()) + "\", " + size / 1024 + " KB");

        // open it from the title screen
        ctx.runOnClient(c -> {
            try { ReplayPlayer.open(saved, new TitleScreen()); } catch (Exception e) { throw new RuntimeException(e); }
        });
        ctx.waitFor(c -> ReplayPlayer.active() && c.world != null && !ReplayPlayer.current().seeking() && c.currentScreen == null, 20 * 60);
        ctx.waitTicks(20);
        System.out.println("[gametest] replay: " + ctx.computeOnClient(c -> ReplayPlayer.current().stats() + ", tick " + ReplayPlayer.current().tick()
                + ", camera at " + c.player.getBlockPos().toShortString() + ", chunks " + c.world.getChunkManager().getLoadedChunkCount() + ", block at a: " + c.world.getBlockState(a)));
        check(ctx.computeOnClient(c -> c.world.getBlockState(a).isOf(Blocks.GOLD_BLOCK)), "replay rebuilds the world as it was when recording started (gold block built before)");
        check(ctx.computeOnClient(c -> !c.world.getBlockState(b).isOf(Blocks.DIAMOND_BLOCK)), "…and the diamond block placed later isn't yet");
        int zombies = ctx.computeOnClient(c -> c.world.getEntitiesByType(EntityType.ZOMBIE, c.player.getBoundingBox().expand(64), e -> true).size());
        check(zombies == 1, "the zombie is back (" + zombies + ")");
        String selfInfo = ctx.computeOnClient(c -> {
            OtherClientPlayerEntity self = ReplayPlayer.current().self();
            return self == null ? "missing" : self.getName().getString() + " at " + self.getBlockPos().toShortString();
        });
        check(!selfInfo.equals("missing") && ctx.computeOnClient(c -> ReplayPlayer.current().self().squaredDistanceTo(0.5, -60, 0.5) < 4),
                "the recorded player is shown where they stood (" + selfInfo + ")");
        check(ctx.computeOnClient(c -> c.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR && c.player.getId() == ReplayPlayer.CAMERA_ID),
                "you watch as a separate spectator camera");
        ctx.takeScreenshot("evade-replay-start");

        // play it: the player moves and the diamond block appears
        ctx.runOnClient(c -> ReplayPlayer.current().setPlaying(true));
        ctx.waitFor(c -> c.world != null && c.world.getBlockState(b).isOf(Blocks.DIAMOND_BLOCK), 20 * 20);
        check(true, "playing: the diamond block appears when it was placed");
        boolean moved = ctx.computeOnClient(c -> ReplayPlayer.current().self() != null && ReplayPlayer.current().self().squaredDistanceTo(6.5, -60, -3.5) < 6);
        check(moved, "playing: the recorded player moved to where they teleported");
        ctx.runOnClient(c -> ReplayPlayer.current().setPlaying(false));

        // seek back to the start (reloads and fast-forwards), then forward again
        ctx.runOnClient(c -> ReplayPlayer.current().seek(ReplayPlayer.current().startTick()));
        ctx.waitFor(c -> ReplayPlayer.active() && c.world != null && !ReplayPlayer.current().seeking() && c.currentScreen == null, 20 * 60);
        ctx.waitTicks(5);
        check(ctx.computeOnClient(c -> c.world.getBlockState(a).isOf(Blocks.GOLD_BLOCK) && !c.world.getBlockState(b).isOf(Blocks.DIAMOND_BLOCK)),
                "seeking back rebuilds the earlier moment");
        int endTick = ctx.computeOnClient(c -> ReplayPlayer.current().endTick());
        ctx.runOnClient(c -> ReplayPlayer.current().seek(endTick - 5));
        ctx.waitFor(c -> ReplayPlayer.active() && !ReplayPlayer.current().seeking(), 20 * 60);
        ctx.waitTicks(5);
        check(ctx.computeOnClient(c -> c.world.getBlockState(b).isOf(Blocks.DIAMOND_BLOCK)), "seeking forward fast-forwards to the end");

        // green screen
        ctx.runOnClient(c -> {
            var p = ReplayPlayer.current();
            p.project.scene.greenScreen = true;
            c.player.refreshPositionAndAngles(0.5, -50, 0.5, 0, -60);
        });
        ctx.waitTicks(5);
        Path shot = ctx.takeScreenshot("evade-replay-greenscreen");
        double green = greenShare(shot);
        check(green > 0.3, String.format("green screen: the sky is pure green (%.0f%% of the frame)", green * 100));
        ctx.runOnClient(c -> ReplayPlayer.current().project.scene.greenScreen = false);

        // a camera path, rendered to video
        double[][] normalSpan = new double[1][];
        Path[] normalSpanVideo = new Path[1];
        int start = ctx.computeOnClient(c -> ReplayPlayer.current().startTick());
        ctx.runOnClient(c -> {
            var p = ReplayPlayer.current();
            p.project.camera.clear();
            p.project.camera.add(new ReplayProject.CamKey(start + 10, -6, -56, -6, -45, 25, 0, 70));
            p.project.camera.add(new ReplayProject.CamKey(start + 40, 8, -55, -2, 60, 20, 10, 60));
            p.project.camera.add(new ReplayProject.CamKey(start + 70, 2, -52, 12, 180, 35, 0, 80));
            p.project.render.width = 640;
            p.project.render.height = 360;
            p.project.render.fps = 30;
            p.project.render.from = -1;
            p.project.render.to = -1;
            ReplayRender.start();
        });
        ctx.waitFor(c -> !ReplayRender.rendering(), 20 * 300);
        Path video = ReplayRender.lastResult;
        check(video != null && Files.exists(video), "render finished: " + (video == null ? ReplayRender.lastError : video.getFileName()));
        try {
            // ffprobe gives the stored size (H.264 pads to 16 internally) and counts every frame
            Process pr = new ProcessBuilder("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries",
                    "stream=width,height,nb_read_frames", "-of", "csv=p=0", video.toString()).redirectErrorStream(true).start();
            String[] f = new String(pr.getInputStream().readAllBytes()).trim().split(",");
            pr.waitFor();
            int w = Integer.parseInt(f[0]), h = Integer.parseInt(f[1]), frames = Integer.parseInt(f[2]);
            // 60 ticks of path = 3 s at 30 fps
            check(w == 640 && h == 360 && Math.abs(frames - 90) <= 3, "render: " + frames + " frames at " + w + "x" + h + " (3 s at 30 fps), whatever the window size");

            // the sound: an AAC track as long as the video, with the explosion where it happened (it reaches the client a
            // tick or two after the command; the zombie's groans are quieter than -20 dB)
            Process pa = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                    "stream=codec_name,sample_rate,channels,duration", "-of", "csv=p=0", video.toString()).redirectErrorStream(true).start();
            String[] au = new String(pa.getInputStream().readAllBytes()).trim().split(",");
            pa.waitFor();
            check(au.length >= 4 && au[0].equals("aac") && au[1].equals("48000") && au[2].equals("2") && Math.abs(Double.parseDouble(au[3]) - 3.0) < 0.1,
                    "render sound: " + String.join(" ", au) + " (AAC, 48 kHz stereo, 3 s)");
            Process ps = new ProcessBuilder("ffmpeg", "-hide_banner", "-i", video.toString(), "-map", "0:a", "-af", "silencedetect=n=-20dB:d=0.05",
                    "-f", "null", "-").redirectErrorStream(true).start();
            String log = new String(ps.getInputStream().readAllBytes());
            ps.waitFor();
            var m = java.util.regex.Pattern.compile("silence_end: ([0-9.]+)").matcher(log);
            double heard = m.find() ? Double.parseDouble(m.group(1)) : -1;
            double expected = (soundAt - 10) / 20.0;
            check(ReplayRender.lastAudioPeak > 3000 && heard >= expected - 0.05 && heard <= expected + 0.25,
                    String.format(java.util.Locale.ROOT, "render sound in sync: the explosion starts at %.2f s (expected %.2f s), peak %d",
                            heard, expected, ReplayRender.lastAudioPeak));

            // the voice: loud at 3 kHz from when it was said (plus the short start-up cushion), quiet there before
            System.out.println("[gametest] voice frames played: " + net.evadeclient.replay.ReplayVoice.framesPlayed + ", last: " + net.evadeclient.replay.ReplayVoice.lastSpeaker);
            double said = (voiceAt - 10) / 20.0;
            double before = bandLevel(video, said - 0.45, 0.35, 3000), during = bandLevel(video, said + 0.1, 0.35, 3000);
            check(during - before > 15, String.format(java.util.Locale.ROOT,
                    "voice chat in the render: the recorded voice is heard at %.2f s (%.0f dB at 3 kHz, %.0f dB just before)", said, during, before));
            normalSpan[0] = loudSpan(video);
            normalSpanVideo[0] = video;
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("render: can't read the video: " + e);
        }

        // slow motion: the same explosion rendered at half speed lasts about twice as long (pitched down, like tape)
        ctx.runOnClient(c -> {
            var p = ReplayPlayer.current();
            p.project.speeds.clear();
            p.project.speeds.add(new ReplayProject.SpeedKey(start, 0.5));
            p.project.render.from = start + soundAt - 4;
            p.project.render.to = start + soundAt + 56;
            ReplayRender.start();
        });
        ctx.waitFor(c -> !ReplayRender.rendering(), 20 * 300);
        Path slow = ReplayRender.lastResult;
        check(slow != null, "slow-motion render finished: " + (slow == null ? ReplayRender.lastError : slow.getFileName()));
        try {
            // from the explosion's start: 1.1 s at normal speed (before the voice) against 2.2 s at half speed
            double normalOn = normalSpan[0][0], slowOn = loudSpan(slow)[0];
            double tNormal = halfEnergyTime(normalSpanVideo[0], normalOn - 0.05, 1.1), tSlow = halfEnergyTime(slow, slowOn - 0.1, 2.2);
            double ratio = tSlow / Math.max(0.001, tNormal);
            check(ratio > 1.7 && ratio < 2.3, String.format(java.util.Locale.ROOT,
                    "slow-motion sound: at half speed the explosion is stretched x%.2f (half its energy after %.2f s instead of %.2f s)", ratio, tSlow, tNormal));
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("slow-motion render: can't read the video: " + e);
        }
        ctx.runOnClient(c -> {
            var p = ReplayPlayer.current();
            p.project.speeds.clear();
            p.project.render.from = -1;
            p.project.render.to = -1;
        });

        // the free camera flies at normal speed even when the replay runs at a quarter
        ctx.runOnClient(c -> { var p = ReplayPlayer.current(); p.seek(p.startTick()); });
        ctx.waitFor(c -> !ReplayPlayer.current().seeking() && c.currentScreen == null, 20 * 60);
        ctx.runOnClient(c -> ReplayPlayer.current().project.previewPath = false); // flying freely, not along the camera path
        double normal = flySpeed(ctx, 1, 24), quarter = flySpeed(ctx, 0.25, 6);
        check(quarter > 0.7 * normal && quarter < 1.4 * normal, String.format(java.util.Locale.ROOT,
                "free camera at 0.25x replay speed flies at %.1f blocks/s, like at normal speed (%.1f blocks/s)", quarter, normal));

        // the editor's three tabs
        ctx.runOnClient(c -> { ReplayPlayer.current().seek(ReplayPlayer.current().startTick() + 40); });
        ctx.waitFor(c -> !ReplayPlayer.current().seeking() && c.currentScreen == null, 20 * 60);
        for (String tab : new String[]{"Camera", "Scene", "Render"}) {
            ctx.runOnClient(c -> { net.evadeclient.replay.ReplayEditorScreen.showTab(tab); c.setScreen(new net.evadeclient.replay.ReplayEditorScreen()); });
            ctx.waitTicks(5);
            ctx.takeScreenshot("evade-replay-editor-" + tab.toLowerCase());
        }
        ctx.runOnClient(c -> c.setScreen(null));

        // leave
        ctx.runOnClient(c -> ReplayPlayer.current().close());
        ctx.waitFor(c -> c.world == null && !ReplayPlayer.active(), 20 * 30);
        check(true, "leaving the replay returns to the menu");
        ctx.runOnClient(c -> c.setScreen(new net.evadeclient.replay.ReplayBrowserScreen(new TitleScreen())));
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-replay-browser");
        ctx.runOnClient(c -> c.setScreen(new TitleScreen()));
        multiplayer(ctx);
    }

    /** On a real server (over the network, compressed, with the configuration phase): record, leave (auto-save), replay. */
    static void multiplayer(ClientGameTestContext ctx) {
        Path before = ReplayRecorder.lastSaved;
        try (var server = ctx.worldBuilder().createServer(); var conn = server.connect()) {
            conn.getClientWorld().waitForChunksRender();
            server.runCommand("gamerule doDaylightCycle false");
            server.runCommand("op Player0");
            server.runCommand("setblock 2 -60 2 minecraft:emerald_block");
            server.runCommand("summon minecraft:skeleton 0 -60 5 {NoAI:1b,PersistenceRequired:1b}");
            ctx.waitTicks(20);
            check(ctx.computeOnClient(c -> ReplayRecorder.active()), "multiplayer: capture runs on a real server connection");
            ctx.runOnClient(c -> ReplayRecorder.start());
            ctx.waitTicks(10);
            server.runCommand("setblock -2 -60 2 minecraft:lapis_block");
            ctx.waitTicks(40);
        } // leaving saves the recording ("Save When Leaving")
        for (int i = 0; i < 100 && (ReplayRecorder.lastSaved == before || !Files.exists(ReplayRecorder.lastSaved)); i++) ctx.waitTicks(5);
        Path saved = ReplayRecorder.lastSaved;
        check(saved != before && Files.exists(saved), "multiplayer: leaving the server saved the replay (" + (saved == null ? "-" : saved.getFileName()) + ")");
        ctx.runOnClient(c -> { try { ReplayPlayer.open(saved, new TitleScreen()); } catch (Exception e) { throw new RuntimeException(e); } });
        ctx.waitFor(c -> ReplayPlayer.active() && c.world != null && !ReplayPlayer.current().seeking() && c.currentScreen == null, 20 * 60);
        ctx.waitTicks(10);
        boolean emerald = ctx.computeOnClient(c -> c.world.getBlockState(new BlockPos(2, -60, 2)).isOf(Blocks.EMERALD_BLOCK));
        int skeletons = ctx.computeOnClient(c -> c.world.getEntitiesByType(EntityType.SKELETON, c.player.getBoundingBox().expand(64), e -> true).size());
        check(emerald && skeletons == 1 && ctx.computeOnClient(c -> ReplayPlayer.current().self() != null),
                "multiplayer replay: world, mob and player are back (" + ctx.computeOnClient(c -> ReplayPlayer.current().stats()) + ")");
        ctx.runOnClient(c -> ReplayPlayer.current().setPlaying(true));
        ctx.waitFor(c -> c.world != null && c.world.getBlockState(new BlockPos(-2, -60, 2)).isOf(Blocks.LAPIS_BLOCK), 20 * 20);
        check(true, "multiplayer replay plays: the block placed while recording appears");
        ctx.runOnClient(c -> ReplayPlayer.current().close());
        ctx.waitFor(c -> c.world == null && !ReplayPlayer.active(), 20 * 30);
    }

    private static double greenShare(Path png) {
        try {
            var img = javax.imageio.ImageIO.read(png.toFile());
            int green = 0, n = 0;
            for (int y = 0; y < img.getHeight() / 2; y += 4) for (int x = 0; x < img.getWidth(); x += 4) {
                int c = img.getRGB(x, y);
                n++;
                if (((c >> 8) & 255) > 240 && ((c >> 16) & 255) < 20 && (c & 255) < 20) green++;
            }
            return green / (double) Math.max(1, n);
        } catch (Exception e) {
            return 0;
        }
    }
}
