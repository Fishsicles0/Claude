package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.util.Util;

import java.lang.ref.WeakReference;

/**
 * Blurs the background behind menus (pause menu, options, advancements, server list, world list...) at its own
 * strength, with a short fade-in, even while the game's Menu Background Blur is 0. It uses the same vanilla menu blur
 * as Inventory Blur (the radius comes from GameRendererBlurMixin); inventories and containers stay Inventory Blur's
 * job, and chat is never blurred. Evade's own screens keep their own look.
 *
 * <p>Hooks: {@code MenuBlurScreenMixin} (HEAD of Screen.applyBlur) and {@code GameRendererBlurMixin} (the radius).
 */
public class MenuBlur extends Module {
    /** A new screen replacing a blurred one this soon after its last frame keeps the blur instead of fading again. */
    private static final long CONTINUE_MS = 250;

    public final NumberSetting strength = add(new NumberSetting("Strength", 6, 1, 15, 1)
            .describe("How blurry the background gets, on the same scale as the game's Menu Background Blur (which goes to 10)."));
    public final NumberSetting fadeIn = add(new NumberSetting("Fade In", 150, 0, 600, 50)
            .describe("Milliseconds the blur takes to fade in when a menu opens. 0 = instant."));
    public final BooleanSetting inWorld = add(new BooleanSetting("In Game Menus", true)
            .describe("Blur the world behind menus opened while playing: pause menu, options, advancements and so on."));
    public final BooleanSetting titleMenus = add(new BooleanSetting("Title Menus", true)
            .describe("Blur the panorama behind menus outside a world: world list, server list, options."));

    private static MenuBlur INSTANCE;

    private WeakReference<Screen> lastScreen = new WeakReference<>(null);
    private long openedAt;
    private long lastShownAt = Long.MIN_VALUE / 2;
    /** The frame's DrawContext that was last blurred, so one frame never asks for the blur twice. */
    private WeakReference<DrawContext> lastBlurred = new WeakReference<>(null);

    public MenuBlur() {
        super("Menu Blur", "Blurs the background behind menus, with its own strength.", Category.VISUALS);
        INSTANCE = this;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> onScreenInit(screen));
    }

    /** Works on the title screen's menus too. */
    @Override
    protected boolean alwaysRunHooks() { return true; }

    public static MenuBlur active() {
        MenuBlur b = INSTANCE;
        return b != null && b.isEnabled() ? b : null;
    }

    private void onScreenInit(Screen screen) {
        if (screen == lastScreen.get()) return;
        lastScreen = new WeakReference<>(screen);
        long now = Util.getMeasuringTimeMs();
        // Going from one menu to the next (Options -> Video Settings) keeps the blur instead of fading in again.
        if (now - lastShownAt > CONTINUE_MS) openedAt = now;
    }

    private float fade() {
        float ms = fadeIn.getFloat();
        if (ms <= 0) return 1f;
        float p = Math.min(1f, Math.max(0f, (Util.getMeasuringTimeMs() - openedAt) / ms));
        float inv = 1f - p;
        return 1f - inv * inv * inv;
    }

    /** Whether the module is on and blurs behind {@code screen}. */
    public static boolean appliesTo(Screen screen) {
        MenuBlur b = active();
        return b != null && screen != null && b.wants(screen);
    }

    private boolean wants(Screen s) {
        if (s instanceof ChatScreen) return false; // chat (and the sleeping chat) stays clear
        if (s instanceof HandledScreen<?>) return false; // inventories: Inventory Blur's job
        if (s.getClass().getName().startsWith("net.evadeclient.gui.")) return false; // Evade's menus keep their look
        if (InventoryBlur.appliesTo(s)) return false;
        return mc.world != null ? inWorld.get() : titleMenus.get();
    }

    /**
     * The blur radius for this frame's shaders (GameRendererBlurMixin): this module's strength, faded in, while a
     * menu it blurs is open; otherwise {@code vanilla} (the game's own option, or what Inventory Blur chose).
     */
    public static int blurRadius(int vanilla) {
        MenuBlur b = active();
        if (b == null || !appliesTo(mc.currentScreen)) return vanilla;
        b.lastShownAt = Util.getMeasuringTimeMs();
        return Math.max(1, Math.round(b.strength.getFloat() * b.fade()));
    }

    /**
     * MenuBlurScreenMixin, HEAD of Screen.applyBlur: blurs behind {@code screen} even when the game's Menu Background
     * Blur is 0. Returns whether it handled the blur (vanilla's check is then skipped).
     */
    public static boolean applyBlur(Screen screen, DrawContext ctx) {
        MenuBlur b = active();
        if (b == null || !appliesTo(screen)) return false;
        b.lastShownAt = Util.getMeasuringTimeMs();
        if (b.lastBlurred.get() == ctx) return true;
        b.lastBlurred = new WeakReference<>(ctx);
        try {
            ctx.applyBlur();
        } catch (IllegalStateException ignored) {
            // Something already blurred this frame.
        }
        return true;
    }
}
