package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.ScreenshotTools;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Screenshots;
import net.evadeclient.util.Screenshots.Shot;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screenshot gallery: this game folder's screenshots/ as a grid of thumbnails, newest first, with search. Click
 * one for the full view, where it can be copied to the clipboard (ready to paste into Discord), renamed, deleted or
 * shown in its folder; arrow keys step through them. Delete moves the file to screenshots/.trash with an Undo
 * (Ctrl+Z); the trash empties itself after a week.
 *
 * <p>Thumbnails and the full image load on background threads and are cached for as long as the screen is open
 * (at most {@link #MAX_THUMBS} thumbnails). Nothing is uploaded anywhere.
 */
public final class ScreenshotGalleryScreen extends EvadePanelScreen {
    private static final int THUMB_W = 192, THUMB_H = 108, MAX_THUMBS = 150, MIN_CARD = 150, GAP = 8;
    /** The full view's longest side; bigger screenshots are scaled down to it for display only. */
    private static final int FULL_MAX = 2048;
    private static final long UNDO_MS = 8000;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Evade-Screenshot-Gallery");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private List<Shot> shots = List.of();
    private boolean loaded;
    /** Index into {@link #filtered()} of the shot in the full view, or -1 for the grid. */
    private int viewing = -1;
    private Path viewingPath;
    /** Rename box text while renaming (full view), else null. */
    private String renaming;
    private Screenshots.Trashed lastDeleted;
    private long deletedAt;

    private final Map<Path, Tex> thumbs = new LinkedHashMap<>(64, 0.75f, true);
    private Tex full;
    private Path fullFor;
    private int seq;

    /** A loaded texture (or one loading: id null) and the size of the image in it. */
    private static final class Tex {
        Identifier id;
        int w, h;
        boolean failed;
        long modified;
    }

    public ScreenshotGalleryScreen(Screen parent) {
        super("Screenshots", parent);
    }

    /** Opens the gallery over the current screen (Back returns there). */
    public static void openFromCurrent() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new ScreenshotGalleryScreen(mc.currentScreen));
    }

    /** Opens the gallery; Back returns to {@code parent} (null: back to the game). */
    public static void open(Screen parent) {
        MinecraftClient.getInstance().setScreen(new ScreenshotGalleryScreen(parent));
    }

    @Override
    protected void init() {
        super.init();
        if (!loaded && shots.isEmpty()) reload(null);
    }

    @Override protected int prefWidth() { return 780; }
    @Override protected int prefHeight() { return 470; }
    @Override protected boolean hasSearch() { return viewing < 0; }

    @Override
    protected String subtitle() {
        if (!loaded) return "Loading...";
        if (shots.isEmpty()) return "No screenshots yet. Press " + screenshotKey() + " in game to take one.";
        return shots.size() + (shots.size() == 1 ? " screenshot" : " screenshots")
                + "  ·  Copy puts the image on your clipboard, ready to paste into Discord";
    }

    private String screenshotKey() {
        return client != null && client.options != null ? client.options.screenshotKey.getBoundKeyLocalizedText().getString() : "F2";
    }

    // ------------------------------------------------------------------ data

    /** Lists the folder again (off the render thread), then shows {@code focus} in the full view if given. */
    private void reload(Path focus) {
        LOADER.execute(() -> {
            Screenshots.emptyOldTrash();
            List<Shot> list = Screenshots.list();
            MinecraftClient.getInstance().execute(() -> {
                shots = list;
                loaded = true;
                if (focus != null) {
                    int i = indexOf(focus);
                    if (i >= 0) { viewing = i; viewingPath = focus; } else closeView();
                } else if (viewingPath != null) {
                    int i = indexOf(viewingPath);
                    if (i >= 0) viewing = i; else closeView();
                }
            });
        });
    }

    private int indexOf(Path p) {
        List<Shot> f = filtered();
        for (int i = 0; i < f.size(); i++) if (f.get(i).file().equals(p)) return i;
        return -1;
    }

    /** The shots the search lets through. */
    private List<Shot> filtered() {
        if (search.isBlank()) return shots;
        String q = search.toLowerCase(Locale.ROOT).strip();
        List<Shot> out = new ArrayList<>();
        for (Shot s : shots) if (s.name().toLowerCase(Locale.ROOT).contains(q) || when(s).toLowerCase(Locale.ROOT).contains(q)) out.add(s);
        return out;
    }

    private static String when(Shot s) {
        return WHEN.format(Instant.ofEpochMilli(s.modified()).atZone(ZoneId.systemDefault()));
    }

    private static String size(long bytes) {
        if (bytes >= 1 << 20) return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1 << 20));
        return Math.max(1, bytes / 1024) + " KB";
    }

    // ------------------------------------------------------------------ textures

    /** The thumbnail of {@code s}, or null while it loads (the load starts on the first call). */
    private Tex thumb(Shot s) {
        Tex t = thumbs.get(s.file());
        if (t != null && t.modified != s.modified()) {
            destroy(t);
            thumbs.remove(s.file());
            t = null;
        }
        if (t == null) {
            Tex fresh = new Tex();
            fresh.modified = s.modified();
            thumbs.put(s.file(), fresh);
            evict();
            load(s.file(), THUMB_W, THUMB_H, fresh, () -> thumbs.containsValue(fresh));
            return null;
        }
        return t.id == null ? null : t;
    }

    private void evict() {
        Iterator<Map.Entry<Path, Tex>> it = thumbs.entrySet().iterator();
        while (thumbs.size() > MAX_THUMBS && it.hasNext()) {
            Tex t = it.next().getValue();
            if (t.id == null && !t.failed) continue; // still loading
            destroy(t);
            it.remove();
        }
    }

    /** The full-size texture for {@code s}, or null while it loads. */
    private Tex fullImage(Shot s) {
        if (!s.file().equals(fullFor)) {
            if (full != null) destroy(full);
            full = new Tex();
            fullFor = s.file();
            Tex mine = full;
            load(s.file(), FULL_MAX, FULL_MAX, mine, () -> full == mine);
        }
        return full.id == null ? null : full;
    }

    /**
     * Reads and scales the PNG on a loader thread, then uploads it on the render thread into {@code into} (unless
     * {@code wanted} says it is no longer needed by then, e.g. the screen moved on).
     */
    private void load(Path file, int maxW, int maxH, Tex into, java.util.function.BooleanSupplier wanted) {
        int id = ++seq;
        LOADER.execute(() -> {
            NativeImage img = null;
            try {
                byte[] bytes = Files.readAllBytes(file);
                try (NativeImage src = NativeImage.read(bytes)) {
                    img = scaled(src, maxW, maxH);
                }
            } catch (Throwable t) {
                if (img != null) img.close();
                img = null;
            }
            NativeImage done = img;
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (done == null) { into.failed = true; return; }
                if (mc.currentScreen != this || !wanted.getAsBoolean()) { done.close(); return; }
                Identifier tex = Identifier.of("evade", "screenshot_gallery/" + id);
                mc.getTextureManager().registerTexture(tex, new NativeImageBackedTexture(() -> "Evade screenshot", done));
                into.w = done.getWidth();
                into.h = done.getHeight();
                into.id = tex;
            });
        });
    }

    /** {@code src} fitted inside maxW x maxH (never enlarged), each pixel averaged from up to 4 x 4 samples. */
    static NativeImage scaled(NativeImage src, int maxW, int maxH) {
        int sw = src.getWidth(), sh = src.getHeight();
        double k = Math.min(1.0, Math.min(maxW / (double) sw, maxH / (double) sh));
        int w = Math.max(1, (int) Math.round(sw * k)), h = Math.max(1, (int) Math.round(sh * k));
        NativeImage out = new NativeImage(w, h, false);
        double fx = sw / (double) w, fy = sh / (double) h;
        int nx = Math.max(1, Math.min(4, (int) Math.ceil(fx))), ny = Math.max(1, Math.min(4, (int) Math.ceil(fy)));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long r = 0, g = 0, b = 0;
                for (int j = 0; j < ny; j++) {
                    int sy = Math.min(sh - 1, (int) ((y + (j + 0.5) / ny) * fy));
                    for (int i = 0; i < nx; i++) {
                        int sx = Math.min(sw - 1, (int) ((x + (i + 0.5) / nx) * fx));
                        int p = src.getColorArgb(sx, sy);
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                int n = nx * ny;
                out.setColorArgb(x, y, 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
            }
        }
        return out;
    }

    private void destroy(Tex t) {
        if (t != null && t.id != null && client != null) client.getTextureManager().destroyTexture(t.id);
        if (t != null) t.id = null;
    }

    @Override
    public void removed() {
        for (Tex t : thumbs.values()) destroy(t);
        thumbs.clear();
        destroy(full);
        full = null;
        fullFor = null;
        super.removed();
    }

    /** Draws {@code t} fitted and centred inside the box, letterboxed. */
    private static void drawFitted(DrawContext ctx, Tex t, int x, int y, int w, int h) {
        float k = Math.min(w / (float) t.w, h / (float) t.h);
        int dw = Math.max(1, Math.round(t.w * k)), dh = Math.max(1, Math.round(t.h * k));
        int c = Theme.fa(0xFFFFFFFF);
        if (c >>> 24 == 0) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, t.id, x + (w - dw) / 2, y + (h - dh) / 2, 0, 0, dw, dh, t.w, t.h, t.w, t.h, c);
    }

    // ------------------------------------------------------------------ grid

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        int x = button(ctx, "folder", "Open Folder", right, y, mx, my, 0, b -> openFolder(null));
        button(ctx, "refresh", "Refresh", x - 6, y, mx, my, 0, b -> reload(null));
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        List<Shot> list = filtered();
        if (list.isEmpty()) {
            String msg = !loaded ? "Loading screenshots..." : shots.isEmpty() ? "Your screenshots will show up here." : "No screenshot matches \"" + search + "\".";
            text(ctx, msg, cx + (cw - width(msg, Role.BODY)) / 2, top + 40, Theme.TEXT_DIM, Role.BODY);
            return 80;
        }
        int cols = Math.max(1, (cw + GAP) / (MIN_CARD + GAP));
        int cardW = (cw - GAP * (cols - 1)) / cols;
        int thumbH = (cardW - 6) * 9 / 16;
        int cardH = thumbH + 32;
        boolean overlay = viewing >= 0;
        for (int i = 0; i < list.size(); i++) {
            int col = i % cols, row = i / cols;
            int x = cx + col * (cardW + GAP), y = top + row * (cardH + GAP);
            if (!inView(y, cardH)) continue;
            Shot s = list.get(i);
            boolean hv = !overlay && mouseIn(mx, my, x, y, x + cardW, y + cardH);
            float h = hover("card:" + s.file(), hv);
            Theme.roundBordered(ctx, x, y, x + cardW, y + cardH, Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h),
                    Theme.lerpColor(Theme.BORDER, Theme.RED, h * 0.7f));
            Theme.round(ctx, x + 3, y + 3, x + cardW - 3, y + 3 + thumbH, 0xFF000000, Theme.R_XS);
            Tex t = thumb(s);
            if (t != null) drawFitted(ctx, t, x + 3, y + 3, cardW - 6, thumbH);
            else {
                Tex pending = thumbs.get(s.file());
                String state = pending != null && pending.failed ? "Can't read this image" : "...";
                text(ctx, state, x + (cardW - width(state, Role.SMALL)) / 2, y + 3 + thumbH / 2 - 4, Theme.TEXT_FAINT, Role.SMALL);
            }
            text(ctx, ellipsize(s.name(), Role.LABEL, cardW - 12), x + 6, y + thumbH + 8, Theme.TEXT, Role.LABEL);
            text(ctx, ellipsize(when(s) + "  ·  " + size(s.size()), Role.SMALL, cardW - 12), x + 6, y + thumbH + 19, Theme.TEXT_DIM, Role.SMALL);
            int index = i;
            if (!overlay) contentHit(x, y, x + cardW, y + cardH, b -> { if (b == 0) openView(index); });
        }
        int rows = (list.size() + cols - 1) / cols;
        return rows * (cardH + GAP);
    }

    // ------------------------------------------------------------------ full view and the undo bar

    private void openView(int index) {
        List<Shot> f = filtered();
        if (index < 0 || index >= f.size()) return;
        viewing = index;
        viewingPath = f.get(index).file();
        renaming = null;
        searchFocused = false;
    }

    private void closeView() {
        viewing = -1;
        viewingPath = null;
        renaming = null;
        if (full != null) destroy(full);
        full = null;
        fullFor = null;
    }

    private void step(int d) {
        List<Shot> f = filtered();
        if (f.isEmpty() || viewing < 0) return;
        openView(Math.floorMod(viewing + d, f.size()));
    }

    private Shot current() {
        List<Shot> f = filtered();
        return viewing >= 0 && viewing < f.size() ? f.get(viewing) : null;
    }

    @Override
    protected void renderOverlay(DrawContext ctx, int mx, int my) {
        renderUndo(ctx, mx, my);
        Shot s = current();
        if (s == null) {
            if (viewing >= 0) closeView();
            return;
        }
        ctx.fill(0, 0, width, height, Theme.fa(0xF0060608));
        hits.add(new Hit(0, 0, width, height, b -> { })); // nothing under the full view can be clicked
        int top = 34, bottom = height - 34;
        Tex t = fullImage(s);
        if (t != null) drawFitted(ctx, t, 16, top, width - 32, bottom - top);
        else {
            String msg = full != null && full.failed ? "Can't read this image" : "Loading...";
            text(ctx, msg, (width - width(msg, Role.BODY)) / 2, (top + bottom) / 2 - 4, Theme.TEXT_DIM, Role.BODY);
        }
        int n = filtered().size();
        String head = (viewing + 1) + " / " + n + "   " + s.name();
        text(ctx, ellipsize(head, Role.LABEL, width - 220), 16, 12, Theme.TEXT, Role.LABEL);
        text(ctx, when(s) + "  ·  " + size(s.size()), 16, 23, Theme.TEXT_DIM, Role.SMALL);
        button(ctx, "v:close", "Close", width - 16, 10, mx, my, 0, b -> closeView());

        int by = height - 26;
        if (renaming != null) {
            renderRename(ctx, by, mx, my);
            return;
        }
        int x = width - 16;
        x = button(ctx, "v:delete", "Delete", x, by, mx, my, 2, b -> delete(s)) - 6;
        x = button(ctx, "v:rename", "Rename", x, by, mx, my, 0, b -> startRename(s)) - 6;
        x = button(ctx, "v:folder", "Show In Folder", x, by, mx, my, 0, b -> openFolder(s)) - 6;
        button(ctx, "v:copy", "Copy Image", x, by, mx, my, 1, b -> ScreenshotTools.copy(s.file().toFile()));
        int prevW = width("‹ Previous", Role.LABEL) + 14, nextW = width("Next ›", Role.LABEL) + 14;
        button(ctx, "v:prev", "‹ Previous", 16 + prevW, by, mx, my, 0, b -> step(-1));
        button(ctx, "v:next", "Next ›", 16 + prevW + 6 + nextW, by, mx, my, 0, b -> step(1));
    }

    private void renderRename(DrawContext ctx, int by, int mx, int my) {
        int x1 = 16, x2 = width - 200;
        Theme.roundBordered(ctx, x1, by, x2, by + 16, Theme.INPUT, Theme.alpha(Theme.RED, 0.8f));
        String shown = renaming + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        text(ctx, ellipsize(shown, Role.BODY, x2 - x1 - 12), x1 + 6, by + 4, Theme.TEXT, Role.BODY);
        int x = button(ctx, "r:cancel", "Cancel", width - 16, by, mx, my, 0, b -> renaming = null) - 6;
        button(ctx, "r:save", "Save Name", x, by, mx, my, 1, b -> finishRename());
    }

    private void renderUndo(DrawContext ctx, int mx, int my) {
        if (lastDeleted == null) return;
        if (System.currentTimeMillis() - deletedAt > UNDO_MS) { lastDeleted = null; return; }
        String msg = "Moved " + lastDeleted.original().getFileName() + " to the trash";
        int w = width(msg, Role.LABEL) + 90;
        int x = (width - w) / 2, y = height - (viewing >= 0 ? 58 : 30);
        Theme.roundBordered(ctx, x, y, x + w, y + 22, Theme.CARD, Theme.BORDER);
        text(ctx, msg, x + 8, y + 7, Theme.TEXT_SOFT, Role.LABEL);
        button(ctx, "undo", "Undo", x + w - 6, y + 3, mx, my, 1, b -> undo());
    }

    // ------------------------------------------------------------------ actions

    private void openFolder(Shot s) {
        Path dir = Screenshots.folder();
        LOADER.execute(() -> {
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
            MinecraftClient.getInstance().execute(() -> {
                if (s != null) ScreenshotTools.openFolder(s.file().toFile());
                else Util.getOperatingSystem().open(dir.toFile());
            });
        });
    }

    private void delete(Shot s) {
        LOADER.execute(() -> {
            try {
                Screenshots.Trashed t = Screenshots.trash(s.file());
                MinecraftClient.getInstance().execute(() -> {
                    lastDeleted = t;
                    deletedAt = System.currentTimeMillis();
                    afterDelete(s);
                });
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> Notifications.push("Screenshots", "Couldn't delete it: " + e.getMessage(), false));
            }
        });
    }

    /** The list without {@code gone}; the full view moves on to the next one (or closes on the last). */
    private void afterDelete(Shot gone) {
        int at = viewing;
        List<Shot> left = new ArrayList<>(shots);
        left.removeIf(x -> x.file().equals(gone.file()));
        shots = left;
        if (at >= 0) {
            List<Shot> f = filtered();
            if (f.isEmpty()) closeView();
            else openView(Math.min(at, f.size() - 1));
        }
    }

    /** Puts the last deleted screenshot back. */
    public void undo() {
        Screenshots.Trashed t = lastDeleted;
        if (t == null) return;
        lastDeleted = null;
        LOADER.execute(() -> {
            try {
                Path back = Screenshots.restore(t);
                MinecraftClient.getInstance().execute(() -> reload(viewing >= 0 ? back : null));
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> Notifications.push("Screenshots", "Couldn't undo: " + e.getMessage(), false));
            }
        });
    }

    private void startRename(Shot s) {
        String n = s.name();
        renaming = n.toLowerCase(Locale.ROOT).endsWith(".png") ? n.substring(0, n.length() - 4) : n;
    }

    private void finishRename() {
        Shot s = current();
        String name = renaming;
        if (s == null || name == null) { renaming = null; return; }
        if (Screenshots.cleanName(name) == null) {
            Notifications.push("Rename", "Use a plain file name (no / \\ : * ? \" < > |)", false);
            return;
        }
        renaming = null;
        LOADER.execute(() -> {
            try {
                Path p = Screenshots.rename(s.file(), name);
                MinecraftClient.getInstance().execute(() -> {
                    viewingPath = p;
                    reload(p);
                });
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> Notifications.push("Rename", e.getMessage(), false));
            }
        });
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        boolean ctrl = (input.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (renaming != null) {
            switch (key) {
                case GLFW.GLFW_KEY_ESCAPE -> renaming = null;
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> finishRename();
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (!renaming.isEmpty()) renaming = ctrl ? "" : renaming.substring(0, renaming.offsetByCodePoints(renaming.length(), -1));
                }
                default -> { }
            }
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_Z) { undo(); return true; }
        if (viewing >= 0) {
            Shot s = current();
            switch (key) {
                case GLFW.GLFW_KEY_ESCAPE -> closeView();
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> step(-1);
                case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> step(1);
                case GLFW.GLFW_KEY_DELETE -> { if (s != null) delete(s); }
                case GLFW.GLFW_KEY_F2 -> { if (s != null) startRename(s); }
                case GLFW.GLFW_KEY_C -> { if (ctrl && s != null) ScreenshotTools.copy(s.file().toFile()); }
                default -> { }
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (renaming != null) {
            if (input.isValidChar()) {
                String s = input.asString();
                if (renaming.length() + s.length() <= 100 && !s.isEmpty() && "/\\:*?\"<>|".indexOf(s.charAt(0)) < 0) renaming += s;
            }
            return true;
        }
        if (viewing >= 0) return true;
        return super.charTyped(input);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        if (viewing >= 0) {
            if (renaming == null && v != 0) step(v > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(x, y, h, v);
    }

    // ------------------------------------------------------------------ test hooks

    public boolean loadedForTest() { return loaded; }
    public List<Shot> shotsForTest() { return shots; }
    public int viewingForTest() { return viewing; }
    public void openViewForTest(int index) { openView(index); }
    public void deleteCurrentForTest() { Shot s = current(); if (s != null) delete(s); }
    public boolean hasUndoForTest() { return lastDeleted != null; }
    /** Whether the thumbnail of the file named {@code name} has been uploaded as a texture. */
    public boolean thumbReadyForTest(String name) {
        for (Map.Entry<Path, Tex> e : thumbs.entrySet())
            if (e.getKey().getFileName().toString().equals(name)) return e.getValue().id != null;
        return false;
    }
    public boolean fullReadyForTest() { return full != null && full.id != null; }
}
