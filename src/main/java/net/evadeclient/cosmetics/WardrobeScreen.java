package net.evadeclient.cosmetics;

import net.evadeclient.gui.ColorPicker;
import net.evadeclient.gui.Motion;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Surface;
import net.evadeclient.gui.kit.Tabs;
import net.evadeclient.gui.menu.MenuScroll;
import net.evadeclient.module.misc.Cosmetics;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Wardrobe (spec 7.6): a stage on the left with your 3D model on a lit floor (the floor glows in the rarity of
 * what you wear in the open slot; drag to turn it, it sways gently on its own), six outfit chips under it; on the
 * right the slot tabs and a scrolling grid of item cards (the model large on a rarity floor glow, a rarity gem, the
 * name and a status chip; cards tilt toward the cursor), and a detail bar with the full description, Colour and the
 * one primary action (Wear, Take off, Get it in the shop, Play). Locked items are tried on in the stage.
 */
public final class WardrobeScreen extends Screen {
    public static final String SHOP_URL = "https://evadeclient.com/shop";
    private static Slot lastSlot = Slot.CAPE;
    private static final int DESIGN_W = 820, DESIGN_H = 500;

    private final Screen parent;
    private final Kit kit = new Kit();
    private final MenuScroll scroll = new MenuScroll();
    private Slot slot = lastSlot;
    private CosmeticItem trying, focus;
    private Emotes.Playing previewEmote;
    private float yawBase = 200, yawDrag, pitch = -6;
    private boolean dragging;
    private int sx1, sy1, sx2, sy2, gx1, gy1, gx2, gy2;
    private final ColorPicker picker = new ColorPicker();
    private ColorSetting colorTarget;
    private Slot colorSlot;
    private String linkText;
    private final long opened = System.nanoTime();
    private long lastNanos = System.nanoTime();
    private float vs = 1f;
    private double menuPx = 2;
    private double swapAt = -1;

    public WardrobeScreen(Screen parent) {
        super(Text.literal("Wardrobe"));
        this.parent = parent;
    }

