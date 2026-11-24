package net.evadeclient.module.client;

import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.InvUtil;
import net.evadeclient.util.Notifications;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.RangedWeaponItem;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Your armour with durability, held items, and counts of your supplies. Supplies wrap into extra rows (or
 * columns) instead of growing sideways, so the panel keeps its size at its corner. Hidden while you have nothing
 * to show. Warns (flash, toast, sound) when a piece is about to break.
 */
public class ArmorHud extends HudElementModule {
    private static final int GREEN = 0xFF55FF55, YELLOW = 0xFFFFD21F, RED = 0xFFFF5555;

    private final ModeSetting layout = add(new ModeSetting("Layout", "Horizontal", "Horizontal", "Vertical"));
    private final ModeSetting order = add(new ModeSetting("Order", "Helmet First", "Helmet First", "Boots First")
            .describe("Which end of your armour comes first: the helmet or the boots."));
    private final ModeSetting durabilityText = add(new ModeSetting("Durability Text", "Percent",
            "Percent", "Remaining", "Remaining/Max", "Off")
            .describe("The label on each item that can wear out: percent left, durability left, durability left out of the total, or none."));
    private final ModeSetting durabilityColor = add(new ModeSetting("Durability Color", "Gradient", "Gradient", "Thresholds", "Static")
            .describe("Gradient fades from green to red. Thresholds: green, yellow under half, red under Warn Below %. Static uses Static Color."));
    private final ColorSetting staticColor = add(new ColorSetting("Static Color", 0xFFFFFFFF)
            .describe("Colour of the durability text when Durability Color is Static."));
    private final ModeSetting textSide = add(new ModeSetting("Text Side", "Under", "Under", "Auto", "Left", "Right")
            .describe("Vertical layout: durability text under each item or beside it. Auto puts it on the side facing the middle of the screen."));
    private final BooleanSetting hands = add(new BooleanSetting("Hands", true));
    private final BooleanSetting heldTotal = add(new BooleanSetting("Held Total", true)
            .describe("For stackable held items, show how many you carry in total instead of the stack in your hand."));
    private final BooleanSetting ammoCount = add(new BooleanSetting("Ammo Count", true)
            .describe("Arrows (and rockets for a crossbow) left for a held bow or crossbow. A red 0 when you're out."));
    private final BooleanSetting elytraTime = add(new BooleanSetting("Elytra Time", true)
            .describe("An elytra shows roughly how much flight time it has left instead of its durability."));
    private final BooleanSetting hideEmpty = add(new BooleanSetting("Hide Empty Slots", false)
            .describe("Leave out slots with nothing in them instead of showing an empty square."));
    private final NumberSetting spacing = add(new NumberSetting("Slot Spacing", 2, 0, 6, 1)
            .describe("Gap between the items."));
    private final BooleanSetting supplies = add(new BooleanSetting("Supplies", true));
    private final ModeSetting supplyPreset = add(new ModeSetting("Supply Preset", "PvP", "PvP", "Crystal", "Builder", "Archery", "Custom")
            .describe("Which supplies to count. Custom counts the items listed in Custom Supplies."));
    private final StringSetting customSupplies = add(new net.evadeclient.setting.ItemListSetting("Custom Supplies",
            "minecraft:totem_of_undying,minecraft:golden_apple", ".set ArmorHUD CustomSupplies <ids>")
            .describe("The items counted by the Custom preset. Click to pick them from a searchable grid."));
    private final BooleanSetting showEmptySupplies = add(new BooleanSetting("Show Empty Supplies", false)
            .describe("Keep supplies you've run out of on the panel, with a red 0."));
    private final BooleanSetting absorptionTimer = add(new BooleanSetting("Absorption Timer", false)
            .describe("A line with the absorption time left, for gapple timing."));
    private final NumberSetting warnBelow = add(new NumberSetting("Warn Below %", 15, 1, 50, 1)
            .describe("Durability left at which an item counts as about to break."));
    private final ModeSetting warnStyle = add(new ModeSetting("Warn Style", "Flash Slot", "Flash Slot", "Flash Border", "Off")
            .describe("How the panel shows an item about to break: flash its slot, flash the panel's border, or nothing."));
    private final BooleanSetting warnToast = add(new BooleanSetting("Warn Toast", true)
            .describe("A notification once when an item drops below Warn Below % (again after it's repaired or replaced)."));
    private final BooleanSetting warnSound = add(new BooleanSetting("Warn Sound", false)
            .describe("A quiet item-break sound with the warning."));
    private final BooleanSetting warnHeld = add(new BooleanSetting("Warn Held Items", true)
            .describe("Also warn about the tools and weapons in your hands, not only armour."));

