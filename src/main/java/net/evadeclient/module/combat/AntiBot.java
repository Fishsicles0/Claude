package net.evadeclient.module.combat;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Stops combat modules from targeting fake players that servers spawn to catch clients:
 * players missing from the tab list, or invisible players with no armour.
 */
public class AntiBot extends Module {
    private final BooleanSetting tabList = add(new BooleanSetting("Not In Tab", true));
    private final BooleanSetting invisible = add(new BooleanSetting("Invisible No Armor", true));
    private final BooleanSetting noPing = add(new BooleanSetting("Zero Ping", false));

    public AntiBot() {
        super("AntiBot", "Ignores fake players used to catch clients.", Category.COMBAT);
        setEnabled(true);
    }

    public static boolean isBot(Entity e) {
        if (!(e instanceof PlayerEntity p) || EvadeClient.modules == null) return false;
        AntiBot ab = EvadeClient.modules.get(AntiBot.class);
        if (ab == null || !ab.isEnabled()) return false;
        var nh = MinecraftClient.getInstance().getNetworkHandler();
        var entry = nh == null ? null : nh.getPlayerListEntry(p.getUuid());
        if (ab.tabList.get() && entry == null) return true;
        if (ab.noPing.get() && entry != null && entry.getLatency() == 0 && !MinecraftClient.getInstance().isInSingleplayer()) return true;
        if (ab.invisible.get() && p.isInvisible()) {
            boolean armor = false;
            for (var slot : net.evadeclient.hud.HudDraw.ARMOR_TOP_DOWN) if (!p.getEquippedStack(slot).isEmpty()) armor = true;
            return !armor;
        }
        return false;
    }
}
