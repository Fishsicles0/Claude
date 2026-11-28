package net.evadeclient.cosmetics;

import net.evadeclient.gui.Motion;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.module.misc.Cosmetics;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The emote wheel (spec 7.7): up to eight emote pods in orbit around a glass hub. A thin track ring carries an accent
 * arc that points at the emote under the cursor (by angle, with a dead zone in the middle); that pod lifts (playful
 * spring), gets an accent rim and a soft glow, and the hub shows it playing on your own model with its name. Numbers
 * 1 to 8 are hotkeys. Click, or let go of the wheel key over an emote, to play it. The game keeps running underneath,
 * and the emote stops as soon as you move. Pods fly in clockwise on open (Full motion; Reduced only fades).
 */
public final class EmoteWheelScreen extends Screen {
    private static final int SLOTS = 8;
    private final List<CosmeticItem> emotes = new ArrayList<>();
    private final long opened = System.nanoTime();
    private int hovered = -1;
    private final float[] lift = new float[SLOTS];
    private final Motion.Spring[] springs = new Motion.Spring[SLOTS];
    private final Motion.Spring arc = new Motion.Spring();
    private long last = System.nanoTime();
    private Emotes.Playing preview;
    private CosmeticItem previewOf;

    private EmoteWheelScreen() {
        super(Text.literal("Emotes"));
        for (CosmeticItem i : CosmeticsCatalog.forSlot(Slot.EMOTE)) {
            if (Wardrobe.owns(i.id) && emotes.size() < SLOTS) { emotes.add(i); CosmeticAssets.request(i); }
        }
        for (int i = 0; i < SLOTS; i++) springs[i] = new Motion.Spring(0f);
    }

