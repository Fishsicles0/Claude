package net.evadeclient.cosmetics;

import net.evadeclient.gui.ColorPicker;
import net.evadeclient.gui.EvadePanelScreen;
import net.evadeclient.module.misc.Cosmetics;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The Wardrobe: a turning 3D preview of your own skin, a tab per slot, your items and the locked ones (try them on in
 * the preview; "Get it in the shop" opens the shop), colours for colourable items, six saved outfits and the shop link
 * code. Opened from the Evade menu's sidebar or with .wardrobe.
 */
public final class WardrobeScreen extends EvadePanelScreen {
    public static final String SHOP_URL = "https://evadeclient.com/shop";
    private static final int PREVIEW_W = 176, CARD_W = 104, CARD_H = 92, GAP = 6;
    private static Slot lastSlot = Slot.CAPE;

    private Slot slot = lastSlot;
    /** A locked item being tried on in the preview (this slot), or null. */
    private CosmeticItem trying;
    /** The emote looping in the preview. */
    private Emotes.Playing previewEmote;
    private float yaw = 200, pitch = -6;
    private boolean dragging, autoTurn = true;
    private int px1, py1, px2, py2;
    private int page;
    private final ColorPicker picker = new ColorPicker();
    private ColorSetting colorTarget;
    private Slot colorSlot;
    private String linkText;
    private final long opened = System.nanoTime();

    public WardrobeScreen(Screen parent) {
        super("Wardrobe", parent);
    }

