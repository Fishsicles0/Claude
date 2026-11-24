package net.evadeclient.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.util.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small HUD widgets a server can show: balances ({@code economy.set / clear}, e.g. "Coins 1,250") and timers
 * ({@code timer.set / clear}, counting down to an event or up from a start). Text the server chooses, drawn on the
 * right side of the screen; nothing here reads the game.
 */
public final class ApiWidgets {
    /** A balance line. */
    public record Balance(String id, String label, String value, int color) {}

    /** A timer: counts down to {@code end}, or (up) from {@code start}. Times are currentTimeMillis values. */
    public record Timer(String id, String label, long start, long end, boolean up, int color) {
        public long seconds(long now) {
            return up ? Math.max(0, (now - start) / 1000) : Math.max(0, (end - now + 999) / 1000);
        }
    }

    static final int MAX = 4;
    static final double MAX_SECONDS = 24 * 3600;
    private static final Map<String, Balance> balances = new LinkedHashMap<>();
    private static final Map<String, Timer> timers = new LinkedHashMap<>();

    private ApiWidgets() {}

    static boolean handle(String type, JsonObject o) {
        String id = ApiJson.id(o);
        switch (type) {
            case "economy.set" -> {
                if (id == null) return false;
                String label = ApiJson.str(o, "label", "", 24);
                JsonElement v = o.get("value");
                String value;
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    double d = v.getAsDouble();
                    if (!Double.isFinite(d)) return false;
                    value = d == Math.rint(d) && Math.abs(d) < 1e15 ? String.format(Locale.ROOT, "%,d", (long) d) : String.format(Locale.ROOT, "%,.2f", d);
                } else value = ApiJson.str(o, "value", "", 32);
                if (value.isEmpty()) return balances.remove(id) != null;
                if (!balances.containsKey(id) && balances.size() >= MAX) return false;
                balances.put(id, new Balance(id, label, value, ApiJson.color(o, "color", 0xFFFFD866)));
                return true;
            }
            case "economy.clear" -> {
                if (id == null) balances.clear(); else balances.remove(id);
                return true;
            }
            case "timer.set" -> {
                if (id == null) return false;
                String label = ApiJson.str(o, "label", "", 24);
                boolean up = "up".equalsIgnoreCase(ApiJson.str(o, "mode", "down", 8));
                double secs = Math.min(MAX_SECONDS, Math.max(0, ApiJson.num(o, "seconds", 0)));
                if (!up && secs <= 0) return timers.remove(id) != null;
                if (!timers.containsKey(id) && timers.size() >= MAX) return false;
                long now = System.currentTimeMillis();
                // Counting up: "seconds" is how far it has already run.
                timers.put(id, up ? new Timer(id, label, now - Math.round(secs * 1000), 0, true, ApiJson.color(o, "color", 0xFFFFFFFF))
                        : new Timer(id, label, now, now + Math.round(secs * 1000), false, ApiJson.color(o, "color", 0xFFFFFFFF)));
                return true;
            }
            case "timer.clear" -> {
                if (id == null) timers.clear(); else timers.remove(id);
                return true;
            }
            default -> { return false; }
        }
    }

    public static List<Balance> balances() { return new ArrayList<>(balances.values()); }

    public static List<Timer> timers() { return new ArrayList<>(timers.values()); }

    /** "4:05", "1:02:03". */
    public static String clock(long s) {
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Draws the widgets on the right, a little above the middle. Countdowns that reached zero stay at 0:00 for 3 s. */
    static void render(DrawContext ctx) {
        if (balances.isEmpty() && timers.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.currentTimeMillis();
        timers.values().removeIf(t -> !t.up() && now > t.end() + 3000);
        List<String[]> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (Balance b : balances.values()) { rows.add(new String[]{b.label(), b.value()}); colors.add(b.color()); }
        for (Timer t : timers.values()) { rows.add(new String[]{t.label(), clock(t.seconds(now))}); colors.add(t.color()); }
        int w = 0;
        for (String[] r : rows) w = Math.max(w, mc.textRenderer.getWidth(r[0]) + (r[0].isEmpty() ? 0 : 8) + mc.textRenderer.getWidth(r[1]));
        int x2 = ctx.getScaledWindowWidth() - 6, x1 = x2 - w - 10;
        int y = ctx.getScaledWindowHeight() / 2 - 30 - rows.size() * 6;
        ctx.fill(x1, y - 4, x2, y + rows.size() * 12 + 2, 0x80000000);
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            int ty = y + i * 12;
            if (!r[0].isEmpty()) ctx.drawTextWithShadow(mc.textRenderer, r[0], x1 + 5, ty, Theme.TEXT_DIM);
            ctx.drawTextWithShadow(mc.textRenderer, r[1], x2 - 5 - mc.textRenderer.getWidth(r[1]), ty, colors.get(i));
        }
    }

    static void clear() {
        balances.clear();
        timers.clear();
    }
}
