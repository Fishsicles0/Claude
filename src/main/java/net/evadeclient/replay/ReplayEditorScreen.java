package net.evadeclient.replay;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The replay editor: a timeline (scrub, markers, camera and speed keyframes) and three tabs:
 * <ul>
 *   <li><b>Camera</b>: keyframes for a smooth camera path, field of view and roll, look at / orbit a player or
 *       mob, speed keyframes for slow motion and time-lapse</li>
 *   <li><b>Scene</b>: time of day, weather, fog, name tags, hide players and mobs, green screen</li>
 *   <li><b>Render</b>: resolution, frame rate, quality, and render to video</li>
 * </ul>
 * The world keeps playing behind it. Close it (Esc / E) to fly the camera; K adds a keyframe while flying.
 */
public final class ReplayEditorScreen extends Screen {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static String tab = "Camera";
    private static int selectedKey = -1;
    private static float nextFov = 70, nextRoll = 0;
    private static double nextSpeed = 0.5;
    private final ReplayPlayer player;
    private boolean dragging;
    private int dragTick = -1;

    public ReplayEditorScreen() {
        super(Text.literal("Replay editor"));
        this.player = ReplayPlayer.current();
    }

    private ReplayProject project() { return player.project; }

    /** Opens on this tab next time ("Camera", "Scene" or "Render"). */
    public static void showTab(String name) { tab = name; }

    // ------------------------------------------------------------------ layout

    private int panelX() { return width - 196; }
    private int barY() { return height - 24; }
    private static Boolean hudBefore;

    @Override
    protected void init() {
        if (player == null) { close(); return; }
        if (hudBefore == null) { hudBefore = mc.options.hudHidden; mc.options.hudHidden = true; }
        int px = panelX(), y = 8;
        // tabs
        buttons.clear();
        // (the tabs are kit Tabs, drawn in render)
        y += 30;
        switch (tab) {
            case "Scene" -> sceneTab(px, y);
            case "Render" -> renderTab(px, y);
            default -> cameraTab(px, y);
        }
        // transport
        int ty = height - 54, tx = 8;
        buttons.add(new KB(tx, ty, 22, () -> "", () -> player.seek(player.startTick()), "To the start").glyph("skip_start"));
        buttons.add(new KB(tx + 24, ty, 30, () -> "−5s", () -> player.seek(player.tick() - 100), "Back 5 seconds"));
        buttons.add(new KB(tx + 56, ty, 26, () -> "", player::togglePlay, "Play / pause (P)").glyph(() -> player.playing() ? "pause" : "play"));
        buttons.add(new KB(tx + 84, ty, 30, () -> "+5s", () -> player.seek(player.tick() + 100), "On 5 seconds"));
        buttons.add(new KB(tx + 120, ty, 20, () -> "−", () -> player.setSpeed(player.speed() / 2), "Slower"));
        buttons.add(new KB(tx + 142, ty, 20, () -> "+", () -> player.setSpeed(player.speed() * 2), "Faster"));
        if (!player.meta.markers.isEmpty())
            buttons.add(new KB(tx + 168, ty, 80, () -> "Next marker", this::nextMarker, null));
    }

    // ------------------------------------------------------------------ kit buttons

    private final net.evadeclient.gui.kit.Kit kit = new net.evadeclient.gui.kit.Kit();
    private final List<KB> buttons = new java.util.ArrayList<>();

    /** A kit button laid out by the tabs' code: its label is re-read every frame. */
    private static final class KB {
        final int x, y, w;
        final Supplier<String> label;
        final Runnable run;
        final String tip;
        Supplier<String> glyph;

        KB(int x, int y, int w, Supplier<String> label, Runnable run, String tip) {
            this.x = x; this.y = y; this.w = w; this.label = label; this.run = run; this.tip = tip;
        }

        KB glyph(String g) { glyph = () -> g; return this; }
        KB glyph(Supplier<String> g) { glyph = g; return this; }
    }

