package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.mods.ModrinthApi;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.ShaderSwitch;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Shader Pack picker (Performance > Shader Pack): every pack in shaderpacks/, not only the two the installer adds.
 * Pick one and Apply to turn shaders on with it, or choose Off. Get More opens the Mod Browser on Shaders. All
 * switching goes through {@link ShaderSwitch}, so nothing here needs Iris' own screens.
 */
public final class ShaderPackScreen extends EvadePanelScreen {
    private static final int ROW = 34;

    private List<String> packs = List.of();
    /** The row picked (null = Off). */
    private String picked;
    private String lastClick = "";
    private long lastClickAt;

    public ShaderPackScreen(Screen parent) {
        super("Shader Packs", parent);
    }

    /** Test hook: the packs listed. */
    public List<String> packsForTest() { return packs; }

    @Override protected int prefWidth() { return 560; }
    @Override protected int prefHeight() { return 400; }
    @Override protected String headerIcon() { return "visuals"; }

    @Override
    protected String subtitle() {
        if (!ShaderSwitch.irisLoaded()) return "Shaders need Iris: run the Evade installer with Shaders on";
        return "Now: " + (ShaderSwitch.enabled() ? ShaderSwitch.selectedPack().map(ShaderSwitch::displayName).orElse("Off") : "Off")
                + " · pick a pack, then Apply (or double-click)";
    }

    @Override
    protected void init() {
        super.init();
        reload();
    }

    private void reload() {
        packs = ShaderSwitch.listPacks();
        Optional<String> sel = ShaderSwitch.selectedPack();
        picked = ShaderSwitch.enabled() && sel.isPresent() && packs.contains(sel.get()) ? sel.get() : null;
    }

    private void apply() {
        String msg = ShaderSwitch.applyPack(picked);
        Notifications.push("Shaders", msg, picked != null);
        reload();
    }

    private void openFolder() {
        try {
            Files.createDirectories(ShaderSwitch.packsDir());
            Util.getOperatingSystem().open(ShaderSwitch.packsDir());
        } catch (Exception e) {
            Notifications.push("Couldn't open the folder", String.valueOf(e.getMessage()), false);
        }
    }

    private void getMore() {
        ModBrowserScreen.setContentTypeForTest(ModrinthApi.ContentType.SHADER);
        if (client != null) client.setScreen(new ModBrowserScreen(this));
    }

    /** Rows: "Off" first, then every pack. */
    private List<String> rows() {
        List<String> r = new ArrayList<>();
        r.add(null);
        r.addAll(packs);
        return r;
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        List<String> r = rows();
        Optional<String> current = ShaderSwitch.enabled() ? ShaderSwitch.selectedPack() : Optional.empty();
        boolean iris = ShaderSwitch.irisLoaded();
        int y = top;
        if (!iris) {
            Paint.surface(ctx, cx, y, cx + cw, y + 34, Tokens.R_SM, Tokens.SUNKEN.withFill(Theme.alpha(Tokens.WARN, 0.08f)), 0, Float.NaN);
            Icons.draw(ctx, "warn", cx + 10, y + 11, 12, Tokens.WARN);
            kit.textCentredY("Shaders need Iris. Run the Evade installer again with Shaders switched on.", cx + 30, y + 17, Tokens.N950, Role.BODY);
            y += 44;
        }
        for (int i = 0; i < r.size(); i++) {
            String pack = r.get(i);
            String key = pack == null ? "#off" : pack;
            if (inView(y, ROW)) {
                float a = enter(i), fd = Theme.fade;
                Theme.fade = fd * a;
                int yy = y + Math.round((1 - a) * 6), y2 = yy + ROW - 3;
                try {
                    boolean on = pack == null ? picked == null : pack.equals(picked);
                    boolean hv = mouseIn(mx, my, cx, yy, cx + cw, y2);
                    rowSurface(cx, yy, cx + cw, y2, hover("row:" + key, hv), on);
                    float mid = (yy + y2) / 2f;
                    Paint.rect(ctx, cx + 8, mid - 11, cx + 30, mid + 11, Tokens.R_XS, on ? Theme.alpha(Tokens.A900, 0.7f) : Tokens.N250);
                    Icons.draw(ctx, pack == null ? "close" : "visuals", cx + 12, Math.round(mid - 7), 14, on ? Tokens.A400 : Tokens.N800);
                    String label = pack == null ? "Off" : ShaderSwitch.displayName(pack);
                    kit.textCentredY(ellipsize(label, Role.STRONG, cw - 160), cx + 40, mid - (pack == null ? 6 : 0), Tokens.N950, Role.STRONG);
                    if (pack == null) kit.textCentredY("No shaders: the game's own look", cx + 40, mid + 7, Tokens.N700, Role.SMALL);
                    boolean live = pack == null ? current.isEmpty() : current.isPresent() && current.get().equals(pack);
                    int rx = cx + cw - 10;
                    if (live) rx -= Badge.draw(kit, rx - Badge.width(kit, "In use"), Math.round(mid - Badge.H / 2f), "In use", Badge.Tone.OK) + Tokens.S3;
                    if (on) Icons.draw(ctx, "check", rx - 12, Math.round(mid - 6), 12, Tokens.A400);
                    contentHit("row:" + key, cx, yy, cx + cw, y2, b -> {
                        if (b != 0) return;
                        long now = System.currentTimeMillis();
                        boolean dbl = key.equals(lastClick) && now - lastClickAt < 400;
                        lastClick = key;
                        lastClickAt = now;
                        picked = pack;
                        if (dbl && ShaderSwitch.irisLoaded()) apply();
                    });
                } finally {
                    Theme.fade = fd;
                }
            }
            y += ROW;
        }
        if (packs.isEmpty()) {
            y += 8;
            y += EmptyState.draw(kit, "empty", cx + cw / 2, y, "visuals", "No packs yet", "Get more finds shader packs on Modrinth, or drop .zip packs in the folder.",
                    "Get more", this::getMore);
        }
        return y - top + 6;
    }

    @Override protected int footerHeight() { return 44; }

    @Override
    protected void renderFooter(DrawContext ctx, int x1, int y, int x2, int mx, int my) {
        int by = y + 10;
        int gw = Button.width(kit, "Get more", Button.Size.MD, "plus", false);
        drawButton("more", "Get more", "plus", x1 - 6, by, gw, Button.Size.MD, GHOST, true, b -> getMore());
        int aw = Button.width(kit, "Apply", Button.Size.MD, null, false);
        drawButton("apply", "Apply", null, x2 - aw, by, aw, Button.Size.MD, PRIMARY, ShaderSwitch.irisLoaded(), b -> apply());
        int fw = Button.width(kit, "Open folder", Button.Size.MD, "folder", false);
        drawButton("folder", "Open folder", "folder", x2 - aw - Tokens.S3 - fw, by, fw, Button.Size.MD, SECONDARY, true, b -> openFolder());
    }
}
