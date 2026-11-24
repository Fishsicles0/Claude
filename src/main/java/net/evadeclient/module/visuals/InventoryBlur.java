package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractCommandBlockScreen;
import net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.BeaconScreen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.gui.screen.ingame.BookSigningScreen;
import net.minecraft.client.gui.screen.ingame.BrewingStandScreen;
import net.minecraft.client.gui.screen.ingame.CartographyTableScreen;
import net.minecraft.client.gui.screen.ingame.CrafterScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.gui.screen.ingame.ForgingScreen;
import net.minecraft.client.gui.screen.ingame.GrindstoneScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.JigsawBlockScreen;
import net.minecraft.client.gui.screen.ingame.LoomScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.client.gui.screen.ingame.StructureBlockScreen;
import net.minecraft.client.gui.screen.ingame.TestBlockScreen;
import net.minecraft.client.gui.screen.ingame.TestInstanceBlockScreen;
import net.minecraft.util.Util;

import java.lang.ref.WeakReference;

/**
 * Blurs the world behind inventories, containers and work blocks (vanilla only blurs other menus), with its own
 * strength, background dim and tint, and a short fade-in when a screen opens. Uses vanilla's own menu blur at this
 * module's strength (GameRendererBlurMixin feeds the radius), so it works even with the game's Menu Background Blur
 * at 0. Which screens blur is chosen per group; command, structure, jigsaw and test blocks never do.
 */
public class InventoryBlur extends Module {
    /** A new screen replacing a blurred one this soon after its last frame keeps the blur instead of fading again. */
    private static final long CONTINUE_MS = 250;
    /** Vanilla's in-game background gradient goes from alpha 0xC0 at the top to 0xD0 at the bottom. */
    private static final float TOP_SHADE = 0xC0 / (float) 0xD0;

    public final NumberSetting strength = add(new NumberSetting("Strength", 5, 1, 15, 1)
            .describe("How blurry the world behind gets, on the same scale as the game's Menu Background Blur (which goes to 10)."));
    public final NumberSetting dim = add(new NumberSetting("Dim %", 50, 0, 100, 5)
            .describe("How much the world behind darkens. 80 looks like vanilla; 0 leaves only the blur."));
    public final ColorSetting tint = add(new ColorSetting("Tint", 0xFF101010)
            .describe("The colour the background darkens toward."));
    public final NumberSetting fadeIn = add(new NumberSetting("Fade In", 150, 0, 600, 50)
            .describe("Milliseconds the blur and dim take to fade in when a screen opens. 0 = instant."));
    public final BooleanSetting playerInventory = add(new BooleanSetting("Player Inventory", true)
            .describe("Your own inventory, survival and creative."));
    public final BooleanSetting storage = add(new BooleanSetting("Storage", true)
            .describe("Chests, barrels, shulker boxes, hoppers, dispensers, droppers and mount inventories."));
    public final BooleanSetting workstations = add(new BooleanSetting("Workstations", true)
            .describe("Crafting tables, furnaces, anvils, enchanting tables, brewing stands, smithing tables, looms, stonecutters, grindstones, cartography tables, villagers, beacons and crafters."));
    public final BooleanSetting booksAndSigns = add(new BooleanSetting("Books & Signs", false)
            .describe("Reading and writing books, lecterns and editing signs."));

    private static InventoryBlur INSTANCE;

    /** The screen the fade last started for; a resize re-inits the same screen and must not restart it. */
    private WeakReference<Screen> lastScreen = new WeakReference<>(null);
    private long openedAt;
    /** When a screen this module blurs was last drawn. */
    private long lastShownAt = Long.MIN_VALUE / 2;
    /** The frame's DrawContext that was last blurred (a new one each frame), so one frame never blurs twice. */
    private WeakReference<DrawContext> lastBlurred = new WeakReference<>(null);

