package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;

/** Client-side weather. Clear removes rain and thunder visuals; Rain/Thunder forces them. */
public class WeatherChanger extends Module {
    private final ModeSetting weather = add(new ModeSetting("Weather", "Clear", "Clear", "Rain", "Thunder"));

    public WeatherChanger() { super("Weather Changer", "Change the weather you see.", Category.MODS); }

    @Override
    public void onTick() {
        float rain = weather.is("Clear") ? 0 : 1;
        float thunder = weather.is("Thunder") ? 1 : 0;
        mc.world.setRainGradient(rain);
        mc.world.setThunderGradient(thunder);
    }

    @Override
    public String getSuffix() { return weather.get(); }
}
