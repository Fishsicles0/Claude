package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.InvUtil;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

/** Disconnects you when you're about to die: low health, or out of totems. Turns itself off afterwards. */
public class AutoLog extends Module {
    private final NumberSetting health = add(new NumberSetting("Health", 6, 1, 19, 1));
    private final BooleanSetting noTotems = add(new BooleanSetting("No Totems Left", false));
    private final BooleanSetting playerNear = add(new BooleanSetting("Only If Player Near", false));

    public AutoLog() {
        super("AutoLog", "Disconnects you when you're about to die.", Category.SERVER);
    }

    @Override
    public void onTick() {
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        String reason = null;
        if (hp <= health.get()) reason = "Health was " + Math.round(hp) + " (below " + health.getInt() + ")";
        else if (noTotems.get() && InvUtil.count(s -> s.isOf(Items.TOTEM_OF_UNDYING)) == 0) reason = "Out of totems";
        if (reason == null) return;
        if (playerNear.get() && mc.world.getPlayers().stream().noneMatch(p -> p != mc.player && mc.player.distanceTo(p) < 16)) return;
        setEnabled(false); // don't instantly log again on rejoin
        mc.getNetworkHandler().getConnection().disconnect(Text.literal("[Evade AutoLog] " + reason));
    }
}
