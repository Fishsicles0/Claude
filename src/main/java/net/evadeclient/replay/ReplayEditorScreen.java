package net.evadeclient.replay;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
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
        String[] tabs = {"Camera", "Scene", "Render"};
        for (int i = 0; i < tabs.length; i++) {
            String t = tabs[i];
            ButtonWidget b = addDrawableChild(ButtonWidget.builder(Text.literal(t), x -> { tab = t; clearAndInit(); }).dimensions(px + i * 63, y, 60, 18).build());
            b.active = !tab.equals(t);
        }
        y += 26;
        switch (tab) {
            case "Scene" -> sceneTab(px, y);
            case "Render" -> renderTab(px, y);
            default -> cameraTab(px, y);
        }
        // transport
        int ty = height - 54, tx = 8;
        addDrawableChild(ButtonWidget.builder(Text.literal("⏮"), b -> player.seek(player.startTick())).dimensions(tx, ty, 20, 18).tooltip(Tooltip.of(Text.literal("To the start"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("-5s"), b -> player.seek(player.tick() - 100)).dimensions(tx + 22, ty, 28, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(player.playing() ? "❚❚" : "▶"), b -> { player.togglePlay(); b.setMessage(Text.literal(player.playing() ? "❚❚" : "▶")); })
                .dimensions(tx + 52, ty, 26, 18).tooltip(Tooltip.of(Text.literal("Play / pause (P)"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+5s"), b -> player.seek(player.tick() + 100)).dimensions(tx + 80, ty, 28, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("−"), b -> player.setSpeed(player.speed() / 2)).dimensions(tx + 116, ty, 18, 18).tooltip(Tooltip.of(Text.literal("Slower"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> player.setSpeed(player.speed() * 2)).dimensions(tx + 136, ty, 18, 18).tooltip(Tooltip.of(Text.literal("Faster"))).build());
        if (!player.meta.markers.isEmpty())
            addDrawableChild(ButtonWidget.builder(Text.literal("Next marker"), b -> nextMarker()).dimensions(tx + 160, ty, 70, 18).build());
    }

    /** A button whose label is recomputed after each click. */
    private ButtonWidget toggle(int x, int y, int w, Supplier<String> label, Runnable action, String tip) {
        ButtonWidget.Builder b = ButtonWidget.builder(Text.literal(label.get()), btn -> { action.run(); btn.setMessage(Text.literal(label.get())); save(); });
        if (tip != null) b.tooltip(Tooltip.of(Text.literal(tip)));
        return addDrawableChild(b.dimensions(x, y, w, 18).build());
    }

    private ButtonWidget action(int x, int y, int w, String label, Runnable action, String tip) {
        ButtonWidget.Builder b = ButtonWidget.builder(Text.literal(label), btn -> { action.run(); save(); clearAndInit(); });
        if (tip != null) b.tooltip(Tooltip.of(Text.literal(tip)));
        return addDrawableChild(b.dimensions(x, y, w, 18).build());
    }

    private void cameraTab(int x, int y) {
        ReplayProject pr = project();
        action(x, y, 92, "Add keyframe", () -> addKeyframe(player), "A keyframe where the camera is now, at this moment (K while flying)");
        action(x + 96, y, 92, "Delete key", () -> { if (selectedKey >= 0 && selectedKey < pr.camera.size()) pr.camera.remove(selectedKey); selectedKey = -1; }, "Delete the selected keyframe (click one on the timeline)");
        y += 20;
        toggle(x, y, 92, () -> "FOV " + Math.round(fovNow()), () -> setFov(cycle(new float[]{30, 50, 70, 90, 110}, fovNow())), "Field of view of the selected keyframe (or the next one you add)");
        toggle(x + 96, y, 92, () -> "Roll " + Math.round(rollNow()) + "°", () -> setRoll(cycle(new float[]{-30, -15, 0, 15, 30}, rollNow())), "Tilt the camera");
        y += 20;
        toggle(x, y, 188, () -> "Play camera path: " + (pr.previewPath ? "on" : "off"), () -> pr.previewPath = !pr.previewPath,
                "While playing, the camera follows the path. Off: fly freely");
        y += 20;
        toggle(x, y, 188, () -> "Look at: " + lookAtName(), this::cycleLookAt, "Keep the camera pointed at a player or mob (works with a path or flying)");
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
        toggle(x, y, 188, () -> "Time: " + times[indexOf(values, s.timeOfDay)], () -> s.timeOfDay = values[(indexOf(values, s.timeOfDay) + 1) % values.length], null);
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
        y += 24;
        action(x, y, 188, "Render video", () -> { save(); ReplayRender.start(); },
                "Renders frame by frame (smooth whatever your FPS) to an MP4 in .minecraft/evade/renders, and into Evade Clips when it's open");
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
        // panel
        ctx.fill(panelX() - 6, 0, width, height, 0xC00C0C10);
        ctx.fill(0, height - 72, panelX() - 6, height, 0xD00C0C10);
        super.render(ctx, mouseX, mouseY, delta);
        int by = barY(), x0 = 10, x1 = panelX() - 10;
        // header: time
        double now = player.seconds(player.tick()), total = player.seconds(player.endTick());
        String head = (player.seeking() ? "Loading... " : player.playing() ? "Playing " : "Paused ") + Replays.time(now) + " / " + Replays.time(total)
                + "   " + Replays.trim(player.speed()) + "x";
        ctx.drawTextWithShadow(textRenderer, head, 10, height - 66, 0xFFFFFFFF);
        // ruler
        ctx.fill(x0, by + 2, x1, by + 6, 0xFF3A3A44);
        int span = Math.max(1, player.endTick() - player.startTick());
        int stepSecs = span / 20 > 300 ? 60 : span / 20 > 60 ? 10 : 5;
        for (int s = 0; s * 20 <= span; s += stepSecs) {
            int tx = (int) xAt(player.startTick() + s * 20);
            ctx.fill(tx, by - 2, tx + 1, by + 2, 0xFF6A6A76);
            if (s % (stepSecs * 2) == 0) ctx.drawText(textRenderer, Replays.time(s), tx + 2, by - 10, 0xFF6A6A76, false);
        }
        // render range
        ReplayProject.Render r = project().render;
        if (r.from >= 0) ctx.fill((int) xAt(r.from), by + 2, (int) xAt(r.to), by + 6, 0x8834C759);
        // played part
        ctx.fill(x0, by + 2, (int) xAt(player.tick()), by + 6, 0xFFE5484D);
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
        ctx.fill(ph - 1, by - 6, ph + 1, by + 24, 0xFFFFFFFF);
        // help
        String help = keys.isEmpty() ? "Close (E), fly to a spot, press K; move on in time, fly somewhere else, K again. That's a camera path."
                : keys.size() + " camera keyframes · click one to select it · Delete removes it · E closes to fly";
        int hw = Math.min(textRenderer.getWidth(help), panelX() - 24);
        ctx.fill(6, 4, 14 + hw, 16, 0xB00C0C10);
        ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(help, panelX() - 24), 10, 6, 0xFFB0B0BA);
        ReplayRender rr = ReplayRender.current();
        if (ReplayRender.lastResult != null && tab.equals("Render"))
            ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth("Last render: " + ReplayRender.lastResult.getFileName(), 188), panelX(), height - 14, 0xFF34C759);
        if (rr != null) ctx.drawTextWithShadow(textRenderer, String.format(Locale.ROOT, "Rendering %d%%", Math.round(rr.progress() * 100)), panelX(), height - 26, 0xFFFFFFFF);
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
