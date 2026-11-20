package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tells you when players pop totems (with a running count) and when players come into or leave render distance. */
public class Notifier extends Module {
    private final BooleanSetting pops = add(new BooleanSetting("Totem Pops", true));
    private final BooleanSetting visualRange = add(new BooleanSetting("Visual Range", true));
    private final Map<UUID, Integer> popCount = new HashMap<>();
    private final Set<UUID> inRange = new HashSet<>();
    private Object world;

    public Notifier() {
        super("Notifier", "Totem pop counter and player join/leave alerts.", Category.MISC);
    }

    /** Called from the network mixin when an entity plays the totem-pop status (35). */
    public static void onPop(Entity e) {
        if (EvadeClient.modules == null || !(e instanceof PlayerEntity p)) return;
        Notifier n = EvadeClient.modules.get(Notifier.class);
        if (n == null || !n.isEnabled() || !n.pops.get()) return;
        int c = n.popCount.merge(p.getUuid(), 1, Integer::sum);
        String who = p == EvadeClient.mc().player ? "You" : p.getName().getString();
        CommandManager.print("§c" + who + "§7 popped " + (c == 1 ? "a totem" : c + " totems"));
        Notifications.push("Totem pop", who + " (" + c + ")", false);
    }

    public int pops(UUID id) { return popCount.getOrDefault(id, 0); }

    @Override
    public void onTick() {
        if (world != mc.world) { inRange.clear(); popCount.clear(); world = mc.world; }
        Set<UUID> now = new HashSet<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            now.add(p.getUuid());
            if (p.isDead()) popCount.remove(p.getUuid()); // counts reset on death
            if (visualRange.get() && inRange.add(p.getUuid()) && mc.player.age > 40)
                CommandManager.print("§d" + p.getName().getString() + "§7 entered render distance");
        }
        for (UUID id : new HashSet<>(inRange)) {
            if (!now.contains(id)) {
                inRange.remove(id);
                var entry = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(id);
                if (visualRange.get() && entry != null) CommandManager.print("§d" + entry.getProfile().name() + "§7 left render distance");
            }
        }
    }
}
