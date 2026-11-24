package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.client.ArmorHud;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ClickTracker;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.Map;

/**
 * Your movement keys (labelled with whatever they're bound to), mouse buttons with CPS, and space bar; optionally
 * sneak and sprint. Keys light up in the accent colour while pressed and fade out when released.
 */
public class KeystrokesHud extends HudElementModule {
    /** Animation slots, one per key (fixed, so a rebind never mixes up two keys' fades). */
    private static final int W = 0, A = 1, S = 2, D = 3, LMB = 4, RMB = 5, SPACE = 6, SNEAK = 7, SPRINT = 8;
    /** How long the Fill animation takes to fill a key on press, in seconds. */
    private static final float FILL_IN = 0.06f;

    /**
     * Short labels for keys whose names don't fit a key cap ("Left Shift" is "LShift"), by translation key so they
     * are the same in every language. Arrow keys get arrows.
     */
    private static final Map<String, String> SHORT = Map.ofEntries(
            Map.entry("key.keyboard.left.shift", "LShift"), Map.entry("key.keyboard.right.shift", "RShift"),
            Map.entry("key.keyboard.left.control", "LCtrl"), Map.entry("key.keyboard.right.control", "RCtrl"),
            Map.entry("key.keyboard.left.alt", "LAlt"), Map.entry("key.keyboard.right.alt", "RAlt"),
            Map.entry("key.keyboard.left.win", "LWin"), Map.entry("key.keyboard.right.win", "RWin"),
            Map.entry("key.keyboard.caps.lock", "Caps"), Map.entry("key.keyboard.num.lock", "NumLk"),
            Map.entry("key.keyboard.scroll.lock", "ScrLk"), Map.entry("key.keyboard.print.screen", "PrtSc"),
            Map.entry("key.keyboard.page.up", "PgUp"), Map.entry("key.keyboard.page.down", "PgDn"),
            Map.entry("key.keyboard.backspace", "Bksp"), Map.entry("key.keyboard.delete", "Del"),
            Map.entry("key.keyboard.insert", "Ins"), Map.entry("key.keyboard.escape", "Esc"),
            Map.entry("key.keyboard.space", "Space"), Map.entry("key.keyboard.enter", "Enter"),
            Map.entry("key.keyboard.tab", "Tab"), Map.entry("key.keyboard.home", "Home"),
            Map.entry("key.keyboard.end", "End"), Map.entry("key.keyboard.menu", "Menu"),
            Map.entry("key.keyboard.pause", "Pause"),
            Map.entry("key.keyboard.up", "▲"), Map.entry("key.keyboard.left", "◀"),
            Map.entry("key.keyboard.down", "▼"), Map.entry("key.keyboard.right", "▶"),
            Map.entry("key.keyboard.keypad.add", "Num+"), Map.entry("key.keyboard.keypad.subtract", "Num-"),
            Map.entry("key.keyboard.keypad.multiply", "Num*"), Map.entry("key.keyboard.keypad.divide", "Num/"),
            Map.entry("key.keyboard.keypad.decimal", "Num."), Map.entry("key.keyboard.keypad.enter", "NumEnt"),
            Map.entry("key.keyboard.keypad.equal", "Num="));
    private static final String[] WASD = {"W", "A", "S", "D"};
    private static final String[] ARROWS = {"▲", "◀", "▼", "▶"};

