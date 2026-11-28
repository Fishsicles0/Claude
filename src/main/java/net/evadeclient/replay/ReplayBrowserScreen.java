package net.evadeclient.replay;

import net.evadeclient.gui.EvadePanelScreen;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import org.lwjgl.glfw.GLFW;
import java.util.Locale;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Your saved replays: watch, rename, delete, and the render queue. On the Evade panel frame (kit). */
public final class ReplayBrowserScreen extends EvadePanelScreen {
    private final List<Path> files = new ArrayList<>();
    private final List<ReplayFile.Meta> metas = new ArrayList<>();
    private int selected = -1;
    private boolean renaming;
    private String lastClick;
    private long lastClickAt;
    private static final int ROW = 38;
    private static final String NAME = "rename";

    public ReplayBrowserScreen(Screen parent) {
        super("Replays", parent);
    }

    @Override protected int prefWidth() { return 700; }
    @Override protected int prefHeight() { return 440; }
    @Override protected String headerIcon() { return "camera"; }

    @Override
    protected String subtitle() {
        int n = RenderQueue.jobs().size();
        return files.size() + (files.size() == 1 ? " replay" : " replays") + (n > 0 ? " · " + n + " in the render queue" : "")
                + (RenderQueue.running() ? " · rendering" : "");
    }

    @Override
    protected void init() {
        super.init();
        files.clear();
        metas.clear();
        for (Path p : ReplayFile.list()) {
            try { metas.add(ReplayFile.readMeta(p)); files.add(p); } catch (Exception ignored) {}
        }
        if (selected >= files.size()) selected = files.size() - 1;
    }

