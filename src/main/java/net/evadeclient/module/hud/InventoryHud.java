package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.util.Arrays;

/**
 * Shows the 27 main inventory slots on screen, optionally with your hotbar under them and your armour and offhand
 * beside them. Can count free slots, warn when the inventory is full and light up slots that just changed.
 */
public class InventoryHud extends HudElementModule {
    private static final EquipmentSlot[] SIDE = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};
    /** The game's own 18 x 18 inventory slot (the sprite chests and the inventory are drawn with). */
    private static final Identifier SLOT_SPRITE = Identifier.ofVanilla("container/slot");
    /** Main inventory and hotbar slots (0-8 hotbar, 9-35 the three rows above it). */
    private static final int SLOTS = PlayerInventory.MAIN_SIZE;
    private static final int FAINT = 0x30FFFFFF;

    private final BooleanSetting slotBackgrounds = add(new BooleanSetting("Slot Backgrounds", true)
            .describe("A background behind every slot, so empty slots show. Slot Style picks its look."));
    private final ModeSetting slotStyle = add(new ModeSetting("Slot Style", "Faint", "Faint", "Vanilla", "Outline")
            .describe("Look of the slot backgrounds: a faint square, the game's own inventory slot, or a thin outline."));
    private final NumberSetting slotSpacing = add(new NumberSetting("Slot Spacing", 18, 16, 22, 1)
            .describe("Distance from one slot to the next, in pixels (18 is the game's own spacing)."));
    private final BooleanSetting hideWhileOpen = add(new BooleanSetting("Hide While Inventory Open", true)
            .describe("Hide the viewer while an inventory or container screen is open."));
    private final BooleanSetting hideWhenEmpty = add(new BooleanSetting("Hide When Empty", false)
            .describe("Hide the viewer while every slot it shows is empty."));
    private final BooleanSetting armorColumn = add(new BooleanSetting("Armor Column", false)
            .describe("Your armour and offhand in a column on the left."));
    private final BooleanSetting showHotbar = add(new BooleanSetting("Show Hotbar", false)
            .describe("Your hotbar as a fourth row under the inventory."));
    private final BooleanSetting highlightSelected = add(new BooleanSetting("Highlight Selected", true)
            .describe("Outline the hotbar slot you're holding (with Show Hotbar)."));
    private final ModeSetting freeSlots = add(new ModeSetting("Free Slots", "Off", "Off", "Text", "Bar")
            .describe("How many of your 36 slots are free, as text (\"14 free\") or a bar that fills as the inventory does."));
    private final NumberSetting lowSpaceAt = add(new NumberSetting("Low Space At", 3, 0, 9, 1)
            .describe("The free-slot count turns Warn Color at this many free slots or fewer."));
    private final ColorSetting warnColor = add(new ColorSetting("Warn Color", 0xFFE11D2E)
            .describe("Colour of the free-slot count when space is low, and of the full-inventory border."));
    private final BooleanSetting flashWhenFull = add(new BooleanSetting("Flash When Full", true)
            .describe("Pulse a border around the viewer while all 36 slots are taken."));
    private final ModeSetting highlightChanges = add(new ModeSetting("Highlight Changes", "Off", "Off", "Gains", "Any Change")
            .describe("Light up slots that just changed: Gains for items you picked up, Any Change for anything moved, "
                    + "used or dropped too (durability wear doesn't count). Not while an inventory screen is open."));
    private final NumberSetting highlightTime = add(new NumberSetting("Highlight Time", 1.5, 0.5, 5.0, 0.5)
            .describe("Seconds a changed slot stays lit, fading out."));
    private final ColorSetting highlightColor = add(new ColorSetting("Highlight Color", 0xFF3BE37A)
            .describe("Colour of the changed-slot highlight."));

    /** What each of the 36 slots held at the last tick (copies), to spot changes. */
    private final ItemStack[] snapshot = new ItemStack[SLOTS];
    /** When each slot last changed (ms), 0 for never. */
    private final long[] changedAt = new long[SLOTS];
    /** False until a snapshot was taken this enable / in this world, so the first one never lights anything. */
    private boolean snapshotValid;
    private Object snapshotWorld;

    /** Free slots among the 36, counted once a tick. */
    private int free = SLOTS;
    private long freeTick = Long.MIN_VALUE;

    public InventoryHud() {
        super("Inventory Viewer", "Your inventory on screen.", 4, -1);
        Arrays.fill(snapshot, ItemStack.EMPTY);
    }

    @Override
    public boolean shouldShow() {
        if (hideWhileOpen.get() && mc.currentScreen instanceof HandledScreen<?>) return false;
        return super.shouldShow();
    }

    @Override
    protected void onEnable() {
        snapshotValid = false;
        Arrays.fill(changedAt, 0L);
    }

    @Override
    public void onTick() {
        var p = mc.player;
        if (p == null) return;
        if (highlightChanges.is("Off")) {
            // Nothing to light up: the first tick after it is turned on takes a fresh snapshot quietly.
            snapshotValid = false;
            return;
        }
        PlayerInventory inv = p.getInventory();
        if (mc.world != snapshotWorld) {
            snapshotValid = false;
            Arrays.fill(changedAt, 0L);
        }
        // Re-snapshot quietly on the first tick and while sorting your own inventory (or a chest).
        boolean quiet = !snapshotValid || mc.currentScreen instanceof HandledScreen<?>;
        boolean gainsOnly = highlightChanges.is("Gains");
        long now = System.currentTimeMillis();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack cur = inv.getStack(i), old = snapshot[i];
            if (ItemStack.areEqual(old, cur)) continue;
            if (!quiet && (gainsOnly ? gained(old, cur) : changed(old, cur))) changedAt[i] = now;
            snapshot[i] = cur.copy();
        }
        snapshotValid = true;
        snapshotWorld = mc.world;
    }

    /** A pickup: an empty slot filled, a stack that grew, or a different item in the slot. */
    private static boolean gained(ItemStack old, ItemStack cur) {
        if (cur.isEmpty()) return false;
        if (old.isEmpty() || !ItemStack.areItemsEqual(old, cur)) return true;
        return ItemStack.areItemsAndComponentsEqual(old, cur) && cur.getCount() > old.getCount();
    }

    /** Anything but wear: a tool losing durability as you use it is not a change worth lighting up. */
    private static boolean changed(ItemStack old, ItemStack cur) {
        if (old.isEmpty() != cur.isEmpty() || !ItemStack.areItemsEqual(old, cur) || old.getCount() != cur.getCount()) return true;
        if (old.isDamageable() && old.getDamage() != cur.getDamage()) {
            ItemStack worn = old.copy();
            worn.setDamage(cur.getDamage());
            return !ItemStack.areEqual(worn, cur);
        }
        return true;
    }

    /** Free slots among the 36 main and hotbar slots, recounted once a tick. */
    private int freeSlots(PlayerInventory inv) {
        long tick = EvadeClient.ticks;
        if (tick != freeTick) {
            freeTick = tick;
            int n = 0;
            for (int i = 0; i < SLOTS; i++) if (inv.getStack(i).isEmpty()) n++;
            free = n;
        }
        return free;
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        int pitch = slotSpacing.getInt();
        boolean side = armorColumn.get(), hotbar = showHotbar.get();
        int sideW = side ? pitch + 3 : 0;
        int gridH = 3 * pitch + (hotbar ? 4 + pitch : 0);
        int sideH = side ? SIDE.length * pitch : 0;
        int bodyH = Math.max(gridH, sideH);
        String freeMode = freeSlots.get();
        int extra = switch (freeMode) {
            case "Text" -> 10;
            case "Bar" -> 4;
            default -> 0;
        };
        width = 9 * pitch + 6 + sideW;
        height = bodyH + 6 + extra;
        var p = mc.player;
        if (p == null) { empty = true; return; }
        var inv = p.getInventory();

        if (!preview && hideWhenEmpty.get() && nothingShown(p, inv, side, hotbar)) { empty = true; return; }

        long now = System.currentTimeMillis();
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        int nFree = freeSlots(inv);
        if (nFree == 0 && flashWhenFull.get()) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 150.0);
            Theme.stroke(ctx, left, top, left + width, top + height, Theme.alpha(warnColor.get() | 0xFF000000, pulse), Theme.R_HUD);
        }

        var tr = mc.textRenderer;
        boolean bg = slotBackgrounds.get();
        if (side) {
            int colTop = top + 3 + (bodyH - sideH) / 2;
            for (int i = 0; i < SIDE.length; i++) {
                int x = left + 3, y = colTop + i * pitch;
                if (bg) slotBackground(ctx, x, y, pitch);
                ItemStack s = p.getEquippedStack(SIDE[i]);
                if (!s.isEmpty()) HudDraw.itemWithDurability(ctx, s, x, y, false, smoothFont.get());
            }
        }

        boolean highlight = !highlightChanges.is("Off");
        float lifeMs = highlightTime.getFloat() * 1000f;
        int gridLeft = left + 3 + sideW;
        int gridTop = top + 3 + (bodyH - gridH) / 2;
        for (int i = 0; i < 27; i++) {
            int x = gridLeft + (i % 9) * pitch, y = gridTop + (i / 9) * pitch;
            slot(ctx, tr, inv.getStack(9 + i), 9 + i, x, y, pitch, bg, highlight, lifeMs, now);
        }
        if (hotbar) {
            int y = gridTop + 3 * pitch + 4;
            for (int i = 0; i < 9; i++) slot(ctx, tr, inv.getStack(i), i, gridLeft + i * pitch, y, pitch, bg, highlight, lifeMs, now);
            if (highlightSelected.get()) {
                int sel = inv.getSelectedSlot();
                if (sel >= 0 && sel < 9) {
                    // Around the slot when there's room, on its edge when slots touch (never over a neighbour).
                    int x = gridLeft + sel * pitch, o = pitch >= 18 ? 1 : 0;
                    Theme.stroke(ctx, x - o, y - o, x + 16 + o, y + 16 + o, Theme.RED | 0xFF000000, 0);
                }
            }
        }

        if (extra > 0) {
            int low = lowSpaceAt.getInt();
            int warn = warnColor.get() | 0xFF000000;
            boolean isLow = nFree <= low;
            int y = top + 3 + bodyH;
            if (freeMode.equals("Text")) {
                String label = nFree == 0 ? "Full" : nFree + " free";
                ctx.drawText(tr, styled(label), gridLeft, y + 1, isLow ? warn : Theme.TEXT, true);
            } else {
                float used = (SLOTS - nFree) / (float) SLOTS;
                int barLeft = left + 3, barW = width - 6;
                Theme.fill(ctx, barLeft, y + 1, barLeft + barW, y + 3, 0x40FFFFFF);
                Theme.fill(ctx, barLeft, y + 1, barLeft + Math.round(barW * used), y + 3, isLow ? warn : Theme.RED | 0xFF000000);
            }
        }
    }

    /** Whether every slot the viewer shows (rows, hotbar if shown, armour column if shown) is empty. */
    private static boolean nothingShown(PlayerEntity p, PlayerInventory inv, boolean side, boolean hotbar) {
        for (int i = hotbar ? 0 : 9; i < SLOTS; i++) if (!inv.getStack(i).isEmpty()) return false;
        if (side) for (EquipmentSlot s : SIDE) if (!p.getEquippedStack(s).isEmpty()) return false;
        return true;
    }

    private void slot(DrawContext ctx, TextRenderer tr, ItemStack s, int index, int x, int y,
                      int pitch, boolean bg, boolean highlight, float lifeMs, long now) {
        if (bg) slotBackground(ctx, x, y, pitch);
        if (highlight && changedAt[index] != 0) {
            float age = now - changedAt[index];
            if (age < lifeMs) Theme.fill(ctx, x, y, x + 16, y + 16, Theme.alpha(highlightColor.get() | 0xFF000000, 0.45f * (1f - age / lifeMs)));
            else changedAt[index] = 0;
        }
        if (!s.isEmpty()) {
            ctx.drawItem(s, x, y);
            ctx.drawStackOverlay(tr, s, x, y);
        }
    }

    /** The background of one 16 x 16 slot at (x, y) in the chosen Slot Style. */
    private void slotBackground(DrawContext ctx, int x, int y, int pitch) {
        switch (slotStyle.get()) {
            case "Vanilla" -> {
                // The sprite is 18 px with a 1 px rim: centred on the item, neighbours share a rim at tight spacing.
                int size = Math.min(18, pitch + 2), off = (size - 16) / 2;
                ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x - off, y - off, size, size);
            }
            case "Outline" -> {
                // Outside the item when there's room between slots, on its edge when they touch.
                int o = pitch >= 18 ? 1 : 0;
                Theme.stroke(ctx, x - o, y - o, x + 16 + o, y + 16 + o, 0x50FFFFFF, 0);
            }
            default -> ctx.fill(x, y, x + 16, y + 16, FAINT);
        }
    }
}
