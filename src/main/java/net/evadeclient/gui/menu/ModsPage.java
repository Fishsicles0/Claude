package net.evadeclient.gui.menu;

import net.evadeclient.EvadeClient;
import net.evadeclient.config.MenuState;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Dropdown;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.KeyChip;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.ListRow;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.gui.kit.Segmented;
import net.evadeclient.gui.kit.Surface;
import net.evadeclient.gui.kit.Toggle;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A module list page (All mods, a category, Pinned, search results). Header: the page title with its count, the
 * search field (Ctrl+F; typing anywhere starts a search), the sort dropdown, the All / Enabled filter and the view
 * switch (List, Cards, Compact). All mods is grouped by category under sticky section headers, with your pinned
 * mods first.
 *
 * <p>List (default, dense): 32 px rows (Density: Comfortable = 40) with the module's icon tile, its name and a
 * one-line description, and the key chip, gear and switch grouped at the right. Cards: a 3-column grid of cards that
 * tilt toward the cursor. Compact: two columns of slim rows. Click toggles a module, right-click (or the gear) opens
 * its settings; arrow keys move the keyboard focus through the list.
 */
public final class ModsPage {
    private static final int K_HEADER = 0, K_MOD = 1;

    private record Item(int kind, Module mod, String title, String count, int x, int y, int w, int h) {}

    private final MenuHost host;
    private final List<Item> items = new ArrayList<>();
    private int contentH;
    private final Map<String, int[]> rects = new HashMap<>();
    private final Map<String, List<String>> wraps = new HashMap<>();
    private Module pendingFocus;
    private boolean pendingVisible;

    static final int HEADER_H = 24, CARD_H = 88, CARD_GAP = 8, COMPACT_H = 24, COMPACT_GAP = 4, ROW_GAP = 2;

    public ModsPage(MenuHost host) { this.host = host; }

    private static ClientSettings cs() { return EvadeClient.modules.get(ClientSettings.class); }

    private static String style() { return cs().guiStyle.get(); }

    // ------------------------------------------------------------------ layout

