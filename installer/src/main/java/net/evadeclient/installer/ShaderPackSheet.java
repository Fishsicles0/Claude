package net.evadeclient.installer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Set;

import static net.evadeclient.installer.LauncherView.AMBER;
import static net.evadeclient.installer.LauncherView.DIM;
import static net.evadeclient.installer.LauncherView.FAINT;
import static net.evadeclient.installer.LauncherView.H;
import static net.evadeclient.installer.LauncherView.HAIRLINE;
import static net.evadeclient.installer.LauncherView.RED;
import static net.evadeclient.installer.LauncherView.RED_HI;
import static net.evadeclient.installer.LauncherView.TEXT;
import static net.evadeclient.installer.LauncherView.W;
import static net.evadeclient.installer.LauncherView.center;
import static net.evadeclient.installer.LauncherView.ellipsizeEnd;
import static net.evadeclient.installer.LauncherView.mix;

/**
 * The "Shader packs" chooser: a sheet (overlay {@code "shaders"}) listing every {@link ShaderPacks} pack with a tick box,
 * a one-line description and a performance hint, and a details panel per pack with its author, licence and Modrinth
 * page. On a PC with built-in graphics ({@link MemoryAdvice}) it recommends the two light packs. Opened from the
 * Shaders row on the instance page ({@code inst-shpacks}). Click ids: {@code ov-sp:<key>} (tick), {@code ov-spinfo:<key>}
 * (details), {@code ov-sp-*} for the rest; Tab, Enter and Escape work as on the other sheets.
 */
final class ShaderPackSheet {
    private final LauncherView v;
    private final LauncherTools tools;
    /** The pack whose details are open, or null for the list. */
    String details;

    ShaderPackSheet(LauncherView v, LauncherTools tools) {
        this.v = v;
        this.tools = tools;
    }

    static final String OVERLAY = "shaders";

    void open() {
        v.closeOverlays();
        v.overlay = OVERLAY;
        details = null;
        v.game.focusId = null;
    }

    private boolean sharedGraphics() { return tools.advice != null && tools.advice.shared(); }

    private boolean available(ShaderPacks.Pack p) {
        return v.release == null || (v.release.iris() != null && v.release.shaderPacks().containsKey(p.key()));
    }

    /** The "Packs" button on the instance page's Shaders row (painted after the row, so it takes the click). */
    void paintRowButton(Graphics2D g, Launcher.Instance i, int ix, int ry, int oh, int iw) {
        int bw = 92, bh = Math.min(32, oh - 8);
        v.ghostButton(g, "inst-shpacks", ix + iw - 44 - 14 - bw, ry + oh / 2 - bh / 2, bw, bh, "Packs", false, "layers");
        v.game.name("inst-shpacks", "Shader packs: " + ShaderPacks.names(i.shaderPacks) + ". Choose");
    }

    // ------------------------------------------------------------------ painting

    void paint(Graphics2D g) {
        Launcher.Instance i = v.focused();
        if (i == null) { v.overlay = null; return; }
        paintList(g, i);
        if (details != null) paintDetails(g, i, ShaderPacks.byKey(details));
    }