    private final BooleanSetting mouse = add(new BooleanSetting("Mouse Buttons", true));
    private final BooleanSetting cps = add(new BooleanSetting("Show CPS", true));
    private final ModeSetting cpsStyle = add(new ModeSetting("CPS Style", "Under Label", "Under Label", "Replace Label", "Combined Row")
            .describe("Where clicks per second show: small under LMB/RMB, instead of the labels, or in one row "
                    + "(\"12 | 9 CPS\") under the buttons. Combined Row also shows with Mouse Buttons off."));
    private final BooleanSetting space = add(new BooleanSetting("Space Bar", true));
    private final ModeSetting spaceStyle = add(new ModeSetting("Space Bar Style", "Bar", "Bar", "Label")
            .describe("The space bar as a dash, or labelled with the key jump is bound to."));
    private final BooleanSetting sneak = add(new BooleanSetting("Sneak Key", false)
            .describe("A sneak key, lit while you hold it, and while the game's own Sneak: Toggle keeps you sneaking."));
    private final BooleanSetting sprint = add(new BooleanSetting("Sprint Key", false)
            .describe("A sprint key, lit while you hold it, and while the game's own Sprint: Toggle keeps you sprinting."));
    private final ModeSetting keyLabels = add(new ModeSetting("Key Labels", "Bound Keys", "Bound Keys", "WASD", "Arrows")
            .describe("Label the movement keys with the keys they're bound to (LShift, MMB, M4...), always W A S D, or arrows."));
    private final NumberSetting keySize = add(new NumberSetting("Key Size", 22, 16, 32, 1)
            .describe("Width and height of a movement key, in pixels. The rows below scale with it."));
    private final NumberSetting gap = add(new NumberSetting("Gap", 2, 0, 6, 1)
            .describe("Space between keys, in pixels."));
    private final ColorSetting keyColor = add(themeColor("Key Color",
            "Background of a key. Reset to follow the widget's background style."));
    private final ColorSetting pressedColor = add(themeColor("Pressed Color",
            "A key while pressed. Reset to follow the accent colour."));
    private final ColorSetting textColor = add(themeColor("Text Color",
            "Key labels. Reset for the theme's text colour."));
    private final ColorSetting pressedTextColor = add(themeColor("Pressed Text Color",
            "Key labels while pressed. Reset for the theme's text-on-accent colour."));
    private final BooleanSetting textShadow = add(new BooleanSetting("Text Shadow", true));
    private final ModeSetting pressAnimation = add(new ModeSetting("Press Animation", "Fade", "Fade", "Instant", "Fill", "Shrink")
            .describe("Fade lights a key at once and fades it out; Instant has no fade; Fill rises from the bottom "
                    + "of the key; Shrink presses the key in a little."));
    private final NumberSetting fadeTime = add(new NumberSetting("Fade Time", 170, 0, 500, 10)
            .describe("Milliseconds a key takes to fade out after you let go. 0 turns it off at once."));

    private final float[] fade = new float[9];
    private long lastFrame = System.nanoTime();
    /** While true, {@link #layout} draws only the keys' soft shadows (see {@link #render}). */
    private boolean shadowPass;
    /** Movement and jump key labels for the chosen Key Labels mode, refreshed once a tick. */
    private final String[] labels = {"W", "A", "S", "D", "Space"};
    private long labelsTick = -1;

    public KeystrokesHud() {
        super("Keystrokes", "Shows your movement keys, mouse and space presses.", -1, -54);
        setEnabled(true);
    }

    /** A colour that is 0 ("Theme") until picked: 0 follows the theme, shown as "Theme" in commands and docs. */
    private static ColorSetting themeColor(String name, String description) {
        ColorSetting c = new ColorSetting(name, 0) {
            @Override
            public String display() { return get() == 0 ? "Theme" : super.display(); }
        };
        return c.describe(description);
    }

    /** At its default spot, the widget sits just above ArmorHUD (when that is at its own default spot too). */
    @Override
    public int resolveY(int screenH) {
        if (atDefaultSpot() && EvadeClient.modules != null) {
            int top = stackedAbove(EvadeClient.modules.get(ArmorHud.class), screenH);
            if (top != Integer.MIN_VALUE) return top;
        }
        return super.resolveY(screenH);
    }

    private void refreshLabels() {
        long tick = EvadeClient.ticks;
        if (tick == labelsTick) return;
        labelsTick = tick;
        var o = mc.options;
        KeyBinding[] binds = {o.forwardKey, o.leftKey, o.backKey, o.rightKey};
        String mode = keyLabels.get();
        for (int i = 0; i < 4; i++) {
            labels[i] = switch (mode) {
                case "WASD" -> WASD[i];
                case "Arrows" -> ARROWS[i];
                default -> bindLabel(binds[i]);
            };
        }
        labels[4] = mode.equals("Bound Keys") ? bindLabel(o.jumpKey) : "Space";
    }

