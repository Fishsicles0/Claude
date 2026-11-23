package net.evadeclient.module.hud;

import com.google.common.hash.Hashing;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackCompatibility;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shows the resource pack you're using (by its display name and icon), every pack you've turned on, or how many.
 * Your own packs and the packs a server or world sends count; the built-in game, mod and feature packs don't.
 */
public class PackDisplayHud extends TextHudModule {
    private final ModeSetting show = add(new ModeSetting("Show", "Top Pack", "Top Pack", "All Packs", "Count")
            .describe("The pack on top (the one that wins), every pack you have on, or just how many."));
    private final NumberSetting maxPacks = add(new NumberSetting("Max Packs", 5, 1, 10, 1)
            .describe("All Packs lists at most this many, then \"+2 more\"."));
    private final ModeSetting order = add(new ModeSetting("Order", "Top First", "Top First", "Bottom First")
            .describe("All Packs: the highest-priority pack first (like the pack screen), or last."));
    private final ModeSetting serverPacks = add(new ModeSetting("Server Packs", "Show", "Show", "Hide", "Only If No Own Pack")
            .describe("Packs a server or world sends you."));
    private final BooleanSetting hideWhenDefault = add(new BooleanSetting("Hide When Default", false)
            .describe("Hide the widget while no pack is on."));
    private final BooleanSetting keepColors = add(new BooleanSetting("Keep Pack Colors", true)
            .describe("Keep the colours in pack names (Chroma always drops them)."));
    private final ModeSetting label = add(new ModeSetting("Label", "Pack:", "Pack:", "None")
            .describe("Start with \"Pack:\", or show only the name."));
    private final BooleanSetting showIcon = add(new BooleanSetting("Show Icon", true)
            .describe("The pack's icon beside its name."));
    private final NumberSetting iconSize = add(new NumberSetting("Icon Size", 16, 8, 32, 2)
            .describe("Icon size in pixels."));
    private final BooleanSetting showDescription = add(new BooleanSetting("Show Description", false)
            .describe("The pack's description under its name, dimmed."));
    private final NumberSetting maxWidth = add(new NumberSetting("Max Width", 160, 60, 300, 10)
            .describe("Longer names and descriptions are cut short with \"...\"."));
    private final BooleanSetting markIncompatible = add(new BooleanSetting("Mark Incompatible", true)
            .describe("Add (old) or (new) to a pack made for another game version."));

    private static final Identifier UNKNOWN_PACK = Identifier.ofVanilla("textures/misc/unknown_pack.png");
    private static final String ICON_NAMESPACE = "evade";
    /** Enabled ids that are never a user's pack, whatever source they report. */
    private static final Set<String> SKIP_IDS = Set.of("vanilla", "fabric", "programmer_art", "high_contrast");
    private static final String ELLIPSIS = "...";

    /** One enabled pack: its id, display name (colour codes kept), description, version fit, kind and icon. */
    private record Pack(String id, String name, String description, ResourcePackCompatibility compat, boolean server, Identifier icon) {}

    /** User and server packs, top (highest priority) first, rebuilt only when the enabled list itself changes. */
    private List<Pack> packs = List.of();
    private Collection<ResourcePackProfile> lastEnabled;
    /** The game's own pack icon, shown beside "Default". */
    private Identifier defaultIcon;
    /** Icon textures this module registered (all in the evade namespace), so stale ones can be freed. */
    private final Set<Identifier> loadedIcons = new HashSet<>();

    /** Per built text line: whether it is dimmed (a description or "+N more"). */
    private boolean[] dimLines = new boolean[0];
    /** The icon drawn left of the text in Top Pack mode, or null for none. */
    private Identifier leadIcon;

    /** All Packs with icons: rows as last built, their text widths, the tick and font raster they were built for. */
    private record Row(Identifier icon, Text name, Text description, boolean more) {}
    private List<Row> rows;
    private int[] rowNameW, rowDescW;
    private long rowTick = -1;
    private int rowFont = -1;

    public PackDisplayHud() { super("Pack Display", "Shows your active resource pack.", 4, 216); }

    // ---- pack list ---------------------------------------------------------------------------------------