    private void drawButtons() {
        for (int i = 0; i < buttons.size(); i++) {
            KB b = buttons.get(i);
            String id = "b" + i;
            int y = b.y - 1;
            if (b.glyph != null) {
                net.evadeclient.gui.PanelGlyphs.button(kit, id, b.x, y, Math.max(20, b.w), b.glyph.get(), b.tip, true, false, btn -> { if (btn == 0) b.run.run(); });
                continue;
            }
            net.evadeclient.gui.kit.Button.draw(kit, id, b.x, y, b.w, net.evadeclient.gui.kit.Button.Size.SM,
                    net.evadeclient.gui.kit.Button.Variant.SECONDARY, kit.ellipsize(b.label.get(), net.evadeclient.util.UiFont.Role.LABEL, b.w - 8), null, true, b.run);
            if (b.tip != null) kit.tooltip(id, b.tip, null, b.x, y, b.x + b.w, y + 20);
        }
    }

    /** A button whose label is recomputed after each click. */
    private KB toggle(int x, int y, int w, Supplier<String> label, Runnable action, String tip) {
        KB b = new KB(x, y + 1, w, label, () -> { action.run(); save(); }, tip);
        buttons.add(b);
        return b;
    }

    private KB action(int x, int y, int w, String label, Runnable action, String tip) {
        KB b = new KB(x, y + 1, w, () -> label, () -> { action.run(); save(); clearAndInit(); }, tip);
        buttons.add(b);
        return b;
    }

    private void cameraTab(int x, int y) {
        ReplayProject pr = project();
        toggle(x, y, 188, () -> "View: " + ReplayOverrides.povName(player), () -> ReplayOverrides.cyclePov(player),
                "Watch through a player's eyes (V while watching): who recorded, then each other player, then the free camera. Renders use it too");
        y += 20;
        action(x, y, 92, "Add keyframe", () -> addKeyframe(player), "A keyframe where the camera is now, at this moment (K while flying)");
        action(x + 96, y, 92, "Delete key", () -> { if (selectedKey >= 0 && selectedKey < pr.camera.size()) pr.camera.remove(selectedKey); selectedKey = -1; }, "Delete the selected keyframe (click one on the timeline)");
        y += 20;
        toggle(x, y, 92, () -> "FOV " + Math.round(fovNow()), () -> setFov(cycle(new float[]{30, 50, 70, 90, 110}, fovNow())), "Field of view of the selected keyframe (or the next one you add)");
        toggle(x + 96, y, 92, () -> "Roll " + Math.round(rollNow()) + "°", () -> setRoll(cycle(new float[]{-30, -15, 0, 15, 30}, rollNow())), "Tilt the camera");
        y += 20;
        toggle(x, y, 188, () -> "Play camera path: " + (pr.previewPath ? "on" : "off"), () -> pr.previewPath = !pr.previewPath,
                "While playing, the camera follows the path. Off: fly freely");
        y += 20;
        toggle(x, y, 92, () -> "Look: " + lookAtName(), this::cycleLookAt, "Keep the camera pointed at a player or mob (works with a path or flying)");
        toggle(x + 96, y, 92, () -> "Follow: " + followName(), this::cycleFollow,
                "Track a player or mob: the camera rides along at the distance it has now. With Look at, a chase camera. A camera path wins while it plays");
        y += 20;
        action(x, y, 188, "Orbit 8 s around " + orbitName(), this::orbit, "Keyframes for one smooth turn around them, starting now");
        y += 20;
        toggle(x, y, 92, () -> "Speed " + Replays.trim(nextSpeed) + "x", () -> nextSpeed = cycle(new double[]{0.1, 0.25, 0.5, 1, 2, 4, 8}, nextSpeed), "Speed for the next speed keyframe");
        action(x + 96, y, 92, "Add speed key", () -> {
            pr.speeds.removeIf(k -> Math.abs(k.tick - player.tick()) < 2);
            pr.speeds.add(new ReplayProject.SpeedKey(player.tick(), nextSpeed));
            pr.sort();
        }, "Slow motion or time-lapse from here: speed eases between speed keyframes (renders and the path)");
        y += 20;
        action(x, y, 92, "Clear path", () -> { pr.camera.clear(); selectedKey = -1; }, "Delete every camera keyframe");
        action(x + 96, y, 92, "Clear speeds", () -> pr.speeds.clear(), "Delete every speed keyframe");
    }