    /** Opens over whatever is showing (the Evade menu comes back after). */
    public static void openFromCurrent() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new WardrobeScreen(mc.currentScreen));
    }

    @Override
    protected void init() {
        CosmeticAssets.preloadBundled();
        Wardrobe.refresh(false);
        if (CosmeticsCatalog.remoteVersion() == null) CosmeticsEngine.refreshCatalog();
    }

    @Override public boolean shouldPause() { return false; }

    private String subtitle() {
        Cosmetics m = Cosmetics.get();
        if (m != null && !m.isEnabled()) return "Cosmetics are off (or switched off by this server): turn the Cosmetics mod on to see them in game";
        return Wardrobe.status();
    }

    // ------------------------------------------------------------------ render

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Tokens.refresh();
        super.renderBackground(ctx, mouseX, mouseY, delta);
        Paint.scrim(ctx, width, height, 1f);
    }

    private void updateScale() {
        if (client == null) return;
        var win = client.getWindow();
        double gs = Math.max(1, win.getScaleFactor());
        double fit = Math.min(win.getFramebufferWidth() / (double) DESIGN_W, win.getFramebufferHeight() / (double) DESIGN_H);
        double k = fit >= 1 ? Math.min(gs, Math.floor(fit)) : fit;
        vs = (float) (k / gs);
        menuPx = k;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        updateScale();
        int realW = width, realH = height;
        width = Math.round(realW / vs);
        height = Math.round(realH / vs);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.scale(vs, vs);
        UiFont.designG = Math.max(UiFont.MIN_G, Math.min(UiFont.MAX_G, (int) Math.ceil(menuPx - 0.01)));
        try {
            int mx = picker.isOpen() ? -10000 : (int) Math.floor(mouseX / vs), my = picker.isOpen() ? -10000 : (int) Math.floor(mouseY / vs);
            kit.begin(ctx, mx, my);
            float a = Motion.standard(Motion.clamp01((System.nanoTime() - opened) / 1e6f / Math.max(1, Motion.duration(Motion.DUR_SLOW))));
            if (Motion.level() == Motion.Level.OFF) a = 1f;
            float base = Theme.fade;
            Theme.fade = base * a;
            m.pushMatrix();
            if (Motion.decorative()) m.translate(0, Theme.snap(ctx, (1 - a) * 10));
            try {
                draw(ctx);
                kit.end();
            } finally {
                m.popMatrix();
                Theme.fade = base;
            }
        } finally {
            UiFont.designG = 0;
            m.popMatrix();
            width = realW;
            height = realH;
        }
        if (picker.isOpen()) picker.render(ctx, mouseX, mouseY, width, height);
    }

    private void draw(DrawContext ctx) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        scroll.frame(dt);
        float time = (now - opened) / 1e9f;
        int ww = Math.min(width - 20, 800), wh = Math.min(height - 16, 480);
        int wx = (width - ww) / 2, wy = (height - wh) / 2;
        Surface.window(kit, wx, wy, wx + ww, wy + wh);
        int right = Surface.header(kit, "wd", wx, wy, wx + ww, "Wardrobe", subtitle(), parent != null ? this::close : null, this::close);
        // Header actions: the shop link code and the shop (secondary: the view's one primary is in the detail bar).
        int sw = Button.width(kit, "Shop", Button.Size.MD, "wardrobe", false);
        Button.draw(kit, "shop", right - sw, wy + 8, sw, Button.Size.MD, Button.Variant.SECONDARY, "Shop", "wardrobe", true, this::openShop);
        int lw = Button.width(kit, "Link the shop", Button.Size.MD, null, false);
        Button.draw(kit, "link", right - sw - 6 - lw, wy + 8, lw, Button.Size.MD, Button.Variant.GHOST, "Link the shop", null, true, this::link);
        kit.tooltip("link", "Link this account to the shop", "Gets a code to enter on evadeclient.com/shop", right - sw - 6 - lw, wy + 8, right - sw - 6, wy + 32);

        int pad = 16, top = wy + Surface.HEADER_H + 12, bottom = wy + wh - pad;
        int stageW = Math.min(250, Math.max(180, ww * 30 / 100));
        stage(ctx, wx + pad, top, wx + pad + stageW, bottom - 56, time);
        outfits(wx + pad, bottom - 44, stageW);

        int gx = wx + pad + stageW + 18, gw = wx + ww - pad - gx;
        grid(ctx, gx, top, gw, bottom - 62);
        detail(gx, bottom - 52, gw);
    }

    /** The stage: an r.xl plate, a lit floor in the worn item's rarity, the turning model and its shadow. */
    private void stage(DrawContext ctx, int x1, int y1, int x2, int y2, float time) {
        sx1 = x1; sy1 = y1; sx2 = x2; sy2 = y2;
        Paint.surface(ctx, x1, y1, x2, y2, Tokens.R_XL, Tokens.E2.noAmbient(), 0, kit.mx - x1);
        Paint.vgradient(ctx, x1 + 0.5f, y1 + 0.5f, x2 - 0.5f, y2 - 0.5f, Tokens.R_XL - 0.5f, Theme.alpha(Tokens.N150, 0.7f), Theme.alpha(Tokens.N25, 0.9f));
        Loadout shown = previewLoadout();
        CosmeticItem lit = trying != null ? trying : wornIn(slot);
        int floor = lit != null ? lit.rarity.color : Tokens.A600;
        float cx = (x1 + x2) / 2f, fy = y2 - 34;
        // The lit floor: an elliptical light pool and a thin ring, then a soft contact shadow under the feet.
        float fw = (x2 - x1) * 0.42f;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(cx, fy);
            m.scale(1f, 0.26f);
            Paint.radial(ctx, -fw * 1.4f, -fw * 1.4f, fw * 1.4f, fw * 1.4f, fw * 1.4f, Theme.alpha(floor, 0.26f), floor & 0xFFFFFF, 0, 0, fw * 1.4f);
            if (Paint.gpu()) UiShape.shape(-fw, -fw, fw, fw).radius(fw).border(Theme.alpha(floor, 0.45f), 1.2f).draw(ctx);
            Paint.radial(ctx, -fw * 0.55f, -fw * 0.55f, fw * 0.55f, fw * 0.55f, fw * 0.55f, Theme.alpha(0xFF000000, 0.55f), 0, 0, 0, fw * 0.55f);
        } finally {
            m.popMatrix();
        }
        // Idle: a slow sway of ±12 degrees while nobody drags (none with reduced motion).
        float sway = Motion.decorative() && !dragging ? (float) Math.sin(time * 0.6) * 12f : 0f;
        // An equip pops the model in (0.9 → 1, playful).
        float pop = kit.anim("pop", swapAt >= 0 && (System.nanoTime() / 1e6 - swapAt) < 60 ? 0.9f : 1f, Motion.PLAYFUL);
        int px1 = x1 + 10, px2 = x2 - 10, py1 = y1 + 14, py2 = (int) fy + 6;
        // The 3D element is placed in screen GUI px (it doesn't follow the matrix): map the design rect, popping
        // about the feet.
        float ph = (py2 - py1) * pop, pw = (px2 - px1) * pop;
        WardrobePreview.draw(ctx, shown, previewEmote, yawBase + yawDrag + sway, pitch, time,
                Math.round((cx - pw / 2) * vs), Math.round((py2 - ph) * vs), Math.round((cx + pw / 2) * vs), Math.round(py2 * vs));
        String hint = trying != null ? "Trying on · not yours yet" : "Drag to turn";
        kit.text(hint, x1 + 14, y2 - 18, trying != null ? Tokens.WARN : Tokens.N600, Role.SMALL);
        if (lit != null) {
            String r = lit.partner != null ? lit.partner : lit.rarity.label;
            int bw = Badge.width(kit, r);
            Badge.draw(kit, x2 - 12 - bw, y2 - 20, r, lit.rarity == Rarity.REGULAR ? Badge.Tone.NEUTRAL : Badge.Tone.WARN);
        }
    }

    private CosmeticItem wornIn(Slot s) {
        Loadout.Worn w = Wardrobe.equipped().get(s);
        return w == null ? null : CosmeticsCatalog.get(w.id());
    }

    private void outfits(int x, int y, int w) {
        UiFont.caps(kit.ctx, kit.tr, "OUTFITS", x + 2, y + 2, Tokens.N700, Role.CAPS, 0.9f, kit.smooth);
        int n = Wardrobe.OUTFITS, gap = 6, cw = (w - gap * (n - 1)) / n, cy = y + 14;
        for (int i = 0; i < n; i++) {
            int cx = x + i * (cw + gap);
            Wardrobe.Outfit o = Wardrobe.outfit(i);
            String id = "outfit" + i;
            int st = kit.state(id, cx, cy, cx + cw, cy + 26, true);
            float hov = kit.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
            if (o != null) Paint.surface(kit.ctx, cx, cy, cx + cw, cy + 26, Tokens.R_SM, Tokens.E3.noAmbient().withFill(Theme.lerpColor(Tokens.N250, Tokens.N300, hov)), hov, kit.mx - cx);
            else {
                if (hov > 0.01f) Paint.rect(kit.ctx, cx, cy, cx + cw, cy + 26, Tokens.R_SM, Theme.alpha(Tokens.N200, hov));
                Paint.dashed(kit.ctx, cx, cy, cx + cw, cy + 26, Tokens.N500);
            }
            String label = String.valueOf(i + 1);
            kit.textCentredY(label, cx + (cw - kit.width(label, Role.STRONG)) / 2, cy + 13, o != null ? Tokens.N950 : Tokens.N600, Role.STRONG);
            if (o != null) Paint.circle(kit.ctx, cx + cw - 6, cy + 6, 4, Tokens.A500);
            if ((st & Kit.FOCUSED) != 0) Paint.focusRing(kit, cx, cy, cx + cw, cy + 26, Tokens.R_SM, Tokens.FOCUS_RING);
            final int idx = i;
            kit.hit(id, cx, cy, cx + cw, cy + 26, (b, mx, my) -> clickOutfit(idx, b), true);
            kit.tooltip(id, o != null ? "Wear outfit " + (i + 1) : "Save what you wear as outfit " + (i + 1),
                    o != null ? "Right-click saves over it" : null, cx, cy, cx + cw, cy + 26);
        }
    }

    /** Slot tabs and the item grid (scrolls; no paging). */
    private void grid(DrawContext ctx, int x, int y, int w, int bottom) {
        List<String> tabs = new ArrayList<>();
        for (Slot s : Slot.values()) tabs.add(s.label);
        Tabs.draw(kit, "slots", x, y, w, tabs, slot.label, l -> { for (Slot s : Slot.values()) if (s.label.equals(l)) selectSlot(s); });
        int top = y + Tabs.H + 10;
        gx1 = x; gy1 = top; gx2 = x + w; gy2 = bottom;
        List<CosmeticItem> items = CosmeticsCatalog.forSlot(slot);
        int gap = 10, cols = Math.max(2, (w + gap) / (132 + gap));
        int cw = (w - gap * (cols - 1)) / cols, ch = Math.round(cw * 1.2f);
        int rows = (items.size() + cols - 1) / cols;
        scroll.clamp(rows * (ch + gap) - gap + 12, bottom - top);
        if (items.isEmpty()) {
            kit.text("Nothing here yet: new items arrive with the shop.", x, top + 8, Tokens.N700, Role.BODY);
            return;
        }
        int si = scroll.px();
        ctx.enableScissor(x - 8, top, x + w + 8, bottom);
        scroll.push(ctx);
        try {
            for (int i = 0; i < items.size(); i++) {
                int cx = x + (i % cols) * (cw + gap), cy = top + 4 + (i / cols) * (ch + gap) - si;
                if (cy + ch < top || cy > bottom) continue;
                card(ctx, items.get(i), cx, cy, cw, ch);
            }
        } finally {
            scroll.pop(ctx);
            ctx.disableScissor();
        }
        scroll.chrome(kit, "wgrid", x - 8, top, x + w, bottom);
        kit.barrier("grid#top", x - 8, y + Tabs.H, x + w + 8, top, null);
        kit.barrier("grid#bottom", x - 8, bottom, x + w + 8, bottom + 60, null);
    }

    private void card(DrawContext ctx, CosmeticItem it, int x, int y, int w, int h) {
        boolean owned = Wardrobe.owns(it.id);
        Loadout.Worn worn = Wardrobe.equipped().get(it.slot);
        boolean wearing = worn != null && worn.id().equals(it.id) && owned;
        boolean tryingThis = trying == it || (previewEmote != null && previewEmote.item() == it);
        String id = "card:" + it.id;
        Surface.card(kit, id, x, y, x + w, y + h, wearing, true, null);
        try {
            kit.hit(id, x, y, x + w, y + h, (b, mx, my) -> { if (b == 0) clickItem(it); }, true);
            float hov = kit.anim(id + "#h", kit.hovered(id) ? 1f : 0f);
            if (kit.hovered(id)) focus = it;
            // Rarity floor glow under the model, brighter on hover; the rarity gem at the top right.
            int rc = it.rarity.color;
            float tcx = x + w / 2f, ts = Math.min(w - 16, h - 48);
            float fy = y + 10 + ts * 0.92f;
            var m = ctx.getMatrices();
            m.pushMatrix();
            try {
                m.translate(tcx, fy);
                m.scale(1f, 0.3f);
                float r = ts * 0.55f;
                Paint.radial(ctx, -r, -r, r, r, r, Theme.alpha(rc, 0.20f + 0.18f * hov), rc & 0xFFFFFF, 0, 0, r);
            } finally {
                m.popMatrix();
            }
            Identifier thumb = CosmeticAssets.thumb(it);
            if (thumb != null) {
                int color = owned ? Theme.fa(0xFFFFFFFF) : Theme.fa(0xB8FFFFFF);
                float[] fr = CosmeticAssets.thumbFrame(it);
                int frames = fr == null ? 1 : (int) fr[3], fa = fr == null ? 0 : (int) fr[0];
                int s = Math.round(ts * (1f + 0.04f * hov)), tx = Math.round(tcx - s / 2f), ty = y + 8 - Math.round(2 * hov);
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, thumb, tx, ty, 0, fa * 128, s, s, 128, 128, 128, 128 * frames, color);
                if (fr != null && fr[2] > 0.02f && fr[1] != fr[0]) {
                    int a2 = Math.round(((color >>> 24) & 255) * fr[2]);
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, thumb, tx, ty, 0, (int) fr[1] * 128, s, s, 128, 128, 128, 128 * frames, a2 << 24 | (color & 0xFFFFFF));
                }
            }
            if (it.rarity != Rarity.REGULAR || it.partner != null) {
                var mm = ctx.getMatrices();
                mm.pushMatrix();
                mm.translate(x + w - 12, y + 12);
                mm.rotate((float) Math.toRadians(45));
                Paint.rect(ctx, -3.5f, -3.5f, 3.5f, 3.5f, 1, rc);
                mm.popMatrix();
            }
            kit.text(kit.ellipsize(it.name, Role.STRONG, w - 16), x + 8, y + h - 34, Tokens.N950, Role.STRONG);
            String chip = wearing ? "Wearing" : tryingThis ? "Trying on" : it.free() ? "Free" : owned ? (it.slot == Slot.EMOTE ? "Owned" : "Owned") : "Locked";
            Badge.Tone tone = wearing ? Badge.Tone.ACCENT : tryingThis ? Badge.Tone.WARN : it.free() && !owned ? Badge.Tone.OK : Badge.Tone.NEUTRAL;
            Badge.draw(kit, x + 8, y + h - 20, chip, tone);
        } finally {
            Surface.endCard(kit);
        }
    }

    /** The detail bar: the selected item's full description, Colour, and the one primary action. */
    private void detail(int x, int y, int w) {
        Paint.fadeLine(kit.ctx, x, y - 6, x + w, Theme.alpha(0xFFFFFFFF, 0.06f));
        CosmeticItem cur = wornIn(slot);
        Loadout.Worn worn = Wardrobe.equipped().get(slot);
        CosmeticItem it = trying != null ? trying : focus != null && focus.slot == slot ? focus : cur;
        int right = x + w;
        // The action, right-aligned, primary.
        String action = null;
        Runnable act = null;
        if (it != null) {
            boolean owned = Wardrobe.owns(it.id);
            boolean wearing = worn != null && worn.id().equals(it.id) && owned;
            if (it.slot == Slot.EMOTE) {
                if (owned) { action = "Play " + it.name; act = () -> { close(); Emotes.play(it); }; }
                else { action = "Get it in the shop"; act = this::openShop; }
            } else if (!owned) { action = "Get it in the shop"; act = this::openShop; }
            else { action = wearing ? "Take off" : "Wear"; act = () -> clickItem(it); }
        }
        if (action != null) {
            int aw = Button.width(kit, action, Button.Size.LG, null, action.startsWith("Get"));
            Button.draw(kit, "act", right - aw, y + 10, aw, Button.Size.LG, Button.Variant.PRIMARY, action, null, action.startsWith("Get"), true, act);
            right -= aw + 8;
        }
        if (cur != null && cur.colourable && trying == null && it == cur) {
            int c = cur.colorFor(worn);
            int cw = Button.width(kit, "Colour", Button.Size.LG, null, false) + 18;
            final int ax = right;
            Button.draw(kit, "colour", right - cw, y + 10, cw, Button.Size.LG, Button.Variant.SECONDARY, "      Colour", null, true, () -> openColour(cur, worn, ax));
            Paint.rect(kit.ctx, right - cw + 10, y + 18, right - cw + 22, y + 30, Tokens.R_XS, 0xFF000000 | (c < 0 ? 0xFFFFFF : c));
            Paint.ring(kit.ctx, right - cw + 10, y + 18, right - cw + 22, y + 30, Tokens.R_XS, Theme.alpha(0xFFFFFFFF, 0.2f), 1);
            right -= cw + 8;
        }
        int tw = right - x - 8;
        if (slot == Slot.EMOTE && it == null) {
            kit.text(kit.ellipsize("Emote wheel: " + KeySetting.keyName(emoteKey()) + " (change it in the Cosmetics mod). Moving ends an emote.", Role.BODY, tw), x, y + 18, Tokens.N800, Role.BODY);
        } else if (it == null) {
            kit.text("Nothing in this slot. Click an item to wear it.", x, y + 18, Tokens.N800, Role.BODY);
        } else {
            String tag = it.partner != null ? it.partner : it.rarity.label;
            kit.text(kit.ellipsize(it.name, Role.STRONG, tw - 80), x, y + 8, Tokens.N950, Role.STRONG);
            kit.text(tag, x + kit.width(kit.ellipsize(it.name, Role.STRONG, tw - 80), Role.STRONG) + 8, y + 9, it.rarity == Rarity.REGULAR ? Tokens.N700 : it.rarity.color, Role.LABEL);
            List<String> lines = kit.wrap(it.description, Role.SMALL, tw, 2);
            int ly = y + 22;
            for (String l : lines) { kit.text(l, x, ly, Tokens.N800, Role.SMALL); ly += 10; }
        }
        if (linkText != null) kit.text(kit.ellipsize(linkText, Role.SMALL, w), x, y + 44, Tokens.OK, Role.SMALL);
    }

    private int emoteKey() {
        Cosmetics m = Cosmetics.get();
        return m == null ? -1 : m.emoteKey.get();
    }

    private Loadout previewLoadout() {
        Loadout l = Wardrobe.renderLoadout();
        if (trying != null) l = l.with(trying.slot, new Loadout.Worn(trying.id, -1));
        return l;
    }

    // ------------------------------------------------------------------ actions (also used by the tests)

    public void selectSlot(Slot s) {
        if (s != slot) scroll.reset();
        slot = s;
        lastSlot = s;
        trying = null;
        focus = null;
        if (s != Slot.EMOTE) previewEmote = null;
        yawBase = switch (s) { case CAPE, WINGS, BACK -> 200; case HAT, MASK, PET -> 20; default -> yawBase; };
        yawDrag = 0;
    }

    public Slot slot() { return slot; }
    public CosmeticItem trying() { return trying; }

    /** A card was clicked: wear or take off an owned item, try on a locked one, preview an emote. */
    public void clickItem(CosmeticItem it) {
        CosmeticAssets.request(it);
        focus = it;
        swapAt = System.nanoTime() / 1e6;
        if (it.slot == Slot.EMOTE) {
            previewEmote = Emotes.preview(it, Emotes.clock());
            trying = null;
            if (!Wardrobe.owns(it.id)) trying = it;
            return;
        }
        if (!Wardrobe.owns(it.id)) {
            trying = trying == it ? null : it;
            return;
        }
        trying = null;
        Loadout.Worn worn = Wardrobe.equipped().get(it.slot);
        if (worn != null && worn.id().equals(it.id)) Wardrobe.equip(it.slot, null, -1);
        else Wardrobe.equip(it.slot, it.id, worn != null && worn.color() >= 0 ? worn.color() : it.defaultColor);
    }

    /** Outfit chip: left-click wears a saved outfit (or saves into an empty one), right-click saves over it. */
    public void clickOutfit(int i, int button) {
        Wardrobe.Outfit o = Wardrobe.outfit(i);
        if (button == 1 || o == null) {
            Wardrobe.saveOutfit(i, null);
            Notifications.push("Wardrobe", "Saved outfit " + (i + 1) + (o == null ? "" : " (right-click saves over it)"), true);
        } else {
            Wardrobe.wear(o.loadout());
            trying = null;
            swapAt = System.nanoTime() / 1e6;
            Notifications.push("Wardrobe", "Wearing outfit " + (i + 1), true);
        }
    }

    private void openShop() {
        if (client != null) ConfirmLinkScreen.open(this, SHOP_URL);
    }

    private void link() {
        linkText = "Getting a code…";
        CosmeticsApi.linkCode().whenComplete((r, err) -> MinecraftClient.getInstance().execute(() -> {
            if (err != null || r == null || !r.has("code")) { linkText = "Couldn't get a code: " + (err == null ? "no code" : CosmeticsApi.reason(err)); return; }
            String code = r.get("code").getAsString();
            long mins = r.has("expiresAt") ? Math.max(1, (r.get("expiresAt").getAsLong() - System.currentTimeMillis()) / 60_000) : 10;
            if (client != null) client.keyboard.setClipboard(code);
            linkText = "Link code " + code + " (copied) · enter it on evadeclient.com/shop within " + mins + " min";
        }));
    }

    private void openColour(CosmeticItem item, Loadout.Worn worn, int anchorX) {
        int c = item.colorFor(worn);
        colorTarget = new ColorSetting("Colour", 0xFF000000 | (c < 0 ? 0xFFFFFF : c)).opaque();
        colorSlot = item.slot;
        picker.open(colorTarget, anchorX * vs, (gy2 + 20) * vs);
    }

    /** Applies a colour to what you wear in {@code s} (also what the picker does when it closes). */
    public void applyColour(Slot s, int rgb) {
        Loadout.Worn w = Wardrobe.equipped().get(s);
        if (w != null) Wardrobe.equip(s, w.id(), rgb & 0xFFFFFF);
    }

    private void closePicker() {
        if (!picker.isOpen()) return;
        picker.close();
        if (colorTarget != null && colorSlot != null) applyColour(colorSlot, colorTarget.get());
        colorTarget = null;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (picker.isOpen()) {
            if (picker.mouseClicked(click.x(), click.y(), click.button())) {
                if (!picker.isOpen() && colorTarget != null && colorSlot != null) { applyColour(colorSlot, colorTarget.get()); colorTarget = null; }
                return true;
            }
            closePicker();
            return true;
        }
        double mx = click.x() / vs, my = click.y() / vs;
        if (kit.mouseClicked(mx, my, click.button())) return true;
        if (mx >= sx1 && mx < sx2 && my >= sy1 && my < sy2) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (picker.isOpen()) return picker.mouseDragged(click.x(), click.y());
        if (dragging) {
            yawDrag += (float) dx / vs * 2.5f;
            pitch = Math.max(-40, Math.min(40, pitch - (float) dy / vs * 2.5f));
            return true;
        }
        return kit.mouseDragged(click.x() / vs, click.y() / vs, click.button()) || super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = false;
        picker.mouseReleased();
        kit.mouseReleased(click.x() / vs, click.y() / vs, click.button());
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        if (picker.isOpen()) return true;
        scroll.wheel(v);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (picker.isOpen()) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) { picker.cancel(); colorTarget = null; return true; }
            boolean was = picker.isOpen();
            boolean r = picker.keyPressed(input);
            if (was && !picker.isOpen() && colorTarget != null && colorSlot != null) { applyColour(colorSlot, colorTarget.get()); colorTarget = null; }
            return r;
        }
        if (kit.keyPressed(input.key(), input.modifiers())) return true;
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (picker.isOpen() && picker.charTyped(input)) return true;
        return super.charTyped(input);
    }

    @Override
    public void removed() {
        closePicker();
        super.removed();
    }
}
