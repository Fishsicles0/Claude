package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.misc.PackFeatures;
import net.evadeclient.module.misc.PackFeatures.Feature;
import net.evadeclient.module.misc.PackFeatures.Report;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Progress;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
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
public final class PackFeaturesScreen extends EvadePanelScreen {
    static final String HOW_TO = "Turn on \"Resource pack features\" for this instance in the Evade Launcher (Instances), then press Play. "
            + "It adds Continuity, CIT Resewn, Entity Texture and Model Features and Skyboxify from Modrinth.";

    private List<Report> reports;

    public PackFeaturesScreen(Screen parent) {
        super("What your packs need", parent);
    }

    /** Test hook: the scan, or null while it runs. */
    public List<Report> reportsForTest() { return reports; }

    @Override protected int prefWidth() { return 600; }
    @Override protected int prefHeight() { return 420; }
    @Override protected String headerIcon() { return "mods"; }
    @Override protected String subtitle() { return "Extra formats each resource pack uses, and whether the mod that reads it is installed"; }

    @Override
    protected void init() {
        super.init();
        if (reports == null) {
            var dir = MinecraftClient.getInstance().getResourcePackDir();
            CompletableFuture.supplyAsync(() -> PackFeatures.scanAll(dir), Util.getIoWorkerExecutor())
                    .thenAccept(r -> MinecraftClient.getInstance().execute(() -> { reports = r; restartEntrance(); }));
        }
    }

    @Override protected int footerHeight() { return 44; }

    @Override
    protected void renderFooter(DrawContext ctx, int x1, int y, int x2, int mx, int my) {
        int fw = Button.width(kit, "Open pack folder", Button.Size.MD, "folder", false);
        drawButton("folder", "Open pack folder", "folder", x2 - fw, y + 10, fw, Button.Size.MD, SECONDARY, true,
                b -> Util.getOperatingSystem().open(MinecraftClient.getInstance().getResourcePackDir()));
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        if (reports == null) {
            Progress.circle(kit, cx + cw / 2f, top + 50, 14, Float.NaN);
            String m = "Reading your packs";
            kit.textCentredY(m, cx + (cw - width(m, Role.SMALL)) / 2, top + 80, Tokens.N700, Role.SMALL);
            return 110;
        }
        if (reports.isEmpty()) {
            return EmptyState.draw(kit, "empty", cx + cw / 2, top + 20, "mods", "No resource packs", "Put packs in your resourcepacks folder and they show up here.", null, null) + 40;
        }
        int y = top;
        boolean anyFixable = false;
        int i = 0;
        for (Report r : reports) {
            // One card per pack: the name, then a line per feature with a status icon.
            int lines = Math.max(1, r.features().size()) + (r.xray() ? 1 : 0);
            int h = 30 + lines * 16 + 6;
            if (inView(y, h)) {
                float a = enter(i), fd = Theme.fade;
                Theme.fade = fd * a;
                int yy = y + Math.round((1 - a) * 6);
                try {
                    Paint.surface(ctx, cx, yy, cx + cw, yy + h, Tokens.R_MD, Tokens.E2.noAmbient(), 0, Float.NaN);
                    Paint.rect(ctx, cx + 10, yy + 7, cx + 28, yy + 25, Tokens.R_XS, Tokens.N250);
                    Icons.draw(ctx, "mods", cx + 13, yy + 10, 12, Tokens.N800);
                    kit.textCentredY(ellipsize(r.name(), Role.STRONG, cw - 140), cx + 36, yy + 16, Tokens.N950, Role.STRONG);
                    int ly = yy + 30;
                    if (r.features().isEmpty() && !r.xray()) {
                        line(ctx, "check", Tokens.N600, "Plain resource pack: works as it is.", ly);
                        ly += 16;
                    }
                    for (Feature f : r.features()) {
                        if (f.supported()) line(ctx, "check", Tokens.OK, f.label + " · works (" + f.modName + ")", ly);
                        else if (f.modId != null) line(ctx, "warn", Tokens.WARN, f.label + " · needs " + f.modName, ly);
                        else line(ctx, "info", Tokens.N600, f.label + " · no mod reads this yet; the rest of the pack works", ly);
                        ly += 16;
                    }
                    if (r.xray()) line(ctx, "error", Tokens.DANGER, "X-ray warning: it makes stone and ground see-through. Most servers ban this.", ly);
                    int need = 0;
                    for (Feature f : r.features()) if (!f.supported() && f.modId != null) need++;
                    String tag = r.xray() ? "X-ray" : need > 0 ? need + " missing" : "Ready";
                    Badge.Tone tone = r.xray() ? Badge.Tone.DANGER : need > 0 ? Badge.Tone.WARN : Badge.Tone.OK;
                    Badge.draw(kit, cx + cw - 10 - Badge.width(kit, tag), yy + 9, tag, tone);
                } finally {
                    Theme.fade = fd;
                }
            }
            for (Feature f : r.features()) if (!f.supported() && f.modId != null) anyFixable = true;
            y += h + Tokens.S3;
            i++;
        }
        if (anyFixable) {
            y += Tokens.S3;
            y = section("GETTING THE MISSING MODS", null, cx, y);
            for (String l : kit.wrap(HOW_TO, Role.BODY, cw, 6)) {
                if (inView(y, 12)) kit.text(l, cx, y, Tokens.N800, Role.BODY);
                y += 12;
            }
        }
        return y - top + 8;
    }

    private void line(DrawContext ctx, String icon, int color, String text, int y) {
        Icons.draw(ctx, icon, cx + 14, y + 1, 10, color);
        kit.text(ellipsize(text, Role.LABEL, cw - 44), cx + 30, y, Tokens.N900, Role.LABEL);
    }
}
