package net.evadeclient.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.util.Projection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Floating text a server places ({@code hologram.add / remove}): up to {@link #MAX_LINES} lines centred over a
 * position, the first line on top. Shown only within {@link #RANGE} blocks and while the camera can see the spot
 * (never through walls). Section-sign colour codes work in the lines.
 */
public final class ApiHolograms {
    private record Hologram(String id, Vec3d pos, List<String> lines, String dim) {}

    static final int MAX = 64, MAX_LINES = 8;
    static final double RANGE = 48;
    private static final int LINE_H = 10;

    private static final Map<String, Hologram> holograms = new LinkedHashMap<>();
    /** Holograms in range and in sight this tick, far to near. */
    private static final List<Hologram> inView = new ArrayList<>();

    private ApiHolograms() {}

    static boolean handle(String type, JsonObject o) {
        switch (type) {
            case "hologram.add" -> {
                String id = ApiJson.id(o);
                if (id == null || !ApiJson.hasPos(o)) return false;
                if (!holograms.containsKey(id) && holograms.size() >= MAX) return false;
                List<String> lines = new ArrayList<>();
                JsonElement l = o.get("lines");
                if (l != null && l.isJsonArray()) {
                    for (JsonElement e : (JsonArray) l) {
                        if (lines.size() >= MAX_LINES) break;
                        if (e.isJsonPrimitive()) lines.add(ApiJson.clean(e.getAsString(), ApiJson.MAX_TEXT));
                    }
                } else {
                    String text = ApiJson.str(o, "text", "", ApiJson.MAX_TEXT);
                    if (!text.isEmpty()) lines.add(text);
                }
                if (lines.isEmpty()) return false;
                holograms.put(id, new Hologram(id, new Vec3d(ApiJson.num(o, "x", 0), ApiJson.num(o, "y", 0), ApiJson.num(o, "z", 0)),
                        List.copyOf(lines), ApiJson.dimension(o, "dimension")));
                return true;
            }
            case "hologram.remove" -> {
                String id = ApiJson.id(o);
                boolean changed;
                if (id == null) {
                    changed = !holograms.isEmpty();
                    holograms.clear();
                } else changed = holograms.remove(id) != null;
                inView.removeIf(h -> !holograms.containsKey(h.id));
                return changed;
            }
            default -> { return false; }
        }
    }

    /** How many holograms are in sight this tick (for tests). */
    public static int inViewCount() { return inView.size(); }

    /** Once a tick: holograms within range whose spot the camera can see. */
    static void tick() {
        inView.clear();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (holograms.isEmpty() || mc.world == null || mc.player == null) return;
        String here = mc.world.getRegistryKey().getValue().toString();
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        for (Hologram h : holograms.values()) {
            if (h.dim != null && !h.dim.equals(here)) continue;
            if (h.pos.squaredDistanceTo(cam) > RANGE * RANGE) continue;
            // The anchor, or the top line (which sits a little higher), must be in plain sight.
            Vec3d top = h.pos.add(0, 0.25 * (h.lines.size() - 1), 0);
            // Rays stop just short of the text, so a hologram touching a block face still shows.
            if (ApiTeams.clearLine(mc, cam, shortOf(cam, h.pos)) || ApiTeams.clearLine(mc, cam, shortOf(cam, top))) inView.add(h);
        }
        inView.sort((a, b) -> Double.compare(b.pos.squaredDistanceTo(cam), a.pos.squaredDistanceTo(cam)));
    }

    /** {@code to}, moved 0.3 blocks back towards {@code from}. */
    private static Vec3d shortOf(Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from);
        double len = d.length();
        return len <= 0.3 ? from : to.subtract(d.multiply(0.3 / len));
    }

    /** The lines over each hologram in view, shrinking with distance like name tags. */
    static void render(DrawContext ctx) {
        if (inView.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        for (Hologram h : inView) {
            double[] sp = Projection.toScreen(h.pos);
            if (sp == null) continue;
            double dist = Math.max(0.001, h.pos.distanceTo(cam));
            float s = (float) Math.max(0.35, Math.min(1.5, 8 / dist));
            int n = h.lines.size();
            var m = ctx.getMatrices();
            m.pushMatrix();
            try {
                m.translate((float) sp[0], (float) sp[1]);
                m.scale(s, s);
                // The block of lines is centred on the anchor; the first line on top.
                int y = -n * LINE_H / 2;
                for (String line : h.lines) {
                    int w = tr.getWidth(line);
                    if (w > 0) {
                        ctx.fill(-w / 2 - 2, y - 1, w / 2 + 2 + (w & 1), y + LINE_H - 1, 0x60000000);
                        ctx.drawTextWithShadow(tr, line, -w / 2, y, 0xFFFFFFFF);
                    }
                    y += LINE_H;
                }
            } finally {
                m.popMatrix();
            }
        }
    }

    static void clear() {
        holograms.clear();
        inView.clear();
    }
}
