package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.ScreenshotTools;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Screenshots;
import net.evadeclient.util.Screenshots.Shot;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Progress;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.gui.kit.Surface;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
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

    @Override protected String headerIcon() { return "camera"; }
    @Override protected String searchHint() { return "Search by name or date"; }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        int x = button("folder", "Open folder", null, right, y, SECONDARY, b -> openFolder(null)) - Tokens.S2;
        button("refresh", "Refresh", "reset", x, y, GHOST, b -> reload(null));
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        List<Shot> list = filtered();
        if (list.isEmpty()) {
            if (!loaded) {
                Progress.circle(kit, cx + cw / 2f, top + 60, 14, Float.NaN);
                String msg = "Loading screenshots";
                kit.textCentredY(msg, cx + (cw - width(msg, Role.SMALL)) / 2, top + 90, Tokens.N700, Role.SMALL);
                return 120;
            }
            boolean none = shots.isEmpty();
            return EmptyState.draw(kit, "empty", cx + cw / 2, top + 26, none ? "camera" : "search",
                    none ? "No screenshots yet" : "Nothing matches",
                    none ? "Press " + screenshotKey() + " in game and your shots show up here." : "Try part of a file name.",
                    none ? "Open folder" : null, () -> openFolder(null)) + 40;
        }
        int cols = Math.max(1, (cw + GAP) / (MIN_CARD + GAP));
        int cardW = (cw - GAP * (cols - 1)) / cols;
        int thumbH = (cardW - 8) * 9 / 16;
        int cardH = thumbH + 36;
        boolean overlay = viewing >= 0;
        for (int i = 0; i < list.size(); i++) {
            int col = i % cols, row = i / cols;
            int x = cx + col * (cardW + GAP), y = top + row * (cardH + GAP);
            if (!inView(y, cardH)) continue;
            Shot s = list.get(i);
            float a = enter(i), f = Theme.fade;
            Theme.fade = f * a;
            y += Math.round((1 - a) * 8);
            int index = i;
            try {
                Surface.card(kit, "card:" + s.file(), x, y, x + cardW, y + cardH, false, !overlay && Motion.decorative(),
                        overlay ? null : () -> openView(index));
                Paint.rect(ctx, x + 4, y + 4, x + cardW - 4, y + 4 + thumbH, Tokens.R_SM, 0xFF000000);
                Tex t = thumb(s);
                if (t != null) drawFitted(ctx, t, x + 4, y + 4, cardW - 8, thumbH);
                else {
                    Tex pending = thumbs.get(s.file());
                    if (pending != null && pending.failed) {
                        String state = "Can't read this image";
                        kit.textCentredY(state, x + (cardW - width(state, Role.SMALL)) / 2, y + 4 + thumbH / 2f, Tokens.N600, Role.SMALL);
                    } else {
                        Progress.circle(kit, x + cardW / 2f, y + 4 + thumbH / 2f, 7, Float.NaN);
                    }
                }
                // A hairline inside the thumbnail edge, so bright shots still read as framed.
                Paint.ring(ctx, x + 4, y + 4, x + cardW - 4, y + 4 + thumbH, Tokens.R_SM, Theme.alpha(0xFFFFFFFF, 0.06f), 1);
                kit.textCentredY(ellipsize(s.name(), Role.STRONG, cardW - 16), x + 8, y + thumbH + 16, Tokens.N950, Role.STRONG);
                kit.textCentredY(ellipsize(when(s) + "  ·  " + size(s.size()), Role.SMALL, cardW - 16), x + 8, y + thumbH + 28, Tokens.N700, Role.SMALL);
            } finally {
                Surface.endCard(kit);
                Theme.fade = f;
            }
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
        if (searchFocused()) kit.focus(null, false);
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

    /** The last mouse position and when it last moved: the full view's chrome fades out after 2 s of stillness. */
    private int lastMx = -1, lastMy = -1;
    private long movedAt;

    @Override
    protected void renderOverlay(DrawContext ctx, int mx, int my) {
        Shot s = current();
        if (s == null) {
            if (viewing >= 0) closeView();
            renderUndo(ctx, mx, my);
            return;
        }
        long now = System.currentTimeMillis();
        if (mx != lastMx || my != lastMy || renaming != null) { lastMx = mx; lastMy = my; movedAt = now; }
        // The full view sits outside the window's entrance transform: the whole screen.
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.identity();
        float f0 = Theme.fade;
        Theme.fade = 1f;
        try {
            Theme.fill(ctx, 0, 0, width, height, Theme.alpha(Tokens.N0, 0.96f));
            barrier("v#scrim", 0, 0, width, height); // nothing under the full view can be clicked
            int top = 8, bottom = height - 8;
            Tex t = fullImage(s);
            if (t != null) drawFitted(ctx, t, 12, top, width - 24, bottom - top);
            else if (full != null && full.failed) {
                String msg = "Can't read this image";
                kit.textCentredY(msg, (width - width(msg, Role.BODY)) / 2, height / 2f, Tokens.N700, Role.BODY);
            } else {
                Progress.circle(kit, width / 2f, height / 2f, 14, Float.NaN);
            }
            // Chrome over scrims at the top and bottom; it fades away while the mouse rests (not while renaming).
            float idle = now - movedAt > 2000 ? 0f : 1f;
            float chrome = kit.anim("v#chrome", idle, Motion.SMOOTH);
            if (Motion.level() == Motion.Level.OFF) chrome = idle;
            Theme.fade = Math.max(0.0f, chrome);
            Paint.vgradient(ctx, 0, 0, width, 64, 0, Theme.alpha(Tokens.N0, 0.72f), Theme.alpha(Tokens.N0, 0f));
            Paint.vgradient(ctx, 0, height - 72, width, height, 0, Theme.alpha(Tokens.N0, 0f), Theme.alpha(Tokens.N0, 0.78f));
            int n = filtered().size();
            String count = (viewing + 1) + " / " + n;
            int cwid = UiFont.tabularWidth(textRenderer, count, Role.STRONG, smooth);
            Paint.pill(ctx, 14, 14, 14 + cwid + 16, 32, Theme.alpha(Tokens.N300, 0.85f));
            UiFont.drawTabular(ctx, textRenderer, count, 22, Math.round(UiFont.centredY(Role.STRONG, 23, smooth)), Tokens.N950, Role.STRONG, smooth);
            int nx = 14 + cwid + 16 + Tokens.S4;
            kit.textCentredY(ellipsize(s.name(), Role.STRONG, width - nx - 60), nx, 18, Tokens.N950, Role.STRONG);
            kit.textCentredY(when(s) + "  ·  " + size(s.size()), nx, 30, Tokens.N800, Role.SMALL);
            Button.icon(kit, "v:close", width - 14 - 28, 10, 28, "close", "Close (Esc)", true, this::closeView);

            int by = height - 14 - Button.Size.MD.h;
            if (renaming != null) {
                renderRename(ctx, by, mx, my);
            } else {
                Button.icon(kit, "v:prev", 14, by - 2, 28, "chevron_l", "Previous (Left)", n > 1, () -> step(-1));
                Button.icon(kit, "v:next", 14 + 28 + Tokens.S2, by - 2, 28, "chevron_r", "Next (Right)", n > 1, () -> step(1));
                int x = width - 14;
                x -= drawButton("v:delete", "Delete", null, x - Button.width(kit, "Delete", Button.Size.MD, null, false), by, 0, Button.Size.MD, DANGER, true, b -> delete(s)) + Tokens.S3;
                x -= drawButton("v:rename", "Rename", "edit", x - Button.width(kit, "Rename", Button.Size.MD, "edit", false), by, 0, Button.Size.MD, GHOST, true, b -> startRename(s)) + Tokens.S2;
                x -= drawButton("v:folder", "Show in folder", null, x - Button.width(kit, "Show in folder", Button.Size.MD, null, false), by, 0, Button.Size.MD, SECONDARY, true, b -> openFolder(s)) + Tokens.S3;
                drawButton("v:copy", "Copy image", null, x - Button.width(kit, "Copy image", Button.Size.MD, null, false), by, 0, Button.Size.MD, PRIMARY, true,
                        b -> ScreenshotTools.copy(s.file().toFile()));
            }
            Theme.fade = 1f;
            renderUndo(ctx, mx, my);
        } finally {
            Theme.fade = f0;
            m.popMatrix();
        }
    }

    private static final String RENAME = "v#rename";

    private void renderRename(DrawContext ctx, int by, int mx, int my) {
        int x1 = 14;
        int sw = Button.width(kit, "Save name", Button.Size.MD, null, false), cwid = Button.width(kit, "Cancel", Button.Size.MD, null, false);
        int x2 = Math.min(x1 + 420, width - 14 - sw - cwid - 2 * Tokens.S3 - 12);
        kit.textCentredY("Rename", x1, by - 10, Tokens.N800, Role.LABEL);
        String typed = SearchField.draw(kit, RENAME, x1, by, x2 - x1, "File name", ".png", false);
        renaming = typed;
        int x = width - 14 - sw;
        drawButton("r:save", "Save name", null, x, by, sw, Button.Size.MD, PRIMARY, true, b -> finishRename());
        drawButton("r:cancel", "Cancel", null, x - Tokens.S3 - cwid, by, cwid, Button.Size.MD, GHOST, true, b -> cancelRename());
    }

    private void cancelRename() {
        renaming = null;
        kit.focus(null, false);
    }

    private void renderUndo(DrawContext ctx, int mx, int my) {
        if (lastDeleted == null) return;
        long now = System.currentTimeMillis();
        if (now - deletedAt > UNDO_MS) { lastDeleted = null; return; }
        String msg = "Moved " + lastDeleted.original().getFileName() + " to the trash";
        int w = width(msg, Role.BODY) + Button.width(kit, "Undo", Button.Size.SM, "reset", false) + 40;
        float in = Motion.decorative() ? Motion.standard(Motion.clamp01((now - deletedAt) / (float) Motion.DUR_BASE)) : 1f;
        int x = (width - w) / 2, y = height - (viewing >= 0 ? 80 : 44) + Math.round((1 - in) * 8);
        float f = Theme.fade;
        Theme.fade = f * Math.min(1f, in * 1.5f);
        try {
            Paint.surface(ctx, x, y, x + w, y + 28, Tokens.R_MD, Tokens.E4, 0, Float.NaN);
            Icons.draw(ctx, "check", x + 10, y + 8, 12, Tokens.OK);
            kit.textCentredY(msg, x + 28, y + 14, Tokens.N950, Role.BODY);
            button("undo", "Undo", "reset", x + w - 4, y + 4, SECONDARY, b -> undo());
            float left = 1f - (now - deletedAt) / (float) UNDO_MS;
            Paint.rect(ctx, x + 8, y + 26, x + 8 + (w - 16) * left, y + 27, 0.5f, Theme.alpha(Tokens.OK, 0.6f));
        } finally {
            Theme.fade = f;
        }
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
        SearchField.set(kit, RENAME, renaming);
        kit.focus(RENAME, false);
    }

    private void finishRename() {
        Shot s = current();
        String name = renaming == null ? null : SearchField.text(kit, RENAME);
        if (s == null || name == null) { renaming = null; return; }
        if (Screenshots.cleanName(name) == null) {
            Notifications.push("Rename", "Use a plain file name (no / \\ : * ? \" < > |)", false);
            return;
        }
        renaming = null;
        kit.focus(null, false);
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
                case GLFW.GLFW_KEY_ESCAPE -> cancelRename();
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> finishRename();
                default -> {
                    if (!kit.isFocused(RENAME)) kit.focus(RENAME, false);
                    kit.keyPressed(key, input.modifiers());
                }
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
                if (SearchField.text(kit, RENAME).length() + s.length() <= 100 && !s.isEmpty() && "/\\:*?\"<>|".indexOf(s.charAt(0)) < 0) {
                    if (!kit.isFocused(RENAME)) kit.focus(RENAME, false);
                    kit.charTyped(input.codepoint());
                }
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
