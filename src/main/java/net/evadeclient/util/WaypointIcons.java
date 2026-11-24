package net.evadeclient.util;

import net.minecraft.client.gui.DrawContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Waypoint icons: a small set of 9 x 9 pixel symbols Evade draws itself (no textures), tinted in the point's colour
 * with a dark rim so they read over any sky. Drawn above a waypoint's label and in the Waypoint Manager.
 */
public final class WaypointIcons {
    private WaypointIcons() {}

    private static final Map<String, String[]> ICONS = new LinkedHashMap<>();
    static {
        ICONS.put("Dot", new String[]{
                ".........",
                "...###...",
                "..#####..",
                ".#######.",
                ".#######.",
                ".#######.",
                "..#####..",
                "...###...",
                "........."});
        ICONS.put("Home", new String[]{
                "....#....",
                "...###...",
                "..#####..",
                ".#######.",
                "#########",
                ".##...##.",
                ".##.#.##.",
                ".##.#.##.",
                ".#######."});
        ICONS.put("Star", new String[]{
                "....#....",
                "....#....",
                "...###...",
                "#########",
                ".#######.",
                "..#####..",
                "..##.##..",
                ".##...##.",
                ".#.....#."});
        ICONS.put("Flag", new String[]{
                ".#######.",
                ".########",
                ".#######.",
                ".######..",
                ".#.......",
                ".#.......",
                ".#.......",
                ".#.......",
                "###......"});
        ICONS.put("Chest", new String[]{
                ".........",
                ".#######.",
                "#.......#",
                "#.......#",
                "####.####",
                "#...#...#",
                "#.......#",
                "#.......#",
                ".#######."});
        ICONS.put("Skull", new String[]{
                "..#####..",
                ".#######.",
                "#########",
                "##..#..##",
                "##..#..##",
                "#########",
                ".###.###.",
                "..#####..",
                "..#.#.#.."});
        ICONS.put("Diamond", new String[]{
                ".........",
                "..#####..",
                ".##.#.##.",
                "#########",
                ".#######.",
                "..#####..",
                "...###...",
                "....#....",
                "........."});
        ICONS.put("Portal", new String[]{
                ".#######.",
                ".#.....#.",
                ".#.###.#.",
                ".#.#.#.#.",
                ".#.###.#.",
                ".#.#.#.#.",
                ".#.###.#.",
                ".#.....#.",
                ".#######."});
        ICONS.put("Sword", new String[]{
                ".......##",
                "......###",
                ".....###.",
                "....###..",
                ".#.###...",
                "..###....",
                "..##.....",
                ".#..#....",
                "#........"});
    }

    /** Icon names in picker order ("None" first means no icon). */
    public static final List<String> NAMES;
    static {
        java.util.ArrayList<String> n = new java.util.ArrayList<>();
        n.add("None");
        n.addAll(ICONS.keySet());
        NAMES = List.copyOf(n);
    }

    /** Whether {@code name} is a real icon (not null, "None" or unknown). */
    public static boolean has(String name) { return name != null && ICONS.containsKey(name); }

    /** The icon's name as stored, matched ignoring case, or null for none/unknown. */
    public static String canonical(String name) {
        if (name == null) return null;
        for (String k : ICONS.keySet()) if (k.equalsIgnoreCase(name.strip())) return k;
        return null;
    }

    /** Draw {@code name} with its top-left at (x, y), {@code px} GUI pixels per icon pixel, in {@code color}. */
    public static void draw(DrawContext ctx, String name, int x, int y, int px, int color) {
        String[] rows = ICONS.get(name);
        if (rows == null || px <= 0) return;
        int rim = Theme.fa(0xC0000000);
        int c = Theme.fa(color | 0xFF000000);
        // Rim first: each lit pixel's four neighbours, one icon pixel out.
        for (int r = 0; r < 9; r++) {
            for (int col = 0; col < 9; col++) {
                if (rows[r].charAt(col) != '#') continue;
                int x0 = x + col * px, y0 = y + r * px;
                ctx.fill(x0 - px, y0, x0 + 2 * px, y0 + px, rim);
                ctx.fill(x0, y0 - px, x0 + px, y0 + 2 * px, rim);
            }
        }
        for (int r = 0; r < 9; r++) {
            int run = -1;
            for (int col = 0; col <= 9; col++) {
                boolean on = col < 9 && rows[r].charAt(col) == '#';
                if (on && run < 0) run = col;
                if (!on && run >= 0) {
                    ctx.fill(x + run * px, y + r * px, x + col * px, y + (r + 1) * px, c);
                    run = -1;
                }
            }
        }
    }
}
