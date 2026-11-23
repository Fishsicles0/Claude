package net.evadeclient.module.visuals;

import com.google.gson.JsonObject;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.module.visuals.tooltip.ContainerPreviewComponent;
import net.evadeclient.module.visuals.tooltip.ContainerPreviewData;
import net.evadeclient.module.visuals.tooltip.FoodPreviewComponent;
import net.evadeclient.module.visuals.tooltip.FoodPreviewData;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.DecoratedPotBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ConsumableComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.consume.ApplyEffectsConsumeEffect;
import net.minecraft.item.consume.ClearAllEffectsConsumeEffect;
import net.minecraft.item.consume.ConsumeEffect;
import net.minecraft.item.consume.RemoveEffectsConsumeEffect;
import net.minecraft.item.consume.TeleportRandomlyConsumeEffect;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Richer item tooltips:
 * <ul>
 *   <li>Containers (shulker boxes, chests, barrels, hoppers... carried as items) show their contents as a slot grid
 *   dyed like the box, a totalled text list, or a compact row of item kinds, in place of vanilla's short
 *   "and 3 more..." summary (ContainerComponentMixin). The grid comes from {@link #tooltipData} (ItemTooltipDataMixin),
 *   drawn by {@link ContainerPreviewComponent}.</li>
 *   <li>An ender chest item shows what your own ender chest held the last time you opened it on this server. Only
 *   what you saw yourself; nothing is asked of the server.</li>
 *   <li>Durability in several formats, an Unbreaking estimate of uses left and a low-durability warning.</li>
 *   <li>Food as text or hunger-bar shanks, its saturation, and the effects eating it gives.</li>
 *   <li>Prior anvil work and furnace fuel time.</li>
 * </ul>
 * Anything a server hid from the tooltip (tooltip_display) stays hidden.
 */
public class BetterTooltips extends Module {
    /** Vanilla's durability line in advanced tooltips (F3+H), "Durability: 12 / 250". */
    private static final String VANILLA_DURABILITY_KEY = "item.durability";
    /** The title vanilla (and plugins opening the real ender chest) give the ender chest screen. */
    private static final String ENDER_CHEST_TITLE_KEY = "container.enderchest";
    /** The undyed shulker box's lavender purple. */
    private static final int UNDYED_SHULKER = 0xFF976997;
    /** Prior work at which an anvil refuses almost anything ("Too Expensive!" at 40 levels). */
    private static final int TOO_EXPENSIVE = 39;
    /** A furnace takes 10 seconds per item. */
    private static final int TICKS_PER_SMELT = 200;

    private static BetterTooltips INSTANCE;

    // ---------------------------------------------------------------- containers
    public final ModeSetting shulkerDisplay = add(new ModeSetting("Shulker Display", "Grid", "Grid", "List", "Compact", "Off")
            .describe("How shulker boxes and other filled containers show what's inside: a slot grid, a totalled list, one icon per item kind, or nothing extra."));
    public final BooleanSetting tintByBox = add(new BooleanSetting("Tint By Box Colour", true)
            .describe("Colours a shulker box's grid like the box itself."));
    public final BooleanSetting hideVanillaSummary = add(new BooleanSetting("Hide Vanilla Summary", true)
            .describe("Leaves out the game's short \"and 3 more...\" list, so contents aren't shown twice."));
    public final BooleanSetting holdShiftToExpand = add(new BooleanSetting("Hold Shift To Expand", false)
            .describe("Shows the compact row of item kinds until you hold Shift, then the full grid or list."));
    public final ModeSetting listSort = add(new ModeSetting("List Sort", "Count", "Count", "Name", "Slot Order")
            .describe("Order of the List and Compact views: most first, alphabetical, or as they sit in the container."));
    public final NumberSetting maxListLines = add(new NumberSetting("Max List Lines", 9, 1, 27, 1)
            .describe("Most item kinds the List view shows; the rest are counted as \"+N more\"."));
    public final BooleanSetting enderChestPreview = add(new BooleanSetting("Ender Chest Preview", true)
            .describe("Ender chest items show what your ender chest held the last time you opened it on this server or world (a grid when Shulker Display is Off). Remembered until you close the game."));

    // ---------------------------------------------------------------- durability
    public final BooleanSetting durability = add(new BooleanSetting("Durability", true)
            .describe("Shows uses left on tools and armour."));
    public final ModeSetting durabilityFormat = add(new ModeSetting("Durability Format", "Remaining/Max", "Remaining/Max", "Percent", "Remaining", "Both")
            .describe("120/250, 48%, 120, or 120/250 (48%)."));
    public final BooleanSetting onlyWhenDamaged = add(new BooleanSetting("Only When Damaged", false)
            .describe("Leaves the durability line off items that haven't been used yet."));
    public final BooleanSetting unbreakingEstimate = add(new BooleanSetting("Unbreaking Estimate", true)
            .describe("On Unbreaking items, adds about how many uses are really left (Unbreaking skips some of the wear)."));
    public final NumberSetting lowWarning = add(new NumberSetting("Low Warning %", 10, 0, 50, 5)
            .describe("Below this much durability the line turns bold red with a warning. 0 turns the warning off."));

    // ---------------------------------------------------------------- food
    public final ModeSetting foodDisplay = add(new ModeSetting("Food Display", "Text", "Text", "Icons", "Off")
            .describe("Shows the hunger a food restores as text, as hunger-bar shanks, or not at all."));
    public final BooleanSetting showSaturation = add(new BooleanSetting("Show Saturation", true)
            .describe("Adds the saturation a food gives (how long before hunger starts dropping again)."));
    public final BooleanSetting showEffects = add(new BooleanSetting("Show Effects", true)
            .describe("Lists the effects eating or drinking something gives, with duration and chance (golden apples, pufferfish, honey, milk...)."));

    // ---------------------------------------------------------------- extra info
    public final BooleanSetting anvilCost = add(new BooleanSetting("Anvil Cost", true)
            .describe("Shows the extra levels earlier anvil work adds to the next repair; red once the anvil would call it too expensive."));
    public final BooleanSetting fuelTime = add(new BooleanSetting("Fuel Time", false)
            .describe("Shows how many items a fuel smelts in a furnace, and for how long it burns."));
    public final BooleanSetting detailsNeedShift = add(new BooleanSetting("Details Need Shift", false)
            .describe("Hides the extra lines (durability, food, anvil, fuel) until you hold Shift. Container previews always show."));

    /** Your ender chest as last seen, per server address or singleplayer world. Memory only, never saved. */
    private final Map<String, EnderSnapshot> enderChests = new HashMap<>();

    /** One-entry cache for the hovered container's preview (built every frame otherwise). */
    private ContainerComponent cachedComponent;
    private Item cachedItem;
    private String cachedKey;
    private ContainerPreviewData cachedData;

    private enum View { GRID, LIST, COMPACT, OFF }

    /** Slots a container item has and how its grid is laid out; {@code tint} is ARGB or 0. */
    private record Shape(int capacity, int columns, int tint) {}

    /** One distinct item (item + components) inside a container, its total count and the first slot it's in. */
    private record Kind(ItemStack sample, int total, int firstSlot) {}

    private record EnderSnapshot(List<ItemStack> stacks, long seenAt) {
        boolean isEmpty() {
            for (ItemStack s : stacks) if (!s.isEmpty()) return false;
            return true;
        }
    }

    public BetterTooltips() {
        super("BetterTooltips", "Container previews, durability, food and more in item tooltips.", Category.VISUALS);
        setEnabled(true);
        INSTANCE = this;
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            BetterTooltips bt = active();
            if (bt == null) return;
            try {
                bt.lines(stack, context, type, lines);
            } catch (RuntimeException e) {
                ModuleManager.crashed(bt, e, "Tooltip");
            }
        });
        // Always mapped (even while off): a preview created a moment before turning off must never reach vanilla,
        // which throws on tooltip data it doesn't know.
        TooltipComponentCallback.EVENT.register(BetterTooltips::component);
    }

    private static TooltipComponent component(TooltipData data) {
        if (data instanceof ContainerPreviewData d) return new ContainerPreviewComponent(d);
        if (data instanceof FoodPreviewData f) return new FoodPreviewComponent(f);
        return null;
    }

    /** Whether vanilla's own short container list should be left out (ContainerComponentMixin). */
    public static boolean replacesContainerList() {
        BetterTooltips bt = active();
        return bt != null && !bt.shulkerDisplay.is("Off") && bt.hideVanillaSummary.get();
    }

    private static BetterTooltips active() {
        BetterTooltips bt = INSTANCE;
        return bt != null && bt.isEnabled() ? bt : null;
    }

    @Override
    public void onConfigLoaded(JsonObject settings) {
        // "Shulker Contents" and "Food Values" were on/off switches before they became modes.
        migrate(settings, "Shulker Contents", shulkerDisplay);
        migrate(settings, "Food Values", foodDisplay);
    }

    private static void migrate(JsonObject settings, String oldName, ModeSetting mode) {
        if (settings.has(mode.getName()) || !settings.has(oldName)) return;
        try {
            mode.set(settings.get(oldName).getAsBoolean() ? mode.getDefault() : "Off");
        } catch (RuntimeException ignored) {
            // A hand-edited value that isn't a boolean: keep the default.
        }
    }

    // ================================================================ ender chest memory

    /** Remembers your ender chest while its screen is open (contents can change while you look). */
    @Override
    public void onTick() {
        if (!enderChestPreview.get() || !(mc.currentScreen instanceof GenericContainerScreen screen)) return;
        if (!(screen.getTitle().getContent() instanceof TranslatableTextContent t) || !ENDER_CHEST_TITLE_KEY.equals(t.getKey())) return;
        String key = worldKey();
        if (key == null) return;
        GenericContainerScreenHandler handler = screen.getScreenHandler();
        int size = Math.min(handler.getRows() * 9, handler.getInventory().size());
        List<ItemStack> copy = new ArrayList<>(size);
        for (int i = 0; i < size; i++) copy.add(handler.getSlot(i).getStack().copy());
        enderChests.put(key, new EnderSnapshot(List.copyOf(copy), Util.getMeasuringTimeMs()));
    }

    /** The server you're on, or the singleplayer world; null when neither is known. */
    private static String worldKey() {
        ServerInfo entry = mc.getCurrentServerEntry();
        if (entry != null && entry.address != null) return "server:" + entry.address.toLowerCase(Locale.ROOT);
        IntegratedServer server = mc.getServer();
        if (server != null) return "world:" + server.getSaveProperties().getLevelName();
        return null;
    }

    private EnderSnapshot enderSnapshot() {
        String key = worldKey();
        return key == null ? null : enderChests.get(key);
    }

    private static String ago(long seenAt) {
        long s = Math.max(0, (Util.getMeasuringTimeMs() - seenAt) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    // ================================================================ graphical tooltip data

    /**
     * The graphical part of a tooltip (ItemTooltipDataMixin, HEAD of Item.getTooltipData): a container's grid or
     * compact row, else the remembered ender chest, else food shanks. Null leaves the item's own (usually none).
     */
    public static TooltipData tooltipData(ItemStack stack) {
        BetterTooltips bt = active();
        if (bt == null || stack.isEmpty() || !mc.isOnThread()) return null;
        try {
            return bt.data(stack);
        } catch (RuntimeException e) {
            ModuleManager.crashed(bt, e, "Tooltip");
            return null;
        }
    }

    private TooltipData data(ItemStack stack) {
        TooltipDisplayComponent display = stack.getOrDefault(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplayComponent.DEFAULT);
        if (display.hideTooltip()) return null;
        boolean shift = mc.isShiftPressed();

        ContainerComponent c = stack.get(DataComponentTypes.CONTAINER);
        if (c != null && display.shouldDisplay(DataComponentTypes.CONTAINER)) {
            View v = view(false, shift);
            if (v == View.GRID || v == View.COMPACT) {
                ContainerPreviewData d = containerData(stack, c, v);
                if (d != null) return d;
            }
        }

        if (enderChestPreview.get() && stack.isOf(Items.ENDER_CHEST)) {
            View v = view(true, shift);
            EnderSnapshot snap = enderSnapshot();
            if (snap != null && !snap.isEmpty() && (v == View.GRID || v == View.COMPACT)) {
                Text header = Text.literal("Last seen " + ago(snap.seenAt()));
                return v == View.GRID
                        ? new ContainerPreviewData(snap.stacks(), null, 9, 0, header, true)
                        : compactData(snap.stacks(), header, 0);
            }
        }

        if (foodIcons(stack, display) && (!detailsNeedShift.get() || shift)) {
            FoodComponent f = stack.get(DataComponentTypes.FOOD);
            return new FoodPreviewData(f.nutrition(), f.saturation(), showSaturation.get());
        }
        return null;
    }

    /** Which container view to show right now; ender chests fall back to the grid when Shulker Display is Off. */
    private View view(boolean enderChest, boolean shift) {
        View mode = switch (shulkerDisplay.get()) {
            case "List" -> View.LIST;
            case "Compact" -> View.COMPACT;
            case "Off" -> enderChest ? View.GRID : View.OFF;
            default -> View.GRID;
        };
        if (mode == View.OFF || !holdShiftToExpand.get()) return mode;
        if (!shift) return View.COMPACT;
        return mode == View.COMPACT ? View.GRID : mode;
    }

    /** Whether the preview is the compact row only because Shift isn't held. */
    private boolean collapsed(boolean shift) {
        return holdShiftToExpand.get() && !shift;
    }

    private ContainerPreviewData containerData(ItemStack stack, ContainerComponent c, View v) {
        String key = v + "|" + tintByBox.get() + "|" + listSort.get();
        if (c == cachedComponent && stack.getItem() == cachedItem && key.equals(cachedKey)) return cachedData;

        Shape shape = shape(stack, c);
        List<ItemStack> slots = slots(c, shape);
        ContainerPreviewData d = null;
        if (slots.stream().anyMatch(s -> !s.isEmpty())) {
            d = v == View.GRID
                    ? new ContainerPreviewData(slots, null, shape.columns(), shape.tint(), null, true)
                    : compactData(slots, null, shape.tint());
        }
        cachedComponent = c;
        cachedItem = stack.getItem();
        cachedKey = key;
        cachedData = d;
        return d;
    }

    /** One icon per item kind with its total, nine to a row, in List Sort order. */
    private ContainerPreviewData compactData(List<ItemStack> slots, Text header, int tint) {
        List<Kind> kinds = kinds(slots);
        List<ItemStack> icons = new ArrayList<>(kinds.size());
        List<String> counts = new ArrayList<>(kinds.size());
        for (Kind k : kinds) {
            icons.add(k.sample().copyWithCount(1));
            counts.add(k.total() == 1 ? "" : shortCount(k.total()));
        }
        return new ContainerPreviewData(icons, counts, 9, tint, header, false);
    }

    /** 64, 999, 1.7k, 12k: fits a slot corner. */
    private static String shortCount(int n) {
        if (n < 1000) return Integer.toString(n);
        if (n < 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1000.0).replace(".0k", "k");
        if (n < 1_000_000) return (n / 1000) + "k";
        return (n / 1_000_000) + "M";
    }

    /**
     * How many slots the item's block has and how they're laid out, so a half-empty box still shows all 27 slots.
     * Unknown containers get rows of nine. Never fewer slots than the item actually holds (commands can overfill).
     */
    private Shape shape(ItemStack stack, ContainerComponent c) {
        int size = (int) c.stream().count();
        Block block = stack.getItem() instanceof BlockItem bi ? bi.getBlock() : null;
        int capacity, columns, tint = 0;
        if (block instanceof ShulkerBoxBlock box) {
            capacity = 27;
            columns = 9;
            if (tintByBox.get()) {
                DyeColor dye = box.getColor();
                tint = dye == null ? UNDYED_SHULKER : dye.getEntityColor();
            }
        } else if (block instanceof ChestBlock || block instanceof BarrelBlock) {
            capacity = 27;
            columns = 9;
        } else if (block instanceof HopperBlock || block instanceof BrewingStandBlock) {
            capacity = 5;
            columns = 5;
        } else if (block instanceof DispenserBlock || block instanceof CrafterBlock) { // droppers are dispensers
            capacity = 9;
            columns = 3;
        } else if (block instanceof AbstractFurnaceBlock) {
            capacity = 3;
            columns = 3;
        } else if (block instanceof DecoratedPotBlock) {
            capacity = 1;
            columns = 1;
        } else {
            columns = 9;
            capacity = Math.max(9, ceilTo(size, 9));
        }
        if (size > capacity) capacity = ceilTo(size, columns);
        return new Shape(capacity, columns, tint);
    }

    private static int ceilTo(int n, int step) {
        return (n + step - 1) / step * step;
    }

    private static List<ItemStack> slots(ContainerComponent c, Shape shape) {
        DefaultedList<ItemStack> slots = DefaultedList.ofSize(shape.capacity(), ItemStack.EMPTY);
        c.copyTo(slots);
        return slots;
    }

    private static boolean hasContents(ContainerComponent c) {
        return c.iterateNonEmpty().iterator().hasNext();
    }

    /** Every non-empty stack totalled per identical item (same item and components), in List Sort order. */
    private List<Kind> kinds(List<ItemStack> slots) {
        List<ItemStack> samples = new ArrayList<>();
        List<int[]> totals = new ArrayList<>();
        List<Integer> first = new ArrayList<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack s = slots.get(slot);
            if (s.isEmpty()) continue;
            int i = 0;
            while (i < samples.size() && !ItemStack.areItemsAndComponentsEqual(samples.get(i), s)) i++;
            if (i == samples.size()) {
                samples.add(s);
                totals.add(new int[]{0});
                first.add(slot);
            }
            totals.get(i)[0] += s.getCount();
        }
        List<Kind> kinds = new ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i++) kinds.add(new Kind(samples.get(i), totals.get(i)[0], first.get(i)));
        // Stable sorts: ties keep slot order.
        switch (listSort.get()) {
            case "Name" -> kinds.sort(Comparator.comparing(k -> k.sample().getName().getString().toLowerCase(Locale.ROOT)));
            case "Slot Order" -> { /* already in slot order */ }
            default -> kinds.sort((a, b) -> Integer.compare(b.total(), a.total()));
        }
        return kinds;
    }

    // ================================================================ text lines

    private void lines(ItemStack stack, Item.TooltipContext context, TooltipType type, List<Text> lines) {
        if (lines.isEmpty()) return;
        TooltipDisplayComponent display = stack.getOrDefault(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplayComponent.DEFAULT);
        boolean shift = mc.isShiftPressed();

        // Container contents in the List view sit right under the name, where the grid would be.
        List<Text> preview = new ArrayList<>();
        boolean previewCollapsed = false;
        ContainerComponent c = stack.get(DataComponentTypes.CONTAINER);
        if (c != null && display.shouldDisplay(DataComponentTypes.CONTAINER) && hasContents(c)) {
            View v = view(false, shift);
            if (v == View.LIST) {
                Shape shape = shape(stack, c);
                listLines("Contents", slots(c, shape), shape.capacity(), preview);
            }
            previewCollapsed = v != View.OFF && collapsed(shift);
        } else if (enderChestPreview.get() && stack.isOf(Items.ENDER_CHEST)) {
            EnderSnapshot snap = enderSnapshot();
            if (snap != null && snap.isEmpty()) {
                preview.add(Text.literal("Empty when last seen " + ago(snap.seenAt())).formatted(Formatting.GRAY));
            } else if (snap != null) {
                if (view(true, shift) == View.LIST) listLines("Last seen " + ago(snap.seenAt()), snap.stacks(), snap.stacks().size(), preview);
                previewCollapsed = collapsed(shift);
            }
        }

        List<Text> details = new ArrayList<>();
        durabilityLine(stack, display, type, details);
        foodLine(stack, display, details);
        effectLines(stack, display, context, details);
        anvilLine(stack, display, details);
        fuelLine(stack, details);
        boolean detailsHidden = false;
        if (detailsNeedShift.get() && !shift) {
            detailsHidden = !details.isEmpty() || foodIcons(stack, display);
            details.clear();
        }

        if (previewCollapsed && detailsHidden) details.add(hint("Hold Shift for more"));
        else if (detailsHidden) details.add(hint("Hold Shift for details"));
        else if (previewCollapsed) preview.add(hint("Hold Shift to expand"));

        // Details go above vanilla's F3+H block (so the item id stays last); the preview right under the name.
        if (!details.isEmpty()) {
            int at = advancedSectionStart(stack, lines);
            if (at < 0) lines.addAll(details); else lines.addAll(at, details);
        }
        if (!preview.isEmpty()) lines.addAll(Math.min(1, lines.size()), preview);
    }

    private static Text hint(String text) {
        return Text.literal(text).formatted(Formatting.DARK_GRAY);
    }

    /** "Contents (5/27 slots):" then "  96x Stone" per item kind, up to Max List Lines, then "  +3 more". */
    private void listLines(String title, List<ItemStack> slots, int capacity, List<Text> out) {
        int used = 0;
        for (ItemStack s : slots) if (!s.isEmpty()) used++;
        out.add(Text.literal(title + " (" + used + "/" + capacity + " slots):").formatted(Formatting.GRAY));
        List<Kind> kinds = kinds(slots);
        int shown = Math.min(kinds.size(), maxListLines.getInt());
        for (int k = 0; k < shown; k++) {
            Kind kind = kinds.get(k);
            out.add(Text.literal("  " + kind.total() + "x ").formatted(Formatting.GRAY)
                    .append(kind.sample().getName().copy().formatted(Formatting.WHITE)));
        }
        if (kinds.size() > shown)
            out.add(Text.literal("  +" + (kinds.size() - shown) + " more").formatted(Formatting.GRAY, Formatting.ITALIC));
    }

    private void durabilityLine(ItemStack stack, TooltipDisplayComponent display, TooltipType type, List<Text> out) {
        if (!durability.get() || !stack.isDamageable() || !display.shouldDisplay(DataComponentTypes.DAMAGE)) return;
        // Advanced tooltips (F3+H) already show vanilla's own line for a damaged item.
        if (type.isAdvanced() && stack.isDamaged()) return;
        if (onlyWhenDamaged.get() && !stack.isDamaged()) return;
        int max = stack.getMaxDamage(), left = Math.max(0, max - stack.getDamage());
        if (max <= 0) return;
        double pct = left * 100.0 / max;
        boolean low = lowWarning.get() > 0 && pct < lowWarning.get();
        Formatting col = low || pct <= 20 ? Formatting.RED : pct <= 50 ? Formatting.YELLOW : Formatting.GREEN;
        int shownPct = left == max ? 100 : Math.max(left > 0 ? 1 : 0, Math.min(99, (int) Math.round(pct)));
        String value = switch (durabilityFormat.get()) {
            case "Percent" -> shownPct + "%";
            case "Remaining" -> Integer.toString(left);
            case "Both" -> left + "/" + max + " (" + shownPct + "%)";
            default -> left + "/" + max;
        };
        MutableText v = Text.literal(value).formatted(col);
        if (low) v.formatted(Formatting.BOLD);
        MutableText line = Text.literal("Durability: ").formatted(Formatting.GRAY).append(v);
        long estimate = unbreakingEstimate.get() ? estimatedUses(stack, left) : -1;
        if (estimate >= 0) line.append(Text.literal(" (~" + String.format(Locale.ROOT, "%,d", estimate) + " uses)").formatted(Formatting.DARK_GRAY));
        if (low) line.append(Text.literal("  Low durability").formatted(Formatting.RED));
        out.add(line);
    }

    /**
     * Uses left counting Unbreaking, which skips wear by chance: tools and weapons take damage 1/(level+1) of the
     * time, armour 60% + 40%/(level+1) (the game's own numbers). Rounded so it doesn't read as exact; -1 without
     * Unbreaking.
     */
    private static long estimatedUses(ItemStack stack, int left) {
        if (mc.world == null || left <= 0) return -1;
        int level = 0;
        for (var e : stack.getEnchantments().getEnchantmentEntries()) {
            RegistryEntry<Enchantment> ench = e.getKey();
            if (ench.matchesKey(Enchantments.UNBREAKING)) level = Math.max(level, e.getIntValue());
        }
        if (level <= 0) return -1;
        double wearChance = stack.isIn(ItemTags.ARMOR_ENCHANTABLE) ? 0.6 + 0.4 / (level + 1) : 1.0 / (level + 1);
        long uses = Math.round(left / wearChance);
        if (uses >= 1000) return Math.round(uses / 100.0) * 100;
        if (uses >= 100) return Math.round(uses / 10.0) * 10;
        return uses;
    }

    /** Whether this food gets the hunger-bar shanks (Food Display: Icons). */
    private boolean foodIcons(ItemStack stack, TooltipDisplayComponent display) {
        if (!foodDisplay.is("Icons") || !display.shouldDisplay(DataComponentTypes.FOOD)) return false;
        FoodComponent f = stack.get(DataComponentTypes.FOOD);
        return f != null && (f.nutrition() > 0 || (showSaturation.get() && f.saturation() > 0));
    }

    private void foodLine(ItemStack stack, TooltipDisplayComponent display, List<Text> out) {
        if (!foodDisplay.is("Text") || !display.shouldDisplay(DataComponentTypes.FOOD)) return;
        FoodComponent f = stack.get(DataComponentTypes.FOOD);
        if (f == null || (f.nutrition() <= 0 && f.saturation() <= 0)) return;
        String s = "Food: " + f.nutrition();
        if (showSaturation.get()) s += "  Saturation: " + String.format(Locale.ROOT, "%.1f", f.saturation());
        out.add(Text.literal(s).formatted(Formatting.GRAY));
    }

    /**
     * What eating or drinking the item does, in the same style as potion effects. Suspicious stews are left alone:
     * the game keeps their effect secret outside creative mode, where it already lists it.
     */
    private void effectLines(ItemStack stack, TooltipDisplayComponent display, Item.TooltipContext context, List<Text> out) {
        if (!showEffects.get()) return;
        ConsumableComponent consumable = stack.get(DataComponentTypes.CONSUMABLE);
        if (consumable == null || !display.shouldDisplay(DataComponentTypes.CONSUMABLE)) return;
        float tickRate = context.getUpdateTickRate();
        for (ConsumeEffect effect : consumable.onConsumeEffects()) {
            switch (effect) {
                case ApplyEffectsConsumeEffect apply -> {
                    for (StatusEffectInstance e : apply.effects()) {
                        MutableText t = PotionContentsComponent.getEffectText(e.getEffectType(), e.getAmplifier());
                        if (!e.isDurationBelow(20))
                            t = Text.translatable("potion.withDuration", t, StatusEffectUtil.getDurationText(e, 1f, tickRate));
                        if (apply.probability() < 1f)
                            t.append(Text.literal(" (" + Math.round(apply.probability() * 100) + "%)"));
                        out.add(t.formatted(e.getEffectType().value().getCategory().getFormatting()));
                    }
                }
                case RemoveEffectsConsumeEffect remove -> {
                    MutableText t = Text.literal("Removes ");
                    boolean firstName = true;
                    for (RegistryEntry<StatusEffect> e : remove.effects()) {
                        if (!firstName) t.append(", ");
                        t.append(Text.translatable(e.value().getTranslationKey()));
                        firstName = false;
                    }
                    if (!firstName) out.add(t.formatted(Formatting.BLUE));
                }
                case ClearAllEffectsConsumeEffect ignored -> out.add(Text.literal("Clears all effects").formatted(Formatting.BLUE));
                case TeleportRandomlyConsumeEffect ignored -> out.add(Text.literal("Teleports you nearby").formatted(Formatting.BLUE));
                default -> { }
            }
        }
    }

    private void anvilLine(ItemStack stack, TooltipDisplayComponent display, List<Text> out) {
        if (!anvilCost.get() || !display.shouldDisplay(DataComponentTypes.REPAIR_COST)) return;
        int cost = stack.getOrDefault(DataComponentTypes.REPAIR_COST, 0);
        if (cost <= 0) return;
        out.add(Text.literal("Prior work: ").formatted(Formatting.GRAY)
                .append(Text.literal("+" + cost + (cost == 1 ? " level" : " levels"))
                        .formatted(cost >= TOO_EXPENSIVE ? Formatting.RED : Formatting.GRAY)));
    }

    private void fuelLine(ItemStack stack, List<Text> out) {
        if (!fuelTime.get() || mc.world == null) return;
        int ticks = mc.world.getFuelRegistry().getFuelTicks(stack);
        if (ticks <= 0) return;
        String items = oneDecimal(ticks / (float) TICKS_PER_SMELT);
        out.add(Text.literal("Smelts " + items + (items.equals("1") ? " item" : " items") + " (" + burnTime(ticks / 20) + ")")
                .formatted(Formatting.GRAY));
    }

    /** 5s, 80s, 16m 40s. */
    private static String burnTime(int seconds) {
        if (seconds < 60) return seconds + "s";
        int m = seconds / 60, s = seconds % 60;
        return s == 0 ? m + "m" : m + "m " + s + "s";
    }

    /** One decimal at most: "0.5", "8", "1.5". */
    private static String oneDecimal(float v) {
        float r = Math.round(v * 10f) / 10f;
        return r == (int) r ? Integer.toString((int) r) : String.format(Locale.ROOT, "%.1f", r);
    }

    /**
     * Where vanilla's advanced (F3+H) section starts: its durability line, else the dark-gray item id. Our lines go
     * above it so the id stays last, as in vanilla. -1 when the tooltip isn't advanced.
     */
    private static int advancedSectionStart(ItemStack stack, List<Text> lines) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        for (int i = lines.size() - 1; i > 0; i--) {
            if (!id.equals(lines.get(i).getString())) continue;
            return isVanillaDurability(lines.get(i - 1)) ? i - 1 : i;
        }
        return -1;
    }

    private static boolean isVanillaDurability(Text line) {
        return line.getContent() instanceof TranslatableTextContent t && VANILLA_DURABILITY_KEY.equals(t.getKey());
    }
}