    /** Lay the list out: sections (pinned first, then categories in their curated order) and the rows or cards. */
    private void layout(int x, int y0, int w) {
        items.clear();
        List<Module> mods = host.visibleModules();
        MenuPage p = host.page();
        boolean searching = !host.search().isBlank();
        boolean grouped = (p == MenuPage.ALL || searching);
        Map<String, List<Module>> sections = new LinkedHashMap<>();
        if (grouped) {
            if (!searching) {
                List<Module> pinned = new ArrayList<>();
                for (Module m : mods) if (MenuState.isPinned(m)) pinned.add(m);
                if (!pinned.isEmpty()) { sections.put("★ Pinned", pinned); mods = new ArrayList<>(mods); mods.removeAll(pinned); }
            }
            for (Module m : mods) sections.computeIfAbsent(m.getCategory().displayName.toUpperCase(Locale.ROOT), c -> new ArrayList<>()).add(m);
        } else {
            sections.put("", mods);
        }
        String st = style();
        boolean cards = st.equals("Cards"), compact = st.equals("Compact");
        int cols = cards ? (w >= 600 ? 3 : 2) : compact ? (w >= 380 ? 2 : 1) : 1;
        int gap = cards ? CARD_GAP : compact ? COMPACT_GAP : ROW_GAP;
        int h = cards ? CARD_H : compact ? COMPACT_H : ListRow.height();
        int cw = (w - gap * (cols - 1)) / cols;
        int y = y0;
        boolean first = true;
        for (var e : sections.entrySet()) {
            if (!e.getKey().isEmpty()) {
                if (!first) y += 10;
                items.add(new Item(K_HEADER, null, e.getKey(), String.valueOf(e.getValue().size()), x, y, w, HEADER_H - 2));
                y += HEADER_H - 2;
            }
            first = false;
            List<Module> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                int col = i % cols;
                if (col == 0 && i > 0) y += h + gap;
                items.add(new Item(K_MOD, list.get(i), null, null, x + col * (cw + gap), y, cw, h));
            }
            if (!list.isEmpty()) y += h + gap;
        }
        contentH = y - y0;
    }

    // ------------------------------------------------------------------ render

    public void render(Kit k) {
        rects.clear();
        int cx = host.cx(), cw = host.cw(), top = host.top(), cy = host.cy(), ch = host.ch();
        MenuScroll scroll = host.scroll();
        layout(cx, cy, cw);
        if (pendingFocus != null) {
            for (Item it : items) if (it.kind == K_MOD && it.mod == pendingFocus) {
                scroll.clamp(contentH, ch);
                scroll.jump(it.y - cy - ch / 2.0 + it.h / 2.0);
                k.focus("row:" + it.mod.getName(), pendingVisible);
            }
            pendingFocus = null;
        }
        scroll.clamp(contentH, ch);

        // The list first (clipped), so the header's controls register on top of rows scrolled under them.
        int si = scroll.px();
        String st = style();
        boolean mixed = host.page() == MenuPage.ALL || host.page() == MenuPage.PINNED || !host.search().isBlank();
        k.ctx.enableScissor(cx - 12, cy, cx + cw + 12, cy + ch);
        scroll.push(k.ctx);
        Item sticky = null;
        try {
            for (Item it : items) {
                int y = it.y - si;
                if (it.kind == K_HEADER && it.y - si <= cy) sticky = it;
                if (y + it.h < cy || y > cy + ch) continue;
                if (it.kind == K_HEADER) header(k, it.title, it.count, it.x, y);
                else if (st.equals("Cards")) card(k, it.mod, it.x, y, it.w, it.h, mixed);
                else if (st.equals("Compact")) compact(k, it.mod, it.x, y, it.w, it.h);
                else row(k, it.mod, it.x, y, it.w, it.h);
            }
        } finally {
            scroll.pop(k.ctx);
            k.ctx.disableScissor();
        }
        // Sticky section header: the current section's header stays at the top, pushed up by the next one.
        if (sticky != null && si > 0) {
            int sy = cy;
            for (Item it : items) if (it.kind == K_HEADER && it != sticky && it.y - si > cy && it.y - si < cy + HEADER_H) sy = it.y - si - HEADER_H;
            k.ctx.enableScissor(cx - 12, cy, cx + cw + 12, cy + ch);
            Paint.vgradient(k.ctx, cx - 12, sy, cx + cw + 12, sy + HEADER_H + 4, 0, Theme.alpha(Tokens.N100, 0.98f), Theme.alpha(Tokens.N100, 0f));
            header(k, sticky.title, sticky.count, cx, sy);
            k.ctx.disableScissor();
        }
        if (items.isEmpty()) empty(k, cx + cw / 2, cy + 30);
        scroll.chrome(k, "listbar", cx - 12, cy, cx + cw, cy + ch);
        // Clicks above and below the list never reach rows scrolled under the header or past the bottom.
        k.barrier("list#top", cx - 12, top, cx + cw + 20, cy, null);
        k.barrier("list#bottom", cx - 12, cy + ch, cx + cw + 20, cy + ch + 40, null);

        headerBar(k, cx, cw, top, cy);
    }

    private void headerBar(Kit k, int cx, int cw, int top, int cy) {
        boolean searching = !host.search().isBlank();
        MenuPage p = host.page();
        String title = searching ? "Search" : p.label;
        k.textCentredY(title, cx, top + 16, Tokens.N950, Role.HEADLINE);
        int total = host.modulesFor(searching ? MenuPage.ALL : p, "").size();
        int shown = 0;
        for (Item it : items) if (it.kind == K_MOD) shown++;
        String count = searching || host.enabledOnly() ? shown + " of " + total : String.valueOf(total);
        int tx = cx + k.width(title, Role.HEADLINE) + 10;
        Badge.draw(k, tx, top + 10, count, Badge.Tone.NEUTRAL);

        int right = host.headerRight();
        int dw = 112;
        Dropdown.draw(k, "sort", right - dw, top + 4, dw, List.of("Featured", "A–Z"), host.sortAz() ? "A–Z" : "Featured", true,
                o -> host.sortAz(o.equals("A–Z")));
        k.tooltip("sort", "Sort", "Featured keeps Evade's order; A–Z sorts by name", right - dw, top + 4, right, top + 28);
        int sw = Math.min(SearchField.MAX_W - 100, right - dw - 8 - (tx + 60));
        SearchField.draw(k, net.evadeclient.gui.EvadeMenu.SEARCH, right - dw - 8 - sw, top + 4, sw, "Search mods and settings", "Ctrl F", true);
        rects.put("search", new int[]{right - dw - 8 - sw, top + 4, right - dw - 8, top + 28});

        int fy = top + 42;
        List<String> filter = List.of("All", "Enabled");
        Segmented.draw(k, "filter", cx, fy, filter, host.enabledOnly() ? "Enabled" : "All", true, o -> host.enabledOnly(o.equals("Enabled")));
        segRects(k, "filter", cx, fy, filter);
        List<String> styles = List.of("List", "Cards", "Compact");
        int vw = Segmented.width(k, styles), vx = host.cx() + cw - vw;
        Segmented.draw(k, "view", vx, fy, styles, style(), true, o -> { cs().guiStyle.set(o); EvadeClient.config.save(); });
        segRects(k, "view", vx, fy, styles);
        UiFont.caps(k.ctx, k.tr, "View", vx - 8 - UiFont.capsWidth(k.tr, "View", Role.CAPS, 0.9f, k.smooth), fy + 8, Tokens.N600, Role.CAPS, 0.9f, k.smooth);
        Paint.fadeLine(k.ctx, cx - 8, cy - 7, cx + cw + 8, Theme.alpha(0xFFFFFFFF, 0.06f));
    }

    private void segRects(Kit k, String id, int x, int y, List<String> options) {
        int sx = x + 2;
        for (String o : options) {
            int w = k.width(o, Role.LABEL) + 20;
            rects.put(id + ":" + o, new int[]{sx, y, sx + w, y + Segmented.H});
            sx += w;
        }
    }

    private void header(Kit k, String title, String count, int x, int y) {
        int ty = y + 8;
        if (title.startsWith("★")) {
            Icons.draw(k.ctx, "star", x, ty - 1, 9, Tokens.WARN);
            title = title.substring(2).toUpperCase(Locale.ROOT);
            x += 13;
        }
        UiFont.caps(k.ctx, k.tr, title, x, ty, Tokens.N700, Role.CAPS, 0.9f, k.smooth);
        int w = UiFont.capsWidth(k.tr, title, Role.CAPS, 0.9f, k.smooth);
        k.text(count, x + w + 7, ty, Tokens.N600, Role.SMALL);
    }

    private void empty(Kit k, int cx, int y) {
        String q = host.search().trim();
        String title, line;
        if (!q.isEmpty()) { title = "No mods match “" + q + "”"; line = host.enabledOnly() ? "Only enabled mods are shown: try All." : "Try fewer words, or a setting's name."; }
        else if (host.enabledOnly()) { title = "Nothing enabled here"; line = "Switch the filter back to All to see every mod."; }
        else if (host.page() == MenuPage.PINNED) { title = "Nothing pinned yet"; line = "Click the star next to a mod's name to pin it."; }
        else { title = "Nothing here yet"; line = null; }
        EmptyState.draw(k, "empty", cx, y, q.isEmpty() ? "star" : "search", host.fit(title, Role.TITLE, host.cw() - 40), line,
                host.enabledOnly() ? "Show all" : null, () -> host.enabledOnly(false));
    }

    // ------------------------------------------------------------------ pieces

    private static String blurb(Module mod) {
        if (net.evadeclient.util.ServerRules.isDisabled(mod)) return "Disabled by this server";
        String addon = net.evadeclient.addon.EvadeAddons.ownerName(mod);
        return addon == null ? mod.getDescription() : "Addon: " + addon + "  ·  " + mod.getDescription();
    }

    private static boolean blocked(Module mod) { return net.evadeclient.util.ServerRules.isDisabled(mod); }

    /** The row/card body's click: left toggles, right opens the settings. */
    private Kit.ClickAction bodyClick(Module mod) {
        return (b, mx, my) -> {
            if (b == 0) mod.toggle();
            else if (b == 1) host.openModule(mod);
        };
    }

    /** The pin star after the name (gold when pinned, faint while the row is hovered). */
    private void star(Kit k, Module mod, int x, float cy, float hov) {
        boolean pinned = MenuState.isPinned(mod);
        if (!pinned && hov < 0.02f) return;
        String id = "star:" + mod.getName();
        boolean over = k.hovered(id);
        int col = pinned ? Tokens.WARN : over ? Tokens.N900 : Theme.alpha(Tokens.N600, hov);
        Icons.draw(k.ctx, "star", x, Math.round(cy - 5), 10, col);
        k.hit(id, x - 2, Math.round(cy - 7), x + 12, Math.round(cy + 7), (b, mx, my) -> {
            if (b != 0) return;
            boolean on = MenuState.togglePin(mod);
            Notifications.push(mod.getName(), on ? "Pinned" : "Unpinned", on);
        }, false);
        k.tooltip(id, pinned ? "Unpin" : "Pin to the top of All mods", null, x - 2, Math.round(cy - 7), x + 12, Math.round(cy + 7));
    }

    /** The icon tile: the module glyph, accent when on. */
    private static void tile(Kit k, Module mod, int x, float cy, int size, float hov) {
        Paint.rect(k.ctx, x, cy - size / 2f, x + size, cy + size / 2f, Tokens.R_XS + 1, Theme.lerpColor(Tokens.N250, Tokens.N300, hov));
        int is = Math.round(size * 0.58f);
        Icons.draw(k.ctx, ModuleIcons.of(mod), x + (size - is) / 2, Math.round(cy - is / 2f), is, mod.isEnabled() ? Tokens.A400 : Tokens.N800);
    }

    /** Key chip, gear and switch, right to left from {@code right}; returns the x left of them. */
    private int trailing(Kit k, Module mod, int right, float cy, boolean gear) {
        String n = mod.getName();
        right -= Toggle.MD_W;
        Toggle.draw(k, "tg:" + n, right, Math.round(cy - Toggle.MD_H / 2f), false, mod.isEnabled(), true, mod::toggle);
        right -= Tokens.S3;
        if (gear) {
            right -= 22;
            Button.icon(k, "gear:" + n, right, Math.round(cy - 11), 22, "settings", "Settings and keybind", true, () -> host.openModule(mod));
            right -= Tokens.S2;
        }
        if (mod.getKey() != GLFW.GLFW_KEY_UNKNOWN) {
            String key = KeyChip.label(net.evadeclient.util.Keybinds.comboName(mod.getKey(), mod.getBindMods()));
            int w = KeyChip.width(k, KeyChip.Mode.BOUND, key);
            right -= w;
            KeyChip.draw(k, "kc:" + n, right, Math.round(cy - KeyChip.H / 2f), KeyChip.Mode.BOUND, key,
                    mod.isHoldBind() ? "Hold to use · click to change" : "Toggle key · click to change", () -> host.openModule(mod));
            right -= Tokens.S3;
        }
        return right;
    }

    private void row(Kit k, Module mod, int x, int y, int w, int h) {
        String id = "row:" + mod.getName();
        int st = k.state(id, x, y, x + w, y + h, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float press = (st & Kit.PRESSED) != 0 ? 0.5f / Theme.deviceScale(k.ctx) : 0f;
        Paint.surface(k.ctx, x, y + press, x + w, y + h + press, Tokens.R_SM,
                Tokens.E2.noAmbient().withFill(Theme.lerpColor(Tokens.N200, Tokens.N250, hov)), hov, k.mx - x);
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + h, Tokens.R_SM, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + w, y + h, bodyClick(mod), true);
        rects.put(id, new int[]{x, y, x + w, y + h});
        float cy = y + h / 2f + press;
        int tx = x + Tokens.S4;
        tile(k, mod, tx, cy, 24, hov);
        tx += 24 + 10;
        int right = trailing(k, mod, x + w - Tokens.S4, cy, true);
        int avail = right - tx - 8;
        String name = host.fit(mod.getName(), Role.STRONG, Math.min(avail, 180));
        int nw = k.width(name, Role.STRONG);
        k.textCentredY(name, tx, cy, Tokens.N950, Role.STRONG);
        star(k, mod, tx + nw + 6, cy, hov);
        int dx = tx + Math.max(nw + 24, 150);
        if (avail - (dx - tx) > 30) {
            String full = blurb(mod), d = host.fit(full, Role.SMALL, right - dx - 8);
            if (blocked(mod)) Icons.draw(k.ctx, "warn", dx - 13, Math.round(cy - 5), 10, Tokens.WARN);
            k.textCentredY(d, dx, cy, blocked(mod) ? Tokens.WARN : Tokens.N700, Role.SMALL);
            if (!d.equals(full)) k.tooltip(id, mod.getName(), full, x, y, x + w, y + h);
        }
    }

    private void compact(Kit k, Module mod, int x, int y, int w, int h) {
        String id = "row:" + mod.getName();
        int st = k.state(id, x, y, x + w, y + h, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        Paint.surface(k.ctx, x, y, x + w, y + h, Tokens.R_SM, Tokens.E2.noAmbient().withFill(Theme.lerpColor(Tokens.N200, Tokens.N250, hov)), hov, k.mx - x);
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + h, Tokens.R_SM, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + w, y + h, bodyClick(mod), true);
        rects.put(id, new int[]{x, y, x + w, y + h});
        float cy = y + h / 2f;
        Icons.draw(k.ctx, ModuleIcons.of(mod), x + 8, Math.round(cy - 6), 12, mod.isEnabled() ? Tokens.A400 : Tokens.N700);
        int right = x + w - 6;
        right -= Toggle.MD_W;
        Toggle.draw(k, "tg:" + mod.getName(), right, Math.round(cy - Toggle.MD_H / 2f), false, mod.isEnabled(), true, mod::toggle);
        right -= 24;
        Button.icon(k, "gear:" + mod.getName(), right, Math.round(cy - 10), 20, "settings", "Settings and keybind", true, () -> host.openModule(mod));
        String name = host.fit(mod.getName(), Role.STRONG, right - (x + 26) - 18);
        k.textCentredY(name, x + 26, cy, blocked(mod) ? Tokens.WARN : Tokens.N950, Role.STRONG);
        star(k, mod, x + 26 + k.width(name, Role.STRONG) + 6, cy, hov);
        k.tooltip(id, mod.getName(), blurb(mod), x, y, x + w, y + h);
    }

    private void card(Kit k, Module mod, int x, int y, int w, int h, boolean mixed) {
        String id = "row:" + mod.getName();
        Surface.card(k, id, x, y, x + w, y + h, false, true, null);
        try {
            k.hit(id, x, y, x + w, y + h, bodyClick(mod), true);
            rects.put(id, new int[]{x, y, x + w, y + h});
            float hov = k.anim(id + "#h", k.hovered(id) ? 1f : 0f);
            int pad = Tokens.S5;
            float cy = y + pad + 13;
            tile(k, mod, x + pad, cy, 26, hov);
            int right = x + w - pad - Toggle.MD_W;
            Toggle.draw(k, "tg:" + mod.getName(), right, Math.round(cy - Toggle.MD_H / 2f), false, mod.isEnabled(), true, mod::toggle);
            int tx = x + pad + 26 + 10;
            String name = host.fit(mod.getName(), Role.STRONG, right - tx - 22);
            k.textCentredY(name, tx, cy, Tokens.N950, Role.STRONG);
            star(k, mod, tx + k.width(name, Role.STRONG) + 6, cy, hov);
            String full = blurb(mod);
            String key = w + ":" + full;
            List<String> lines = wraps.get(key);
            if (lines == null) {
                if (wraps.size() > 600) wraps.clear();
                lines = k.wrap(full, Role.SMALL, w - 2 * pad, 2);
                wraps.put(key, lines);
            }
            int ly = y + pad + 32;
            for (String l : lines) { k.text(l, x + pad, ly, blocked(mod) ? Tokens.WARN : Tokens.N700, Role.SMALL); ly += 10; }
            // Footer: the category (only where the page mixes them), the key chip and the gear.
            float fy = y + h - pad - 8;
            if (mixed) Badge.draw(k, x + pad, Math.round(fy - Badge.H / 2f), mod.getCategory().displayName, Badge.Tone.NEUTRAL);
            int gx = x + w - pad + 3 - 22;
            Button.icon(k, "gear:" + mod.getName(), gx, Math.round(fy - 11), 22, "settings", "Settings and keybind", true, () -> host.openModule(mod));
            if (mod.getKey() != GLFW.GLFW_KEY_UNKNOWN) {
                String kn = KeyChip.label(net.evadeclient.util.Keybinds.comboName(mod.getKey(), mod.getBindMods()));
                int kw = KeyChip.width(k, KeyChip.Mode.BOUND, kn);
                KeyChip.draw(k, "kc:" + mod.getName(), gx - 4 - kw, Math.round(fy - KeyChip.H / 2f), KeyChip.Mode.BOUND, kn, null, () -> host.openModule(mod));
            }
        } finally {
            Surface.endCard(k);
        }
    }

    // ------------------------------------------------------------------ keyboard

    /** Up / Down (and Left / Right in grids) move the keyboard focus through the list, scrolling it into view. */
    public boolean keyNav(int key) {
        Kit k = host.kit();
        String f = k.focused();
        if (f == null) return false;
        if (f.equals(net.evadeclient.gui.EvadeMenu.SEARCH) && key == GLFW.GLFW_KEY_DOWN) {
            for (Item it : items) if (it.kind == K_MOD) {
                k.focus("row:" + it.mod.getName(), true);
                host.scroll().to(it.y - host.cy() - HEADER_H);
                return true;
            }
            return false;
        }
        int colon = f.indexOf(':');
        if (colon < 0) return false;
        String kind = f.substring(0, colon + 1);
        if (!kind.equals("row:") && !kind.equals("gear:") && !kind.equals("tg:") && !kind.equals("kc:")) return false;
        String name = f.substring(colon + 1);
        List<Item> mods = new ArrayList<>();
        int at = -1;
        for (Item it : items) if (it.kind == K_MOD) { if (it.mod.getName().equals(name)) at = mods.size(); mods.add(it); }
        if (at < 0) return false;
        boolean cards = style().equals("Cards"), compact = style().equals("Compact");
        int cols = cards ? (host.cw() >= 600 ? 3 : 2) : compact ? (host.cw() >= 380 ? 2 : 1) : 1;
        int to = switch (key) {
            case GLFW.GLFW_KEY_DOWN -> at + cols;
            case GLFW.GLFW_KEY_UP -> at - cols;
            case GLFW.GLFW_KEY_RIGHT -> cols > 1 ? at + 1 : -1;
            case GLFW.GLFW_KEY_LEFT -> cols > 1 ? at - 1 : -1;
            default -> Integer.MIN_VALUE;
        };
        if (to == Integer.MIN_VALUE) return false;
        if (to < 0 || to >= mods.size()) return true;
        Item it = mods.get(to);
        k.focus("row:" + it.mod.getName(), true);
        MenuScroll s = host.scroll();
        int vy = it.y - host.cy();
        if (vy < s.target + 4) s.to(vy - HEADER_H);
        else if (vy + it.h > s.target + host.ch() - 4) s.to(vy + it.h - host.ch() + 8);
        return true;
    }

    /** Back from a module page: focus its row again (shown when the way back was the keyboard). */
    public void returnFocus(Module mod, boolean visible) {
        pendingFocus = mod;
        pendingVisible = visible;
    }

    /** Test hook: where a control of this page was drawn last frame (design px), or null. */
    public int[] rectForTest(String id) { return rects.get(id); }
}
