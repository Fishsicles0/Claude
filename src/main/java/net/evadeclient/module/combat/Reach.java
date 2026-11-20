package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

import java.util.concurrent.ThreadLocalRandom;

/** Extends how far you can hit entities (and optionally reach blocks). */
public class Reach extends Module {
    private final NumberSetting entity = add(new NumberSetting("Entity Reach", 3.3, 3.0, 6.0, 0.05));
    private final NumberSetting variance = add(new NumberSetting("Variance", 0.1, 0, 0.5, 0.05));
    private final BooleanSetting blocks = add(new BooleanSetting("Blocks", false));
    private final NumberSetting block = add(new NumberSetting("Block Reach", 5.0, 4.5, 6.0, 0.1));

    private double current = 3.3;

    public Reach() {
        super("Reach", "Hit entities from further away.", Category.COMBAT);
    }

    @Override
    public void onTick() {
        // Re-roll a little each tick so the distance isn't a constant fingerprint.
        double v = variance.get();
        current = entity.get() - (v > 0 ? ThreadLocalRandom.current().nextDouble(0, v) : 0);
    }

    public double entityRange() { return Math.max(3.0, current); }
    public boolean blockRangeEnabled() { return blocks.get(); }
    public double blockRange() { return block.get(); }

    @Override
    public String getSuffix() { return String.format("%.2f", entity.get()); }
}