    private void paintList(Graphics2D g, Launcher.Instance i) {
        veil(g);
        boolean hintShared = sharedGraphics(), hintOff = !i.shaders;
        int rh = 74, w = 660;
        int h = 118 + (hintShared ? 50 : 0) + (hintOff ? 50 : 0) + ShaderPacks.ALL.length * rh + 84;
        int x = (W - w) / 2, y = Math.max(12, (H - h) / 2), ix = x + 32, iw = w - 64;
        v.raised(g, x, y, w, h);
        v.hitMap.put("ov-panel", new Rectangle(x, y, w, h));
        g.setFont(v.interBold.deriveFont(21f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, "Shader packs for " + i.name, iw), ix, y + 48);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        int ly = y + 72;
        for (String l : LauncherView.wrapLines(g, "Tick the packs Play adds with Iris. Each comes from Modrinth when you press Play (Evade never hosts them) "
                + "and goes away again when you untick it. Packs you added yourself stay. Switch between them in game.", iw, 2)) {
            g.drawString(l, ix, ly);
            ly += 17;
        }
        int ry = y + 114;
        if (hintShared) {
            strip(g, ix, ry, iw, new Color(245, 165, 36, 26), AMBER, "Built-in graphics: Complementary Reimagined or Sildur's Vibrant run best on this PC.",
                    "ov-sp-light", "Use those two");
            ry += 50;
        }
        if (hintOff) {
            strip(g, ix, ry, iw, new Color(225, 29, 46, 22), RED_HI, "Shaders are off for " + i.name + ": switch them on to use these packs.",
                    "ov-sp-on", "Switch on");
            ry += 50;
        }
        for (int k = 0; k < ShaderPacks.ALL.length; k++) {
            ShaderPacks.Pack p = ShaderPacks.ALL[k];
            boolean on = i.shaderPacks.contains(p.key()), avail = available(p);
            String id = "ov-sp:" + p.key();
            float t = v.anim(id, id.equals(v.hovered));
            if (t > 0.01f && avail) {
                g.setColor(new Color(255, 255, 255, Math.round(8 * t)));
                g.fill(new RoundRectangle2D.Double(ix - 10, ry + 2, iw + 20, rh - 6, 14, 14));
            }
            checkbox(g, ix, ry + 15, on, avail, t);
            int tx = ix + 32;
            g.setFont(v.interBold.deriveFont(14f));
            g.setColor(avail ? TEXT : FAINT);
            g.drawString(p.name(), tx, ry + 28);
            int cx = tx + g.getFontMetrics().stringWidth(p.name()) + 10;
            int cw = chip(g, p.tier(), cx, ry + 16, p.light());
            if (p.defaultOn()) {
                g.setFont(v.inter.deriveFont(11f));
                g.setColor(FAINT);
                g.drawString("Default", cx + cw + 8, ry + 28);
            }
            g.setFont(v.inter.deriveFont(12f));
            g.setColor(avail ? DIM : FAINT);
            g.drawString(ellipsizeEnd(g, avail ? p.sub() : "Not available for " + InstallerCore.MC_VERSION + " on Modrinth right now", iw - 150), tx, ry + 47);
            g.setFont(v.inter.deriveFont(11f));
            g.setColor(FAINT);
            String perf = p.perf();
            InstallerCore.ModFile f = v.release != null ? v.release.shaderPacks().get(p.key()) : null;
            g.drawString(ellipsizeEnd(g, perf + (f != null ? "  ·  " + f.version() : ""), iw - 150), tx, ry + 64);
            if (avail) v.hit(id, new Rectangle(ix - 10, ry + 2, iw - 100, rh - 6));
            v.game.name(id, p.name() + " shader pack, " + (on ? "ticked" : "not ticked") + ". " + p.sub() + ". " + p.perf());
            v.ghostButton(g, "ov-spinfo:" + p.key(), ix + iw - 88, ry + rh / 2 - 16, 88, 32, "Details", false);
            v.game.name("ov-spinfo:" + p.key(), p.name() + ": licence and Modrinth page");
            if (k < ShaderPacks.ALL.length - 1) {
                g.setColor(HAIRLINE);
                g.fillRect(ix, ry + rh - 2, iw, 1);
            }
            ry += rh;
        }
        g.setFont(v.inter.deriveFont(12f));
        Set<String> c = ShaderPacks.clean(i.shaderPacks);
        String foot = c.isEmpty() ? "No packs ticked: Iris only, with the packs you add yourself."
                : i.dirty ? "Applied the next time you press Play with " + i.name + "." : "Changes apply the next time you press Play.";
        g.setColor(c.isEmpty() || i.dirty ? AMBER : FAINT);
        g.drawString(ellipsizeEnd(g, foot, iw - 300), ix, y + h - 34);
        if (!ShaderPacks.isDefault(i.shaderPacks)) v.textLink(g, "ov-sp-reset", "Back to the defaults", x + w - 32 - 120 - 18, y + h - 34, 1);
        v.smallPrimary(g, "ov-sp-done", x + w - 32 - 120, y + h - 60, 120, 40, "Done", null);
        if (details == null && (v.game.focusId == null || !v.hitMap.containsKey(v.game.focusId))) v.game.focusId = "ov-sp-done";
    }