    public InventoryBlur() {
        super("Inventory Blur", "Blurs the background behind open inventories.", Category.VISUALS);
        INSTANCE = this;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> onScreenInit(screen));
    }

    public static InventoryBlur active() {
        InventoryBlur b = INSTANCE;
        return b != null && b.isEnabled() ? b : null;
    }

    public static boolean on() { return active() != null; }

    private void onScreenInit(Screen screen) {
        if (screen == lastScreen.get()) return;
        lastScreen = new WeakReference<>(screen);
        long now = Util.getMeasuringTimeMs();
        // A server swapping one menu for the next (or a chest's screen replacing another) keeps going smoothly.
        if (now - lastShownAt > CONTINUE_MS) openedAt = now;
    }

    /** 0..1, eased (fast start, soft finish), for the screen that's open now. */
    private float fade() {
        float ms = fadeIn.getFloat();
        if (ms <= 0) return 1f;
        float p = Math.min(1f, Math.max(0f, (Util.getMeasuringTimeMs() - openedAt) / ms));
        float inv = 1f - p;
        return 1f - inv * inv * inv;
    }

    // ================================================================ which screens

    /** Whether the module is on and blurs {@code screen} (in a world; the title screen's menus are left alone). */
    public static boolean appliesTo(Screen screen) {
        InventoryBlur b = active();
        return b != null && screen != null && mc.world != null && b.wants(screen);
    }

    private boolean wants(Screen s) {
        if (s instanceof AbstractCommandBlockScreen || s instanceof StructureBlockScreen || s instanceof JigsawBlockScreen
                || s instanceof TestBlockScreen || s instanceof TestInstanceBlockScreen) return false;
        // LecternScreen is a BookScreen.
        if (s instanceof BookScreen || s instanceof BookEditScreen || s instanceof BookSigningScreen
                || s instanceof AbstractSignEditScreen) return booksAndSigns.get();
        if (!(s instanceof HandledScreen<?>)) return false;
        if (s instanceof InventoryScreen || s instanceof CreativeInventoryScreen) return playerInventory.get();
        if (s instanceof CraftingScreen || s instanceof AbstractFurnaceScreen<?> || s instanceof ForgingScreen<?> // anvil, smithing
                || s instanceof EnchantmentScreen || s instanceof BrewingStandScreen || s instanceof LoomScreen
                || s instanceof StonecutterScreen || s instanceof GrindstoneScreen || s instanceof CartographyTableScreen
                || s instanceof MerchantScreen || s instanceof BeaconScreen || s instanceof CrafterScreen)
            return workstations.get();
        // Chests, shulker boxes, hoppers, dispensers, horses and other mounts, and other mods' containers.
        return storage.get();
    }

    // ================================================================ hooks

    /**
     * The blur radius the frame's shaders get (GameRendererBlurMixin): this module's strength, faded in, while a
     * screen it blurs is open; otherwise the game's own Menu Background Blur ({@code vanilla}).
     */
    public static int blurRadius(int vanilla) {
        InventoryBlur b = active();
        if (b == null || !appliesTo(mc.currentScreen)) return vanilla;
        b.lastShownAt = Util.getMeasuringTimeMs();
        return Math.max(1, Math.round(b.strength.getFloat() * b.fade()));
    }

    /**
     * Asks for the menu blur behind {@code screen} (HandledScreenMixin for inventories, ScreenMixin for books and
     * signs). Called directly, not through Screen.applyBlur, which skips it when Menu Background Blur is 0.
     */
    public static void blurBehind(Screen screen, DrawContext ctx) {
        InventoryBlur b = active();
        if (b == null || !appliesTo(screen) || b.lastBlurred.get() == ctx) return;
        b.lastBlurred = new WeakReference<>(ctx);
        try {
            ctx.applyBlur();
        } catch (IllegalStateException ignored) {
            // Something else already blurred this frame.
        }
    }

    /**
     * ScreenMixin, HEAD of Screen.renderInGameBackground: replaces vanilla's dark gradient behind a screen this module
     * blurs with its own Dim % and Tint, faded in with the blur. Books and signs get their blur here too. Returns
     * whether vanilla's gradient should be skipped.
     */
    public static boolean renderInGameBackground(Screen screen, DrawContext ctx) {
        InventoryBlur b = active();
        if (b == null || !appliesTo(screen)) return false;
        b.lastShownAt = Util.getMeasuringTimeMs();
        if (!(screen instanceof HandledScreen<?>)) blurBehind(screen, ctx);
        int alpha = Math.round(b.dim.getFloat() / 100f * 255f * b.fade());
        if (alpha > 0) {
            int rgb = b.tint.get() & 0xFFFFFF;
            int top = Math.round(alpha * TOP_SHADE);
            ctx.fillGradient(0, 0, screen.width, screen.height, (top << 24) | rgb, (alpha << 24) | rgb);
        }
        return true;
    }
}