    /** Opens over whatever is showing (the Evade menu comes back after). */
    public static void openFromCurrent() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new WardrobeScreen(mc.currentScreen));
    }

    @Override protected int prefWidth() { return 720; }
    @Override protected int prefHeight() { return 440; }

    @Override
    protected void init() {
        super.init();
        CosmeticAssets.preloadBundled();
        Wardrobe.refresh(true);
        if (CosmeticsCatalog.remoteVersion() == null) CosmeticsEngine.refreshCatalog();
    }

    @Override
    protected String subtitle() {
        Cosmetics m = Cosmetics.get();
        if (m != null && !m.isEnabled()) return "Cosmetics are off (or switched off by this server): turn the Cosmetics mod on to see them in game";
        return Wardrobe.status();
    }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        int x = button(ctx, "shop", "Shop", right, y, mx, my, 1, b -> openShop());
        button(ctx, "link", "Link the shop", x - 6, y, mx, my, 0, b -> link());
    }

    // ------------------------------------------------------------------ content

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        top = cy;   // nothing here scrolls: the item grid pages instead
        float time = (System.nanoTime() - opened) / 1e9f;
        if (autoTurn && !dragging) yaw += dt * 22f;

        // preview panel
        px1 = cx; py1 = top; px2 = cx + PREVIEW_W; py2 = cy + ch - 44;
        Theme.roundBordered(ctx, px1, py1, px2, py2, Theme.CARD, Theme.BORDER);
        Loadout shown = previewLoadout();
        WardrobePreview.draw(ctx, shown, previewEmote, yaw, pitch, time, px1 + 8, py1 + 10, px2 - 8, py2 - 22);
        text(ctx, "Drag to turn", px1 + 8, py2 - 14, Theme.TEXT_FAINT, Role.SMALL);
        if (trying != null) text(ctx, "Trying on", px2 - 8 - width("Trying on", Role.SMALL), py2 - 14, Theme.WARN, Role.SMALL);

        // outfits under the preview
        int oy = cy + ch - 38;
        text(ctx, "Outfits", cx, oy, Theme.TEXT_DIM, Role.LABEL);
        int ow = (PREVIEW_W - 5 * 4) / 6;
        for (int i = 0; i < Wardrobe.OUTFITS; i++) {
            int x1 = cx + i * (ow + 4), y1 = oy + 12;
            Wardrobe.Outfit o = Wardrobe.outfit(i);
            boolean hv = mx >= x1 && mx < x1 + ow && my >= y1 && my < y1 + 20;
            float h = hover("outfit" + i, hv);
            Theme.roundBordered(ctx, x1, y1, x1 + ow, y1 + 20, Theme.lerpColor(o != null ? Theme.CONTROL_HOVER : Theme.CONTROL, Theme.RED, h * 0.35f),
                    o != null ? Theme.alpha(Theme.RED, 0.6f) : Theme.BORDER);
            String label = String.valueOf(i + 1);
            text(ctx, label, x1 + (ow - width(label, Role.LABEL)) / 2, y1 + 6, o != null ? Theme.TEXT : Theme.TEXT_FAINT, Role.LABEL);
            final int idx = i;
            contentHit(x1, y1, x1 + ow, y1 + 20, b -> clickOutfit(idx, b));
        }

        // slot tabs
        int gx = cx + PREVIEW_W + 14, gw = cx + cw - gx;
        int tx = gx, ty = top;
        for (Slot s : Slot.values()) {
            int w = width(s.label, Role.LABEL) + 14;
            if (tx + w > gx + gw) { tx = gx; ty += 20; }
            boolean on = s == slot, hv = mx >= tx && mx < tx + w && my >= ty && my < ty + 16;
            float h = hover("tab" + s, hv);
            Theme.roundBordered(ctx, tx, ty, tx + w, ty + 16, on ? Theme.RED : Theme.lerpColor(Theme.CONTROL, Theme.CONTROL_HOVER, h),
                    on ? Theme.RED : Theme.BORDER);
            text(ctx, s.label, tx + 7, ty + 4, on ? Theme.ON_ACCENT : Theme.TEXT_SOFT, Role.LABEL);
            contentHit(tx, ty, tx + w, ty + 16, b -> selectSlot(s));
            tx += w + 4;
        }

        // item cards
        int gy = ty + 24;
        List<CosmeticItem> items = CosmeticsCatalog.forSlot(slot);
        int cols = Math.max(1, (gw + GAP) / (CARD_W + GAP));
        int rows = Math.max(1, (cy + ch - 44 - gy + GAP) / (CARD_H + GAP));
        int per = cols * rows, pages = Math.max(1, (items.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        if (items.isEmpty()) text(ctx, "Nothing here yet: new items arrive with the shop.", gx, gy + 4, Theme.TEXT_DIM, Role.BODY);
        for (int i = page * per; i < Math.min(items.size(), (page + 1) * per); i++) {
            int k = i - page * per;
            card(ctx, items.get(i), gx + (k % cols) * (CARD_W + GAP), gy + (k / cols) * (CARD_H + GAP), mx, my);
        }
        if (pages > 1) {
            int by = cy + ch - 18;
            String pg = (page + 1) + " / " + pages;
            text(ctx, pg, gx + gw - 60 - width(pg, Role.LABEL), by + 4, Theme.TEXT_DIM, Role.LABEL);
            button(ctx, "prev", "<", gx + gw - 30, by, mx, my, 0, b -> page = Math.max(0, page - 1));
            button(ctx, "next", ">", gx + gw, by, mx, my, 0, b -> page++);
        }

        // details of the selected slot: colour, emote, shop
        int dy = cy + ch - 38;
        Loadout.Worn worn = Wardrobe.equipped().get(slot);
        CosmeticItem cur = worn == null ? null : CosmeticsCatalog.get(worn.id());
        String line = trying != null ? trying.name + ": " + trying.description
                : cur != null ? "Wearing " + cur.name + ". " + cur.description : "Nothing in this slot. Click an item to wear it.";
        if (slot == Slot.EMOTE) line = "Emote wheel: " + KeySetting.keyName(emoteKey()) + " (change it in the Cosmetics mod). Moving ends an emote.";
        text(ctx, ellipsize(line, Role.LABEL, gw - 120), gx, dy, Theme.TEXT_DIM, Role.LABEL);
        if (linkText != null) text(ctx, ellipsize(linkText, Role.LABEL, gw - 120), gx, dy + 12, Theme.OK, Role.LABEL);
        int right = gx + gw - (pages > 1 ? 100 : 0);
        if (trying != null) {
            button(ctx, "getit", "Get it in the shop", right, dy + 8, mx, my, 1, b -> openShop());
        } else if (cur != null && cur.colourable) {
            int x = button(ctx, "colour", "Colour", right, dy + 8, mx, my, 0, b -> openColour(cur, worn, right));
            int c = cur.colorFor(worn);
            Theme.round(ctx, x - 20, dy + 10, x - 8, dy + 22, 0xFF000000 | (c < 0 ? 0xFFFFFF : c), 2);
        } else if (slot == Slot.EMOTE && previewEmote != null && Wardrobe.owns(previewEmote.item().id)) {
            CosmeticItem e = previewEmote.item();
            button(ctx, "play", "Play " + e.name, right, dy + 8, mx, my, 1, b -> { close(); Emotes.play(e); });
        }
        return ch;
    }

    private int emoteKey() {
        Cosmetics m = Cosmetics.get();
        return m == null ? -1 : m.emoteKey.get();
    }

    private void card(DrawContext ctx, CosmeticItem it, int x, int y, int mx, int my) {
        boolean owned = Wardrobe.owns(it.id);
        Loadout.Worn worn = Wardrobe.equipped().get(it.slot);
        boolean wearing = worn != null && worn.id().equals(it.id) && owned;
        boolean tryingThis = trying == it || (previewEmote != null && previewEmote.item() == it);
        boolean hv = mx >= x && mx < x + CARD_W && my >= y && my < y + CARD_H;
        float h = hover("card:" + it.id, hv);
        int border = wearing ? Theme.RED : tryingThis ? Theme.WARN : Theme.lerpColor(Theme.BORDER, it.rarity.color, 0.35f + h * 0.4f);
        Theme.roundBordered(ctx, x, y, x + CARD_W, y + CARD_H, Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h), border);
        // rarity strip
        Theme.round(ctx, x + 1, y + 1, x + CARD_W - 1, y + 3, Theme.alpha(it.rarity.color, 0.85f), 1);
        Identifier thumb = CosmeticAssets.thumb(it);
        int ts = 56;
        if (thumb != null) {
            int color = owned ? 0xFFFFFFFF : 0xB0FFFFFF;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, thumb, x + (CARD_W - ts) / 2, y + 6, 0, 0, ts, ts, 128, 128, 128, 128, color);
        }
        text(ctx, ellipsize(it.name, Role.LABEL, CARD_W - 10), x + 5, y + 64, Theme.TEXT, Role.LABEL);
        String sub = wearing ? "Wearing" : owned ? (it.slot == Slot.EMOTE ? "Owned · preview" : "Owned · click to wear")
                : "Locked · try on";
        int subColor = wearing ? Theme.RED : owned ? Theme.OK : Theme.TEXT_FAINT;
        text(ctx, ellipsize(it.partner != null ? it.partner + " · " + sub : sub, Role.SMALL, CARD_W - 10), x + 5, y + 76, subColor, Role.SMALL);
        contentHit(x, y, x + CARD_W, y + CARD_H, b -> clickItem(it));
    }

    private Loadout previewLoadout() {
        Loadout l = Wardrobe.renderLoadout();
        if (trying != null) l = l.with(trying.slot, new Loadout.Worn(trying.id, -1));
        return l;
    }

    // ------------------------------------------------------------------ actions (also used by the tests)

    public void selectSlot(Slot s) {
        slot = s;
        lastSlot = s;
        trying = null;
        page = 0;
        if (s != Slot.EMOTE) previewEmote = null;
        // turn to where the slot is best seen
        yaw = switch (s) { case CAPE, WINGS, BACK -> 200; case HAT, MASK, PET -> 20; default -> yaw; };
    }

    public Slot slot() { return slot; }
    public CosmeticItem trying() { return trying; }

    /** A card was clicked: wear or take off an owned item, try on a locked one, preview an emote. */
    public void clickItem(CosmeticItem it) {
        CosmeticAssets.request(it);
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
            Notifications.push("Wardrobe", "Wearing outfit " + (i + 1), true);
        }
    }

    private void openShop() {
        if (client != null) ConfirmLinkScreen.open(this, SHOP_URL);
    }

    private void link() {
        linkText = "Getting a code...";
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
        picker.open(colorTarget, anchorX, cy + ch - 30);
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

    // ------------------------------------------------------------------ input

    @Override
    protected void renderOverlay(DrawContext ctx, int mx, int my) {
        if (picker.isOpen()) picker.render(ctx, mx, my, width, height);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (picker.isOpen()) {
            if (picker.mouseClicked(click.x(), click.y(), click.button())) return true;
            closePicker();
            return true;
        }
        if (click.x() >= px1 && click.x() < px2 && click.y() >= py1 && click.y() < py2) {
            dragging = true;
            autoTurn = false;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (picker.isOpen()) return picker.mouseDragged(click.x(), click.y());
        if (dragging) {
            yaw += (float) dx * 2.5f;
            pitch = Math.max(-40, Math.min(40, pitch - (float) dy * 2.5f));
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = false;
        picker.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        if (v < 0) page++; else if (v > 0) page = Math.max(0, page - 1);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (picker.isOpen()) {
            if (picker.keyPressed(input)) return true;
            if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { picker.cancel(); colorTarget = null; return true; }
        }
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
