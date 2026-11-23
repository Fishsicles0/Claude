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

    static void run(ClientGameTestContext ctx) {
        Path saved;
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
            ctx.waitTicks(30);
            server.runCommand("setblock -3 -60 5 minecraft:diamond_block");
            ctx.waitTicks(40);
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
        } catch (Exception e) {
            throw new AssertionError("render: can't read the video: " + e);
        }

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
