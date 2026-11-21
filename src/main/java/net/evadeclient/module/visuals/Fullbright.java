package net.evadeclient.module.visuals;

import net.evadeclient.mixin.SimpleOptionAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Maximises client-side brightness so caves and night are easy to see. Visual only; sends nothing to the server. */
public class Fullbright extends Module {
    private Double previousGamma;

    public Fullbright() {
        super("Fullbright", "Brightens caves and night to full gamma.", Category.VISUALS);
    }

    @Override
    protected void onEnable() {
        if (mc.options == null) return;
        var gamma = mc.options.getGamma();
        if (previousGamma == null) previousGamma = gamma.getValue();
        ((SimpleOptionAccessor) (Object) gamma).evade$setValue(15.0);
    }

    @Override
    protected void onDisable() {
        if (mc.options == null || previousGamma == null) return;
        ((SimpleOptionAccessor) (Object) mc.options.getGamma()).evade$setValue(previousGamma);
        previousGamma = null;
    }
}
