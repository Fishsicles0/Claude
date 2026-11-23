package net.evadeclient.module.mods;

import net.evadeclient.mixin.WorldWeatherAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.lang.ref.WeakReference;

/**
 * Client-side weather. Only what you see and hear changes (sky, fog, rain or snow, splashes and rain sounds): the game
 * itself keeps the server's real weather, so things like Riptide work exactly as they should.
 *
 * <p>How: the client world reports Weather Changer's rain and thunder strength only to the code that draws the world
 * and ticks its rain particles and sounds (GameRendererMixin marks those spans with {@link #beginVisuals}, and
 * WorldWeatherMixin asks {@link #rain} and {@link #thunder}). The world's own values are never written, so they always
 * hold the server's weather; Weather Changer reads them raw (WorldWeatherAccessor) to fade from them when it takes
 * over and back to them when it lets go, so turning it off, or picking Server, eases back to the real weather.
 *
 * <p>Thunder strength works as in vanilla: the sky darkens by thunder times rain, so Thunder Darkness also scales
 * with Rain Intensity. The precipitation type (Snow modes, Precipitation) and Rain Volume are applied in
 * WeatherChangerRenderingMixin; Fake lightning flashes are made here, on the client only.
 */
public class WeatherChanger extends Module {
    private static WeatherChanger instance;

    private final ModeSetting weather = add(new ModeSetting("Weather", "Clear", "Server", "Clear", "Rain", "Snow", "Thunder", "Thunder Snow")
            .describe("The weather you see. Server shows the real weather (Precipitation and Rain Volume still apply)."));
    private final ModeSetting precipitation = add(new ModeSetting("Precipitation", "Biome", "Biome", "Always Rain", "Always Snow")
            .describe("Rain or snow: as each biome has it, or the same everywhere. Deserts and other dry biomes stay dry."));
    private final NumberSetting rainIntensity = add(new NumberSetting("Rain Intensity %", 100, 10, 100, 10)
            .describe("How heavy forced rain or snow is, and how much it darkens the sky."));
    private final NumberSetting thunderDarkness = add(new NumberSetting("Thunder Darkness %", 100, 0, 100, 10)
            .describe("How dark a forced thunderstorm makes the sky. Lighter rain makes it lighter too, as in vanilla."));
    private final BooleanSetting smooth = add(new BooleanSetting("Smooth Transition", true)
            .describe("Fade between weathers, and back to the real weather when turned off, instead of switching at once."));
    private final NumberSetting seconds = add(new NumberSetting("Transition Seconds", 3, 0.5, 10, 0.5)
            .describe("How long a full change of weather takes."));
    private final ModeSetting lightning = add(new ModeSetting("Lightning Flashes", "Vanilla", "Vanilla", "Fake (Thunder modes)")
            .describe("Fake: distant flashes and thunder in the Thunder modes, even when the server has no storm. "
                    + "Hide Lightning Flashes in the game's accessibility options still hides the flashes."));
    private final NumberSetting rainVolume = add(new NumberSetting("Rain Volume %", 100, 0, 100, 10)
            .describe("How loud rain sounds are. 0 mutes them."));

    /** The thread drawing (or ticking the visuals of) the world right now, and how deep; see {@link #beginVisuals}. */
    private static Thread visualThread;
    private static int visualDepth;

    // ---- what is shown (client thread only) --------------------------------------------------------------------
    /** Shown rain strength and raw thunder strength (before vanilla multiplies it by rain). */
    private static float shownRain, shownThunder;
    /** True while the shown weather is Weather Changer's own (forced, or still fading back to the real weather). */
    private static boolean overriding;
    private static long lastNanos;
    private static WeakReference<World> shownWorld = new WeakReference<>(null);
    /** Set while reading the world's real weather through its getters (only if WorldWeatherAccessor is missing). */
    private static boolean readingReal;

    private final Random random = Random.create();