    private void sceneTab(int x, int y) {
        ReplayProject.Scene s = project().scene;
        String[] times = {"Recorded", "Morning", "Noon", "Sunset", "Night", "Midnight"};
        int[] values = {-1, 1000, 6000, 12500, 14000, 18000};
        toggle(x, y, 92, () -> "Time: " + times[indexOf(values, s.timeOfDay)], () -> s.timeOfDay = values[(indexOf(values, s.timeOfDay) + 1) % values.length], null);
        action(x + 96, y, 44, "Key", () -> {
            int now = s.timeOfDay >= 0 ? s.timeOfDay : (int) (mc.world.getTimeOfDay() % 24000);
            s.timeKeys.removeIf(k -> Math.abs(k.tick - player.tick()) < 2);
            s.timeKeys.add(new ReplayProject.TimeKey(player.tick(), now));
        }, "Time keyframe here: the time chosen on the left (or the world's). The sky moves on from key to key, e.g. day to night");
        action(x + 144, y, 44, "Clear", () -> s.timeKeys.clear(), "Delete every time keyframe");
        y += 20;
        toggle(x, y, 92, () -> "Weather: " + (s.clearWeather ? "clear" : "as is"), () -> s.clearWeather = !s.clearWeather, null);
        toggle(x + 96, y, 92, () -> "Fog: " + (s.noFog ? "off" : "normal"), () -> s.noFog = !s.noFog, null);
        y += 20;
        toggle(x, y, 92, () -> "Names: " + (s.hideNametags ? "hidden" : "shown"), () -> s.hideNametags = !s.hideNametags, "Name tags above players and mobs");
        toggle(x + 96, y, 92, () -> "You: " + (s.hideSelf ? "hidden" : "shown"), () -> s.hideSelf = !s.hideSelf, "Show or hide " + player.meta.playerName + ", who recorded");
        y += 20;
        toggle(x, y, 188, () -> "Green screen: " + (s.greenScreen ? "on" : "off"), () -> s.greenScreen = !s.greenScreen, "Replace the sky with pure green, for keying in an editor");
        y += 20;
        action(x, y, 92, "Hide target", this::hideTargeted, "Hide the player or mob the camera is looking at (close the editor with E to aim)");
        action(x + 96, y, 92, "Unhide all (" + s.hidden.size() + ")", () -> s.hidden.clear(), null);
        y += 20;
        action(x, y, 188, "HUD mods" + (s.hiddenHud.isEmpty() ? "" : " (" + s.hiddenHud.size() + " hidden)"),
                () -> client.setScreen(new ReplayHudScreen(this, project())),
                "Choose which HUD mods (FPS, Keystrokes, CPS...) show while watching and in videos of this replay");
    }

