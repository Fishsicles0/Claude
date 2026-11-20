package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.util.Label;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Marks where players logged out, with their name, health and how long ago. Cleared when they come back. */
public class LogoutSpots extends Module {
    private record Seen(String name, Box box, float health) {}
    private record Spot(String name, Box box, float health, long time) {}

    private final Map<UUID, Seen> seen = new HashMap<>();
    private final Map<UUID, Spot> spots = new HashMap<>();
    private Object world;

    public LogoutSpots() {
        super("LogoutSpots", "Shows where players logged out.", Category.VISUALS);
    }

    @Override
    public void onTick() {
        if (world != mc.world) { seen.clear(); spots.clear(); world = mc.world; }
        var nh = mc.getNetworkHandler();
        if (nh == null) return;
        Map<UUID, Seen> now = new HashMap<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            now.put(p.getUuid(), new Seen(p.getName().getString(), p.getBoundingBox(), p.getHealth() + p.getAbsorptionAmount()));
        }
        for (var e : seen.entrySet()) {
            // gone from the world AND from the tab list = logged out (not just out of render distance)
            if (!now.containsKey(e.getKey()) && nh.getPlayerListEntry(e.getKey()) == null) {
                Seen s = e.getValue();
                spots.put(e.getKey(), new Spot(s.name, s.box, s.health, System.currentTimeMillis()));
            }
        }
        spots.keySet().removeIf(id -> nh.getPlayerListEntry(id) != null);
        seen.clear();
        seen.putAll(now);
    }

    @Override
    public void onRender(RenderContext ctx) {
        for (Spot s : spots.values()) {
            ctx.boxFilled(s.box, 0x30E11D2E);
            ctx.boxOutline(s.box, 0xFFE11D2E, 2f);
        }
    }

    @Override
    public void onHud(DrawContext ctx, float td) {
        long now = System.currentTimeMillis();
        for (Spot s : spots.values()) {
            long sec = (now - s.time) / 1000;
            String ago = sec < 60 ? sec + "s" : (sec / 60) + "m";
            Label.draw(ctx, s.box.getCenter().add(0, s.box.getLengthY() / 2 + 0.4, 0),
                    s.name + " §c" + String.format("%.0f", s.health) + "❤ §7" + ago + " ago", 0xFFE11D2E);
        }
    }

    @Override
    public String getSuffix() { return String.valueOf(spots.size()); }
}
