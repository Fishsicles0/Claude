package net.evadeclient.replay;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Your saved replays: watch, rename, delete. */
public final class ReplayBrowserScreen extends Screen {
    private final Screen parent;
    private final List<Path> files = new ArrayList<>();
    private final List<ReplayFile.Meta> metas = new ArrayList<>();
    private int selected = -1, scroll;
    private ButtonWidget watch, rename, delete, queue;
    private TextFieldWidget renameField;
    private static final int ROW = 34;

    public ReplayBrowserScreen(Screen parent) {
        super(Text.literal("Replays"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        files.clear();
        metas.clear();
        for (Path p : ReplayFile.list()) {
            try { metas.add(ReplayFile.readMeta(p)); files.add(p); } catch (Exception ignored) {}
        }
        if (selected >= files.size()) selected = files.size() - 1;
        int by = height - 28, bw = 76;
        int x = width / 2 - (bw * 5 + 4 * 4) / 2;
        watch = addDrawableChild(ButtonWidget.builder(Text.literal("Watch"), b -> watch()).dimensions(x, by, bw, 20).build());
        rename = addDrawableChild(ButtonWidget.builder(Text.literal("Rename"), b -> startRename()).dimensions(x + (bw + 4), by, bw, 20).build());
        delete = addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), b -> confirmDelete()).dimensions(x + (bw + 4) * 2, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Open folder"), b -> {
            try { Files.createDirectories(ReplayFile.dir()); } catch (Exception ignored) {}
            Util.getOperatingSystem().open(ReplayFile.dir().toFile());
        }).dimensions(x + (bw + 4) * 3, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close()).dimensions(x + (bw + 4) * 4, by, bw, 20).build());
        // Render queue: queue the selected replay, switch share links, and run the queue (top right).
        queue = addDrawableChild(ButtonWidget.builder(Text.literal("Queue render"), b -> {
            if (selected < 0) return;
            RenderQueue.add(files.get(selected), RenderQueue.shareNew);
            net.evadeclient.util.Notifications.push("Render queue", "Queued " + metas.get(selected).name + " (" + RenderQueue.jobs().size() + " in the queue)", true);
            clearAndInit();
        }).dimensions(width - 6 - 90, 6, 90, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                "Render this replay later with its saved camera path and render settings"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Share links: " + (RenderQueue.shareNew ? "On" : "Off")), b -> {
            RenderQueue.shareNew = !RenderQueue.shareNew;
            clearAndInit();
        }).dimensions(width - 6 - 90 - 4 - 100, 6, 100, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                "Queued renders also get a share link through Evade Clips (needs a share server set up there)"))).build());
        int n = RenderQueue.jobs().size();
        ButtonWidget run = addDrawableChild(ButtonWidget.builder(Text.literal(RenderQueue.running() ? "Stop queue" : "Render queue (" + n + ")"), b -> {
            if (RenderQueue.running()) RenderQueue.stop(); else RenderQueue.start();
            clearAndInit();
        }).dimensions(width - 6 - 90, 30, 90, 20).build());
        run.active = RenderQueue.running() || n > 0;
        if (n > 0 && !RenderQueue.running()) addDrawableChild(ButtonWidget.builder(Text.literal("Clear queue"), b -> {
            RenderQueue.clear();
            clearAndInit();
        }).dimensions(width - 6 - 90 - 4 - 100, 30, 100, 20).build());
        renameField = new TextFieldWidget(textRenderer, width / 2 - 120, height - 54, 240, 18, Text.literal("Name"));
        renameField.setMaxLength(80);
        renameField.visible = false;
        addDrawableChild(renameField);
        update();
    }

    private void update() {
        boolean has = selected >= 0 && selected < files.size();
        watch.active = has;
        rename.active = has;
        delete.active = has;
        if (queue != null) queue.active = has;
    }

    private void watch() {
        if (selected < 0) return;
        try { ReplayPlayer.open(files.get(selected), new ReplayBrowserScreen(parent)); }
        catch (Exception e) { net.evadeclient.util.Notifications.push("Replay", "Couldn't open: " + e.getMessage(), false); }
    }

    private void startRename() {
        if (selected < 0) return;
        renameField.visible = true;
        renameField.setText(metas.get(selected).name);
        setFocused(renameField);
    }

    private void finishRename() {
        if (selected < 0 || !renameField.visible) return;
        String name = renameField.getText().trim();
        renameField.visible = false;
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
        if (selected < 0) return;
        Path f = files.get(selected);
        client.setScreen(new ConfirmScreen(yes -> {
            if (yes) try { Files.deleteIfExists(f); Files.deleteIfExists(ReplayPlayer.projectFile(f)); } catch (Exception ignored) {}
            client.setScreen(this);
        }, Text.literal("Delete this replay?"), Text.literal(metas.get(selected).name)));
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (renameField.visible && (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            finishRename();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        int top = 40;
        if (click.y() >= top && click.y() < height - 60) {
            int i = (int) ((click.y() - top) / ROW) + scroll;
            if (i >= 0 && i < files.size()) {
                if (i == selected && doubled) { watch(); return true; }
                selected = i;
                renameField.visible = false;
                update();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        int visible = Math.max(1, (height - 100) / ROW);
        scroll = Math.max(0, Math.min(Math.max(0, files.size() - visible), scroll - (int) Math.signum(v)));
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, "Replays", width / 2, 14, 0xFFFFFFFF);
        int top = 40, w = Math.min(460, width - 40), x = (width - w) / 2;
        if (files.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "No replays yet.", width / 2, top + 20, 0xFFB0B0BA);
            ctx.drawCenteredTextWithShadow(textRenderer, "Press Esc in game → Start recording, then Save replay.", width / 2, top + 34, 0xFF7A7A86);
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());
        int visible = Math.max(1, (height - 100) / ROW);
        for (int r = 0; r < visible && r + scroll < files.size(); r++) {
            int i = r + scroll, y = top + r * ROW;
            ReplayFile.Meta m = metas.get(i);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW - 2;
            ctx.fill(x, y, x + w, y + ROW - 2, i == selected ? 0xCC3A1B22 : hover ? 0x88202028 : 0x66101016);
            if (i == selected) ctx.fill(x, y, x + 2, y + ROW - 2, 0xFFE5484D);
            ctx.drawTextWithShadow(textRenderer, m.name, x + 8, y + 6, 0xFFFFFFFF);
            String info = fmt.format(Instant.ofEpochMilli(m.recordedAt)) + "  ·  " + Replays.time(m.keptTicks() / 20.0)
                    + "  ·  " + (m.sizeBytes < 1_000_000 ? Math.max(1, m.sizeBytes / 1000) + " KB" : String.format("%.1f MB", m.sizeBytes / 1e6)) + (m.markers.isEmpty() ? "" : "  ·  " + m.markers.size() + " markers");
            ctx.drawTextWithShadow(textRenderer, info, x + 8, y + 18, 0xFF8A8A96);
        }
    }

    @Override public void close() { client.setScreen(parent); }
}