    // ---- supplies -----------------------------------------------------------------------------------------

    private record Supply(ItemStack icon, Predicate<ItemStack> match) {
        static Supply of(Item item) { return new Supply(new ItemStack(item), s -> s.isOf(item)); }
    }

    private static final Set<Item> TORCHES = Set.of(Items.TORCH, Items.SOUL_TORCH, Items.COPPER_TORCH);

    private static final Map<String, List<Supply>> PRESETS = Map.of(
            "PvP", List.of(
                    Supply.of(Items.TOTEM_OF_UNDYING), Supply.of(Items.GOLDEN_APPLE), Supply.of(Items.ENCHANTED_GOLDEN_APPLE),
                    Supply.of(Items.ENDER_PEARL), Supply.of(Items.WIND_CHARGE), Supply.of(Items.EXPERIENCE_BOTTLE),
                    Supply.of(Items.SPLASH_POTION), Supply.of(Items.END_CRYSTAL), Supply.of(Items.OBSIDIAN)),
            "Crystal", List.of(
                    Supply.of(Items.TOTEM_OF_UNDYING), Supply.of(Items.END_CRYSTAL), Supply.of(Items.OBSIDIAN),
                    Supply.of(Items.ENCHANTED_GOLDEN_APPLE), Supply.of(Items.EXPERIENCE_BOTTLE),
                    Supply.of(Items.RESPAWN_ANCHOR), Supply.of(Items.GLOWSTONE)),
            "Builder", List.of(
                    new Supply(new ItemStack(Items.BRICKS), ArmorHud::isBuildingBlock),
                    new Supply(new ItemStack(Items.TORCH), s -> TORCHES.contains(s.getItem())),
                    Supply.of(Items.SCAFFOLDING), Supply.of(Items.WATER_BUCKET),
                    new Supply(new ItemStack(Items.COOKED_BEEF), s -> s.contains(DataComponentTypes.FOOD))),
            "Archery", List.of(
                    Supply.of(Items.ARROW), Supply.of(Items.SPECTRAL_ARROW), Supply.of(Items.TIPPED_ARROW),
                    Supply.of(Items.FIREWORK_ROCKET)));

    /** Counts shown for the editor's sample supplies when you carry none. */
    private static final int[] SAMPLE_COUNTS = {1, 12, 3};

    /**
     * Placeable building blocks: items named as blocks (so not seeds, redstone dust or string), never a shulker box
     * or anything else that holds items, and not the torches or scaffolding the Builder preset counts on their own.
     */
    private static boolean isBuildingBlock(ItemStack s) {
        return s.getItem() instanceof BlockItem b && b.getTranslationKey().startsWith("block.")
                && !s.contains(DataComponentTypes.CONTAINER) && !s.contains(DataComponentTypes.BUNDLE_CONTENTS)
                && !TORCHES.contains(s.getItem()) && !s.isOf(Items.SCAFFOLDING);
    }

    /** Custom Supplies parsed, and the text it was parsed from (re-parsed only when the setting changes). */
    private String customKey;
    private List<Supply> customList = List.of();

    /** Supply counts for {@link #countsFor}, read once per client tick. */
    private List<Supply> countsFor;
    private long countsTick = Long.MIN_VALUE;
    private int[] counts = new int[0];

    // ---- warnings -----------------------------------------------------------------------------------------

    private static final EquipmentSlot[] ALERT_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    /** Items already warned about ("slot|item id"); re-armed once seen above Warn Below % + 5. */
    private final Set<String> warned = new HashSet<>();
    /** The player {@link #warned} belongs to; a new one (respawn, dimension change, new server) starts clean. */
    private ClientPlayerEntity alertPlayer;

