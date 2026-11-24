package net.evadeclient.api;

import com.google.gson.JsonObject;
import net.evadeclient.module.visuals.Waypoints;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Waypoints a server adds ({@code waypoint.add / remove / clear}). They are drawn by the Waypoints mod next to the
 * player's own points (box, beam, label, off-screen arrow, Direction HUD and Minimap), but are kept here, apart from
 * the player's saved points: never written to disk, never touched by {@code .waypoint} commands, gone when the player
 * leaves the server.
 */
public final class ApiWaypoints {
    /**
     * One server waypoint. {@code dim} is a dimension id, or null for "whatever dimension the player is in";
     * {@code color} is ARGB.
     */
    public record ServerWaypoint(String id, String name, int x, int y, int z, int color, String dim, boolean beam) {}

    static final int MAX = 256;

    private static final Map<String, ServerWaypoint> points = new LinkedHashMap<>();
    /** Bumped on every change, so the Waypoints mod knows to rebuild its list. */
    private static int version;

    private ApiWaypoints() {}

    /** The server's waypoints, in the order they were added. Render/client thread only. */
    public static Collection<ServerWaypoint> all() { return Collections.unmodifiableCollection(points.values()); }

    /** Changes on every add, remove or clear. */
    public static int version() { return version; }

    static boolean handle(String type, JsonObject o) {
        switch (type) {
            case "waypoint.add" -> {
                String id = ApiJson.id(o);
                if (id == null || !ApiJson.hasPos(o)) return false;
                if (!points.containsKey(id) && points.size() >= MAX) return false;
                String name = ApiJson.str(o, "name", id, ApiJson.MAX_NAME);
                if (name.isEmpty()) name = id;
                points.put(id, new ServerWaypoint(id, name,
                        (int) Math.floor(ApiJson.num(o, "x", 0)), (int) Math.floor(ApiJson.num(o, "y", 0)),
                        (int) Math.floor(ApiJson.num(o, "z", 0)),
                        ApiJson.color(o, "color", Waypoints.DEFAULT_COLOR) | 0xFF000000,
                        ApiJson.dimension(o, "dimension"), ApiJson.bool(o, "beam", true)));
            }
            case "waypoint.remove" -> {
                String id = ApiJson.id(o);
                if (id == null || points.remove(id) == null) return false;
            }
            case "waypoint.clear" -> points.clear();
            default -> { return false; }
        }
        version++;
        return true;
    }

    static void clear() {
        if (points.isEmpty()) return;
        points.clear();
        version++;
    }
}