    private boolean has() { return selected >= 0 && selected < files.size(); }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        // Render queue: run or stop it, clear it, and whether new renders get a share link.
        int n = RenderQueue.jobs().size();
        int x = right;
        String runL = RenderQueue.running() ? "Stop queue" : "Render queue · " + n;
        int rw = Button.width(kit, runL, Button.Size.SM, null, false);
        drawButton("run", runL, null, x - rw, y, rw, Button.Size.SM, SECONDARY, RenderQueue.running() || n > 0, b -> {
            if (RenderQueue.running()) RenderQueue.stop(); else RenderQueue.start();
        });
        x -= rw + Tokens.S2;
        if (n > 0 && !RenderQueue.running()) x = button("clear", "Clear", null, x, y, GHOST, b -> RenderQueue.clear()) - Tokens.S2;
        x = button("share", "Share links", null, x, y, RenderQueue.shareNew ? SELECTED : GHOST, b -> RenderQueue.shareNew = !RenderQueue.shareNew);
        kit.tooltip("share", "Queued renders also get a share link through Evade Clips", "Needs a share server set up there", x, y, x + 100, y + 20);
        headerReach(x);
    }

    private void watch() {
        if (!has()) return;
        try { ReplayPlayer.open(files.get(selected), new ReplayBrowserScreen(parent)); }
        catch (Exception e) { net.evadeclient.util.Notifications.push("Replay", "Couldn't open: " + e.getMessage(), false); }
    }

    private void queue() {
        if (!has()) return;
        RenderQueue.add(files.get(selected), RenderQueue.shareNew);
        net.evadeclient.util.Notifications.push("Render queue", "Queued " + metas.get(selected).name + " (" + RenderQueue.jobs().size() + " in the queue)", true);
    }

    private void startRename() {
        if (!has()) return;
        renaming = true;
        SearchField.set(kit, NAME, metas.get(selected).name);
        kit.focus(NAME, false);
    }

    private void finishRename() {
        if (!has() || !renaming) return;
        String name = SearchField.text(kit, NAME).trim();
        renaming = false;
        kit.focus(null, false);
        if (name.isEmpty()) return;
        try {
            ReplayFile.Meta m = metas.get(selected);
            m.name = name;
            ReplayFile.updateMeta(files.get(selected), m);
            Path to = files.get(selected).resolveSibling(name.replaceAll("[\\\\/:*?\"<>|]+", "_") + "." + ReplayFile.EXT);
            if (!Files.exists(to)) {
                Path side = ReplayPlayer.projectFile(files.get(selected));
                Files.move(files.get(selected), to);
                if (Files.exists(side)) Files.move(side, ReplayPlayer.projectFile(to));
            }
        } catch (Exception e) {
            net.evadeclient.util.Notifications.push("Replay", "Couldn't rename: " + e.getMessage(), false);
        }
        clearAndInit();
    }

    private void confirmDelete() {
        if (!has()) return;
        Path f = files.get(selected);
        client.setScreen(new ConfirmScreen(yes -> {
            if (yes) try { Files.deleteIfExists(f); Files.deleteIfExists(ReplayPlayer.projectFile(f)); } catch (Exception ignored) {}
            client.setScreen(this);
        }, Text.literal("Delete this replay?"), Text.literal(metas.get(selected).name)));
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int key = input.key();
        if (renaming) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { finishRename(); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE) { renaming = false; kit.focus(null, false); return true; }
        } else if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && has() && kit.focused() == null) { watch(); return true; }
        return super.keyPressed(input);
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        if (files.isEmpty()) {
            return EmptyState.draw(kit, "empty", cx + cw / 2, top + 30, "camera", "No replays yet",
                    "Press Esc in game, Start recording, then Save replay.", null, null) + 50;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
        int y = top;
        for (int i = 0; i < files.size(); i++) {
            if (inView(y, ROW)) {
                float a = enter(i), fd = Theme.fade;
                Theme.fade = fd * a;
                int yy = y + Math.round((1 - a) * 6), y2 = yy + ROW - 3;
                try {
                    ReplayFile.Meta m = metas.get(i);
                    boolean sel = i == selected;
                    String key = files.get(i).getFileName().toString();
                    boolean hv = mouseIn(mx, my, cx, yy, cx + cw, y2);
                    rowSurface(cx, yy, cx + cw, y2, hover("r:" + key, hv), sel);
                    float mid = (yy + y2) / 2f;
                    Paint.rect(ctx, cx + 8, mid - 12, cx + 32, mid + 12, Tokens.R_XS, sel ? Theme.alpha(Tokens.A900, 0.7f) : Tokens.N250);
                    Icons.draw(ctx, "camera", cx + 13, Math.round(mid - 7), 14, sel ? Tokens.A400 : Tokens.N800);
                    kit.textCentredY(ellipsize(m.name, Role.STRONG, cw - 200), cx + 42, mid - 6, Tokens.N950, Role.STRONG);
                    String info = fmt.format(Instant.ofEpochMilli(m.recordedAt)) + "  ·  " + (m.sizeBytes < 1_000_000 ? Math.max(1, m.sizeBytes / 1000) + " KB"
                            : String.format(Locale.ROOT, "%.1f MB", m.sizeBytes / 1e6)) + (m.markers.isEmpty() ? "" : "  ·  " + m.markers.size() + " markers");
                    kit.textCentredY(ellipsize(info, Role.SMALL, cw - 200), cx + 42, mid + 7, Tokens.N700, Role.SMALL);
                    String dur = Replays.time(m.keptTicks() / 20.0);
                    int dw = UiFont.tabularWidth(textRenderer, dur, Role.STRONG, smooth);
                    UiFont.drawTabular(ctx, textRenderer, dur, cx + cw - 12 - dw, Math.round(UiFont.centredY(Role.STRONG, mid, smooth)), Tokens.N900, Role.STRONG, smooth);
                    int idx = i;
                    contentHit("r:" + key, cx, yy, cx + cw, y2, b -> {
                        long now = System.currentTimeMillis();
                        boolean dbl = key.equals(lastClick) && now - lastClickAt < 400;
                        lastClick = key;
                        lastClickAt = now;
                        if (selected != idx) renaming = false;
                        selected = idx;
                        if (dbl) watch();
                    });
                } finally {
                    Theme.fade = fd;
                }
            }
            y += ROW;
        }
        return y - top + 6;
    }

    @Override protected int footerHeight() { return 44; }

    @Override
    protected void renderFooter(DrawContext ctx, int x1, int y, int x2, int mx, int my) {
        int by = y + 10;
        int fw = Button.width(kit, "Open folder", Button.Size.MD, "folder", false);
        drawButton("folder", "Open folder", "folder", x1 - 6, by, fw, Button.Size.MD, GHOST, true, b -> {
            try { Files.createDirectories(ReplayFile.dir()); } catch (Exception ignored) {}
            Util.getOperatingSystem().open(ReplayFile.dir().toFile());
        });
        if (renaming && has()) {
            int sw = Button.width(kit, "Save name", Button.Size.MD, null, false);
            int fx = x1 + fw + Tokens.S3;
            SearchField.draw(kit, NAME, fx, by, x2 - sw - Tokens.S3 - fx, "Replay name", null, false);
            String t = SearchField.text(kit, NAME);
            if (t.length() > 80) SearchField.set(kit, NAME, t.substring(0, 80));
            drawButton("save", "Save name", null, x2 - sw, by, sw, Button.Size.MD, PRIMARY, true, b -> finishRename());
            return;
        }
        boolean h = has();
        int ww2 = Button.width(kit, "Watch", Button.Size.MD, null, false);
        int x = x2 - ww2;
        drawButton("watch", "Watch", null, x, by, ww2, Button.Size.MD, PRIMARY, h, b -> watch());
        int qw = Button.width(kit, "Queue render", Button.Size.MD, null, false);
        x -= qw + Tokens.S3;
        drawButton("queue", "Queue render", null, x, by, qw, Button.Size.MD, SECONDARY, h, b -> queue());
        int rw = Button.width(kit, "Rename", Button.Size.MD, "edit", false);
        x -= rw + Tokens.S2;
        drawButton("rename", "Rename", "edit", x, by, rw, Button.Size.MD, GHOST, h, b -> startRename());
        int dw = Button.width(kit, "Delete", Button.Size.MD, null, false);
        x -= dw + Tokens.S2;
        drawButton("delete", "Delete", null, x, by, dw, Button.Size.MD, DANGER, h, b -> confirmDelete());
    }
}
