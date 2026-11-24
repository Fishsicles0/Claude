package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.List;

/**
 * How many Totems of Undying you carry (inventory, hotbar and offhand), with the totem's icon. The count turns
 * Low Color at or below Low At, and Empty Color at zero. Only your own inventory is read.
 */
public class TotemCounterHud extends TextHudModule {
    private static final ItemStack ICON = new ItemStack(Items.TOTEM_OF_UNDYING);

    private final BooleanSetting showIcon = add(new BooleanSetting("Show Icon", true)
            .describe("Draw the totem's icon in front of the count."));
    private final BooleanSetting showLabel = add(new BooleanSetting("Show Label", false)
            .describe("Write \"Totems\" after the count."));
    private final BooleanSetting hideWhenZero = add(new BooleanSetting("Hide When Zero", false)
            .describe("Hide the counter while you have no totems at all."));
    private final NumberSetting lowAt = add(new NumberSetting("Low At", 1, 0, 10, 1)
            .describe("The count turns Low Color at this many totems or fewer (0 turns the warning off)."));
    private final ColorSetting lowColor = add(new ColorSetting("Low Color", 0xFFFFB020)
            .describe("Colour of the count when you're down to Low At totems."));
    private final ColorSetting emptyColor = add(new ColorSetting("Empty Color", 0xFFFF4D5E)
            .describe("Colour of the count when you have no totems."));

    /** The count shown, worked out once per frame-batch in {@link #lines}. */
    private int count;

    public TotemCounterHud() {
        super("Totem Counter", "How many Totems of Undying you carry.", 4, 200);
    }

    /** Totems in the main inventory (hotbar included) and the offhand. */
    public static int countTotems(PlayerEntity p) {
        if (p == null) return 0;
        PlayerInventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.TOTEM_OF_UNDYING)) n += s.getCount();
        }
        ItemStack off = p.getOffHandStack();
        if (off.isOf(Items.TOTEM_OF_UNDYING)) n += off.getCount();
        return n;
    }

    @Override
    protected List<String> lines(boolean preview) {
        count = mc.player == null ? (preview ? 2 : 0) : countTotems(mc.player);
        if (preview && mc.player != null && count == 0) count = 2;
        if (!preview && (mc.player == null || (count == 0 && hideWhenZero.get()))) return List.of();
        return List.of(showLabel.get() ? count + (count == 1 ? " Totem" : " Totems") : String.valueOf(count));
    }

    @Override
    protected int lineColor(int index, int base) {
        if (count == 0) return emptyColor.get();
        if (lowAt.getInt() > 0 && count <= lowAt.getInt()) return lowColor.get();
        return base;
    }

    @Override
    protected int leadingWidth() { return showIcon.get() ? 18 : 0; }

    @Override
    protected int minHeight() { return showIcon.get() ? 20 : 0; }

    @Override
    protected void drawLeading(DrawContext ctx, int x, int y, int h) {
        if (showIcon.get()) ctx.drawItem(ICON, x, y + Math.max(0, (h - 16) / 2));
    }

    /** Test hook: the count shown right now. */
    public int countForTest() { return countTotems(mc.player); }
}