    /** An item slot on the panel: what's in it, its label (or null), the label's colour and its count text. */
    private record SlotView(EquipmentSlot slot, ItemStack stack, @Nullable String label, int color,
                            @Nullable String count, boolean warn) {}

    /** A supply cell: icon, count label, its colour and its width (icon, gap, label, padding). */
    private record Cell(ItemStack icon, Text label, int color, int width) {}

    public ArmorHud() {
        super("ArmorHUD", "Shows your armour durability and supplies.", -1, -24);
        setEnabled(true);
    }

    /** Old configs: "Durability %" off becomes Durability Text Off. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (settings.has(durabilityText.getName()) || !settings.has("Durability %")) return;
        try {
            if (!settings.get("Durability %").getAsBoolean()) durabilityText.set("Off");
        } catch (RuntimeException ignored) {
            // unreadable: keep the default
        }
    }

    @Override
    protected void onDisable() {
        warned.clear();
        alertPlayer = null;
    }

    // ---- low durability alerts ------------------------------------------------------------------------------

    @Override
    public void onTick() {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        if (p != alertPlayer) {
            alertPlayer = p;
            warned.clear();
        }
        float threshold = warnBelow.getFloat();
        for (EquipmentSlot slot : ALERT_SLOTS) {
            ItemStack s = p.getEquippedStack(slot);
            if (!canWarn(slot, s)) continue;
            String key = alertKey(p, slot, s);
            float pct = HudDraw.durability(s) * 100f;
            if (warned.contains(key)) {
                if (pct >= threshold + 5) warned.remove(key);
            } else if (pct < threshold) {
                warned.add(key);
                alert(slot, s, pct);
            }
        }
    }

    /** Whether {@code s} in {@code slot} is something the warnings look at. */
    private boolean canWarn(EquipmentSlot slot, ItemStack s) {
        if (s.isEmpty() || !s.isDamageable() || s.getMaxDamage() <= 0) return false;
        return !isHand(slot) || warnHeld.get();
    }

    private boolean lowDurability(EquipmentSlot slot, ItemStack s) {
        return canWarn(slot, s) && HudDraw.durability(s) * 100f < warnBelow.getFloat();
    }

    private static boolean isHand(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND;
    }

    /** Identifies one item in one place, so switching hotbar slots never repeats a warning. */
    private static String alertKey(ClientPlayerEntity p, EquipmentSlot slot, ItemStack s) {
        String where = slot == EquipmentSlot.MAINHAND ? "hotbar" + p.getInventory().getSelectedSlot() : slot.getName();
        return where + "|" + Registries.ITEM.getId(s.getItem());
    }