    /**
     * A key cap label for whatever {@code bind} is bound to: "-" when unbound, LMB/RMB/MMB or M4, M5... for mouse
     * buttons, a short name for long key names ("LShift", "PgUp", arrows for arrow keys) and otherwise the key's
     * name as the game shows it (the letter on your keyboard layout).
     */
    public static String bindLabel(KeyBinding bind) {
        if (bind == null || bind.isUnbound()) return "-";
        String key = bind.getBoundKeyTranslationKey();
        if (key.startsWith("key.mouse.")) {
            String b = key.substring("key.mouse.".length());
            return switch (b) {
                case "left" -> "LMB";
                case "right" -> "RMB";
                case "middle" -> "MMB";
                default -> "M" + b;
            };
        }
        String s = SHORT.get(key);
        if (s != null) return s;
        if (key.startsWith("key.keyboard.keypad.")) return "Num" + key.substring("key.keyboard.keypad.".length());
        s = bind.getBoundKeyLocalizedText().getString().strip();
        // No layout name from GLFW and no translation (letters, digits): the game shows the raw key, e.g. "key.keyboard.w".
        if (s.equals(key) && key.startsWith("key.keyboard.")) s = key.substring("key.keyboard.".length());
        if (s.isEmpty()) return "?";
        return s.length() <= 2 ? s.toUpperCase(Locale.ROOT) : s;
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        refreshLabels();
        animate(dt);
        // Every key's soft shadow first, then the keys: a key's shadow never lands on a neighbour drawn before it
        // (with a small Gap it would darken that key's edge, and its press highlight).
        if (background.get() && !"Outline".equals(Theme.hudStyle)) {
            shadowPass = true;
            try {
                layout(ctx, left, top);
            } finally {
                shadowPass = false;
            }
        }
        layout(ctx, left, top);
    }

    /** Advances every key's press animation once per frame (both passes then read the same values). */
    private void animate(float dt) {
        var o = mc.options;
        anim(W, o.forwardKey.isPressed(), dt);
        anim(A, o.leftKey.isPressed(), dt);
        anim(S, o.backKey.isPressed(), dt);
        anim(D, o.rightKey.isPressed(), dt);
        anim(LMB, ClickTracker.leftDown(), dt);
        anim(RMB, ClickTracker.rightDown(), dt);
        anim(SPACE, o.jumpKey.isPressed(), dt);
        // isPressed() is the real key, or the latched state while the game's own Sneak/Sprint: Toggle is on.
        anim(SNEAK, o.sneakKey.isPressed(), dt);
        anim(SPRINT, o.sprintKey.isPressed(), dt);
    }

    /** Lays out and draws the keys (or, in the shadow pass, only their shadows), and sets the widget's size. */
    private void layout(DrawContext ctx, int left, int top) {
        int k = keySize.getInt(), g = gap.getInt();
        // Rows under the movement keys keep their proportions at every key size (12 px at the default 22).
        int small = Math.max(10, Math.round(k * 6 / 11f));
        width = k * 3 + g * 2;
        int y = top;
        key(ctx, W, labels[0], left + k + g, y, k, k, k);
        y += k + g;
        key(ctx, A, labels[1], left, y, k, k, k);
        key(ctx, S, labels[2], left + k + g, y, k, k, k);
        key(ctx, D, labels[3], left + 2 * (k + g), y, k, k, k);
        y += k + g;
        boolean showCps = cps.get();
        String style = cpsStyle.get();
        boolean combined = showCps && style.equals("Combined Row");
        if (mouse.get()) {
            int mw = (width - g) / 2;
            String lSub = null, rSub = null, lLabel = "LMB", rLabel = "RMB";
            if (showCps && style.equals("Under Label")) {
                lSub = ClickTracker.leftCps() + " CPS";
                rSub = ClickTracker.rightCps() + " CPS";
            } else if (showCps && style.equals("Replace Label")) {
                lLabel = cpsLabel(ClickTracker.leftCps(), mw);
                rLabel = cpsLabel(ClickTracker.rightCps(), width - mw - g);
            }
            button(ctx, LMB, lLabel, lSub, left, y, mw, k, k);
            button(ctx, RMB, rLabel, rSub, left + mw + g, y, width - mw - g, k, k);
            y += k + g;
        }
        if (combined) {
            String text = ClickTracker.leftCps() + " | " + ClickTracker.rightCps() + " CPS";
            key(ctx, -1, text, left, y, width, small, k);
            y += small + g;
        }
        if (sneak.get() || sprint.get()) {
            boolean both = sneak.get() && sprint.get();
            int hw = both ? (width - g) / 2 : width;
            if (sneak.get()) key(ctx, SNEAK, "SNEAK", left, y, hw, small, k);
            if (sprint.get()) {
                int sx = both ? left + hw + g : left;
                key(ctx, SPRINT, "SPRINT", sx, y, both ? width - hw - g : width, small, k);
            }
            y += small + g;
        }
        if (space.get()) {
            if (spaceStyle.is("Label")) {
                key(ctx, SPACE, labels[4], left, y, width, small, k);
            } else {
                float t = fade[SPACE];
                int in = shrinkInset(t, k);
                box(ctx, left + in, y + in, width - 2 * in, small - 2 * in, t);
                if (!shadowPass) {
                    // The dash takes the label colour, and the text-on-accent colour while pressed.
                    int bar = Theme.lerpColor(labelColor(), pressedLabelColor(), t);
                    int half = Math.round(k * 0.64f), mid = y + small / 2;
                    ctx.fill(left + width / 2 - half + in, mid - 1, left + width / 2 + half - in, mid + 1, bar);
                }
            }
            y += small + g;
        }
        height = Math.max(1, y - top - g);
    }

