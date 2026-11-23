package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.world.World;

/**
 * Client-side weather. Clear removes rain and thunder visuals; Rain/Thunder forces them. Only what you see and hear
 * changes (sky, fog, rain, splashes and rain sounds): the game itself keeps the server's real weather, so things
 * like Riptide work exactly as they should, and turning the module off brings the real weather back at once.
 */
public class WeatherChanger extends Module {
    private static WeatherChanger instance;

    private final ModeSetting weather = add(new ModeSetting("Weather", "Clear", "Clear", "Rain", "Thunder"));

    /** The thread drawing (or ticking the visuals of) the world right now, and how deep; see {@link #beginVisuals}. */
    private static Thread visualThread;
    private static int visualDepth;

    public WeatherChanger() {
        super("Weather Changer", "Change the weather you see.", Category.MODS);
        instance = this;
    }

    /**
     * Marks the start of purely visual work on the client thread: drawing the world, or ticking rain particles and
     * sounds. Only reads of the weather made in between are changed (see WorldWeatherMixin). Pair with
     * {@link #endVisuals()} in a finally block.
     */
    public static void beginVisuals() {
        visualThread = Thread.currentThread();
        visualDepth++;
    }

    public static void endVisuals() {
        if (visualDepth > 0 && --visualDepth == 0) visualThread = null;
    }

    /** The rain strength {@code world} reports for {@code value}: forced while drawing, untouched otherwise. */
    public static float rain(World world, float value) {
        WeatherChanger m = forcing(world);
        return m == null ? value : (m.weather.is("Clear") ? 0f : 1f);
    }

    /** As {@link #rain} for thunder. */
    public static float thunder(World world, float value) {
        WeatherChanger m = forcing(world);
        return m == null ? value : (m.weather.is("Thunder") ? 1f : 0f);
    }

    private static WeatherChanger forcing(World world) {
        if (visualThread != Thread.currentThread()) return null; // gameplay reads, the server thread, workers
        WeatherChanger m = instance;
        if (m == null || !m.isEnabled() || !world.isClient()) return null;
        // Dimensions without weather (Nether, End) stay as they are.
        return world.canHaveWeather() ? m : null;
    }

    @Override
    public String getSuffix() { return weather.get(); }
}