    /** Rebuilds {@link #packs} and loads icons when the enabled-profile list has been replaced. Render thread. */
    private void refresh() {
        ResourcePackManager manager = mc.getResourcePackManager();
        if (manager == null) return;
        Collection<ResourcePackProfile> enabled = manager.getEnabledProfiles();
        if (enabled == lastEnabled) return;
        lastEnabled = enabled;
        Set<Identifier> keep = new HashSet<>();
        List<Pack> out = new ArrayList<>();
        Identifier def = null;
        for (ResourcePackProfile p : enabled) { // bottom to top
            try {
                String id = p.getId();
                if ("vanilla".equals(id)) {
                    def = loadIcon(p, keep);
                    continue;
                }
                ResourcePackSource src = p.getSource();
                boolean server = src == ResourcePackSource.SERVER || src == ResourcePackSource.WORLD;
                if (!server && src != ResourcePackSource.NONE) continue; // built-in, feature and mod packs
                if (SKIP_IDS.contains(id)) continue;
                String name = server ? (src == ResourcePackSource.WORLD ? "World Pack" : "Server Pack") : displayName(p);
                String description = oneLine(p.getDescription().getString());
                out.add(0, new Pack(id, name, description, p.getCompatibility(), server, loadIcon(p, keep)));
            } catch (RuntimeException e) {
                EvadeClient.LOG.debug("Pack Display skipped pack {}", p.getId(), e);
            }
        }
        var textures = mc.getTextureManager();
        for (Identifier old : loadedIcons) {
            if (!keep.contains(old) && ICON_NAMESPACE.equals(old.getNamespace())) textures.destroyTexture(old);
        }
        loadedIcons.clear();
        loadedIcons.addAll(keep);
        packs = List.copyOf(out);
        defaultIcon = def;
    }

