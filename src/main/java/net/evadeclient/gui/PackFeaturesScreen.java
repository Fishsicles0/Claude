package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.misc.PackFeatures;
import net.evadeclient.module.misc.PackFeatures.Feature;
import net.evadeclient.module.misc.PackFeatures.Report;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pack Organizer's "What do my packs need?" list: every resource pack in resourcepacks/, the extra formats it uses
 * (connected textures, custom item textures, random entities, entity models, custom skies...), whether the mod that
 * reads each one is installed, and how to get it. Packs that make stone see-through get an x-ray warning (a warning
 * only). The scan runs off the render thread.
 */
public final class PackFeaturesScreen extends Screen {
    static final String HOW_TO = "Turn on \"Resource pack features\" for this instance in the Evade Launcher (Instances), then press Play. "
            + "It adds Continuity, CIT Resewn, Entity Texture and Model Features and Skyboxify from Modrinth.";

    private final Screen parent;
    private List<Report> reports;
    private int px, py, pw, ph, listY, listH;
    private int scroll, contentH;

    public PackFeaturesScreen(Screen parent) {
        super(Text.literal("Pack Features"));
        this.parent = parent;
    }

    /** Test hook: the scan, or null while it runs. */
    public List<Report> reportsForTest() { return reports; }

    @Override
    protected void init() {
        pw = Math.min(420, width - 20);
        ph = Math.min(300, height - 20);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        listY = py + 46;
        listH = ph - 46 - 34;
        if (reports == null) {
            var dir = MinecraftClient.getInstance().getResourcePackDir();
            CompletableFuture.supplyAsync(() -> PackFeatures.scanAll(dir), Util.getIoWorkerExecutor())
                    .thenAccept(r -> MinecraftClient.getInstance().execute(() -> reports = r));
        }
        int bw = (pw - 28 - 4) / 2, by = py + ph - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Open Pack Folder"), b -> Util.getOperatingSystem().open(MinecraftClient.getInstance().getResourcePackDir()))
                .dimensions(px + 14, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(px + 14 + bw + 4, by, bw, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        scroll = (int) Math.max(0, Math.min(Math.max(0, contentH - listH), scroll - v * 16));
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
        UiFont.draw(ctx, textRenderer, "What your packs need", px + 14, py + 12, Theme.TEXT, Role.TITLE, smooth);
        UiFont.draw(ctx, textRenderer, "Extra formats each resource pack uses, and whether the mod that reads it is installed.",
                px + 14, py + 30, Theme.TEXT_DIM, Role.SMALL, smooth);
        int x1 = px + 14, x2 = px + pw - 14, w = x2 - x1;
        if (reports == null) {
            UiFont.draw(ctx, textRenderer, "Reading your packs...", x1, listY + 6, Theme.TEXT_DIM, Role.BODY, smooth);
            return;
        }
        List<String[]> lines = layout(w, smooth);
        contentH = 0;
        for (String[] l : lines) contentH += lineH(l);
        scroll = Math.max(0, Math.min(Math.max(0, contentH - listH), scroll));
        ctx.enableScissor(x1, listY, x2, listY + listH);
        int y = listY - scroll;
        for (String[] l : lines) {
            int h = lineH(l);
            if (y + h >= listY && y <= listY + listH) {
                switch (l[0]) {
                    case "pack" -> {
                        Theme.fill(ctx, x1, y + 1, x2, y + 2, Theme.DIVIDER);
                        UiFont.draw(ctx, textRenderer, l[1], x1, y + 6, Theme.TEXT, Role.STRONG, smooth);
                    }
                    case "ok" -> UiFont.draw(ctx, textRenderer, l[1], x1 + 8, y + 2, Theme.OK, Role.LABEL, smooth);
                    case "miss" -> UiFont.draw(ctx, textRenderer, l[1], x1 + 8, y + 2, Theme.WARN, Role.LABEL, smooth);
                    case "warn" -> UiFont.draw(ctx, textRenderer, l[1], x1 + 8, y + 2, Theme.DANGER, Role.LABEL, smooth);
                    default -> UiFont.draw(ctx, textRenderer, l[1], x1 + 8, y + 2, Theme.TEXT_DIM, Role.LABEL, smooth);
                }
            }
            y += h;
        }
        ctx.disableScissor();
    }

    private static int lineH(String[] l) { return l[0].equals("pack") ? 18 : 11; }

    /** The list as typed lines: a pack heading, then one line per feature, warning or hint. */
    private List<String[]> layout(int w, boolean smooth) {
        List<String[]> out = new ArrayList<>();
        boolean anyFixable = false;
        if (reports.isEmpty()) out.add(new String[]{"dim", "No resource packs in your resourcepacks folder."});
        for (Report r : reports) {
            out.add(new String[]{"pack", ellipsize(r.name(), w, Role.STRONG, smooth)});
            if (r.features().isEmpty() && !r.xray()) out.add(new String[]{"dim", "Plain resource pack: works as it is."});
            for (Feature f : r.features()) {
                if (f.supported()) out.add(new String[]{"ok", f.label + ": works (" + f.modName + ")"});
                else if (f.modId != null) {
                    out.add(new String[]{"miss", f.label + ": needs " + f.modName});
                    anyFixable = true;
                } else out.add(new String[]{"dim", f.label + ": no mod reads this format yet; the pack's other parts still work"});
            }
            if (r.xray()) out.add(new String[]{"warn", "X-ray warning: it makes stone and ground see-through. Most servers ban this."});
        }
        if (anyFixable) {
            out.add(new String[]{"pack", "Getting the missing mods"});
            for (String line : wrap(HOW_TO, w - 8, smooth)) out.add(new String[]{"dim", line});
        }
        return out;
    }

    private String ellipsize(String s, int max, Role r, boolean smooth) {
        if (UiFont.width(textRenderer, s, r, smooth) <= max) return s;
        while (s.length() > 1 && UiFont.width(textRenderer, s + "...", r, smooth) > max) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private List<String> wrap(String s, int max, boolean smooth) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (UiFont.width(textRenderer, next, Role.LABEL, smooth) > max && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
