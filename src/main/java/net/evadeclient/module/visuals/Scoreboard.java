package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

/** Tidies the sidebar scoreboard: hide the red numbers, resize it, or hide it altogether. */
public class Scoreboard extends Module {
    public final BooleanSetting hideNumbers = add(new BooleanSetting("Hide Numbers", true));
    public final BooleanSetting hide = add(new BooleanSetting("Hide Scoreboard", false));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.5, 1.5, 0.05));

    public Scoreboard() {
        super("Scoreboard", "Hide the red numbers, resize or hide the sidebar.", Category.VISUALS);
    }

    public static Scoreboard active() {
        if (EvadeClient.modules == null) return null;
        Scoreboard s = EvadeClient.modules.get(Scoreboard.class);
        return s != null && s.isEnabled() ? s : null;
    }
}
