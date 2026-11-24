package net.evadeclient.gametest.addon;

import net.evadeclient.addon.AddonCategory;
import net.evadeclient.addon.AddonHudElement;
import net.evadeclient.addon.AddonModule;
import net.evadeclient.addon.AddonRegistry;
import net.evadeclient.addon.AddonTextHud;
import net.evadeclient.addon.EvadeAddon;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The example Evade addon from the docs, loaded only in the in-game tests (the test mod lists it under the
 * {@code "evade"} entrypoint). It adds a module with one of each setting type, a text widget and a drawn widget, and
 * tries to register a module whose name clashes with a built-in one (which Evade refuses).
 */
public final class ExampleEvadeAddon implements EvadeAddon {
    /** How many times {@link #register} ran (Evade calls it once). */
    public static int registered;
    public static Greeter greeter;

    @Override
    public void register(AddonRegistry registry) {
        registered++;
        greeter = new Greeter();
        registry.module(greeter);
        registry.hud(new TicksWidget());
        registry.hud(new BarWidget());
        registry.module(new Clash()); // refused: Evade already has "Fullbright"
    }

    /** A module with one setting of each type. */
    public static final class Greeter extends AddonModule {
        public final BooleanSetting loud = toggle("Loud", false, "Say it in capitals.");
        public final NumberSetting times = slider("Times", 1, 1, 5, 1, "How many times to say it.");
        public final ModeSetting style = mode("Style", "Friendly", "How it sounds.", "Friendly", "Formal");
        public final ColorSetting tint = color("Tint", 0xFF3BE37A, "Colour of the greeting.");
        public final KeySetting greetKey = key("Greet Key", GLFW.GLFW_KEY_UNKNOWN, "Hold to greet.");
        public final StringSetting greeting = text("Greeting", "Hello", 32, "What it says.");
        public int ticks;

        public Greeter() {
            super("Example Greeter", "An example addon module that counts ticks while on.", AddonCategory.MISC);
        }

        @Override public void onTick() { ticks++; }
    }

    /** A text widget. */
    public static final class TicksWidget extends AddonTextHud {
        public TicksWidget() { super("Example Ticks", "An example addon widget showing the greeter's tick count.", 4, 120); }

        @Override
        protected List<String> lines(boolean preview) {
            return List.of("Ticks: " + (greeter == null ? 0 : greeter.ticks));
        }
    }

    /** A widget that draws its own content. */
    public static final class BarWidget extends AddonHudElement {
        public final ColorSetting bar = color("Bar Color", 0xFFE11D2E, "Colour of the bar.");

        public BarWidget() { super("Example Bar", "An example addon widget that draws a bar.", 4, 140); }

        @Override
        public void render(DrawContext ctx, int left, int top, boolean preview) {
            size(60, 10);
            background(ctx, left, top);
            int fill = 4 + (int) ((System.currentTimeMillis() / 50) % 52);
            ctx.fill(left + 4, top + 3, left + fill, top + 7, bar.get());
        }
    }

    /** Clashes with Evade's Fullbright, so Evade refuses it. */
    public static final class Clash extends AddonModule {
        public Clash() { super("Full Bright", "Clashes with a built-in name.", AddonCategory.VISUALS); }
    }
}