    /**
     * "12 CPS" when even "99 CPS" fits a {@code w} px button at full size, else just "12": decided by the button's
     * size, not the number, so the label doesn't switch styles as the count changes.
     */
    private String cpsLabel(int n, int w) {
        boolean roomy = mc.textRenderer.getWidth(styled("99 CPS")) <= w - 4;
        return roomy ? n + " CPS" : String.valueOf(n);
    }

    private int labelColor() { return textColor.get() != 0 ? textColor.get() | 0xFF000000 : Theme.TEXT; }

    private int pressedLabelColor() { return pressedTextColor.get() != 0 ? pressedTextColor.get() | 0xFF000000 : Theme.ON_ACCENT; }

    /** How far a key is pressed in for the Shrink animation: one pixel at the default size, two from 32. */
    private int shrinkInset(float t, int k) {
        if (!pressAnimation.is("Shrink") || t <= 0) return 0;
        return Math.round(t * Math.max(1f, k / 16f));
    }

    /**
     * One key: its box, press highlight and centred label (only the box's shadow in the shadow pass). {@code slot}
     * -1 is a key that never lights up (the combined CPS row). Labels too wide for the key (or any label in a short
     * row) use small text.
     */
    private void key(DrawContext ctx, int slot, String label, int x, int y, int w, int h, int k) {
        float t = slot < 0 ? 0f : fade[slot];
        int in = shrinkInset(t, k);
        x += in; y += in; w -= 2 * in; h -= 2 * in;
        box(ctx, x, y, w, h, t);
        if (shadowPass) return;
        int color = Theme.lerpColor(labelColor(), pressedLabelColor(), t);
        boolean smallRow = h < k - 2 * in;
        Text full = styled(label);
        if (!smallRow && mc.textRenderer.getWidth(full) <= w - 4) {
            drawLabel(ctx, full, x + w / 2f, y + (h - 8) / 2f, 1f, color);
            return;
        }
        float s = 0.7f;
        int max = Math.round((w - 3) / s);
        String fit = HudDraw.trim(label, max, smoothFont.get());
        drawLabel(ctx, styled(fit), x + w / 2f, y + (h - 8 * s) / 2f, s, color);
    }

    /** A mouse button: label, and optionally a small CPS line under it. */
    private void button(DrawContext ctx, int slot, String label, String sub, int x, int y, int w, int h, int k) {
        if (sub == null) { key(ctx, slot, label, x, y, w, h, k); return; }
        float t = fade[slot];
        int in = shrinkInset(t, k);
        x += in; y += in; w -= 2 * in; h -= 2 * in;
        box(ctx, x, y, w, h, t);
        if (shadowPass) return;
        int color = Theme.lerpColor(labelColor(), pressedLabelColor(), t);
        // Label (8 px) over a 0.7-scale sub-label, centred together: 3 and 13 px down in a 22 px key.
        int top = y + Math.max(0, (h - 15) / 2);
        Text main = styled(label);
        if (mc.textRenderer.getWidth(main) <= w - 4) drawLabel(ctx, main, x + w / 2f, top, 1f, color);
        else drawLabel(ctx, styled(HudDraw.trim(label, Math.round((w - 3) / 0.7f), smoothFont.get())), x + w / 2f, top + 1, 0.7f, color);
        int subColor = Theme.lerpColor(Theme.alpha(labelColor(), 0.8f), pressedLabelColor(), t);
        drawLabel(ctx, styled(sub), x + w / 2f, top + 10, 0.7f, subColor);
    }

