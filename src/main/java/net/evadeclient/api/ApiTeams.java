package net.evadeclient.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.util.Projection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Teams a server shares ({@code team.set / clear}): a list of player UUIDs and a colour. A small marker floats above
 * each of those players, only while the player can actually see them (a clear line from the camera; never through
 * walls, never for invisible players), and the Minimap shows them as dots, only for players the client already has
 * loaded. Nothing is shown for anyone the server didn't list.
 */
public final class ApiTeams {
    /** A player in view this tick and their team colour. */
    public record Member(PlayerEntity player, int color) {}

    static final int MAX_TEAMS = 16, MAX_PLAYERS = 256;
    /** Markers are shown up to this far away (blocks). */
    static final double RANGE = 128;
    private static final int DEFAULT_COLOR = 0xFF3BE37A;

    /** Team id -> its colour and players. */
    private record Team(int color, List<UUID> players) {}

    private static final Map<String, Team> teams = new LinkedHashMap<>();
    /** Every listed player and the colour they're shown in (the last team that lists them wins). */
    private static final Map<UUID, Integer> colors = new HashMap<>();
    /** Listed players in line of sight this tick, nearest first. */
    private static final List<Member> inView = new ArrayList<>();

    private ApiTeams() {}

    static boolean handle(String type, JsonObject o) {
        switch (type) {
            case "team.set" -> {
                String id = ApiJson.id(o);
                if (id == null) id = "team";
                JsonElement list = o.get("players");
                if (list == null || !list.isJsonArray()) return false;
                if (!teams.containsKey(id) && teams.size() >= MAX_TEAMS) return false;
                List<UUID> players = new ArrayList<>();
                for (JsonElement e : (JsonArray) list) {
                    if (players.size() >= MAX_PLAYERS) break;
                    if (!e.isJsonPrimitive()) continue;
                    try {
                        players.add(UUID.fromString(e.getAsString().strip()));
                    } catch (IllegalArgumentException ignored) {
                        // not a UUID: skipped
                    }
                }
                teams.put(id, new Team(ApiJson.color(o, "color", DEFAULT_COLOR) | 0xFF000000, players));
            }
            case "team.clear" -> {
                String id = ApiJson.id(o);
                if (id == null) teams.clear();
                else if (teams.remove(id) == null) return false;
            }
            default -> { return false; }
        }
        rebuild();
        return true;
    }

    private static void rebuild() {
        colors.clear();
        for (Team t : teams.values()) for (UUID u : t.players) colors.put(u, t.color);
        inView.clear();
    }

    /** The colour {@code uuid} is shown in, or null when no team lists them. */
    public static Integer colorOf(UUID uuid) {
        return colors.isEmpty() ? null : colors.get(uuid);
    }

    /** Whether any team is set. */
    public static boolean any() { return !colors.isEmpty(); }

    /**
     * Listed players for the Minimap: only players already in the client's loaded entity list (so it can never show
     * more than the client has), not the local player, not spectators, not players hidden from this client.
     */
    public static List<Member> loaded() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (colors.isEmpty() || mc.world == null || mc.player == null) return List.of();
        List<Member> out = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!shownAtAll(mc, p)) continue;
            Integer c = colors.get(p.getUuid());
            if (c != null) out.add(new Member(p, c));
        }
        return out;
    }

    private static boolean shownAtAll(MinecraftClient mc, PlayerEntity p) {
        return p != mc.player && !p.isRemoved() && !p.isSpectator() && !p.isInvisibleTo(mc.player);
    }

    /** Listed players in line of sight this tick (for tests). */
    public static List<Member> inView() { return List.copyOf(inView); }

    /** Once a tick: which listed players the camera can see. */
    static void tick() {
        inView.clear();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (colors.isEmpty() || mc.world == null || mc.player == null) return;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double r2 = RANGE * RANGE;
        for (PlayerEntity p : mc.world.getPlayers()) {
            Integer c = colors.get(p.getUuid());
            if (c == null || !shownAtAll(mc, p) || p.squaredDistanceTo(cam) > r2) continue;
            if (canSee(mc, cam, p)) inView.add(new Member(p, c));
        }
        inView.sort((a, b) -> Double.compare(a.player.squaredDistanceTo(cam), b.player.squaredDistanceTo(cam)));
    }

    /** A clear line (blocks you can't see through stop it; glass doesn't) from the camera to the head or middle. */
    static boolean canSee(MinecraftClient mc, Vec3d cam, Entity e) {
        Box b = e.getBoundingBox();
        Vec3d mid = b.getCenter();
        return clearLine(mc, cam, e.getEyePos()) || clearLine(mc, cam, mid);
    }

    static boolean clearLine(MinecraftClient mc, Vec3d from, Vec3d to) {
        return mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.VISUAL,
                RaycastContext.FluidHandling.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    /** The markers: a small downward arrow in the team colour above each player in view, above their name tag. */
    static void render(DrawContext ctx, float td) {
        if (inView.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        // Far first, so near markers end up on top.
        for (int i = inView.size() - 1; i >= 0; i--) {
            Member m = inView.get(i);
            PlayerEntity p = m.player;
            if (p.isRemoved()) continue;
            Vec3d at = p.getLerpedPos(td).add(0, p.getHeight() + 0.95, 0);
            double[] sp = Projection.toScreen(at);
            if (sp == null || sp[0] < -8 || sp[0] > sw + 8 || sp[1] < -8 || sp[1] > sh + 8) continue;
            double dist = Math.max(0.001, at.distanceTo(cam));
            float s = (float) Math.max(0.6, Math.min(1.4, 10 / dist));
            drawMarker(ctx, (float) sp[0], (float) sp[1], s, m.color);
        }
    }

    /** A 7 px wide arrow pointing down with a dark rim, its tip at (x, y). */
    private static void drawMarker(DrawContext ctx, float x, float y, float s, int color) {
        var mat = ctx.getMatrices();
        mat.pushMatrix();
        try {
            mat.translate(Math.round(x), Math.round(y));
            mat.scale(s, s);
            int rim = 0xB0000000;
            // Rim: a row above, each fill row one pixel wider on both sides, and a pixel under the tip.
            ctx.fill(-4, -5, 5, -4, rim);
            for (int i = 0; i < 4; i++) ctx.fill(-4 + i, -4 + i, 5 - i, -3 + i, rim);
            ctx.fill(0, 0, 1, 1, rim);
            // Fill: rows 7, 5, 3, 1 wide, tip at y = 0.
            for (int i = 0; i < 4; i++) ctx.fill(-3 + i, -4 + i, 4 - i, -3 + i, color);
        } finally {
            mat.popMatrix();
        }
    }

    static void clear() {
        teams.clear();
        colors.clear();
        inView.clear();
    }
}