    public WeatherChanger() {
        super("Weather Changer", "Change the weather you see. The server's weather is untouched.", Category.MODS);
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

    /** The rain strength {@code world} reports for {@code value}: Weather Changer's while drawing, untouched otherwise. */
    public static float rain(World world, float value) {
        return forcing(world) ? shownRain : value;
    }

    /** As {@link #rain} for thunder (already multiplied by rain, as vanilla's getter returns it). */
    public static float thunder(World world, float value) {
        return forcing(world) ? shownThunder * shownRain : value;
    }

    private static boolean forcing(World world) {
        if (readingReal || visualThread != Thread.currentThread()) return false; // gameplay reads, the server thread, workers
        WeatherChanger m = instance;
        // Dimensions without weather (Nether, End) stay as they are.
        if (m == null || !world.isClient() || !world.canHaveWeather()) return false;
        if (!m.isEnabled() && !overriding) return false;
        m.update(world);
        return overriding;
    }

    // ------------------------------------------------------------------ fading

    private boolean forcedPrecipitation() { return !weather.is("Server") && !weather.is("Clear"); }

    private boolean forcedThunder() { return weather.is("Thunder") || weather.is("Thunder Snow"); }

    /**
     * Steps the shown weather towards where it should be, by real time (a full 0..1 change takes Transition Seconds).
     * Forced weathers head for their strengths; when off, or on Server, it heads back to the world's real weather and
     * stops overriding once it gets there.
     */
    private void update(World world) {
        float realRain, realThunder;
        if ((Object) world instanceof WorldWeatherAccessor raw) {
            realRain = raw.evade$rainGradient();
            realThunder = raw.evade$thunderGradient();
        } else {
            readingReal = true;
            try {
                realRain = world.getRainGradient(1f);
                realThunder = realRain > 0f ? world.getThunderGradient(1f) / realRain : 0f;
            } finally {
                readingReal = false;
            }
        }
        boolean follow = !isEnabled() || weather.is("Server");
        float rainTarget = follow ? realRain : forcedPrecipitation() ? rainIntensity.getFloat() / 100f : 0f;
        float thunderTarget = follow ? realThunder : forcedThunder() ? thunderDarkness.getFloat() / 100f : 0f;

        long now = System.nanoTime();
        if (shownWorld.get() != world) {
            // A new world or dimension: start on the target, no fade from the last one.
            shownWorld = new WeakReference<>(world);
            shownRain = rainTarget;
            shownThunder = thunderTarget;
            overriding = !follow;
            lastNanos = now;
            return;
        }
        if (!overriding) {
            lastNanos = now;
            if (follow) return;
            // Taking over (switched on, or away from Server): fade from the real weather.
            shownRain = realRain;
            shownThunder = realThunder;
            overriding = true;
        }
        float dt = Math.max(0f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float step = smooth.get() ? dt / seconds.getFloat() : 1f;
        shownRain = approach(shownRain, rainTarget, step);
        shownThunder = approach(shownThunder, thunderTarget, step);
        if (follow && shownRain == rainTarget && shownThunder == thunderTarget) overriding = false;
    }

    /** The rain strength last shown by Weather Changer, and whether it is still overriding the real weather (tests). */
    public static float shownRain() { return shownRain; }

    public static boolean overriding() { return overriding; }

    private static float approach(float from, float to, float step) {
        return Math.abs(to - from) <= step ? to : from + Math.signum(to - from) * step;
    }

    // ------------------------------------------------------------------ fake lightning

    @Override
    public void onTick() {
        ClientWorld w = mc.world;
        if (w == null || mc.gameRenderer == null || !lightning.is("Fake (Thunder modes)") || !forcedThunder()) return;
        if (!overriding || shownWorld.get() != w || !w.canHaveWeather()) return;
        // About one strike every 30 seconds (1 in 600 ticks) once the storm has rolled in, fewer while it builds up.
        float full = rainIntensity.getFloat() / 100f;
        float storm = full > 0f ? Math.min(1f, shownRain / full) : 0f;
        if (storm < 0.5f || random.nextFloat() * 600f >= storm) return;
        w.setLightningTicksLeft(2); // the sky flash, as a real bolt's client-side tick does
        // Its thunder from 120-220 blocks away in a random direction, heard at 45-80% loudness. Sounds louder than 1
        // only carry further (range = 16 x volume), so the volume is picked to give that loudness at that distance.
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 120 + random.nextDouble() * 100;
        float loudness = 0.45f + random.nextFloat() * 0.35f;
        float volume = (float) (dist / (16.0 * (1.0 - loudness)));
        w.playSoundClient(cam.x + Math.cos(angle) * dist, cam.y + 30, cam.z + Math.sin(angle) * dist,
                SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, volume, 0.8f + random.nextFloat() * 0.2f, false);
    }

    // ------------------------------------------------------------------ WeatherChangerRenderingMixin

    /**
     * The precipitation drawn at a spot that would have {@code original}: snow in the Snow modes, otherwise as
     * Precipitation says. Spots without any (dry biomes, unloaded chunks) stay without.
     */
    public static Biome.Precipitation precipitation(World world, Biome.Precipitation original) {
        WeatherChanger m = instance;
        if (original == Biome.Precipitation.NONE || m == null || !m.isEnabled() || !world.canHaveWeather()) return original;
        if (m.weather.is("Snow") || m.weather.is("Thunder Snow")) return Biome.Precipitation.SNOW;
        return switch (m.precipitation.get()) {
            case "Always Rain" -> Biome.Precipitation.RAIN;
            case "Always Snow" -> Biome.Precipitation.SNOW;
            default -> original;
        };
    }

    /** A rain sound's volume after Rain Volume (0 means don't play it). */
    public static float rainVolume(float volume) {
        WeatherChanger m = instance;
        if (m == null || !m.isEnabled()) return volume;
        return volume * MathHelper.clamp(m.rainVolume.getFloat() / 100f, 0f, 1f);
    }

    @Override
    public String getSuffix() { return weather.get(); }
}
