package net.evadeclient.module.render;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

public class Fullbright extends Module {
    public Fullbright() {
        super("Fullbright", "See in the dark.", Category.RENDER);
    }

    @Override
    public void onTick() {
        // Client-side night vision; long duration avoids the flicker vanilla does under 10s.
        mc.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 420, 0, false, false));
    }

    @Override
    protected void onDisable() {
        mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
    }
}