    private void paintDetails(Graphics2D g, Launcher.Instance i, ShaderPacks.Pack p) {
        if (p == null) { details = null; return; }
        // Only the details panel answers clicks and Tab while it's open; clicking beside it goes back to the list.
        v.hitMap.keySet().removeIf(k -> k.startsWith("ov-") || k.equals("dismiss"));
        g.setColor(new Color(4, 4, 6, 110));
        if (v.roundCorners) g.fill(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
        else g.fillRect(0, 0, W, H);
        v.hitMap.put("ov-sp-veil", new Rectangle(0, 0, W, H));
        int w = 540, h = 372, x = (W - w) / 2, y = (H - h) / 2, ix = x + 32, iw = w - 64;
        v.raised(g, x, y, w, h);
        v.hitMap.put("ov-panel", new Rectangle(x, y, w, h));
        g.setFont(v.interBold.deriveFont(21f));
        g.setColor(TEXT);
        g.drawString(p.name(), ix, y + 48);
        chip(g, p.tier(), ix + g.getFontMetrics().stringWidth(p.name()) + 12, y + 33, p.light());
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, "By " + p.author() + "  ·  modrinth.com/shader/" + p.slug(), iw), ix, y + 72);
        int ry = y + 108;
        InstallerCore.ModFile f = v.release != null ? v.release.shaderPacks().get(p.key()) : null;
        String[][] rows = {
                {"Looks", p.sub()},
                {"Runs on", p.perf()},
                {"Licence", p.licence()},
                {"Version", f != null ? f.version() + " for Minecraft " + InstallerCore.MC_VERSION + " (Iris)"
                        : v.release == null ? "The newest for Minecraft " + InstallerCore.MC_VERSION + " (Iris)" : "Not available right now"},
        };
        for (String[] r : rows) {
            g.setFont(v.interBold.deriveFont(11f));
            g.setColor(FAINT);
            g.drawString(r[0].toUpperCase(java.util.Locale.ROOT), ix, ry);
            g.setFont(v.inter.deriveFont(13f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, r[1], iw - 110), ix + 110, ry);
            ry += 30;
        }
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(DIM);
        for (String l : LauncherView.wrapLines(g, "Evade downloads it from Modrinth when you press Play and checks the file; it never hosts a copy. "
                + "All credit goes to " + p.author() + ".", iw, 2)) {
            g.drawString(l, ix, ry + 4);
            ry += 17;
        }
        int by = y + h - 60;
        v.ghostButton(g, "ov-spurl:" + p.key(), ix, by, 170, 40, "Open on Modrinth", false, "external");
        v.game.name("ov-spurl:" + p.key(), "Open " + p.name() + " on Modrinth");
        if (p.licenceUrl() != null) {
            v.ghostButton(g, "ov-splic:" + p.key(), ix + 180, by, 120, 40, "Licence", false, "book");
            v.game.name("ov-splic:" + p.key(), "Read the " + p.name() + " licence");
        }
        v.smallPrimary(g, "ov-sp-back", x + w - 32 - 110, by, 110, 40, "Back", null);
        if (v.game.focusId == null || !v.hitMap.containsKey(v.game.focusId)) v.game.focusId = "ov-sp-back";
    }

    private void veil(Graphics2D g) {
        g.setColor(new Color(4, 4, 6, 150));
        if (v.roundCorners) g.fill(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
        else g.fillRect(0, 0, W, H);
    }

    /** A tinted note with a link on the right. */
    private void strip(Graphics2D g, int x, int y, int w, Color fill, Color dot, String text, String id, String link) {
        g.setColor(fill);
        g.fill(new RoundRectangle2D.Double(x, y, w, 40, 20, 20));
        LauncherView.icon(g, "alert", x + 20, y + 20, 14, dot);
        g.setFont(v.interBold.deriveFont(12f));
        int lw = g.getFontMetrics().stringWidth(link) + 28;
        v.ghostButton(g, id, x + w - lw - 6, y + 5, lw, 30, link, false);
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, text, w - lw - 54), x + 38, y + 25);
    }

    /** A small label pill (green for the light packs); returns its width. */
    private int chip(Graphics2D g, String text, int x, int y, boolean light) {
        g.setFont(v.interBold.deriveFont(9.5f));
        String s = text.toUpperCase(java.util.Locale.ROOT);
        int cw = g.getFontMetrics().stringWidth(s) + 14;
        g.setColor(light ? new Color(61, 220, 132, 30) : new Color(255, 255, 255, 16));
        g.fill(new RoundRectangle2D.Double(x, y, cw, 16, 16, 16));
        g.setColor(light ? LauncherView.OK : DIM);
        center(g, s, x, y, cw, 16);
        return cw;
    }

    private void checkbox(Graphics2D g, int x, int y, boolean on, boolean enabled, float t) {
        if (!enabled) on = false;
        g.setColor(on ? RED : mix(new Color(0x1C1C22), new Color(0x2A2A32), t));
        g.fill(new RoundRectangle2D.Double(x, y, 20, 20, 7, 7));
        if (!on) {
            g.setColor(enabled ? mix(new Color(0x34343C), new Color(0x4A4A55), t) : new Color(0x26262C));
            g.draw(new RoundRectangle2D.Double(x + .5, y + .5, 19, 19, 7, 7));
        } else {
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D c = new Path2D.Double();
            c.moveTo(x + 5, y + 10.5);
            c.lineTo(x + 8.6, y + 14);
            c.lineTo(x + 15, y + 6.6);
            g.draw(c);
            g.setStroke(new BasicStroke(1f));
        }
    }

    // ------------------------------------------------------------------ clicks

    /** This sheet's controls; false for anything else. */
    boolean click(String id) {
        Launcher.Instance foc = v.focused();
        switch (id) {
            case "inst-shpacks" -> open();
            case "ov-sp-done" -> { v.overlay = null; details = null; v.game.focusId = null; }
            case "ov-sp-back", "ov-sp-veil" -> { details = null; v.game.focusId = null; }
            case "ov-sp-reset" -> change(foc, ShaderPacks.defaults());
            case "ov-sp-light" -> change(foc, ShaderPacks.clean(List.of(ShaderPacks.REIMAGINED.key(), ShaderPacks.SILDURS.key())));
            case "ov-sp-on" -> {
                if (foc == null) return true;
                foc.shaders = true;
                foc.dirty = true;
                v.actions.instanceChanged(foc);
                v.toast("Shaders are on: Play installs Iris and the ticked packs");
            }
            default -> {
                if (id.startsWith("ov-sp:")) {
                    if (foc == null) return true;
                    Set<String> s = ShaderPacks.clean(foc.shaderPacks);
                    String key = id.substring(6);
                    if (!s.remove(key)) s.add(key);
                    change(foc, s);
                } else if (id.startsWith("ov-spinfo:")) {
                    details = id.substring(10);
                    v.game.focusId = null;
                } else if (id.startsWith("ov-spurl:")) {
                    ShaderPacks.Pack p = ShaderPacks.byKey(id.substring(9));
                    if (p != null) v.actions.openUrl(p.url());
                } else if (id.startsWith("ov-splic:")) {
                    ShaderPacks.Pack p = ShaderPacks.byKey(id.substring(9));
                    if (p != null && p.licenceUrl() != null) v.actions.openUrl(p.licenceUrl());
                } else return false;
            }
        }
        return true;
    }

    private void change(Launcher.Instance foc, Set<String> packs) {
        if (foc == null) return;
        Set<String> c = ShaderPacks.clean(packs);
        if (c.equals(ShaderPacks.clean(foc.shaderPacks))) return;
        foc.shaderPacks = c;
        foc.dirty = true;
        v.actions.instanceChanged(foc);
    }

    /** Words for a screen reader. */
    String labelFor(String id) {
        if ("inst-shpacks".equals(id)) return "Choose shader packs";
        return null;
    }
}
