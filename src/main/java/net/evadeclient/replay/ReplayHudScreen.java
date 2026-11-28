package net.evadeclient.replay;

import net.evadeclient.gui.EvadePanelScreen;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Toggle;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Which HUD mods show in this replay: every HUD widget you have on (FPS, Keystrokes, CPS, coordinates, the module
 * list...), each one click to hide or show. Saved with the replay's project; applies while watching it and in its
 * videos (when the video's HUD is on). Your normal HUD isn't changed.
 */
public final class ReplayHudScreen extends EvadePanelScreen {
    private final ReplayProject project;

    public ReplayHudScreen(Screen parent, ReplayProject project) {
        super("HUD mods in this replay", parent);
        this.project = project;
    }

    private static List<HudElementModule> widgets() {
        List<HudElementModule> out = new ArrayList<>();
        if (EvadeClient.modules != null) for (HudElementModule m : EvadeClient.modules.hudElements()) if (m.isEnabled()) out.add(m);
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    @Override protected int prefWidth() { return 600; }
    @Override protected int prefHeight() { return 420; }
    @Override protected String headerIcon() { return "hud"; }

    @Override
    protected String subtitle() {
        return "Switch one off to hide it while watching and in videos · your normal HUD stays as it is";
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        List<HudElementModule> all = widgets();
        if (all.isEmpty()) {
            return EmptyState.draw(kit, "empty", cx + cw / 2, top + 30, "hud", "No HUD mods on", "Switch some on in the menu and they show up here.", null, null) + 50;
        }
        int cols = cw >= 420 ? 2 : 1, gap = Tokens.S3, rowH = 34;
        int colW = (cw - (cols - 1) * gap) / cols;
        int shown = 0;
        for (HudElementModule m : all) if (!project.scene.hiddenHud.contains(m.getName())) shown++;
        int y = section("SHOWN IN THIS REPLAY", shown + " of " + all.size(), cx, top);
        for (int i = 0; i < all.size(); i++) {
            HudElementModule m = all.get(i);
            int c = i % cols, r = i / cols;
            int x = cx + c * (colW + gap), yy = y + r * rowH;
            if (!inView(yy, rowH)) continue;
            float a = enter(i), fd = Theme.fade;
            Theme.fade = fd * a;
            try {
                boolean on = !project.scene.hiddenHud.contains(m.getName());
                int y2 = yy + rowH - 4;
                boolean hv = mouseIn(mx, my, x, yy, x + colW, y2);
                rowSurface(x, yy, x + colW, y2, hover("w:" + m.getName(), hv), false);
                float mid = (yy + y2) / 2f;
                Paint.rect(ctx, x + 6, mid - 10, x + 26, mid + 10, Tokens.R_XS, Tokens.N250);
                Icons.draw(ctx, "hud", x + 10, Math.round(mid - 6), 12, on ? Tokens.A400 : Tokens.N600);
                kit.textCentredY(ellipsize(m.getName(), Role.STRONG, colW - 80), x + 34, mid, on ? Tokens.N950 : Tokens.N700, Role.STRONG);
                Runnable flip = () -> { if (!project.scene.hiddenHud.remove(m.getName())) project.scene.hiddenHud.add(m.getName()); };
                contentHit("w:" + m.getName(), x, yy, x + colW, y2, b -> { if (b == 0) flip.run(); });
                Toggle.draw(kit, "t:" + m.getName(), x + colW - 8 - Toggle.MD_W, Math.round(mid - Toggle.MD_H / 2f), false, on, true, flip);
            } finally {
                Theme.fade = fd;
            }
        }
        int rows = (all.size() + cols - 1) / cols;
        return y - top + rows * rowH + 6;
    }

    @Override protected int footerHeight() { return 44; }

    @Override
    protected void renderFooter(DrawContext ctx, int x1, int y, int x2, int mx, int my) {
        int by = y + 10;
        int sw = Button.width(kit, "Show all", Button.Size.MD, null, false);
        drawButton("all", "Show all", null, x1 - 6, by, sw, Button.Size.MD, GHOST, !project.scene.hiddenHud.isEmpty(), b -> project.scene.hiddenHud.clear());
        int dw = Button.width(kit, "Done", Button.Size.MD, null, false);
        drawButton("done", "Done", null, x2 - dw, by, dw, Button.Size.MD, PRIMARY, true, b -> close());
    }

    @Override
    public void close() {
        if (ReplayPlayer.current() != null) ReplayPlayer.current().saveProject();
        client.setScreen(parent);
    }
}