    /**
     * The pack's pack.png as a texture in the evade namespace, or null when it has none or it can't be read. The
     * texture id hashes the pack id with SHA-1 like the game's pack screen (an id, not a security use).
     */
    @SuppressWarnings("deprecation")
    private static Identifier loadIcon(ResourcePackProfile profile, Set<Identifier> loaded) {
        Identifier id = Identifier.of(ICON_NAMESPACE, "pack_icon/" + Hashing.sha1().hashUnencodedChars(profile.getId()));
        try (ResourcePack pack = profile.createResourcePack()) {
            InputSupplier<InputStream> supplier = pack.openRoot("pack.png");
            if (supplier == null) return null;
            try (InputStream in = supplier.get()) {
                NativeImage image = NativeImage.read(in);
                try {
                    mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(id::toString, image));
                } catch (RuntimeException e) {
                    image.close();
                    throw e;
                }
                loaded.add(id);
                return id;
            }
        } catch (Exception e) {
            EvadeClient.LOG.debug("Pack Display couldn't load the icon of {}", profile.getId(), e);
            return null;
        }
    }

    /** The pack's name without a trailing ".zip", or its id when it has no name. */
    private static String displayName(ResourcePackProfile p) {
        String n = p.getDisplayName().getString().strip();
        if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4).strip();
        return n.isEmpty() ? p.getId() : n;
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String stripped = Formatting.strip(s);
        return stripped == null ? "" : stripped.replaceAll("\\s+", " ").strip();
    }

    /** The packs to show, top first, with every server or world pack folded into one entry. */
    private List<Pack> visible() {
        refresh();
        boolean hasOwn = false;
        for (Pack p : packs) if (!p.server()) { hasOwn = true; break; }
        boolean withServer = serverPacks.is("Show") || serverPacks.is("Only If No Own Pack") && !hasOwn;
        List<Pack> out = new ArrayList<>(packs.size());
        int serverAt = -1, serverCount = 0;
        for (Pack p : packs) {
            if (p.server()) {
                if (!withServer) continue;
                serverCount++;
                if (serverAt >= 0) continue;
                serverAt = out.size();
            }
            out.add(p);
        }
        if (serverCount > 1) {
            Pack first = out.get(serverAt);
            out.set(serverAt, new Pack(first.id(), "Server Packs (" + serverCount + ")", first.description(), first.compat(), true, first.icon()));
        }
        return out;
    }

    // ---- text --------------------------------------------------------------------------------------------

    private boolean stripColors() { return !keepColors.get() || chroma.get(); }

    private String prefix() { return label.is("None") ? "" : "Pack: "; }

    private String marker(Pack p) {
        if (!markIncompatible.get()) return "";
        return switch (p.compat()) {
            case TOO_OLD -> " (old)";
            case TOO_NEW -> " (new)";
            default -> "";
        };
    }

    /** "Pack: Name (old)" (the label only on the first line), cut to Max Width; colours end before the marker. */
    private String nameLine(Pack p, boolean first) {
        TextRenderer tr = mc.textRenderer;
        String prefix = first ? prefix() : "", mark = marker(p);
        String name = p.name();
        if (stripColors()) name = Formatting.strip(name);
        int room = maxWidth.getInt() - tr.getWidth(prefix) - tr.getWidth(mark);
        name = fit(tr, name, Math.max(24, room));
        return prefix + name + (name.indexOf('§') >= 0 ? "§r" : "") + mark;
    }

    private String descriptionLine(Pack p) {
        if (!showDescription.get() || p.description().isEmpty()) return null;
        return fit(mc.textRenderer, p.description(), maxWidth.getInt());
    }

    /**
     * {@code s} cut to {@code width} pixels with "..." when it is longer. Colour codes take no width, and a code
     * is never cut in half.
     */
    private static String fit(TextRenderer tr, String s, int width) {
        if (tr.getWidth(s) <= width) return s;
        String cut = tr.trimToWidth(s, Math.max(0, width - tr.getWidth(ELLIPSIS)));
        if (cut.endsWith("§")) cut = cut.substring(0, cut.length() - 1);
        return cut.stripTrailing() + ELLIPSIS;
    }

    private String defaultLine() { return prefix() + "Default"; }

    private String countLine(int n) { return prefix() + n + (n == 1 ? " pack" : " packs"); }

    /** The packs in All Packs order, at most Max Packs. */
    private List<Pack> listed(List<Pack> visible) {
        List<Pack> list = new ArrayList<>(visible);
        if (order.is("Bottom First")) Collections.reverse(list);
        return list.subList(0, Math.min(list.size(), Math.max(1, maxPacks.getInt())));
    }

    @Override
    protected List<String> lines(boolean preview) {
        List<Pack> v = visible();
        List<String> out = new ArrayList<>();
        List<Boolean> dim = new ArrayList<>();
        leadIcon = null;
        if (v.isEmpty()) {
            if (!hideWhenDefault.get() || preview) {
                out.add(defaultLine());
                dim.add(false);
                if (showIcon.get() && !show.is("Count")) leadIcon = defaultIcon != null ? defaultIcon : UNKNOWN_PACK;
            }
        } else if (show.is("Count")) {
            out.add(countLine(v.size()));
            dim.add(false);
        } else if (show.is("All Packs")) {
            List<Pack> list = listed(v);
            for (int i = 0; i < list.size(); i++) {
                out.add(nameLine(list.get(i), i == 0));
                dim.add(false);
                String d = descriptionLine(list.get(i));
                if (d != null) { out.add(d); dim.add(true); }
            }
            if (v.size() > list.size()) { out.add("+" + (v.size() - list.size()) + " more"); dim.add(true); }
        } else {
            Pack top = v.get(0);
            out.add(nameLine(top, true));
            dim.add(false);
            String d = descriptionLine(top);
            if (d != null) { out.add(d); dim.add(true); }
            if (showIcon.get()) leadIcon = top.icon() != null ? top.icon() : UNKNOWN_PACK;
        }
        boolean[] flags = new boolean[dim.size()];
        for (int i = 0; i < flags.length; i++) flags[i] = dim.get(i);
        dimLines = flags;
        return out;
    }

    @Override
    protected int lineColor(int index, int base) {
        return index < dimLines.length && dimLines[index] ? dim(base) : base;
    }

    private static int dim(int c) {
        int r = (c >> 16 & 0xFF) * 6 / 10, g = (c >> 8 & 0xFF) * 6 / 10, b = (c & 0xFF) * 6 / 10;
        return c & 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ---- icon beside a single pack -----------------------------------------------------------------------

    private int iconPx() { return Math.max(8, Math.min(32, iconSize.getInt())); }

    @Override
    protected int leadingWidth() { return leadIcon != null ? iconPx() + 2 : 0; }

    @Override
    protected int minHeight() { return leadIcon != null ? iconPx() + 4 : 0; }

    @Override
    protected void drawLeading(DrawContext ctx, int x, int y, int h) {
        Identifier icon = leadIcon;
        if (icon == null) return;
        int s = iconPx();
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x, y + Math.max(0, (h - s) / 2), 0, 0, s, s, s, s);
    }

    // ---- All Packs with icons: one row per pack, the icon beside its name (and description) ---------------

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        if (show.is("All Packs") && showIcon.get()) renderRows(ctx, left, top, preview);
        else super.render(ctx, left, top, preview);
    }

    private int align() {
        return switch (textAlign.get()) {
            case "Left" -> -1;
            case "Center" -> 0;
            case "Right" -> 1;
            default -> x == CENTER ? 0 : x < 0 ? 1 : -1;
        };
    }

    private Text styledText(String s, Style font) {
        MutableText t = Text.literal(brackets.get() ? "[" + s + "]" : s);
        if (font != null) t.setStyle(font);
        return t;
    }

    private void buildRows(TextRenderer tr, int fontKey, boolean preview) {
        Style font = fontKey > 0 ? UiFont.style(UiFont.Role.BODY, fontKey) : null;
        List<Pack> v = visible();
        List<Row> out = new ArrayList<>();
        if (v.isEmpty()) {
            if (!hideWhenDefault.get() || preview) {
                out.add(new Row(defaultIcon != null ? defaultIcon : UNKNOWN_PACK, styledText(defaultLine(), font), null, false));
            }
        } else {
            List<Pack> list = listed(v);
            for (int i = 0; i < list.size(); i++) {
                Pack p = list.get(i);
                String d = descriptionLine(p);
                out.add(new Row(p.icon() != null ? p.icon() : UNKNOWN_PACK, styledText(nameLine(p, i == 0), font),
                        d == null ? null : styledText(d, font), false));
            }
            if (v.size() > list.size()) out.add(new Row(null, styledText("+" + (v.size() - list.size()) + " more", font), null, true));
        }
        int[] nameW = new int[out.size()], descW = new int[out.size()];
        for (int i = 0; i < out.size(); i++) {
            nameW[i] = tr.getWidth(out.get(i).name());
            descW[i] = out.get(i).description() == null ? 0 : tr.getWidth(out.get(i).description());
        }
        rows = out;
        rowNameW = nameW;
        rowDescW = descW;
    }

    private int rowHeight(Row r, int s) {
        int textH = r.description() != null ? 20 : 10;
        return r.more() ? 10 : Math.max(s, textH);
    }

    private void renderRows(DrawContext ctx, int left, int top, boolean preview) {
        TextRenderer tr = mc.textRenderer;
        long tick = EvadeClient.ticks;
        int fontKey = smoothFont.get() ? smoothRaster(ctx) : 0;
        if (preview || NO_CACHE || rows == null || rowTick != tick || rowFont != fontKey) {
            buildRows(tr, fontKey, preview);
            rowTick = preview ? -1 : tick;
            rowFont = fontKey;
        }
        List<Row> rs = rows;
        if (rs.isEmpty()) {
            width = 40;
            height = 13;
            empty = true;
            return;
        }
        int s = iconPx(), lead = s + 2;
        int textW = 0, contentH = 0;
        for (int i = 0; i < rs.size(); i++) {
            textW = Math.max(textW, Math.max(rowNameW[i], rowDescW[i]));
            contentH += rowHeight(rs.get(i), s) + (i > 0 ? 2 : 0);
        }
        width = Math.max(40, textW + 8 + lead);
        height = contentH + 4;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        int base = color.get(), faded = dim(base), align = align();
        boolean sh = shadow.get(), rainbow = chroma.get();
        int y = top + 2;
        for (int i = 0; i < rs.size(); i++) {
            Row r = rs.get(i);
            int rh = rowHeight(r, s);
            if (r.icon() != null) {
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, r.icon(), left + 3, y + (rh - s) / 2, 0, 0, s, s, s, s);
            }
            int lines = r.description() != null ? 2 : 1;
            int ty = y + 1 + Math.max(0, (rh - lines * 10) / 2);
            drawRowText(ctx, tr, r.name(), rowNameW[i], left, ty, lead, align, r.more() ? faded : base, sh, rainbow);
            if (r.description() != null) drawRowText(ctx, tr, r.description(), rowDescW[i], left, ty + 10, lead, align, faded, sh, rainbow);
            y += rh + 2;
        }
    }

    private void drawRowText(DrawContext ctx, TextRenderer tr, Text t, int w, int left, int y, int lead, int align,
                             int c, boolean sh, boolean rainbow) {
        int tx = align < 0 ? left + lead + 4
                : align > 0 ? left + width - 4 - w
                : left + lead + (width - lead - w) / 2;
        if (rainbow) drawChroma(ctx, tr, t, tx, y, sh);
        else ctx.drawText(tr, t, tx, y, c, sh);
    }

    // ---- config ------------------------------------------------------------------------------------------

    /** Older configs had Show All (on/off); map it onto Show while Show is still at its default. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (!settings.has(show.getName()) && show.isDefault()) {
            JsonElement e = settings.get("Show All");
            try {
                if (e != null && e.isJsonPrimitive() && e.getAsBoolean()) show.set("All Packs");
            } catch (RuntimeException ignored) {
                // not a boolean: keep the default
            }
        }
    }
}
