package net.evadeclient.replay;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Which HUD mods show in this replay: every HUD widget you have on (FPS, Keystrokes, CPS, coordinates, the module
 * list...), each one click to hide or show. Saved with the replay's project; applies while watching it and in its
 * videos (when the video's HUD is on). Your normal HUD isn't changed.
 */
public final class ReplayHudScreen extends Screen {
    private final Screen parent;
    private final ReplayProject project;
    private int page;

    public ReplayHudScreen(Screen parent, ReplayProject project) {
        super(Text.literal("HUD mods in this replay"));
        this.parent = parent;
        this.project = project;
    }

    private static List<HudElementModule> widgets() {
        List<HudElementModule> out = new ArrayList<>();
        if (EvadeClient.modules != null) for (HudElementModule m : EvadeClient.modules.hudElements()) if (m.isEnabled()) out.add(m);
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    @Override
    protected void init() {
        List<HudElementModule> all = widgets();
        int cols = width >= 420 ? 2 : 1, colW = 180, rowH = 22, top = 44;
        int rows = Math.max(1, (height - top - 60) / rowH), perPage = rows * cols;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        int left = width / 2 - (cols * colW + (cols - 1) * 8) / 2;
        for (int i = 0; i < perPage && page * perPage + i < all.size(); i++) {
            HudElementModule m = all.get(page * perPage + i);
            int c = i / rows, r = i % rows;
            addDrawableChild(ButtonWidget.builder(label(m), b -> {
                if (!project.scene.hiddenHud.remove(m.getName())) project.scene.hiddenHud.add(m.getName());
                b.setMessage(label(m));
            }).dimensions(left + c * (colW + 8), top + r * rowH, colW, 20).build());
        }
        int by = height - 30;
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { page = (page + pages - 1) % pages; clearAndInit(); })
                    .dimensions(width / 2 - 154, by, 30, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page = (page + 1) % pages; clearAndInit(); })
                    .dimensions(width / 2 + 124, by, 30, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Show all"), b -> { project.scene.hiddenHud.clear(); clearAndInit(); })
                .dimensions(width / 2 - 118, by, 116, 20).tooltip(Tooltip.of(Text.literal("Show every HUD mod again"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(width / 2 + 2, by, 116, 20).build());
    }

    private Text label(HudElementModule m) {
        boolean hidden = project.scene.hiddenHud.contains(m.getName());
        return Text.literal((hidden ? "§8✗ " : "§a✔ ") + m.getName() + (hidden ? " §8(hidden)" : ""));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, "HUD mods in this replay", width / 2, 14, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, widgets().isEmpty() ? "You have no HUD mods on." : "Click one to hide it while watching and in videos. Your normal HUD stays as it is.",
                width / 2, 27, 0xFF9A9AA6);
    }

    @Override
    public void close() {
        if (ReplayPlayer.current() != null) ReplayPlayer.current().saveProject();
        client.setScreen(parent);
    }
}