    /** {@code text} centred on {@code cx} with its top at {@code top}, at {@code scale}. */
    private void drawLabel(DrawContext ctx, Text text, float cx, float top, float scale, int color) {
        var tr = mc.textRenderer;
        int tw = tr.getWidth(text);
        if (scale == 1f) {
            ctx.drawText(tr, text, Math.round(cx - tw / 2f), Math.round(top), color, textShadow.get());
            return;
        }
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(cx, top);
        ctx.getMatrices().scale(scale, scale);
        ctx.drawText(tr, text, -tw / 2, 0, color, textShadow.get());
        ctx.getMatrices().popMatrix();
    }

    /**
     * A key's background and press highlight ({@code t} 0..1), or in the shadow pass only the background's soft
     * shadow. The background follows the widget's Background Style (shadow, fill, Glass rim, Outline border) without
     * the Evade style's accent edge, which would be busy on every key; a Key Color replaces the style's fill.
     */
    private void box(DrawContext ctx, int x, int y, int w, int h, float t) {
        if (w <= 0 || h <= 0) return;
        float bg = backgroundShown(t);
        if (bg > 0f) {
            float saved = Theme.fade;
            Theme.fade = saved * bg;
            try {
                int kc = keyColor.get();
                if (shadowPass) HudDraw.panelShadow(ctx, x, y, w, h);
                else if (kc != 0) Theme.round(ctx, x, y, x + w, y + h, Theme.alpha(kc | 0xFF000000, 0.75f * Theme.hudOpacity), Theme.R_HUD);
                else HudDraw.panelBody(ctx, x, y, w, h, false);
            } finally {
                Theme.fade = saved;
            }
        }
        if (shadowPass || t <= 0) return;
        int pc = pressedColor.get() != 0 ? pressedColor.get() | 0xFF000000 : Theme.RED | 0xFF000000;
        if (pressAnimation.is("Fill")) {
            // Rises from the bottom edge; the fill is solid, its height is the animation.
            int fh = Math.round(h * t);
            if (fh <= 0) return;
            int corners = fh >= h ? Theme.ALL : Theme.BL | Theme.BR;
            Theme.round(ctx, x, y + h - fh, x + w, y + h, Theme.alpha(pc, 0.85f), Math.min(Theme.R_HUD, fh / 2), corners);
            return;
        }
        Theme.round(ctx, x, y, x + w, y + h, Theme.alpha(pc, 0.85f * t), Theme.R_HUD);
    }

    /**
     * How much of a key's background shows at press progress {@code t}: all of it at rest and none once the key is
     * fully lit (the highlight covers the key). Fill keeps it whole under the part not yet filled; the other
     * animations cross-fade it with the highlight, so a release fades the shadow and fill back in without a step.
     */
    private float backgroundShown(float t) {
        if (!background.get() || t >= 1f) return 0f;
        return pressAnimation.is("Fill") ? 1f : 1f - t;
    }

    /** Advances the press animation of key {@code slot} by {@code dt} seconds for the chosen Press Animation. */
    private void anim(int slot, boolean down, float dt) {
        float ms = fadeTime.getFloat();
        float prev = fade[slot];
        float v = switch (pressAnimation.get()) {
            case "Instant" -> down ? 1f : 0f;
            // Quick fill on press, drains over Fade Time on release.
            case "Fill" -> down ? Math.min(1f, prev + dt / FILL_IN) : Math.max(0f, prev - dt / Math.max(0.01f, ms / 1000f));
            // Fade and Shrink: instant press, smooth release.
            default -> down ? 1f : ms <= 0 ? 0f : Math.max(0f, prev - dt * 1000f / ms);
        };
        fade[slot] = v;
    }
}