    private void renderTab(int x, int y) {
        ReplayProject.Render r = project().render;
        int[][] sizes = {{0, 0}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {1080, 1920}};
        String[] names = {"Window size", "720p", "1080p", "1440p", "4K", "Vertical 1080x1920"};
        toggle(x, y, 188, () -> "Size: " + names[sizeIndex(sizes, r)], () -> {
            int i = (sizeIndex(sizes, r) + 1) % sizes.length;
            r.width = sizes[i][0]; r.height = sizes[i][1];
        }, "Renders at this size whatever your window is");
        y += 20;
        toggle(x, y, 92, () -> r.fps + " fps", () -> r.fps = r.fps == 30 ? 60 : r.fps == 60 ? 120 : 30, null);
        toggle(x + 96, y, 92, () -> "Quality: " + (r.quality <= 16 ? "high" : r.quality <= 21 ? "good" : "small"),
                () -> r.quality = r.quality <= 16 ? 20 : r.quality <= 21 ? 26 : 16, "Higher quality makes bigger files");
        y += 20;
        toggle(x, y, 92, () -> "HUD: " + (project().scene.hideHud ? "off" : "on"), () -> project().scene.hideHud = !project().scene.hideHud, "Show the HUD in the video");
        toggle(x + 96, y, 92, () -> r.from < 0 ? (project().hasPath() ? "Range: path" : "Range: all") : "Range: marked",
                () -> { if (r.from < 0) { r.from = player.tick(); r.to = player.endTick(); } else { r.from = -1; r.to = -1; } },
                "Path: from its first to its last keyframe. Marked: from the playhead (when you pressed this) to the end");
        y += 20;
        toggle(x, y, 92, () -> "Sound: " + switch (r.audio == null ? "sounds" : r.audio) {
                    case "music" -> "+ music";
                    case "off" -> "off";
                    default -> "game";
                }, () -> r.audio = switch (r.audio == null ? "sounds" : r.audio) {
                    case "sounds" -> "music";
                    case "music" -> "off";
                    default -> "sounds";
                }, "Game sounds and voice chat as heard from the camera, in sync with the video (+ music adds the game's music). You won't hear it while it renders");
        toggle(x + 96, y, 92, () -> "Slow-mo: " + (r.slowSound ? "deep" : "normal"), () -> r.slowSound = !r.slowSound,
                "Deep: in slow motion, sounds slow down too (lower and longer, like slowed-down tape). Normal: they play at normal speed");
        y += 24;
        action(x, y, 188, "Render video", () -> { save(); ReplayRender.start(); },
                "Renders frame by frame (smooth whatever your FPS) to an MP4 in .minecraft/evade/renders, and into Evade Clips when it's open");
        y += 24;
        action(x, y, 188, "Add to render queue", () -> {
            save();
            ReplayPlayer p = ReplayPlayer.current();
            if (p == null) return;
            RenderQueue.add(p.file, RenderQueue.shareNew);
            net.evadeclient.util.Notifications.push("Render queue", RenderQueue.jobs().size() + " in the queue. Run it from Replays on the title screen", true);
        }, "Render it later with these settings, with other queued replays one after another (Replays, Render queue)");
    }

    // ------------------------------------------------------------------ camera actions

    /** A keyframe where the camera is now (replaces one at the same moment). */
    public static void addKeyframe(ReplayPlayer p) {
        if (mc.player == null) return;
        ReplayProject pr = p.project;
        double t = p.tick();
        Vec3d eye = mc.player.getEyePos();
        pr.camera.removeIf(k -> Math.abs(k.tick - t) < 2);
        pr.camera.add(new ReplayProject.CamKey(t, eye.x, eye.y, eye.z, mc.player.getYaw(), mc.player.getPitch(), nextRoll, nextFov));
        pr.sort();
        for (int i = 0; i < pr.camera.size(); i++) if (Math.abs(pr.camera.get(i).tick - t) < 1e-6) selectedKey = i;
        p.saveProject();
    }

    private float fovNow() { return selectedKey >= 0 && selectedKey < project().camera.size() ? project().camera.get(selectedKey).fov : nextFov; }
    private float rollNow() { return selectedKey >= 0 && selectedKey < project().camera.size() ? project().camera.get(selectedKey).roll : nextRoll; }
    private void setFov(float f) { if (selectedKey >= 0 && selectedKey < project().camera.size()) project().camera.get(selectedKey).fov = f; else nextFov = f; }
    private void setRoll(float r) { if (selectedKey >= 0 && selectedKey < project().camera.size()) project().camera.get(selectedKey).roll = r; else nextRoll = r; }

