package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Evade menu: a centred window with a sidebar of pages, a search bar, a grid of mod cards with
 * animated switches, and a settings page per mod. Black and red throughout.
 */
public class EvadeMenu extends Screen {
    private enum Page {
        ALL("All Mods", null), COMBAT("Combat", Category.COMBAT), PVP("PvP macros", Category.PVP),
        MOVEMENT("Movement", Category.MOVEMENT), SERVER("Server-facing", Category.SERVER), VISUALS("Visuals", Category.VISUALS),
        WORLD("World", Category.WORLD), UTILITY("Utility", Category.UTILITY), HUD("HUD", Category.HUD),
        MODS("Mods", Category.MODS), MISC("Misc", Category.MISC), SETTINGS("Settings", Category.CLIENT);

        final String label;
        final Category category;

        Page(String label, Category category) { this.label = label; this.category = category; }
    }

    private static Page page = Page.ALL;
    private static Module selected;

    private String search = "";
    private boolean searchFocused;
    private double scroll, scrollTarget;
    private long openedAt;
    private final Map<Object, Float> anim = new HashMap<>();
    private long lastFrame = System.nanoTime();
    private float dt;

    /** The module (keybind) or KeySetting waiting for a key press. */
    private Object listening;
    private NumberSetting dragging;
    private int dragX, dragW;

    // layout (recomputed each frame)
    private int wx, wy, ww, wh, cx, cy, cw, ch;
    private static final int SIDEBAR = 118, CARD_H = 56, GAP = 8, ROW_H = 24, SLIDER_W = 120, NAV_STEP = 18;

    public EvadeMenu() {
        super(Text.literal("Evade"));
        selected = null; // opening the menu always starts on the grid, not wherever it was last
    }

    /** Open the menu, optionally straight onto a module's settings page. */
    public static EvadeMenu open(Module select) {
        EvadeMenu menu = new EvadeMenu();
        if (select != null) selected = select;
        return menu;
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
    }

    private ClientSettings settings() { return EvadeClient.modules.get(ClientSettings.class); }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (settings().blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xB0000000, 0xD0140005);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        scroll += (scrollTarget - scroll) * (1 - Math.exp(-18 * dt));

        ww = Math.min(width - 30, 680);
        wh = Math.min(height - 30, 380);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        cx = wx + SIDEBAR + 14;
        cy = wy + 44;
        cw = ww - SIDEBAR - 28;
        ch = wh - 54;

        float open = settings().animations.get() ? ease(Math.min(1, (System.currentTimeMillis() - openedAt) / 220f)) : 1;
        var m = ctx.getMatrices();
        m.pushMatrix();
        float s = 0.94f + 0.06f * open;
        m.translate(width / 2f, height / 2f + (1 - open) * 14);
        m.scale(s, s);
        m.translate(-width / 2f, -height / 2f);

        if (panelsMode()) {
            renderPanels(ctx, mouseX, mouseY);
        } else {
            // window with a soft red glow
            Theme.round(ctx, wx - 3, wy - 3, wx + ww + 3, wy + wh + 3, 0x30E11D2E);
            Theme.roundBordered(ctx, wx, wy, wx + ww, wy + wh, Theme.BG, Theme.BORDER);
            renderSidebar(ctx, mouseX, mouseY);
            renderTopBar(ctx, mouseX, mouseY);
            if (selected != null) renderSettingsPage(ctx, mouseX, mouseY, selected);
            else if (page == Page.SETTINGS) renderSettingsPage(ctx, mouseX, mouseY, settings());
            else renderGrid(ctx, mouseX, mouseY);
        }