    public static void open() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !CosmeticsEngine.allowed()) return;
        mc.setScreen(new EmoteWheelScreen());
    }

    public List<CosmeticItem> emotes() { return emotes; }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Tokens.refresh();
        float a = appear();
        Paint.scrim(ctx, width, height, 0.75f * a);
    }

    private float appear() {
        float a = Motion.clamp01((System.nanoTime() - opened) / 1e6f / Math.max(1, Motion.duration(Motion.DUR_BASE)));
        return Motion.level() == Motion.Level.OFF ? 1f : Motion.standard(a);
    }

    private static boolean smooth() { return !Theme.square; }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        var tr = textRenderer;
        float cx = width / 2f, cy = height / 2f;
        float R = Math.min(150, Math.min(width, height) * 0.36f);   // pod orbit
        float pod = Math.max(36, Math.min(60, R * 0.42f));          // pod diameter
        float hub = R * 0.56f;                                       // hub radius
        float a = appear();
        float base = Theme.fade;
        Theme.fade = base * a;
        try {
            double dx = mx - cx, dy = my - cy, dist = Math.sqrt(dx * dx + dy * dy);
            int n = Math.max(1, emotes.size());
            hovered = -1;
            if (dist > hub * 0.55f && !emotes.isEmpty()) {
                double ang = (Math.atan2(dy, dx) + Math.PI * 2.5) % (Math.PI * 2);
                hovered = (int) (ang / (Math.PI * 2 / n)) % n;
            }
            // The hub's backplate and the thin track ring.
            Paint.surface(ctx, cx - hub, cy - hub, cx + hub, cy + hub, hub, Tokens.E4, 0, Float.NaN);
            Paint.radial(ctx, cx - hub, cy - hub, cx + hub, cy + hub, hub, Theme.alpha(Tokens.N300, 0.0f), Theme.alpha(Tokens.N0, 0.35f), cx, cy, hub);
            float track = R + pod / 2f + 10;
            if (Paint.gpu()) UiShape.arc(ctx, cx, cy, track, 1.5f, 0, 360, Theme.alpha(0xFFFFFFFF, 0.07f));
            if (emotes.isEmpty()) {
                String s = "No emotes yet: find them in the Wardrobe";
                UiFont.draw(ctx, tr, s, Math.round(cx - UiFont.width(tr, s, Role.BODY, smooth()) / 2f), Math.round(UiFont.centredY(Role.BODY, cy, smooth())),
                        Tokens.N800, Role.BODY, smooth());
                return;
            }
            float step = 360f / n;
            // The pointer arc glides to the hovered slot (smooth spring, the short way round).
            if (hovered >= 0) {
                float target = hovered * step;
                if (!arc.init) arc.set(target);
                float cur = arc.x;
                while (target - cur > 180) target -= 360;
                while (target - cur < -180) target += 360;
                float at = Motion.spring(arc, target, Motion.SMOOTH, dt);
                if (Paint.gpu()) {
                    UiShape.arc(ctx, cx, cy, track + 1.5f, 4.5f, at + 4, step - 8, Tokens.A500);
                    if (Tokens.glows()) UiShape.arc(ctx, cx, cy, track + 5, 12, at + 6, step - 12, Theme.alpha(Tokens.A600, 0.10f));
                }
            }
            for (int i = 0; i < emotes.size(); i++) {
                float t = Motion.spring(springs[i], i == hovered ? 1f : 0f, Motion.PLAYFUL, dt);
                lift[i] = t;
                // Clockwise fly-in: each pod 12 ms after the last.
                float in = Motion.level() == Motion.Level.FULL
                        ? Motion.standard(Motion.clamp01(((System.nanoTime() - opened) / 1e6f - i * 12) / Motion.DUR_BASE)) : 1f;
                double ang = Math.toRadians(-90 + (i + 0.5) * step);
                float r = R * (0.9f + 0.1f * in) + 6 * t;
                float px = cx + (float) Math.cos(ang) * r, py = cy + (float) Math.sin(ang) * r;
                float d = pod * (1f + 0.12f * t) * (0.9f + 0.1f * in);
                float fa = Theme.fade;
                Theme.fade = fa * in;
                try {
                    if (t > 0.02f && Tokens.glows() && Paint.gpu())
                        UiShape.glow(ctx, px - d / 2, py - d / 2, px + d / 2, py + d / 2, d / 2, 8, Theme.alpha(Tokens.A600, 0.30f * t));
                    Paint.surface(ctx, px - d / 2, py - d / 2, px + d / 2, py + d / 2, d / 2,
                            Tokens.E3.withFill(Theme.lerpColor(Theme.alpha(Tokens.N250, 0.94f), Tokens.ACCENT_SURFACE, t)), t, Float.NaN);
                    if (t > 0.02f) Paint.ring(ctx, px - d / 2, py - d / 2, px + d / 2, py + d / 2, d / 2, Theme.alpha(Tokens.A400, t), 1.5f);
                    Identifier thumb = CosmeticAssets.thumb(emotes.get(i));
                    if (thumb != null) {
                        int s = Math.round(d * 0.78f);
                        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, thumb, Math.round(px - s / 2f), Math.round(py - s / 2f), 0, 0, s, s, 128, 128, 128, 128, Theme.fa(0xFFFFFFFF));
                    }
                    // The hotkey number just outside the pod, toward the rim.
                    float nr = r + d / 2 + 9;
                    float nx = cx + (float) Math.cos(ang) * nr, ny = cy + (float) Math.sin(ang) * nr;
                    String num = String.valueOf(i + 1);
                    UiFont.draw(ctx, tr, num, Math.round(nx - UiFont.width(tr, num, Role.TINY, smooth()) / 2f), Math.round(UiFont.centredY(Role.TINY, ny, smooth())),
                            i == hovered ? Tokens.A400 : Tokens.N600, Role.TINY, smooth());
                } finally {
                    Theme.fade = fa;
                }
            }
            // The hub: the hovered emote on your model, with its name.
            CosmeticItem sel = hovered >= 0 ? emotes.get(hovered) : null;
            if (sel != previewOf) { previewOf = sel; preview = sel == null ? null : Emotes.preview(sel, Emotes.clock()); }
            if (sel != null && client != null && client.player != null) {
                int bw = Math.round(hub * 0.9f), bh = Math.round(hub * 1.05f);
                WardrobePreview.draw(ctx, Wardrobe.renderLoadout(), preview, 180, -4, (System.nanoTime() - opened) / 1e9f,
                        Math.round(cx - bw / 2f), Math.round(cy - hub * 0.86f), Math.round(cx + bw / 2f), Math.round(cy - hub * 0.86f) + bh);
            }
            String name = sel != null ? sel.name : "Pick an emote";
            int nw = UiFont.width(tr, name, Role.STRONG, smooth());
            float ny = sel != null ? cy + hub * 0.52f : cy - 4;
            UiFont.draw(ctx, tr, name, Math.round(cx - nw / 2f), Math.round(UiFont.centredY(Role.STRONG, ny, smooth())), Tokens.N950, Role.STRONG, smooth());
            String hint = sel != null ? "Click or let go to play" : "Point, then click · 1–" + emotes.size();
            int hw = UiFont.width(tr, hint, Role.SMALL, smooth());
            UiFont.draw(ctx, tr, hint, Math.round(cx - hw / 2f), Math.round(UiFont.centredY(Role.SMALL, ny + 12, smooth())), Tokens.N700, Role.SMALL, smooth());
        } finally {
            Theme.fade = base;
        }
    }

    @Override
    public void tick() {
        Cosmetics m = Cosmetics.get();
        if (m == null || client == null) return;
        int key = m.emoteKey.get();
        if (key > 0 && !InputUtil.isKeyPressed(client.getWindow(), key) && (System.nanoTime() - opened) > 2.5e8 && hovered >= 0) pick(hovered);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (hovered >= 0) { pick(hovered); return true; }
        close();
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int k = input.key();
        if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_8 && k - GLFW.GLFW_KEY_1 < emotes.size()) { pick(k - GLFW.GLFW_KEY_1); return true; }
        return super.keyPressed(input);
    }

    /** Plays emote {@code i} of the wheel and closes it. */
    public void pick(int i) {
        if (i < 0 || i >= emotes.size()) return;
        CosmeticItem e = emotes.get(i);
        close();
        Emotes.play(e);
    }
}