    private List<Entity> candidates() {
        List<Entity> out = new ArrayList<>();
        if (mc.world == null || mc.player == null) return out;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !(e instanceof LivingEntity) || e.squaredDistanceTo(mc.player) > 96 * 96) continue;
            out.add(e);
        }
        out.sort(Comparator.comparing((Entity e) -> !(e instanceof PlayerEntity)).thenComparingDouble(e -> e.squaredDistanceTo(mc.player)));
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    private Entity byUuid(String uuid) {
        if (uuid == null || uuid.isBlank() || mc.world == null) return null;
        for (Entity e : mc.world.getEntities()) if (e.getUuidAsString().equals(uuid)) return e;
        return null;
    }

    private String lookAtName() {
        Entity e = byUuid(project().lookAt);
        return e == null ? (project().lookAt.isBlank() ? "nothing" : "(not here)") : e.getName().getString();
    }

    private String followName() {
        Entity e = byUuid(project().follow);
        return e == null ? (project().follow.isBlank() ? "off" : "(not here)") : e.getName().getString();
    }

    private void cycleFollow() {
        List<Entity> c = candidates();
        String cur = project().follow;
        int i = -1;
        for (int k = 0; k < c.size(); k++) if (c.get(k).getUuidAsString().equals(cur)) i = k;
        ReplayOverrides.follow(project(), i + 1 < c.size() ? c.get(i + 1) : null);
    }

    private void cycleLookAt() {
        List<Entity> c = candidates();
        String cur = project().lookAt;
        int i = -1;
        for (int k = 0; k < c.size(); k++) if (c.get(k).getUuidAsString().equals(cur)) i = k;
        project().lookAt = i + 1 < c.size() ? c.get(i + 1).getUuidAsString() : "";
    }

    private Entity orbitTarget() {
        Entity e = byUuid(project().lookAt);
        return e != null ? e : player.self();
    }

    private String orbitName() {
        Entity e = orbitTarget();
        return e == null ? "the camera's view" : e.getName().getString();
    }

    private void orbit() {
        if (mc.player == null) return;
        Entity e = orbitTarget();
        Vec3d c = e != null ? e.getEntityPos().add(0, e.getStandingEyeHeight() * 0.6, 0) : mc.player.getEyePos().add(mc.player.getRotationVector().multiply(6));
        Vec3d cam = mc.player.getEyePos();
        double r = Math.max(3, Math.min(12, Math.hypot(cam.x - c.x, cam.z - c.z)));
        float start = (float) Math.toDegrees(Math.atan2(-(cam.x - c.x), cam.z - c.z));
        project().orbit(c, r, 1.5, player.tick(), 160, 360, start, nextFov);
        project().previewPath = true;
    }

    private void hideTargeted() {
        Entity t = mc.targetedEntity;
        if (t == null && mc.player != null) {
            // the nearest entity close to where the camera points
            Vec3d eye = mc.player.getEyePos(), dir = mc.player.getRotationVector();
            double best = 0.97;
            for (Entity e : candidates()) {
                Vec3d to = e.getEntityPos().add(0, e.getHeight() / 2, 0).subtract(eye).normalize();
                double dot = to.dotProduct(dir);
                if (dot > best) { best = dot; t = e; }
            }
        }
        if (t == null) { net.evadeclient.util.Notifications.push("Replay", "Look at a player or mob first", false); return; }
        if (t == player.self()) { project().scene.hideSelf = true; return; }
        if (!project().scene.hidden.contains(t.getUuidAsString())) project().scene.hidden.add(t.getUuidAsString());
        net.evadeclient.util.Notifications.push("Replay", "Hidden: " + t.getName().getString(), true);
    }

    private void nextMarker() {
        for (var m : player.meta.markers) if (m.tick() > player.tick() + 2) { player.seek(m.tick()); return; }
        if (!player.meta.markers.isEmpty()) player.seek(player.meta.markers.get(0).tick());
    }

    private void save() { player.saveProject(); }

    // ------------------------------------------------------------------ timeline

    private int tickAt(double mx) {
        int x0 = 10, x1 = panelX() - 10;
        double f = Math.max(0, Math.min(1, (mx - x0) / (x1 - x0)));
        return player.startTick() + (int) Math.round(f * (player.endTick() - player.startTick()));
    }

    private double xAt(double tick) {
        int x0 = 10, x1 = panelX() - 10;
        return x0 + (x1 - x0) * (tick - player.startTick()) / Math.max(1.0, player.endTick() - player.startTick());
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (kit.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (super.mouseClicked(click, doubled)) return true;
        int by = barY();
        if (click.x() < panelX() - 4 && click.y() >= by - 6 && click.y() <= by + 22) {
            // a keyframe diamond?
            for (int i = 0; i < project().camera.size(); i++) {
                double kx = xAt(project().camera.get(i).tick);
                if (Math.abs(click.x() - kx) <= 4 && click.y() >= by + 10) {
                    selectedKey = i;
                    player.seek((int) Math.round(project().camera.get(i).tick));
                    clearAndInit();
                    return true;
                }
            }
            dragging = true;
            dragTick = tickAt(click.x());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging) { dragTick = tickAt(click.x()); return true; }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging) {
            dragging = false;
            player.seek(tickAt(click.x()));
            dragTick = -1;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int k = input.key();
        if (k == GLFW.GLFW_KEY_E) { close(); return true; }
        if (k == GLFW.GLFW_KEY_P || k == GLFW.GLFW_KEY_SPACE) { player.togglePlay(); clearAndInit(); return true; }
        if (k == GLFW.GLFW_KEY_K) { addKeyframe(player); clearAndInit(); return true; }
        if ((k == GLFW.GLFW_KEY_DELETE || k == GLFW.GLFW_KEY_BACKSPACE) && selectedKey >= 0 && selectedKey < project().camera.size()) {
            project().camera.remove(selectedKey);
            selectedKey = -1;
            save();
            clearAndInit();
            return true;
        }
        if (k == GLFW.GLFW_KEY_LEFT) { player.seek(player.tick() - 100); return true; }
        if (k == GLFW.GLFW_KEY_RIGHT) { player.seek(player.tick() + 100); return true; }
        return super.keyPressed(input);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // no blur: the world is the point
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        kit.begin(ctx, mouseX, mouseY);
        // The side panel and the timeline bar: glass over the world (no blur: the world is the point).
        net.evadeclient.gui.kit.Paint.surface(ctx, panelX() - 8, 4, width - 4, height - 4, net.evadeclient.util.Tokens.R_LG,
                net.evadeclient.util.Tokens.E1.withFill(net.evadeclient.util.Theme.alpha(net.evadeclient.util.Tokens.N100, 0.86f)), 0, Float.NaN);
        net.evadeclient.gui.kit.Paint.surface(ctx, 4, height - 74, panelX() - 14, height - 4, net.evadeclient.util.Tokens.R_LG,
                net.evadeclient.util.Tokens.E1.withFill(net.evadeclient.util.Theme.alpha(net.evadeclient.util.Tokens.N100, 0.86f)), 0, Float.NaN);
        super.render(ctx, mouseX, mouseY, delta);
        net.evadeclient.gui.kit.Tabs.draw(kit, "tabs", panelX(), 10, 188, List.of("Camera", "Scene", "Render"), tab, t -> {
            if (!t.equals(tab)) { tab = t; clearAndInit(); }
        });
        drawButtons();
        int by = barY(), x0 = 10, x1 = panelX() - 10;
        // header: time
        double now = player.seconds(player.tick()), total = player.seconds(player.endTick());
        String head = (player.seeking() ? "Loading... " : player.playing() ? "Playing " : "Paused ") + Replays.time(now) + " / " + Replays.time(total)
                + "   " + Replays.trim(player.speed()) + "x";
        kit.text(head, 12, height - 68, net.evadeclient.util.Tokens.N950, net.evadeclient.util.UiFont.Role.STRONG);
        // ruler
        net.evadeclient.gui.kit.Paint.sunken(ctx, x0, by + 2, x1, by + 6, 2);
        int span = Math.max(1, player.endTick() - player.startTick());
        int stepSecs = span / 20 > 300 ? 60 : span / 20 > 60 ? 10 : 5;
        for (int s = 0; s * 20 <= span; s += stepSecs) {
            int tx = (int) xAt(player.startTick() + s * 20);
            ctx.fill(tx, by - 2, tx + 1, by + 2, 0xFF6A6A76);
            if (s % (stepSecs * 2) == 0) kit.text(Replays.time(s), tx + 2, by - 11, net.evadeclient.util.Tokens.N600, net.evadeclient.util.UiFont.Role.TINY);
        }
        // render range
        ReplayProject.Render r = project().render;
        if (r.from >= 0) ctx.fill((int) xAt(r.from), by + 2, (int) xAt(r.to), by + 6, 0x8834C759);
        // played part
        float playedTo = (float) xAt(player.tick());
        if (playedTo > x0 + 1) net.evadeclient.gui.kit.Paint.hgradient(ctx, x0, by + 2, playedTo, by + 6, 2, net.evadeclient.util.Tokens.A700, net.evadeclient.util.Tokens.A500);
        // markers
        for (var m : player.meta.markers) {
            int mx = (int) xAt(m.tick());
            ctx.fill(mx - 1, by - 4, mx + 1, by + 8, 0xFFFFC04D);
            if (mouseX >= mx - 3 && mouseX <= mx + 3 && mouseY >= by - 6 && mouseY <= by + 8)
                ctx.drawTooltip(textRenderer, Text.literal(m.label()), mouseX, mouseY);
        }
        // camera keyframes (and the path between them)
        var keys = project().camera;
        if (keys.size() >= 2) ctx.fill((int) xAt(keys.get(0).tick), by + 14, (int) xAt(keys.get(keys.size() - 1).tick), by + 15, 0x664C8DFF);
        for (int i = 0; i < keys.size(); i++) {
            int kx = (int) xAt(keys.get(i).tick);
            int c = i == selectedKey ? 0xFFFFFFFF : 0xFF4C8DFF;
            ctx.fill(kx - 3, by + 11, kx + 3, by + 18, c);
        }
        // speed keyframes
        for (var s : project().speeds) {
            int sx = (int) xAt(s.tick);
            ctx.fill(sx - 2, by + 19, sx + 2, by + 23, 0xFFFF4FA3);
        }
        // playhead (or where a drag will land)
        int ph = (int) xAt(dragTick >= 0 ? dragTick : player.tick());
        net.evadeclient.gui.kit.Paint.rect(ctx, ph - 1, by - 6, ph + 1, by + 24, 1, 0xFFFFFFFF);
        net.evadeclient.gui.kit.Paint.circle(ctx, ph, by + 4, 8, 0xFFFFFFFF);
        // help
        String help = keys.isEmpty() ? "Close (E), fly to a spot, press K; move on in time, fly somewhere else, K again. That's a camera path."
                : keys.size() + " camera keyframes · click one to select it · Delete removes it · E closes to fly";
        String shown = kit.ellipsize(help, net.evadeclient.util.UiFont.Role.SMALL, panelX() - 40);
        int hw = kit.width(shown, net.evadeclient.util.UiFont.Role.SMALL);
        net.evadeclient.gui.kit.Paint.pill(ctx, 6, 6, 26 + hw, 22, net.evadeclient.util.Theme.alpha(net.evadeclient.util.Tokens.N300, 0.88f));
        kit.textCentredY(shown, 16, 14, net.evadeclient.util.Tokens.N900, net.evadeclient.util.UiFont.Role.SMALL);
        ReplayRender rr = ReplayRender.current();
        if (ReplayRender.lastResult != null && tab.equals("Render"))
            kit.text(kit.ellipsize("Last render: " + ReplayRender.lastResult.getFileName(), net.evadeclient.util.UiFont.Role.SMALL, 188), panelX(), height - 18,
                    net.evadeclient.util.Tokens.N800, net.evadeclient.util.UiFont.Role.SMALL);
        if (rr != null) net.evadeclient.gui.kit.Progress.linear(kit, panelX(), height - 44, 186, (float) rr.progress(), "Rendering");
        kit.end();
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { save(); super.close(); }

    @Override
    public void removed() {
        if (hudBefore != null) { mc.options.hudHidden = hudBefore; hudBefore = null; }
        super.removed();
    }

    // ------------------------------------------------------------------ small helpers

    private static float cycle(float[] values, float cur) {
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - cur) < 0.5f) return values[(i + 1) % values.length];
        return values[0];
    }

    private static double cycle(double[] values, double cur) {
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - cur) < 1e-3) return values[(i + 1) % values.length];
        return values[0];
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    private static int sizeIndex(int[][] sizes, ReplayProject.Render r) {
        for (int i = 0; i < sizes.length; i++) if (sizes[i][0] == r.width && sizes[i][1] == r.height) return i;
        return 0;
    }
}