    private void alert(EquipmentSlot slot, ItemStack s, float pct) {
        if (warnToast.get()) {
            String what = switch (slot) {
                case HEAD -> "Helmet";
                case CHEST -> s.contains(DataComponentTypes.GLIDER) ? "Elytra" : "Chestplate";
                case LEGS -> "Leggings";
                case FEET -> "Boots";
                default -> s.getName().getString();
            };
            Notifications.push("Low durability", what + " at " + Math.round(pct) + "%", true);
        }
        if (warnSound.get())
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_ITEM_BREAK.value(), 1f, 0.35f));
    }

    // ---- rendering -----------------------------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var p = mc.player;
        if (p == null) { empty = true; return; }
        var tr = mc.textRenderer;
        boolean smooth = smoothFont.get();
        boolean vertical = layout.is("Vertical");
        int gap = spacing.getInt();

        List<SlotView> slots = slots(p, preview);

        List<Cell> cells = new ArrayList<>();
        if (supplies.get()) {
            List<Supply> list = supplyList();
            int[] n = supplyCounts(list);
            for (int i = 0; i < list.size(); i++) {
                if (n[i] > 0 || showEmptySupplies.get()) cells.add(cell(list.get(i).icon, n[i], smooth));
                else if (preview && cells.size() < 3) cells.add(cell(list.get(i).icon, SAMPLE_COUNTS[cells.size()], smooth));
            }
        }
        if (!preview && cells.isEmpty() && slots.stream().allMatch(s -> s.stack.isEmpty())) { empty = true; return; }

        Text hint = null;
        if (absorptionTimer.get()) {
            var absorb = p.getStatusEffect(StatusEffects.ABSORPTION);
            if (absorb != null && !absorb.isInfinite()) hint = HudDraw.styled("Absorb " + HudDraw.seconds(absorb.getDuration()), smooth);
            else if (preview) hint = HudDraw.styled("Absorb " + HudDraw.seconds(20 * 102), smooth);
        }

        boolean anyLabel = false;
        int maxLabelW = 0;
        int[] labelW = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            String label = slots.get(i).label;
            if (label == null) continue;
            anyLabel = true;
            labelW[i] = HudDraw.width(label, smooth);
            maxLabelW = Math.max(maxLabelW, labelW[i]);
        }

        // Item positions relative to the panel, and the length of the item row/column.
        int n = slots.size();
        int[][] itemAt = new int[n][];
        String side = "Under";
        int colW = 0, mainLen;
        if (!vertical) {
            int cx = 3;
            for (int i = 0; i < n; i++) {
                // A cell is as wide as the icon or its 3/4-size label, so long labels never run into each other.
                int cw = Math.max(16, labelW[i] == 0 ? 0 : (int) Math.ceil(labelW[i] * 0.75f) + 1);
                itemAt[i] = new int[]{cx + (cw - 16) / 2, 3};
                cx += cw + gap;
            }
            mainLen = n == 0 ? 0 : cx - gap - 3;
        } else {
            side = anyLabel ? resolveTextSide() : "Under";
            boolean under = side.equals("Under");
            int step = 16 + gap + (under && anyLabel ? 6 : 0);
            colW = n == 0 ? 0
                    : under ? Math.max(22, (int) Math.ceil(maxLabelW * 0.75f) + 6)
                    : anyLabel ? 22 + maxLabelW + 4 : 22;
            int iconX = under ? (colW - 16) / 2 : side.equals("Left") && anyLabel ? maxLabelW + 7 : 3;
            for (int i = 0; i < n; i++) itemAt[i] = new int[]{iconX, 3 + i * step};
            mainLen = n == 0 ? 0 : (n - 1) * step + 16 + (under && anyLabel ? 7 : 0);
        }

        // Supply positions relative to the panel, wrapped so the block is never longer than the item row/column
        // (or four slots, when only a few slots are shown).
        int wrapLen = Math.max(mainLen, 4 * 16 + 3 * gap);
        int[][] at = new int[cells.size()][];
        if (!vertical) {
            int mainH = n == 0 ? 3 : 3 + 16 + (anyLabel ? 8 : 2);
            int cx = 0, cy = 0, suppliesW = 0;
            for (int i = 0; i < cells.size(); i++) {
                int w = cells.get(i).width;
                if (cx > 0 && cx + w > wrapLen) { cx = 0; cy += 18; }
                at[i] = new int[]{3 + cx, mainH + cy};
                cx += w;
                suppliesW = Math.max(suppliesW, cx);
            }
            int suppliesH = cells.isEmpty() ? 0 : cy + 18;
            // Supply cells carry their own 3px right padding.
            width = Math.max(Math.max(mainLen + 6, cells.isEmpty() ? 0 : 3 + suppliesW), 22);
            height = mainH + suppliesH + 1;
        } else {
            int perCol = Math.max(1, wrapLen / 18);
            int colX = n == 0 ? 3 : colW + 2, cellColW = 0;
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0 && i % perCol == 0) { colX += cellColW; cellColW = 0; }
                at[i] = new int[]{colX, 3 + (i % perCol) * 18};
                cellColW = Math.max(cellColW, cells.get(i).width);
            }
            int suppliesH = Math.min(cells.size(), perCol) * 18 - 2;
            width = Math.max(cells.isEmpty() ? colW : colX + cellColW, 22);
            height = Math.max(mainLen, Math.max(0, suppliesH)) + 6;
        }
        int hintY = height - 1;
        if (hint != null) {
            height += 10;
            width = Math.max(width, tr.getWidth(hint) + 6);
        }

        if (background.get()) HudDraw.panel(ctx, left, top, width, height);

        // Low durability warning.
        boolean anyWarn = false;
        for (SlotView s : slots) anyWarn |= s.warn;
        String style = warnStyle.get();
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 140.0);
        if (anyWarn && style.equals("Flash Border")) outline(ctx, left, top, width, height, ColorUtil.withAlpha(0xFF3030, (int) (80 + 175 * pulse)));

        for (int i = 0; i < n; i++) {
            SlotView s = slots.get(i);
            int x = left + itemAt[i][0], y = top + itemAt[i][1];
            if (s.warn && style.equals("Flash Slot"))
                ctx.fill(x - 1, y - 1, x + 17, y + 17, ColorUtil.withAlpha(0xFF3030, (int) (40 + 120 * pulse)));
            if (s.stack.isEmpty()) {
                net.evadeclient.util.Theme.round(ctx, x + 3, y + 3, x + 13, y + 13, 0x30FFFFFF, net.evadeclient.util.Theme.R_XS);
                continue;
            }
            boolean under = !vertical || side.equals("Under");
            HudDraw.itemWithDurability(ctx, s.stack, x, y, under ? s.label : null, s.color, s.count, smooth);
            if (!under && s.label != null) {
                Text label = HudDraw.styled(s.label, smooth);
                int lx = side.equals("Left") ? left + 3 + maxLabelW - labelW[i] : x + 20;
                ctx.drawTextWithShadow(tr, label, lx, y + 4, s.color);
            }
        }

        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            int x = left + at[i][0], y = top + at[i][1];
            ctx.drawItem(c.icon, x, y);
            ctx.drawTextWithShadow(tr, c.label, x + 18, y + 5, c.color);
        }

        // Absorption time for gapple timing, as its own line along the bottom of the panel.
        if (hint != null) ctx.drawTextWithShadow(tr, hint, left + 3, top + hintY, 0xFFFFAA00);
    }

    /**
     * Test hook: each shown slot as "label|count" right now, in panel order ("-" for no label, or for vanilla's own
     * count).
     */
    public List<String> slotsForTest() {
        var p = mc.player;
        if (p == null) return List.of();
        List<String> out = new ArrayList<>();
        for (SlotView v : slots(p, false))
            out.add((v.label == null ? "-" : v.label) + "|" + (v.count == null ? "-" : v.count));
        return out;
    }

    /** Vertical Text Side with Auto resolved: text towards the middle of the screen (left when anchored right). */
    private String resolveTextSide() {
        String s = textSide.get();
        if (!s.equals("Auto")) return s;
        return x < 0 && x != CENTER ? "Left" : "Right";
    }

    private static void outline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y + 1, x + 1, y + h - 1, color);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** The armour (in Order) and, with Hands on, both hands; empty ones left out with Hide Empty Slots. */
    private List<SlotView> slots(ClientPlayerEntity p, boolean preview) {
        List<EquipmentSlot> shown = new ArrayList<>(List.of(HudDraw.ARMOR_TOP_DOWN));
        if (order.is("Boots First")) Collections.reverse(shown);
        if (hands.get()) {
            shown.add(EquipmentSlot.MAINHAND);
            shown.add(EquipmentSlot.OFFHAND);
        }
        List<SlotView> out = new ArrayList<>(shown.size());
        for (EquipmentSlot slot : shown) {
            ItemStack s = p.getEquippedStack(slot);
            if (s.isEmpty() && hideEmpty.get()) continue;
            out.add(view(p, slot, s));
        }
        // The editor always shows something to drag: empty armour slots when everything is hidden.
        if (out.isEmpty() && preview)
            for (EquipmentSlot slot : shown) if (!isHand(slot)) out.add(new SlotView(slot, ItemStack.EMPTY, null, 0, null, false));
        return out;
    }

    private SlotView view(ClientPlayerEntity p, EquipmentSlot slot, ItemStack s) {
        if (s.isEmpty()) return new SlotView(slot, s, null, 0, null, false);
        String label = null;
        int color = 0;
        if (s.isDamageable() && s.getMaxDamage() > 0) {
            float frac = HudDraw.durability(s);
            color = durabilityColor(frac);
            if (elytraTime.get() && s.contains(DataComponentTypes.GLIDER)) label = flightTime(s);
            else label = durabilityLabel(s);
        }
        return new SlotView(slot, s, label, color, isHand(slot) ? countText(p, s) : null, lowDurability(slot, s));
    }

    private @Nullable String durabilityLabel(ItemStack s) {
        int max = s.getMaxDamage(), left = Math.max(0, max - s.getDamage());
        return switch (durabilityText.get()) {
            case "Remaining" -> String.valueOf(left);
            case "Remaining/Max" -> left + "/" + max;
            case "Off" -> null;
            default -> String.valueOf(Math.round(HudDraw.durability(s) * 100));
        };
    }

    private int durabilityColor(float frac) {
        return switch (durabilityColor.get()) {
            case "Static" -> staticColor.get();
            case "Thresholds" -> frac * 100f < warnBelow.getFloat() ? RED : frac < 0.5f ? YELLOW : GREEN;
            default -> ColorUtil.healthColor(frac);
        };
    }

    /**
     * Rough flight time left on a glider, "~m:ss": gliding costs one durability a second and stops one short of
     * breaking; Unbreaking makes each point last (level + 1) times as long on average.
     */
    private static String flightTime(ItemStack s) {
        int usable = Math.max(0, s.getMaxDamage() - 1 - s.getDamage());
        int unbreaking = 0;
        for (var e : s.getEnchantments().getEnchantmentEntries())
            if (e.getKey().matchesKey(Enchantments.UNBREAKING)) unbreaking = e.getIntValue();
        long secs = (long) usable * (unbreaking + 1);
        return String.format(Locale.ROOT, "~%d:%02d", secs / 60, secs % 60);
    }

    /**
     * The corner count of a held item: ammo left for a bow or crossbow (a red 0 when out, nothing in creative), or
     * the total carried of a stackable item. Null keeps vanilla's count.
     */
    private @Nullable String countText(ClientPlayerEntity p, ItemStack s) {
        if (s.getItem() instanceof RangedWeaponItem weapon) {
            if (!ammoCount.get() || p.isInCreativeMode()) return null;
            if (p.getProjectileType(s).isEmpty()) return "§c0";
            return HudDraw.shortCount(InvUtil.count(weapon.getProjectiles()));
        }
        if (heldTotal.get() && s.isStackable()) {
            Item item = s.getItem();
            int total = InvUtil.count(x -> x.isOf(item));
            return total <= 1 ? null : HudDraw.shortCount(total);
        }
        return null;
    }

    private List<Supply> supplyList() {
        if (!supplyPreset.is("Custom")) return PRESETS.getOrDefault(supplyPreset.get(), PRESETS.get("PvP"));
        String text = customSupplies.get();
        if (!text.equals(customKey)) {
            customKey = text;
            customList = parseCustom(text);
        }
        return customList;
    }

    /** Item ids separated by commas or spaces ("minecraft:" may be left out); unknown ids are skipped. */
    private static List<Supply> parseCustom(String text) {
        Set<Item> items = new LinkedHashSet<>();
        for (String part : text.split("[,;\\s]+")) {
            String id = part.strip().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null || !Registries.ITEM.containsId(parsed)) continue;
            Item item = Registries.ITEM.get(parsed);
            if (item != Items.AIR) items.add(item);
        }
        List<Supply> out = new ArrayList<>(items.size());
        for (Item item : items) out.add(Supply.of(item));
        return List.copyOf(out);
    }

    /** How many of each supply you carry, counted once per client tick. */
    private int[] supplyCounts(List<Supply> list) {
        if (list != countsFor || countsTick != EvadeClient.ticks || counts.length != list.size()) {
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) out[i] = InvUtil.count(list.get(i).match);
            counts = out;
            countsFor = list;
            countsTick = EvadeClient.ticks;
        }
        return counts;
    }

    private Cell cell(ItemStack icon, int count, boolean smooth) {
        Text label = HudDraw.styled(HudDraw.shortCount(count), smooth);
        return new Cell(icon, label, count == 0 ? RED : ColorUtil.TEXT, 16 + 2 + mc.textRenderer.getWidth(label) + 3);
    }
}