        m.popMatrix();
    }

    private void renderSidebar(DrawContext ctx, int mx, int my) {
        TextRenderer tr = textRenderer;
        ctx.fill(wx + 1, wy + 1, wx + SIDEBAR, wy + wh - 1, Theme.SIDEBAR);
        ctx.fill(wx + SIDEBAR, wy + 1, wx + SIDEBAR + 1, wy + wh - 1, Theme.BORDER);

        // logo
        net.evadeclient.util.Logo.lockup(ctx, wx + 12, wy + 11, 22);
        small(ctx, "C L I E N T", wx + 40, wy + 31, Theme.TEXT_FAINT, 0.65f);
        ctx.fill(wx + 12, wy + 40, wx + 34, wy + 41, Theme.RED);

        int y = wy + 52;
        for (Page p : Page.values()) {
            if (p == Page.SETTINGS) continue;
            navItem(ctx, p.label, countLabel(p), y, mx, my, page == p && selected == null);
            y += NAV_STEP;
        }
        y += 8;
        ctx.fill(wx + 12, y - 4, wx + SIDEBAR - 12, y - 3, Theme.BORDER);
        navItem(ctx, "Edit HUD Layout", null, y, mx, my, false);
        y += 20;
        navItem(ctx, "Settings", null, y, mx, my, page == Page.SETTINGS && selected == null);

        String user = client != null && client.getSession() != null ? client.getSession().getUsername() : "";
        small(ctx, user, wx + 12, wy + wh - 22, Theme.TEXT_DIM, 0.75f);
        small(ctx, "v" + EvadeClient.VERSION, wx + 12, wy + wh - 13, Theme.TEXT_FAINT, 0.75f);
    }

    private String countLabel(Page p) {
        List<Module> list = modulesFor(p, "");
        long on = list.stream().filter(Module::isEnabled).count();
        return on + "/" + list.size();
    }

    private void navItem(DrawContext ctx, String label, String badge, int y, int mx, int my, boolean active) {
        boolean hover = mx >= wx + 6 && mx < wx + SIDEBAR - 6 && my >= y - 4 && my < y + 14;
        float t = animate("nav:" + label, active ? 1 : hover ? 0.5f : 0, 16);
        if (t > 0.01f) Theme.round(ctx, wx + 6, y - 4, wx + SIDEBAR - 6, y + 14, Theme.alpha(0xFF2A0B10, t));
        if (active) ctx.fill(wx + 6, y - 2, wx + 8, y + 12, Theme.RED);
        ctx.drawText(textRenderer, label, wx + 14, y + 1, active ? Theme.TEXT : Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, t), false);
        if (badge != null) {
            int bw = Math.round(textRenderer.getWidth(badge) * 0.75f);
            small(ctx, badge, wx + SIDEBAR - 12 - bw, y + 3, active ? Theme.RED_BRIGHT : Theme.TEXT_FAINT, 0.75f);
        }
    }

    private void renderTopBar(DrawContext ctx, int mx, int my) {
        String title = selected != null ? selected.getName() : page.label;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, wy + 14);
        m.scale(1.5f, 1.5f);
        ctx.drawText(textRenderer, title, 0, 0, Theme.TEXT, false);
        m.popMatrix();

        // close button
        int bx = wx + ww - 22, by = wy + 10;
        boolean hc = mx >= bx && mx < bx + 14 && my >= by && my < by + 14;
        Theme.round(ctx, bx, by, bx + 14, by + 14, hc ? Theme.RED : 0xFF1A1A1F);
        ctx.drawText(textRenderer, "x", bx + 4, by + 3, Theme.TEXT, false);

        if (selected != null || page == Page.SETTINGS) return;
        int sw = 150, sx = bx - 8 - sw, sy = wy + 9;
        boolean hs = mx >= sx && mx < sx + sw && my >= sy && my < sy + 16;
        Theme.roundBordered(ctx, sx, sy, sx + sw, sy + 16, 0xFF111114, searchFocused ? Theme.RED : hs ? 0xFF3A3A44 : Theme.BORDER);
        String shown = search.isEmpty() && !searchFocused ? "Search mods..." : search;
        boolean caret = searchFocused && (System.currentTimeMillis() / 500) % 2 == 0;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(shown, sw - 14) + (caret ? "_" : ""), sx + 6, sy + 4,
                search.isEmpty() && !searchFocused ? Theme.TEXT_FAINT : Theme.TEXT, false);
    }

    private List<Module> modulesFor(Page p, String filter) {
        List<Module> out = new ArrayList<>();
        String f = filter.toLowerCase(Locale.ROOT);
        for (Module mod : EvadeClient.modules.all()) {
            if (mod.getCategory() == Category.CLIENT) continue;
            if (p.category != null && mod.getCategory() != p.category) continue;
            if (!f.isEmpty() && !mod.getName().toLowerCase(Locale.ROOT).contains(f)
                    && !mod.getDescription().toLowerCase(Locale.ROOT).contains(f)) continue;
            out.add(mod);
        }
        return out;
    }

    /** The Doomsday style is a full-screen multi-panel ClickGUI (one panel per category) instead of a window. */
    private boolean panelsMode() { return settings().guiStyle.get().equals("Doomsday") && selected == null && page != Page.SETTINGS; }

    private static final int P_W = 128, P_HEAD = 15, P_ROW = 12, P_GAP = 8;

    private record PRow(Module mod, int y) {}
    private record Panel(String title, int x, int y, int w, int h, List<PRow> rows) {}

    /** Saved panel positions per category (drag a panel's header to move it). Null until first loaded. */
    private static java.util.Map<String, int[]> PANEL_POS;
    private String dragTitle;
    private int dragOX, dragOY;

    private static java.nio.file.Path posFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("panels.json");
    }

    /** Load saved positions once, then fill in a tidy masonry default for any category without one. */
    private void ensurePositions() {
        if (PANEL_POS == null) {
            PANEL_POS = new java.util.LinkedHashMap<>();
            try {
                if (java.nio.file.Files.exists(posFile())) {
                    var type = new com.google.gson.reflect.TypeToken<java.util.LinkedHashMap<String, int[]>>() {}.getType();
                    java.util.Map<String, int[]> m = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(posFile()), type);
                    if (m != null) PANEL_POS.putAll(m);
                }
            } catch (Exception ignored) {}
        }
        int margin = 30, top = 44;
        int cols = Math.max(1, Math.min((width - 2 * margin + P_GAP) / (P_W + P_GAP), 6));
        int startX = (width - (cols * P_W + (cols - 1) * P_GAP)) / 2;
        int[] colBase = new int[cols];
        java.util.Arrays.fill(colBase, top);
        for (Page p : Page.values()) {
            if (p == Page.ALL || p == Page.SETTINGS) continue;
            List<Module> mods = modulesFor(p, "");
            if (mods.isEmpty()) continue;
            int h = P_HEAD + mods.size() * P_ROW + 3;
            int col = 0;
            for (int i = 1; i < cols; i++) if (colBase[i] < colBase[col]) col = i;
            PANEL_POS.putIfAbsent(p.label, new int[]{startX + col * (P_W + P_GAP), colBase[col]});
            colBase[col] += h + P_GAP;
        }
    }

    private void savePositions() {
        try {
            java.nio.file.Files.createDirectories(posFile().getParent());
            java.nio.file.Files.writeString(posFile(), new com.google.gson.Gson().toJson(PANEL_POS));
        } catch (Exception ignored) {}
    }

    /** One panel per category at its saved position; headers are drag handles. */
    private List<Panel> layoutPanels() {
        ensurePositions();
        List<Panel> out = new ArrayList<>();
        for (Page p : Page.values()) {
            if (p == Page.ALL || p == Page.SETTINGS) continue;
            List<Module> mods = modulesFor(p, search);
            if (mods.isEmpty()) continue;
            int[] pos = PANEL_POS.getOrDefault(p.label, new int[]{40, 44});
            int x = pos[0], y = pos[1];
            int h = P_HEAD + mods.size() * P_ROW + 3;
            List<PRow> rows = new ArrayList<>();
            for (int i = 0; i < mods.size(); i++) rows.add(new PRow(mods.get(i), y + P_HEAD + i * P_ROW));
            out.add(new Panel(p.label, x, y, P_W, h, rows));
        }
        return out;
    }

    private void renderPanels(DrawContext ctx, int mx, int my) {
        // top menu bar
        small(ctx, "MODULES", width / 2 - 150, 16, Theme.TEXT, 0.9f);
        boolean hSet = topBtnHover(mx, my, 0), hHud = topBtnHover(mx, my, 1), hX = topBtnHover(mx, my, 2);
        small(ctx, "SETTINGS", topBtnX(0), 16, hSet ? Theme.RED_BRIGHT : Theme.TEXT_DIM, 0.9f);
        small(ctx, "EDIT HUD", topBtnX(1), 16, hHud ? Theme.RED_BRIGHT : Theme.TEXT_DIM, 0.9f);
        small(ctx, "CLOSE", topBtnX(2), 16, hX ? Theme.RED_BRIGHT : Theme.TEXT_DIM, 0.9f);
        // centred search box
        int sw = 150, sx = (width - sw) / 2, sy = 28;
        Theme.roundBordered(ctx, sx, sy, sx + sw, sy + 16, 0xF2111114, searchFocused ? Theme.RED : Theme.BORDER);
        boolean caret = searchFocused && (System.currentTimeMillis() / 500) % 2 == 0;
        String shown = search.isEmpty() && !searchFocused ? "Search" : search + (caret ? "_" : "");
        ctx.drawText(textRenderer, shown, sx + (search.isEmpty() && !searchFocused ? (sw - textRenderer.getWidth("Search")) / 2 : 6), sy + 4,
                search.isEmpty() && !searchFocused ? Theme.TEXT_FAINT : Theme.TEXT, false);

        for (Panel panel : layoutPanels()) {
            if (panel.y + panel.h < 40 || panel.y > height) continue;
            Theme.roundBordered(ctx, panel.x, panel.y, panel.x + panel.w, panel.y + panel.h, Theme.BG, Theme.BORDER);
            // header (drag handle): title, a grip, and a red underline
            boolean hHead = mx >= panel.x && mx < panel.x + panel.w && my >= panel.y && my < panel.y + P_HEAD;
            ctx.fill(panel.x + 1, panel.y + 1, panel.x + panel.w - 1, panel.y + P_HEAD, hHead || panel.title.equals(dragTitle) ? 0xFF201013 : Theme.SIDEBAR);
            int tw = textRenderer.getWidth(panel.title);
            ctx.drawText(textRenderer, panel.title, panel.x + (panel.w - tw) / 2, panel.y + 3, Theme.TEXT, false);
            small(ctx, "⋮⋮", panel.x + 5, panel.y + 4, Theme.TEXT_FAINT, 0.9f);
            ctx.fill(panel.x + 1, panel.y + P_HEAD, panel.x + panel.w - 1, panel.y + P_HEAD + 1, Theme.RED);
            for (PRow r : panel.rows) {
                boolean hover = mx >= panel.x && mx < panel.x + panel.w && my >= r.y && my < r.y + P_ROW;
                float on = animate("pon:" + r.mod.getName(), r.mod.isEnabled() ? 1 : 0, 16);
                if (on > 0.01f) ctx.fill(panel.x + 1, r.y, panel.x + panel.w - 1, r.y + P_ROW, Theme.alpha(0xFF3C0F16, on));
                if (hover) ctx.fill(panel.x + 1, r.y, panel.x + panel.w - 1, r.y + P_ROW, 0x30FFFFFF);
                if (on > 0.5f) ctx.fill(panel.x + 1, r.y + 1, panel.x + 2, r.y + P_ROW - 1, Theme.RED);
                ctx.drawText(textRenderer, textRenderer.trimToWidth(r.mod.getName(), panel.w - 10),
                        panel.x + 6, r.y + 2, on > 0.5f ? Theme.TEXT : Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, hover ? 1 : 0), false);
            }
        }
        small(ctx, "Drag a panel's header to move it", 8, height - 22, Theme.TEXT_FAINT, 0.8f);
        small(ctx, "Evade v" + EvadeClient.VERSION, width - 90, height - 12, Theme.TEXT_FAINT, 0.8f);
    }

    private int topBtnX(int i) { return width / 2 - 70 + i * 60; }
    private boolean topBtnHover(int mx, int my, int i) { int x = topBtnX(i); return my >= 14 && my < 24 && mx >= x && mx < x + 50; }

    private boolean clickPanels(double mx, double my, int button) {
        int sw = 150, sx = (width - sw) / 2, sy = 28;
        searchFocused = mx >= sx && mx < sx + sw && my >= sy && my < sy + 16;
        if (searchFocused) return true;
        if (topBtnHover((int) mx, (int) my, 0)) { go(Page.SETTINGS); return true; }
        if (topBtnHover((int) mx, (int) my, 1)) { client.setScreen(new HudEditorScreen(this)); return true; }
        if (topBtnHover((int) mx, (int) my, 2)) { close(); return true; }
        for (Panel panel : layoutPanels()) {
            // header → start dragging this panel
            if (mx >= panel.x && mx < panel.x + panel.w && my >= panel.y && my < panel.y + P_HEAD) {
                dragTitle = panel.title;
                dragOX = (int) mx - panel.x;
                dragOY = (int) my - panel.y;
                return true;
            }
            for (PRow r : panel.rows) {
                if (mx >= panel.x && mx < panel.x + panel.w && my >= r.y && my < r.y + P_ROW) {
                    if (button == 1) { selected = r.mod; scroll = scrollTarget = 0; }
                    else if (button == 0) r.mod.toggle();
                    return true;
                }
            }
        }
        return true;
    }

    private int columns() { return cw >= 420 ? 3 : 2; }

    // ---- GUI style: "Cards" (big cards), "Compact" (one thin row per line), "Doomsday" (two dense columns).
    private String style() { return settings().guiStyle.get(); }
    private boolean cards() { return style().equals("Cards"); }
    private int cols() { return cards() ? columns() : style().equals("Doomsday") ? 2 : 1; }
    private int rowH() { return cards() ? CARD_H : style().equals("Doomsday") ? 16 : 22; }
    private int rowGap() { return cards() ? GAP : 3; }

    private void renderGrid(DrawContext ctx, int mx, int my) {
        List<Module> mods = modulesFor(page, search);
        int cols = cols();
        int rh = rowH(), rg = rowGap();
        int cardW = (cw - rg * (cols - 1)) / cols;
        int rows = (mods.size() + cols - 1) / cols;
        int contentH = rows * (rh + rg);
        scrollTarget = Math.max(0, Math.min(scrollTarget, contentH - ch));

        ctx.enableScissor(cx - 2, cy, cx + cw + 2, cy + ch);
        for (int i = 0; i < mods.size(); i++) {
            Module mod = mods.get(i);
            int x = cx + (i % cols) * (cardW + rg);
            int y = cy + (i / cols) * (rh + rg) - (int) Math.round(scroll);
            if (y + rh < cy || y > cy + ch) continue;
            if (cards()) renderCard(ctx, mod, x, y, cardW, mx, my);
            else renderRow(ctx, mod, x, y, cardW, rh, mx, my);
        }
        if (mods.isEmpty()) {
            String msg = "No mods match \"" + search + "\"";
            ctx.drawText(textRenderer, msg, cx + (cw - textRenderer.getWidth(msg)) / 2, cy + 30, Theme.TEXT_DIM, false);
        }
        ctx.disableScissor();

        if (contentH > ch) {
            int barH = Math.max(20, ch * ch / contentH);
            int barY = cy + (int) ((ch - barH) * (scroll / (contentH - ch)));
            ctx.fill(cx + cw + 6, cy, cx + cw + 8, cy + ch, 0xFF16161A);
            ctx.fill(cx + cw + 6, barY, cx + cw + 8, barY + barH, Theme.RED);
        }
    }

    private void renderCard(DrawContext ctx, Module mod, int x, int y, int w, int mx, int my) {
        boolean inView = my >= cy && my < cy + ch;
        boolean hover = inView && mx >= x && mx < x + w && my >= y && my < y + CARD_H;
        float h = animate("hover:" + mod.getName(), hover ? 1 : 0, 14);
        float on = animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14);

        int border = Theme.lerpColor(Theme.lerpColor(Theme.BORDER, 0xFF5A1018, on), Theme.RED, h * 0.8f);
        Theme.roundBordered(ctx, x, y, x + w, y + CARD_H, Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h), border);
        if (on > 0.01f) ctx.fill(x + 1, y + 3, x + 2, y + CARD_H - 3, Theme.alpha(Theme.RED, on));

        small(ctx, mod.getCategory().displayName.toUpperCase(Locale.ROOT), x + 8, y + 6, Theme.alpha(Theme.RED, 0.55f + 0.45f * on), 0.65f);
        if (mod.getKey() != GLFW.GLFW_KEY_UNKNOWN) {
            String k = KeySetting.keyName(mod.getKey());
            int kw = Math.round(textRenderer.getWidth(k) * 0.65f);
            Theme.round(ctx, x + w - kw - 12, y + 4, x + w - 5, y + 13, 0xFF22222A);
            small(ctx, k, x + w - kw - 9, y + 6, Theme.TEXT_DIM, 0.65f);
        }
        ctx.drawText(textRenderer, textRenderer.trimToWidth(mod.getName(), w - 14), x + 8, y + 15, Theme.TEXT, false);
        small(ctx, trimScaled(mod.getDescription(), w - 14, 0.7f), x + 8, y + 27, Theme.TEXT_DIM, 0.7f);

        Theme.toggle(ctx, x + 8, y + CARD_H - 15, on);
        if (!mod.getSettings().isEmpty()) {
            int gx = x + w - 16, gy = y + CARD_H - 16;
            boolean hg = inView && mx >= gx - 3 && mx < gx + 12 && my >= gy - 2 && my < gy + 12;
            Theme.round(ctx, gx - 3, gy - 2, gx + 12, gy + 12, hg ? Theme.RED : 0xFF202027);
            ctx.drawText(textRenderer, "⚙", gx + 1, gy + 1, Theme.TEXT, false);
        }
    }

    /** A thin module row for the Compact and Doomsday styles. */
    private void renderRow(DrawContext ctx, Module mod, int x, int y, int w, int h, int mx, int my) {
        boolean doom = style().equals("Doomsday");
        boolean inView = my >= cy && my < cy + ch;
        boolean hover = inView && mx >= x && mx < x + w && my >= y && my < y + h;
        float hv = animate("hover:" + mod.getName(), hover ? 1 : 0, 14);
        float on = animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14);

        if (doom) {
            // terminal style: full-width row highlight, red left bar, a ">" prefix when on
            if (hv > 0.01f || on > 0.01f) ctx.fill(x, y, x + w, y + h, Theme.alpha(0xFF24070C, Math.max(hv * 0.8f, on * 0.5f)));
            ctx.fill(x, y, x + 1, y + h, Theme.lerpColor(Theme.BORDER, Theme.RED, Math.max(on, hv)));
            int tc = Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, Math.max(on, hv * 0.7f));
            small(ctx, (on > 0.5f ? "> " : "  ") + mod.getName(), x + 5, y + 4, on > 0.5f ? Theme.RED_BRIGHT : tc, 0.9f);
        } else {
            // compact style: rounded row, name left, soft on-tint
            int fill = Theme.lerpColor(Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, hv), 0xFF24090E, on * 0.7f);
            int border = Theme.lerpColor(Theme.BORDER, Theme.RED, hv * 0.7f);
            Theme.roundBordered(ctx, x, y, x + w, y + h, fill, border);
            if (on > 0.01f) ctx.fill(x + 1, y + 2, x + 2, y + h - 2, Theme.alpha(Theme.RED, on));
            ctx.drawText(textRenderer, textRenderer.trimToWidth(mod.getName(), w - 46), x + 8, y + (h - 8) / 2, Theme.TEXT, false);
        }
        // toggle on the right, gear just left of it when the mod has settings
        Theme.toggle(ctx, x + w - 24, y + (h - 10) / 2, on);
        if (!mod.getSettings().isEmpty()) {
            int gx = x + w - 40, gy = y + (h - 10) / 2;
            boolean hg = inView && mx >= gx - 2 && mx < gx + 11 && my >= gy - 1 && my < gy + 11;
            small(ctx, "⚙", gx, gy + 1, hg ? Theme.RED_BRIGHT : Theme.TEXT_DIM, 0.9f);
        }
    }

    private List<Object> rowsFor(Module mod) {
        List<Object> rows = new ArrayList<>();
        if (mod.getCategory() != Category.CLIENT) rows.add("keybind");
        rows.addAll(mod.getSettings());
        return rows;
    }

    private int settingsTop() { return selected != null ? cy + 24 : cy; }

    private void renderSettingsPage(DrawContext ctx, int mx, int my, Module mod) {
        TextRenderer tr = textRenderer;
        if (selected != null) {
            boolean hb = mx >= cx && mx < cx + 46 && my >= cy && my < cy + 16;
            Theme.round(ctx, cx, cy, cx + 46, cy + 16, hb ? Theme.RED : 0xFF1A1A1F);
            ctx.drawText(tr, "‹ Back", cx + 7, cy + 4, Theme.TEXT, false);
            small(ctx, trimScaled(mod.getDescription(), cw - 90, 0.8f), cx + 54, cy + 5, Theme.TEXT_DIM, 0.8f);
            Theme.toggle(ctx, cx + cw - 22, cy + 3, animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14));
        }
        int top = settingsTop();
        List<Object> rows = rowsFor(mod);
        int contentH = rows.size() * ROW_H;
        int viewH = cy + ch - top;
        scrollTarget = Math.max(0, Math.min(scrollTarget, contentH - viewH));

        ctx.enableScissor(cx - 2, top, cx + cw + 2, cy + ch);
        int y = top - (int) Math.round(scroll);
        for (Object row : rows) {
            boolean hover = mx >= cx && mx < cx + cw && my >= y && my < y + ROW_H - 2 && my >= top;
            Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 3, hover ? 0xFF18181D : 0xFF121216);
            String label = row instanceof Setting<?> st ? st.getName() : "Keybind";
            ctx.drawText(tr, label, cx + 8, y + 7, Theme.TEXT, false);
            renderControl(ctx, row, mod, cx + cw - 8, y);
            y += ROW_H;
        }
        ctx.disableScissor();
    }

    /** Draws the control for a settings row, right-aligned at {@code right}. */
    private void renderControl(DrawContext ctx, Object row, Module mod, int right, int y) {
        TextRenderer tr = textRenderer;
        int midY = y + (ROW_H - 3) / 2;
        boolean isKeybind = row.equals("keybind");
        if (isKeybind || row instanceof KeySetting) {
            boolean l = isKeybind ? listening == mod : listening == row;
            String text = l ? "Press a key..." : isKeybind ? KeySetting.keyName(mod.getKey()) : ((KeySetting) row).display();
            int w = Math.max(50, tr.getWidth(text) + 14);
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, 0xFF1A1A20, l ? Theme.RED : Theme.BORDER);
            ctx.drawText(tr, text, right - w + (w - tr.getWidth(text)) / 2, midY - 4, l ? Theme.RED_BRIGHT : Theme.TEXT, false);
        } else if (row instanceof BooleanSetting b) {
            Theme.toggle(ctx, right - 20, midY - 5, animate(b, b.get() ? 1 : 0, 14));
        } else if (row instanceof NumberSetting n) {
            int sx = right - SLIDER_W;
            float pct = (float) ((n.get() - n.getMin()) / (n.getMax() - n.getMin()));
            float shown = animate(n, pct, 20);
            Theme.round(ctx, sx, midY - 2, sx + SLIDER_W, midY + 2, 0xFF2A2A31);
            Theme.round(ctx, sx, midY - 2, sx + Math.max(4, Math.round(SLIDER_W * shown)), midY + 2, Theme.RED);
            int kx = sx + Math.round(SLIDER_W * shown);
            Theme.round(ctx, kx - 4, midY - 4, kx + 4, midY + 4, 0xFFFFFFFF);
            String v = n.display();
            ctx.drawText(tr, v, sx - 8 - tr.getWidth(v), midY - 4, Theme.TEXT_DIM, false);
        } else if (row instanceof ModeSetting md) {
            String v = md.get();
            int w = Math.max(70, tr.getWidth(v) + 30);
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, 0xFF1A1A20, Theme.BORDER);
            ctx.drawText(tr, "‹", right - w + 5, midY - 4, Theme.RED, false);
            ctx.drawText(tr, "›", right - 9, midY - 4, Theme.RED, false);
            ctx.drawText(tr, v, right - w + (w - tr.getWidth(v)) / 2, midY - 4, Theme.TEXT, false);
        } else if (row instanceof ColorSetting c) {
            int n = ColorSetting.PALETTE.length, sz = 9, gap = 3;
            int sx = right - n * (sz + gap) + gap;
            for (int i = 0; i < n; i++) {
                int col = ColorSetting.PALETTE[i];
                int x = sx + i * (sz + gap);
                if ((c.get() | 0xFF000000) == col) Theme.round(ctx, x - 2, midY - 6, x + sz + 2, midY + 6, 0xFFFFFFFF);
                ctx.fill(x, midY - 4, x + sz, midY + 4, col);
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int button = click.button();
        if (listening != null) { listening = null; return true; }

        if (panelsMode()) return clickPanels(mx, my, button);

        if (mx >= wx + ww - 22 && mx < wx + ww - 8 && my >= wy + 10 && my < wy + 24) { close(); return true; }

        if (mx >= wx && mx < wx + SIDEBAR && my >= wy && my < wy + wh) {
            int y = wy + 52;
            for (Page p : Page.values()) {
                if (p == Page.SETTINGS) continue;
                if (my >= y - 4 && my < y + 14) { go(p); return true; }
                y += NAV_STEP;
            }
            y += 8;
            if (my >= y - 4 && my < y + 14) { client.setScreen(new HudEditorScreen(this)); return true; }
            y += 20;
            if (my >= y - 4 && my < y + 14) { go(Page.SETTINGS); return true; }
            return true;
        }

        int sx = wx + ww - 30 - 150, sy = wy + 9;
        searchFocused = selected == null && page != Page.SETTINGS && mx >= sx && mx < sx + 150 && my >= sy && my < sy + 16;
        if (searchFocused) return true;

        if (my < cy || my > cy + ch || mx < cx || mx > cx + cw) return super.mouseClicked(click, doubled);
        if (selected != null || page == Page.SETTINGS) return clickSettings(mx, my, button);

        List<Module> mods = modulesFor(page, search);
        int cols = cols();
        int rh = rowH(), rg = rowGap();
        int cardW = (cw - rg * (cols - 1)) / cols;
        for (int i = 0; i < mods.size(); i++) {
            int x = cx + (i % cols) * (cardW + rg);
            int y = cy + (i / cols) * (rh + rg) - (int) Math.round(scroll);
            if (mx < x || mx >= x + cardW || my < y || my >= y + rh) continue;
            Module mod = mods.get(i);
            // gear region: bottom-right for cards, a small square left of the toggle for rows
            boolean gear = !mod.getSettings().isEmpty() && (cards()
                    ? mx >= x + cardW - 19 && my >= y + rh - 18
                    : mx >= x + cardW - 42 && mx < x + cardW - 26);
            if (gear || button == 1) {
                selected = mod;
                scroll = scrollTarget = 0;
            } else if (button == 0) {
                mod.toggle();
            }
            return true;
        }
        return true;
    }

    private boolean clickSettings(double mx, double my, int button) {
        Module mod = selected != null ? selected : settings();
        if (selected != null && my < cy + 16) {
            if (mx < cx + 46) { back(); return true; }
            if (mx >= cx + cw - 24) { mod.toggle(); return true; }
            return true;
        }
        int top = settingsTop();
        if (my < top) return true;
        int y = top - (int) Math.round(scroll);
        int right = cx + cw - 8;
        for (Object row : rowsFor(mod)) {
            if (my >= y && my < y + ROW_H - 2) {
                if (row.equals("keybind")) {
                    if (button == 1) mod.setKey(GLFW.GLFW_KEY_UNKNOWN); else listening = mod;
                } else if (row instanceof KeySetting k) {
                    if (button == 1) k.set(GLFW.GLFW_KEY_UNKNOWN); else listening = k;
                } else if (row instanceof BooleanSetting b) {
                    b.toggle();
                } else if (row instanceof NumberSetting n) {
                    dragging = n;
                    dragX = right - SLIDER_W;
                    dragW = SLIDER_W;
                    updateSlider(mx);
                } else if (row instanceof ModeSetting md) {
                    int w = Math.max(70, textRenderer.getWidth(md.get()) + 30);
                    md.cycle(button != 1 && mx >= right - w / 2.0);
                } else if (row instanceof ColorSetting c) {
                    int n = ColorSetting.PALETTE.length, sz = 9, gap = 3;
                    int sx = right - n * (sz + gap) + gap;
                    int i = (int) Math.floor((mx - sx) / (sz + gap));
                    if (i >= 0 && i < n) c.set(ColorSetting.PALETTE[i]);
                }
                return true;
            }
            y += ROW_H;
        }
        return true;
    }

    private void updateSlider(double mx) {
        double pct = Math.max(0, Math.min(1, (mx - dragX) / dragW));
        dragging.set(dragging.getMin() + pct * (dragging.getMax() - dragging.getMin()));
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) { updateSlider(click.x()); return true; }
        if (dragTitle != null && PANEL_POS != null) {
            int nx = (int) Math.max(0, Math.min(width - P_W, click.x() - dragOX));
            int ny = (int) Math.max(26, Math.min(height - 20, click.y() - dragOY));
            PANEL_POS.put(dragTitle, new int[]{nx, ny});
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        if (dragTitle != null) { dragTitle = null; savePositions(); }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scrollTarget = Math.max(0, scrollTarget - v * 30);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (listening != null) {
            boolean clear = key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE;
            int value = clear ? GLFW.GLFW_KEY_UNKNOWN : key;
            if (listening instanceof Module m) m.setKey(value);
            else if (listening instanceof KeySetting k) k.set(value);
            listening = null;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (selected != null) { back(); return true; }
            if (!search.isEmpty()) { search = ""; searchFocused = false; return true; }
            close();
            return true;
        }
        if (searchFocused && key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            scrollTarget = 0;
            return true;
        }
        if (!searchFocused && key == settings().menuKey.get()) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (selected != null || page == Page.SETTINGS || !input.isValidChar()) return false;
        // Typing anywhere starts a search, like a launcher.
        searchFocused = true;
        if (search.length() < 32) search += input.asString();
        scrollTarget = 0;
        return true;
    }

    private void go(Page p) {
        page = p;
        selected = null;
        search = "";
        searchFocused = false;
        scroll = scrollTarget = 0;
    }

    private void back() {
        selected = null;
        scroll = scrollTarget = 0;
    }

    @Override
    public void close() {
        EvadeClient.config.save();
        super.close();
    }

    @Override
    public boolean shouldPause() { return false; }

    // ------------------------------------------------------------------ helpers

    private float animate(Object key, float target, float speed) {
        float v = anim.getOrDefault(key, target);
        if (!settings().animations.get()) v = target;
        else v += (target - v) * (1 - (float) Math.exp(-speed * dt));
        anim.put(key, v);
        return v;
    }

    private void small(DrawContext ctx, String s, int x, int y, int color, float scale) {
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        ctx.drawText(textRenderer, s, 0, 0, color, false);
        m.popMatrix();
    }

    private String trimScaled(String s, int maxW, float scale) {
        int limit = Math.round(maxW / scale);
        if (textRenderer.getWidth(s) <= limit) return s;
        return textRenderer.trimToWidth(s, limit - textRenderer.getWidth("...")) + "...";
    }

    private static float ease(float t) { return 1 - (float) Math.pow(1 - t, 3); }
}
