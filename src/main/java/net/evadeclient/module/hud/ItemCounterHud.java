package net.evadeclient.module.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Counts up to three kinds of item across your inventory, one row each: whatever you're holding, a pinned item or
 * a category (arrows, pearls, blocks...). Rows can show the item's icon, warn when you're running low and show
 * "+12" / "-3" for a moment when the count changes.
 */
public class ItemCounterHud extends TextHudModule {
    private static final String[] KINDS = {"Held Item", "Pinned", "Blocks", "Arrows", "Food", "Gapples", "Pearls",
            "Totems", "Fireworks", "XP Bottles", "Wind Charges", "Potions", "Torches"};
    private static final String OFF = "Off";
    private static final int GAIN_COLOR = 0xFF3BE37A, LOSS_COLOR = 0xFFFF4D5E;
    /** Height of one row in the icon layouts. */
    private static final int ROW = 18;

    private static final Map<String, Predicate<ItemStack>> CATEGORY = Map.ofEntries(
            Map.entry("Blocks", ItemCounterHud::isBuildingBlock),
            Map.entry("Arrows", s -> s.isOf(Items.ARROW) || s.isOf(Items.SPECTRAL_ARROW) || s.isOf(Items.TIPPED_ARROW)),
            Map.entry("Food", s -> s.contains(DataComponentTypes.FOOD)),
            Map.entry("Gapples", s -> s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE)),
            Map.entry("Pearls", s -> s.isOf(Items.ENDER_PEARL)),
            Map.entry("Totems", s -> s.isOf(Items.TOTEM_OF_UNDYING)),
            Map.entry("Fireworks", s -> s.isOf(Items.FIREWORK_ROCKET)),
            Map.entry("XP Bottles", s -> s.isOf(Items.EXPERIENCE_BOTTLE)),
            Map.entry("Wind Charges", s -> s.isOf(Items.WIND_CHARGE)),
            Map.entry("Potions", s -> s.isOf(Items.POTION) || s.isOf(Items.SPLASH_POTION) || s.isOf(Items.LINGERING_POTION)),
            Map.entry("Torches", s -> s.isOf(Items.TORCH) || s.isOf(Items.SOUL_TORCH) || s.isOf(Items.COPPER_TORCH)));

    /** Sample rows for the HUD editor when nothing is counted yet. */
    private static final Item[] SAMPLE_ITEMS = {Items.ARROW, Items.ENDER_PEARL, Items.TOTEM_OF_UNDYING};
    private static final String[] SAMPLE_LABELS = {"Arrows", "Pearls", "Totems"};
    private static final int[] SAMPLE_COUNTS = {64, 16, 3};

    private final ModeSetting track = add(new ModeSetting("Track", "Held Item", KINDS)
            .describe("What the first row counts: the item in your hand, your pinned item, or a category."));
    private final ModeSetting track2 = add(new ModeSetting("Track 2", OFF, withOff(KINDS))
            .describe("A second row, counting something else."));
    private final ModeSetting track3 = add(new ModeSetting("Track 3", OFF, withOff(KINDS))
            .describe("A third row, counting something else."));
    private final ModeSetting display = add(new ModeSetting("Display", "Icon + Count", "Text", "Icon + Count", "Icon + Name + Count")
            .describe("Text only (\"64x Arrows\"), the item's icon with its count, or the icon with count and name."));
    private final ModeSetting layout = add(new ModeSetting("Layout", "Vertical", "Vertical", "Horizontal")
            .describe("Rows stacked, or side by side (with an icon display)."));
    private final BooleanSetting hideWhenZero = add(new BooleanSetting("Hide When Zero", true)
            .describe("Hide the counter while you have none of anything it counts."));
    private final BooleanSetting hideEmptyRows = add(new BooleanSetting("Hide Empty Rows", false)
            .describe("Leave out rows you have none of, so only what you carry shows."));
    private final ModeSetting countIn = add(new ModeSetting("Count In", "Inventory + Equipment", "Hotbar", "Inventory",
            "Inventory + Equipment", "Including Containers")
            .describe("Where to count: the hotbar, the whole inventory, plus what you wear, or also inside shulker "
                    + "boxes and bundles you carry. The offhand always counts."));
    private final ModeSetting match = add(new ModeSetting("Match", "Item Type", "Item Type", "Exact")
            .describe("For Held Item: count every item of the same type, or only exact copies (same potion, same "
                    + "enchantments, same name)."));
    private final ModeSetting countFormat = add(new ModeSetting("Count Format", "Items", "Items", "Stacks", "Stacks + Items", "Items (Stacks)")
            .describe("200 items, 3.1 st (stacks), 3s 8 (stacks and the rest), or 200 (3.1 st). Uses the item's own stack size."));
    private final NumberSetting lowAt = add(new NumberSetting("Low Warning At", 16, 0, 256, 1)
            .describe("A row turns Warning Color at this many items or fewer (0 turns it off). Scaled for items that "
                    + "stack to 16 or 1: 16 warns at 4 pearls, or at your last totem."));
    private final ColorSetting warnColor = add(new ColorSetting("Warning Color", 0xFFFF4D5E)
            .describe("Colour of a row that is running low."));
    private final BooleanSetting flashLow = add(new BooleanSetting("Flash When Low", true)
            .describe("Blink a low row between its normal colour and Warning Color."));
    private final BooleanSetting lowAlert = add(new BooleanSetting("Low Alert", false)
            .describe("A notification when a row drops to the warning amount (\"16 Arrows left\")."));
    private final BooleanSetting showChange = add(new BooleanSetting("Show Change", true)
            .describe("Show +12 or -3 next to a count for a moment after it changes."));
    private final NumberSetting changeDuration = add(new NumberSetting("Change Duration", 2.0, 0.5, 5.0, 0.5)
            .describe("Seconds the change stays (changes within it add up)."));
    private final ModeSetting heldMemory = add(new ModeSetting("Held Item Memory", "Last Stackable", OFF, "Last Item", "Last Stackable")
            .describe("What Held Item counts while your hand is empty: nothing, the last item you held, or the last "
                    + "stackable item (so blocks stay counted while you hold a sword)."));
    private final KeySetting pinKey = add(new KeySetting("Pin Held Item", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Press to pin the item in your hand to the Pinned row."));
    private final StringSetting pinned = add(new StringSetting("Pinned Item", "minecraft:arrow", "Pin key / .counter pin")
            .maxLength(96))
            .describe("The item the Pinned row counts, as an item id. Set it with the Pin Held Item key or .counter pin.");

    /** One counted row, rebuilt once a tick. {@code real} is false for a row with nothing to count (empty hand). */
    private record Row(int tracker, String label, ItemStack icon, int count, int maxStack, boolean real) {}

    /** What one tracker remembers between ticks: the last count, the change window and the low state. */
    private static final class Tracker {
        /** What was counted (kind, scope, match, reference); a different one starts over without a change shown. */
        String sig;
        int prev = -1;
        int delta;
        long deltaAt;
        boolean wasLow;
        /** Icon copy, replaced only when the item shown changes. */
        ItemStack icon = ItemStack.EMPTY;

        void reset() {
            sig = null;
            prev = -1;
            delta = 0;
            wasLow = false;
        }
    }

    private final Tracker[] trackers = {new Tracker(), new Tracker(), new Tracker()};
    private List<Row> rows = List.of();
    /** The rows behind the lines being drawn, so {@link #lineColor} can find the row of a line. */
    private List<Row> drawn = List.of();
    /** The last (stackable) item held, for Held Item Memory. */
    private ItemStack lastHeld = ItemStack.EMPTY;
    private Object lastWorld;
    private boolean pinWasDown;
    /** First matching stack of the last {@link #countMatching} call (for the icon). */
    private ItemStack firstMatch = ItemStack.EMPTY;
    private String pinnedKey;
    private Item pinnedItem;

    public ItemCounterHud() {
        super("Item Counter", "Counts items in your inventory.", 4, 232);
    }

    private static String[] withOff(String[] kinds) {
        String[] out = new String[kinds.length + 1];
        out[0] = OFF;
        System.arraycopy(kinds, 0, out, 1, kinds.length);
        return out;
    }

    /**
     * Placeable blocks: items named as blocks (so not seeds, redstone dust or string), never a shulker box or
     * anything else that holds items.
     */
    private static boolean isBuildingBlock(ItemStack s) {
        return s.getItem() instanceof BlockItem b && b.getTranslationKey().startsWith("block.")
                && !s.contains(DataComponentTypes.CONTAINER) && !s.contains(DataComponentTypes.BUNDLE_CONTENTS);
    }

    /** The item shown for a category when none is in the inventory. */
    private static Item fallbackIcon(String kind) {
        return switch (kind) {
            case "Blocks" -> Items.GRASS_BLOCK;
            case "Arrows" -> Items.ARROW;
            case "Food" -> Items.COOKED_BEEF;
            case "Gapples" -> Items.GOLDEN_APPLE;
            case "Pearls" -> Items.ENDER_PEARL;
            case "Totems" -> Items.TOTEM_OF_UNDYING;
            case "Fireworks" -> Items.FIREWORK_ROCKET;
            case "XP Bottles" -> Items.EXPERIENCE_BOTTLE;
            case "Wind Charges" -> Items.WIND_CHARGE;
            case "Potions" -> Items.SPLASH_POTION;
            case "Torches" -> Items.TORCH;
            default -> Items.AIR;
        };
    }

    /** What tracker {@code i} counts, or null when it is off. */
    private String kind(int i) {
        String k = switch (i) {
            case 0 -> track.get();
            case 1 -> track2.get();
            default -> track3.get();
        };
        return OFF.equals(k) ? null : k;
    }

    // ------------------------------------------------------------------ pinned item

    /** An item id ("minecraft:ender_pearl", or "ender_pearl"), or null when it isn't a real item. */
    private static Item parseItem(String s) {
        if (s == null) return null;
        s = s.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        Identifier id = Identifier.tryParse(s);
        if (id == null || !Registries.ITEM.containsId(id)) return null;
        Item item = Registries.ITEM.get(id);
        return item == Items.AIR ? null : item;
    }

    /** The item in Pinned Item, or null for none (re-parsed only when the setting changes). */
    private Item pinnedItem() {
        String v = pinned.get();
        if (!v.equals(pinnedKey)) {
            pinnedKey = v;
            pinnedItem = parseItem(v);
        }
        return pinnedItem;
    }

    private boolean tracksPinned() {
        for (int i = 0; i < 3; i++) if ("Pinned".equals(kind(i))) return true;
        return false;
    }

    /** Pins {@code item}; returns the confirmation ("Pinned Ender Pearl"). */
    private String pin(Item item) {
        pinned.set(Registries.ITEM.getId(item).toString());
        if (EvadeClient.config != null) EvadeClient.config.save();
        String msg = "Pinned " + item.getName().getString();
        return tracksPinned() ? msg : msg + " (set a Track to Pinned)";
    }

    private void pollPinKey() {
        boolean down = pinKey.isDown();
        if (down && !pinWasDown && mc.player != null) {
            ItemStack hand = mc.player.getMainHandStack();
            if (hand.isEmpty()) Notifications.push(getName(), "Hold an item to pin it", false);
            else Notifications.push(getName(), pin(hand.getItem()), true);
        }
        pinWasDown = down;
    }

    @Override public List<String> commandAliases() { return List.of("counter"); }

    @Override
    public List<String> commandHelp() {
        return List.of("§d.counter pin§7 [item id] - pin the held item (or any item) to Item Counter's Pinned row",
                "§d.counter unpin§7 - clear Item Counter's pinned item");
    }

    @Override
    public void command(String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "pin" -> {
                Item item;
                if (a.length > 2) {
                    item = parseItem(a[2]);
                    if (item == null) throw new CommandManager.CommandError("Unknown item \"" + a[2] + "\". Use an id like minecraft:ender_pearl.");
                } else {
                    if (mc.player == null || mc.player.getMainHandStack().isEmpty())
                        throw new CommandManager.CommandError("Hold an item to pin it, or name one: .counter pin minecraft:ender_pearl");
                    item = mc.player.getMainHandStack().getItem();
                }
                CommandManager.print("§7" + pin(item) + ".");
            }
            case "unpin" -> {
                pinned.set("");
                if (EvadeClient.config != null) EvadeClient.config.save();
                CommandManager.print("§7Item Counter's pinned item cleared.");
            }
            default -> throw new CommandManager.CommandError("Usage: .counter pin [item id] | .counter unpin");
        }
    }

    // ------------------------------------------------------------------ counting

    /** The item Held Item counts this tick (Held Item Memory applied); empty for none. */
    private ItemStack heldReference(PlayerEntity p) {
        ItemStack hand = p.getMainHandStack();
        return switch (heldMemory.get()) {
            case OFF -> hand;
            case "Last Item" -> hand.isEmpty() ? lastHeld : hand;
            default -> hand.isEmpty() || hand.getMaxCount() <= 1 ? (lastHeld.isEmpty() ? hand : lastHeld) : hand;
        };
    }

    private void rememberHeld(PlayerEntity p) {
        boolean stackableOnly = heldMemory.is("Last Stackable");
        // A sword remembered under Last Item is forgotten when switching to Last Stackable.
        if (stackableOnly && !lastHeld.isEmpty() && lastHeld.getMaxCount() <= 1) lastHeld = ItemStack.EMPTY;
        ItemStack hand = p.getMainHandStack();
        if (hand.isEmpty() || (stackableOnly && hand.getMaxCount() <= 1)) return;
        if (!ItemStack.areItemsAndComponentsEqual(lastHeld, hand)) lastHeld = hand.copyWithCount(1);
    }

    /** Matching items in the Count In scope; the offhand always counts, once. */
    private int countMatching(PlayerEntity p, Predicate<ItemStack> pred) {
        firstMatch = ItemStack.EMPTY;
        String scope = countIn.get();
        boolean deep = scope.equals("Including Containers");
        PlayerInventory inv = p.getInventory();
        int end = scope.equals("Hotbar") ? PlayerInventory.HOTBAR_SIZE : PlayerInventory.MAIN_SIZE;
        int n = 0;
        for (int i = 0; i < end; i++) n += countStack(inv.getStack(i), pred, deep);
        n += countStack(inv.getStack(PlayerInventory.OFF_HAND_SLOT), pred, deep);
        if (deep || scope.equals("Inventory + Equipment"))
            for (EquipmentSlot slot : HudDraw.ARMOR_TOP_DOWN) n += countStack(p.getEquippedStack(slot), pred, deep);
        return n;
    }

    /** {@code s} if it matches, plus (with {@code deep}) matching items inside it, one level down. */
    private int countStack(ItemStack s, Predicate<ItemStack> pred, boolean deep) {
        if (s.isEmpty()) return 0;
        int n = 0;
        if (pred.test(s)) {
            n += s.getCount();
            if (firstMatch.isEmpty()) firstMatch = s;
        }
        if (!deep) return n;
        ContainerComponent box = s.get(DataComponentTypes.CONTAINER);
        if (box != null) for (ItemStack in : box.iterateNonEmpty()) n += countInner(in, pred);
        BundleContentsComponent bundle = s.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) for (ItemStack in : bundle.iterate()) n += countInner(in, pred);
        return n;
    }

    private int countInner(ItemStack s, Predicate<ItemStack> pred) {
        if (s.isEmpty() || !pred.test(s)) return 0;
        if (firstMatch.isEmpty()) firstMatch = s;
        return s.getCount();
    }

    /** Tracker {@code i}'s row this tick, and what it counted (for {@link Tracker#sig}). */
    private Row row(int i, String kind, PlayerEntity p, ItemStack held, StringBuilder sig) {
        sig.append(kind).append('|').append(countIn.get()).append('|');
        Tracker t = trackers[i];
        switch (kind) {
            case "Held Item" -> {
                boolean exact = match.is("Exact");
                sig.append(match.get()).append('|');
                if (held.isEmpty()) return new Row(i, "Item", ItemStack.EMPTY, 0, 64, false);
                sig.append(Registries.ITEM.getId(held.getItem()));
                if (exact) sig.append('#').append(held.getComponents().hashCode());
                ItemStack ref = held.copyWithCount(1);
                Predicate<ItemStack> pred = exact ? s -> ItemStack.areItemsAndComponentsEqual(s, ref) : s -> s.isOf(ref.getItem());
                int n = countMatching(p, pred);
                String label = (exact ? ref.getName() : ref.getItem().getName()).getString();
                return new Row(i, label, icon(t, ref), n, ref.getMaxCount(), true);
            }
            case "Pinned" -> {
                Item item = pinnedItem();
                if (item == null) return new Row(i, "Pinned (none)", ItemStack.EMPTY, 0, 64, false);
                sig.append(Registries.ITEM.getId(item));
                int n = countMatching(p, s -> s.isOf(item));
                ItemStack icon = icon(t, firstMatch.isEmpty() ? new ItemStack(item) : firstMatch);
                return new Row(i, item.getName().getString(), icon, n, icon.getMaxCount(), true);
            }
            default -> {
                Predicate<ItemStack> pred = CATEGORY.getOrDefault(kind, s -> false);
                int n = countMatching(p, pred);
                ItemStack icon = icon(t, firstMatch.isEmpty() ? new ItemStack(fallbackIcon(kind)) : firstMatch);
                return new Row(i, kind, icon, n, icon.getMaxCount(), true);
            }
        }
    }

    /** A one-item copy of {@code s} for the icon, reused while the item shown stays the same. */
    private static ItemStack icon(Tracker t, ItemStack s) {
        if (!ItemStack.areItemsAndComponentsEqual(t.icon, s)) t.icon = s.copyWithCount(1);
        return t.icon;
    }

    /** Matching items for the first row (what Track counts), counted now. */
    public int count() {
        var p = mc.player;
        String kind = kind(0);
        if (p == null || kind == null) return 0;
        return row(0, kind, p, heldReference(p), new StringBuilder()).count();
    }

    private void resetTrackers() {
        for (Tracker t : trackers) t.reset();
        rows = List.of();
    }

    @Override
    protected void onEnable() {
        resetTrackers();
        pinWasDown = pinKey.isDown();
    }

    @Override
    public void onTick() {
        var p = mc.player;
        if (p == null) return;
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            lastHeld = ItemStack.EMPTY;
            resetTrackers();
        }
        pollPinKey();
        rememberHeld(p);
        ItemStack held = heldReference(p);
        long now = System.currentTimeMillis();
        long window = Math.round(changeDuration.get() * 1000);
        List<Row> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            String kind = kind(i);
            Tracker t = trackers[i];
            if (kind == null) { t.reset(); continue; }
            StringBuilder sig = new StringBuilder();
            Row r = row(i, kind, p, held, sig);
            out.add(r);
            boolean low = isLow(r);
            String s = sig.toString();
            if (!s.equals(t.sig)) {
                // Something else is counted now (other item in hand, other setting): start over quietly.
                t.sig = s;
                t.prev = r.count();
                t.delta = 0;
                t.wasLow = low;
                continue;
            }
            if (r.count() != t.prev) {
                if (t.prev >= 0) {
                    t.delta += r.count() - t.prev;
                    t.deltaAt = now;
                }
                if (low && !t.wasLow && t.prev > r.count() && lowAlert.get())
                    Notifications.push(getName(), r.count() + " " + r.label() + " left", false);
                t.prev = r.count();
            }
            if (t.delta != 0 && now - t.deltaAt >= window) t.delta = 0;
            t.wasLow = low;
        }
        rows = out;
    }

    // ------------------------------------------------------------------ formatting

    /** The low-warning amount for {@code r}, scaled down for items that stack to fewer than 64; 0 for none. */
    private int threshold(Row r) {
        int at = lowAt.getInt();
        if (at <= 0 || !r.real()) return 0;
        if (r.maxStack() >= 64) return at;
        return Math.max(1, Math.round(at * r.maxStack() / 64f));
    }

    private boolean isLow(Row r) {
        int th = threshold(r);
        return th > 0 && r.count() <= th;
    }

    /** {@code base}, or Warning Color while the row is low (blinking with Flash When Low). */
    private int rowColor(Row r, int base) {
        if (!isLow(r)) return base;
        if (flashLow.get() && (System.currentTimeMillis() / 250) % 2 == 0) return base;
        return warnColor.get() | 0xFF000000;
    }

    private static String oneDecimal(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** {@code n} in Count Format, using the item's stack size {@code max}. */
    private String formatCount(int n, int max) {
        String mode = countFormat.get();
        if (max <= 1 || mode.equals("Items")) return String.valueOf(n);
        String stacks = oneDecimal(n / (double) max) + " st";
        return switch (mode) {
            case "Stacks" -> stacks;
            case "Stacks + Items" -> {
                int full = n / max, rest = n % max;
                yield full == 0 ? String.valueOf(rest) : rest == 0 ? full + "s" : full + "s " + rest;
            }
            default -> n + " (" + stacks + ")";
        };
    }

    /** "64x Arrows" (or "3s 8 Arrows" in a stack format), or just the count. */
    private String rowText(Row r, boolean withName) {
        String n = formatCount(r.count(), r.maxStack());
        if (!withName) return n;
        return countFormat.is("Items") || r.maxStack() <= 1 ? n + "x " + r.label() : n + " " + r.label();
    }

    /** "+12" / "-3" while the row's change window is open, else null. */
    private String deltaText(Row r, long now) {
        if (!showChange.get() || !r.real()) return null;
        Tracker t = trackers[r.tracker()];
        if (t.delta == 0 || now - t.deltaAt >= changeDuration.get() * 1000) return null;
        return t.delta > 0 ? "+" + t.delta : String.valueOf(t.delta);
    }

    /** Opacity of the change text: full, then fading over the last half second (or half the window). */
    private float deltaAlpha(Row r, long now) {
        double window = changeDuration.get() * 1000;
        double left = window - (now - trackers[r.tracker()].deltaAt);
        return (float) Math.max(0, Math.min(1, left / Math.min(500, window / 2)));
    }

    /** The rows on screen: Hide When Zero and Hide Empty Rows applied, sample rows in the HUD editor if none. */
    private List<Row> visibleRows(boolean preview) {
        List<Row> out = new ArrayList<>(rows.size());
        boolean any = false;
        for (Row r : rows) if (r.count() > 0) any = true;
        if (any || !hideWhenZero.get())
            for (Row r : rows) if (r.count() > 0 || !hideEmptyRows.get()) out.add(r);
        if (preview && out.isEmpty()) return previewRows();
        return out;
    }

    private List<Row> previewRows() {
        List<Row> out = new ArrayList<>(3);
        int j = 0;
        for (int i = 0; i < 3; i++) {
            String kind = kind(i);
            if (kind == null) continue;
            Row real = null;
            for (Row r : rows) if (r.tracker() == i) real = r;
            ItemStack icon;
            String label;
            if (real != null && real.real()) {
                icon = real.icon();
                label = real.label();
            } else if (CATEGORY.containsKey(kind)) {
                icon = new ItemStack(fallbackIcon(kind));
                label = kind;
            } else {
                icon = new ItemStack(SAMPLE_ITEMS[j]);
                label = SAMPLE_LABELS[j];
            }
            out.add(new Row(i, label, icon, SAMPLE_COUNTS[j], icon.isEmpty() ? 64 : icon.getMaxCount(), false));
            j++;
        }
        return out;
    }

    // ------------------------------------------------------------------ text display

    @Override
    protected List<String> lines(boolean preview) {
        List<Row> rs = visibleRows(preview);
        drawn = rs;
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>(rs.size());
        for (Row r : rs) {
            String d = deltaText(r, now);
            out.add(d == null ? rowText(r, true) : rowText(r, true) + " " + d);
        }
        return out;
    }

    /** The Text display: the change in green or red after the count (the rest follows Text Color). */
    @Override
    protected List<Text> styledLines(boolean preview) {
        List<Row> rs = visibleRows(preview);
        drawn = rs;
        long now = System.currentTimeMillis();
        List<Text> out = new ArrayList<>(rs.size());
        for (Row r : rs) {
            MutableText t = Text.literal(rowText(r, true));
            String d = deltaText(r, now);
            if (d != null) t.append(Text.literal(" " + d).withColor(trackers[r.tracker()].delta > 0 ? GAIN_COLOR & 0xFFFFFF : LOSS_COLOR & 0xFFFFFF));
            out.add(t);
        }
        return out;
    }

    @Override
    protected int lineColor(int index, int base) {
        List<Row> d = drawn;
        return index >= 0 && index < d.size() ? rowColor(d.get(index), base) : base;
    }

    // ------------------------------------------------------------------ icon displays

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        if (display.is("Text")) {
            super.render(ctx, left, top, preview);
            return;
        }
        List<Row> rs = visibleRows(preview);
        drawn = rs;
        if (rs.isEmpty()) {
            width = 40;
            height = 13;
            empty = true;
            return;
        }
        var tr = mc.textRenderer;
        boolean withName = display.is("Icon + Name + Count"), br = brackets.get();
        long now = System.currentTimeMillis();
        int n = rs.size();
        Text[] texts = new Text[n], deltas = new Text[n];
        int[] tw = new int[n], dw = new int[n];
        for (int i = 0; i < n; i++) {
            Row r = rs.get(i);
            String s = rowText(r, withName);
            texts[i] = styled(br ? "[" + s + "]" : s);
            tw[i] = tr.getWidth(texts[i]);
            String d = deltaText(r, now);
            if (d != null) {
                deltas[i] = styled(" " + d);
                dw[i] = tr.getWidth(deltas[i]);
            }
        }
        boolean horizontal = layout.is("Horizontal");
        if (horizontal) {
            int w = 2;
            for (int i = 0; i < n; i++) w += 18 + tw[i] + dw[i] + 6;
            width = w - 6 + 4;
            height = ROW + 2;
        } else {
            int max = 0;
            for (int i = 0; i < n; i++) max = Math.max(max, tw[i] + dw[i]);
            width = 20 + max + 4;
            height = n * ROW + 2;
        }
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        // Anchored to the right edge (or Text Align Right): icons sit on the right, so they stay put as counts change.
        boolean mirror = !horizontal && (textAlign.is("Right") || (textAlign.is("Auto") && x != CENTER && x < 0));
        boolean sh = shadow.get(), rainbow = chroma.get();
        int base = color.get();
        int cx = left + 2, cy = top + 2;
        for (int i = 0; i < n; i++) {
            Row r = rs.get(i);
            int iconX, tx;
            if (mirror) {
                iconX = left + width - 2 - 16;
                tx = iconX - 2 - tw[i] - dw[i];
            } else {
                iconX = cx;
                tx = cx + 18;
            }
            if (!r.icon().isEmpty()) ctx.drawItem(r.icon(), iconX, cy);
            int ty = cy + 4;
            if (rainbow) drawChroma(ctx, tr, texts[i], tx, ty, sh);
            else ctx.drawText(tr, texts[i], tx, ty, rowColor(r, base), sh);
            if (deltas[i] != null) {
                int dc = trackers[r.tracker()].delta > 0 ? GAIN_COLOR : LOSS_COLOR;
                float a = deltaAlpha(r, now);
                if (a > 0.02f) ctx.drawText(tr, deltas[i], tx + tw[i], ty, Theme.alpha(dc, a), sh);
            }
            if (horizontal) cx += 18 + tw[i] + dw[i] + 6;
            else cy += ROW;
        }
    }

    // ------------------------------------------------------------------ config

    /**
     * Older configs had Show Icon (an icon in front of "64x Arrows") instead of Display: keep their look. A new
     * setting is only changed while it is still at its default, so imported profile codes migrate the same way.
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (settings.has(display.getName()) || !settings.has("Show Icon") || !display.isDefault()) return;
        JsonElement v = settings.get("Show Icon");
        boolean icon = true;
        try {
            icon = v.getAsBoolean();
        } catch (RuntimeException ignored) {
            // unreadable: keep the old default (on)
        }
        display.set(icon ? "Icon + Name + Count" : "Text");
    }
}
