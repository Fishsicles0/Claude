package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

/** Visual-only 1.7 style first-person animations: swing while eating/drinking, and the old held-item position. */
public class OldAnimations extends Module {
    public final BooleanSetting swingWhileUsing = add(new BooleanSetting("Swing While Eating", true));
    public final BooleanSetting oldPosition = add(new BooleanSetting("1.7 Item Position", true));
    public final NumberSetting lower = add(new NumberSetting("Item Lowered By", 0.08, 0, 0.3, 0.01));

    public OldAnimations() {
        super("1.7 Animations", "Visual 1.7 swing-while-eating and held item position.", Category.VISUALS);
    }

    public static OldAnimations active() {
        if (EvadeClient.modules == null) return null;
        OldAnimations o = EvadeClient.modules.get(OldAnimations.class);
        return o != null && o.isEnabled() ? o : null;
    }
}
