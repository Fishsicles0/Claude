package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;

/**
 * Shrinks, moves, trims or hides the boss health bars at the top of the screen. Vanilla still draws them;
 * {@code BossBarHudMixin} scales them around the top centre, moves them down by Y Offset, stops after Max Bars and
 * leaves out the bar or the name for Mode.
 */
public class BossBar extends Module {
    /** Vanilla's layout (GUI pixels): the first bar's top, the step to the next one and a bar's height. */
    private static final int FIRST_Y = 12, STEP = 19, BAR_H = 5;

    public final BooleanSetting hide = add(new BooleanSetting("Hide", false)
            .describe("Hides boss bars completely."));
    public final NumberSetting scale = add(new NumberSetting("Scale", 0.8, 0.5, 1.5, 0.05)
            .describe("How big the boss bars are drawn (1 = vanilla size)."));
    public final NumberSetting yOffset = add(new NumberSetting("Y Offset", 0, 0, 100, 1)
            .describe("Move the boss bars down from the top of the screen."));
    public final NumberSetting maxBars = add(new NumberSetting("Max Bars", 6, 1, 6, 1)
            .describe("The most boss bars shown at once. Vanilla also stops at a third of the screen."));
    public final ModeSetting mode = add(new ModeSetting("Mode", "Full", "Full", "Bar Only", "Text Only")
            .describe("Show each bar with the boss's name, just the bar, or just the name."));

    public BossBar() {
        super("Boss Bar", "Shrink, move, trim or hide the boss health bars.", Category.VISUALS);
    }

    public static BossBar active() {
        if (EvadeClient.modules == null) return null;
        BossBar b = EvadeClient.modules.get(BossBar.class);
        return b != null && b.isEnabled() ? b : null;
    }

    /**
     * How many of {@code total} boss bars are drawn on a {@code guiHeight}-tall screen: vanilla's walk (a bar every
     * 19 GUI pixels from y 12, stopping once the next would start past a third of the screen), capped by Max Bars.
     */
    public static int shownBars(int total, int guiHeight) {
        if (total <= 0) return 0;
        BossBar b = active();
        if (b != null && b.hide.get()) return 0;
        int max = b == null ? Integer.MAX_VALUE : b.maxBars.getInt();
        int limit = guiHeight / 3, y = FIRST_Y, drawn = 0;
        while (drawn < total) {
            drawn++;
            y += STEP;
            if (drawn >= max || y >= limit) break;
        }
        return drawn;
    }

    /**
     * How far down (GUI pixels) the boss bars reach with this module's settings: the bottom of the last bar (of the
     * last name, in Text Only) for {@code total} bars on a {@code guiHeight}-tall screen; 0 when none are drawn.
     * Follows Scale, Y Offset, Max Bars and Mode, so top-centre HUD widgets can sit just below the bars.
     */
    public static int bottomGui(int total, int guiHeight) {
        int drawn = shownBars(total, guiHeight);
        if (drawn == 0) return 0;
        BossBar b = active();
        int lastY = FIRST_Y + (drawn - 1) * STEP;
        // A name is drawn 9 pixels above its bar and is 9 tall, so in Text Only the last name ends where its bar would start.
        int bottom = b != null && b.mode.is("Text Only") ? lastY : lastY + BAR_H;
        float s = b == null ? 1f : b.scale.getFloat();
        int dy = b == null ? 0 : b.yOffset.getInt();
        return (int) Math.ceil(dy + bottom * s);
    }
}
