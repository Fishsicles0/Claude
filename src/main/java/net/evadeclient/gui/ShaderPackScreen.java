package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.mods.ModrinthApi;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.ShaderSwitch;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
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
public final class ShaderPackScreen extends Screen {
    private static final int ROW = 20;

    private final Screen parent;
    private List<String> packs = List.of();
    /** The row picked (null = Off). */
    private String picked;
    private int px, py, pw, ph, listY, listH;
    private int scroll;

    public ShaderPackScreen(Screen parent) {
        super(Text.literal("Shader Packs"));
        this.parent = parent;
    }

    /** Test hook: the packs listed. */
    public List<String> packsForTest() { return packs; }

    @Override
    protected void init() {
        reload();
        pw = Math.min(340, width - 20);
        ph = Math.min(260, height - 20);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        listY = py + 46;
        listH = ph - 46 - 36;
        int bw = (pw - 28 - 3 * 4) / 4, by = py + ph - 28;
        boolean iris = ShaderSwitch.irisLoaded();
        ButtonWidget apply = ButtonWidget.builder(Text.literal("Apply"), b -> apply()).dimensions(px + 14, by, bw, 20).build();
        apply.active = iris;
        addDrawableChild(apply);
        addDrawableChild(ButtonWidget.builder(Text.literal("Open Folder"), b -> openFolder()).dimensions(px + 14 + (bw + 4), by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Get More"), b -> {
            ModBrowserScreen.setContentTypeForTest(ModrinthApi.ContentType.SHADER);
            if (client != null) client.setScreen(new ModBrowserScreen(this));
        }).dimensions(px + 14 + (bw + 4) * 2, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(px + 14 + (bw + 4) * 3, by, bw, 20).build());
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

    /** Rows: "Off" first, then every pack. */
    private List<String> rows() {
        List<String> r = new ArrayList<>();
        r.add(null);
        r.addAll(packs);
        return r;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && click.x() >= px + 14 && click.x() < px + pw - 14 && click.y() >= listY && click.y() < listY + listH) {
            int i = (int) ((click.y() - listY + scroll) / ROW);
            List<String> r = rows();
            if (i >= 0 && i < r.size()) {
                picked = r.get(i);
                if (doubled && ShaderSwitch.irisLoaded()) apply();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        int max = Math.max(0, rows().size() * ROW - listH);
        scroll = (int) Math.max(0, Math.min(max, scroll - v * ROW));
        return true;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        if (s == null || s.blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xB0000000, 0xD0000000);
        Theme.shadow(ctx, Theme.SH_WINDOW, px, py, px + pw, py + ph, 0x90000000);
        Theme.round(ctx, px, py, px + pw, py + ph, Theme.BG, Theme.R_WIN);
        Theme.stroke(ctx, px, py, px + pw, py + ph, Theme.BORDER, Theme.R_WIN);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        boolean smooth = s == null || s.smoothFont.get();
        UiFont.draw(ctx, textRenderer, "Shader Packs", px + 14, py + 12, Theme.TEXT, Role.TITLE, smooth);
        String sub = !ShaderSwitch.irisLoaded() ? "Shaders need Iris: run the Evade installer with Shaders on."
                : packs.isEmpty() ? "No packs yet. Get More finds them on Modrinth."
                : "Now: " + (ShaderSwitch.enabled() ? ShaderSwitch.selectedPack().map(ShaderSwitch::displayName).orElse("Off") : "Off")
                        + ". Pick a pack, then Apply (or double-click).";
        UiFont.draw(ctx, textRenderer, sub, px + 14, py + 30, ShaderSwitch.irisLoaded() ? Theme.TEXT_DIM : Theme.WARN, Role.SMALL, smooth);

        List<String> r = rows();
        int max = Math.max(0, r.size() * ROW - listH);
        scroll = Math.max(0, Math.min(max, scroll));
        int x1 = px + 14, x2 = px + pw - 14;
        ctx.enableScissor(x1, listY, x2, listY + listH);
        Optional<String> current = ShaderSwitch.enabled() ? ShaderSwitch.selectedPack() : Optional.empty();
        for (int i = 0; i < r.size(); i++) {
            int y = listY + i * ROW - scroll;
            if (y + ROW < listY || y > listY + listH) continue;
            String pack = r.get(i);
            boolean on = pack == null ? picked == null : pack.equals(picked);
            boolean hv = mx >= x1 && mx < x2 && my >= Math.max(y, listY) && my < Math.min(y + ROW, listY + listH);
            Theme.round(ctx, x1, y + 1, x2, y + ROW - 1, on ? Theme.tint(0.18f) : hv ? Theme.ROW_HOVER : Theme.ROW, Theme.R);
            if (on) Theme.round(ctx, x1, y + 5, x1 + 2, y + ROW - 5, Theme.RED, 1);
            String label = pack == null ? "Off (no shaders)" : ShaderSwitch.displayName(pack);
            UiFont.draw(ctx, textRenderer, label, x1 + 8, y + 6, on ? Theme.TEXT : Theme.TEXT_SOFT, Role.BODY, smooth);
            boolean live = pack == null ? current.isEmpty() : current.isPresent() && current.get().equals(pack);
            if (live) {
                String tag = "IN USE";
                int tw = UiFont.width(textRenderer, tag, Role.CAPS, smooth);
                UiFont.draw(ctx, textRenderer, tag, x2 - 8 - tw, y + 7, Theme.OK, Role.CAPS, smooth);
            }
        }
        ctx.disableScissor();
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
