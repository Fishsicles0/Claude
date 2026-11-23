package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.client.ArmorHud;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ClickTracker;
import net.evadeclient.util.Theme;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Your movement keys (labelled with whatever they're bound to), mouse buttons with CPS, and space bar; optionally
 * sneak and sprint. Keys light up in the accent colour while pressed and fade out when released.
 */
public class KeystrokesHud extends HudElementModule {
    private static final int K = 22, SMALL_ROW = 12;
    /** Fade slots, one per key. */
    private static final int W = 0, A = 1, S = 2, D = 3, LMB = 4, RMB = 5, SPACE = 6, SNEAK = 7, SPRINT = 8;

    private final BooleanSetting mouse = add(new BooleanSetting("Mouse Buttons", true));
    private final BooleanSetting cps = add(new BooleanSetting("Show CPS", true));
    private final BooleanSetting space = add(new BooleanSetting("Space Bar", true));
    private final BooleanSetting sneak = add(new BooleanSetting("Sneak Key", false)
            .describe("A sneak key, lit while you hold it (not while Toggle Sneak keeps you sneaking)."));
    private final BooleanSetting sprint = add(new BooleanSetting("Sprint Key", false)
            .describe("A sprint key, lit while you hold it (not while Toggle Sprint keeps you sprinting)."));
    private final ColorSetting keyColor = add(themeColor("Key Color",
            "Background of a key. Reset to follow the widget's background style."));
    private final ColorSetting pressedColor = add(themeColor("Pressed Color",
            "A key while pressed. Reset to follow the accent colour."));
    private final ColorSetting textColor = add(themeColor("Text Color",
            "Key labels. Reset for the theme's text colour."));
    private final ColorSetting pressedTextColor = add(themeColor("Pressed Text Color",
            "Key labels while pressed. Reset for the theme's text-on-accent colour."));
    private final BooleanSetting textShadow = add(new BooleanSetting("Text Shadow", true));
    private final NumberSetting fadeTime = add(new NumberSetting("Fade Time", 170, 0, 500, 10)
            .describe("Milliseconds a key takes to fade out after you let go. 0 turns it off at once."));
    private final NumberSetting gap = add(new NumberSetting("Gap", 2, 0, 4, 1)
            .describe("Space between keys, in pixels."));

    private final float[] fade = new float[9];
    private long lastFrame = System.nanoTime();
    /** Movement key labels from the current bindings (W, A, S, D on QWERTY), refreshed once a tick. */
    private final String[] labels = {"W", "A", "S", "D"};
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
        for (int i = 0; i < 4; i++) {
            String s = binds[i].getBoundKeyLocalizedText().getString().strip();
            labels[i] = s.isEmpty() ? "?" : s.toUpperCase(Locale.ROOT);
        }
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        refreshLabels();
        var o = mc.options;
        int g = gap.getInt();
        width = K * 3 + g * 2;
        int y = top;
        key(ctx, W, labels[0], o.forwardKey.isPressed(), left + K + g, y, K, K, dt);
        y += K + g;
        key(ctx, A, labels[1], o.leftKey.isPressed(), left, y, K, K, dt);
        key(ctx, S, labels[2], o.backKey.isPressed(), left + K + g, y, K, K, dt);
        key(ctx, D, labels[3], o.rightKey.isPressed(), left + 2 * (K + g), y, K, K, dt);
        y += K + g;
        if (mouse.get()) {
            int mw = (width - g) / 2;
            button(ctx, LMB, "LMB", ClickTracker.leftDown(), cps.get() ? ClickTracker.leftCps() + " CPS" : null, left, y, mw, K, dt);
            button(ctx, RMB, "RMB", ClickTracker.rightDown(), cps.get() ? ClickTracker.rightCps() + " CPS" : null, left + mw + g, y, width - mw - g, K, dt);
            y += K + g;
        }
        if (sneak.get() || sprint.get()) {
            boolean both = sneak.get() && sprint.get();
            int hw = both ? (width - g) / 2 : width;
            if (sneak.get()) key(ctx, SNEAK, "SNEAK", physicallyDown(o.sneakKey), left, y, hw, SMALL_ROW, dt);
            if (sprint.get()) {
                int sx = both ? left + hw + g : left;
                key(ctx, SPRINT, "SPRINT", physicallyDown(o.sprintKey), sx, y, both ? width - hw - g : width, SMALL_ROW, dt);
            }
            y += SMALL_ROW + g;
        }
        if (space.get()) {
            float t = anim(SPACE, o.jumpKey.isPressed(), dt);
            box(ctx, left, y, width, SMALL_ROW, t);
            int bar = Theme.lerpColor(labelColor(), pressedLabelColor(), t);
            ctx.fill(left + width / 2 - 14, y + 5, left + width / 2 + 14, y + 7, bar);
            y += SMALL_ROW + g;
        }
        height = Math.max(1, y - top - g);
    }

    /**
     * Whether the key bound to {@code bind} is physically held. Toggle Sprint and the vanilla toggle options keep
     * {@code isPressed()} true while toggled on, which would leave the key lit.
     */
    private boolean physicallyDown(KeyBinding bind) {
        if (mc.currentScreen != null || mc.getWindow() == null) return false;
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(bind);
        if (key == null || key == InputUtil.UNKNOWN_KEY) return false;
        if (key.getCategory() == InputUtil.Type.MOUSE)
            return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), key.getCode()) == GLFW.GLFW_PRESS;
        return key.getCategory() == InputUtil.Type.KEYSYM && InputUtil.isKeyPressed(mc.getWindow(), key.getCode());
    }

    private int labelColor() { return textColor.get() != 0 ? textColor.get() | 0xFF000000 : Theme.TEXT; }

    private int pressedLabelColor() { return pressedTextColor.get() != 0 ? pressedTextColor.get() | 0xFF000000 : Theme.ON_ACCENT; }

    private void key(DrawContext ctx, int slot, String label, boolean down, int x, int y, int w, int h, float dt) {
        float t = anim(slot, down, dt);
        box(ctx, x, y, w, h, t);
        int color = Theme.lerpColor(labelColor(), pressedLabelColor(), t);
        // Small rows (sneak, sprint) and labels too wide for the key ("LEFT SHIFT", arrows' names) use small text.
        boolean small = h < K;
        Text full = styled(label);
        if (!small && mc.textRenderer.getWidth(full) <= w - 4) {
            drawLabel(ctx, full, x + w / 2f, y + (h - 8) / 2f, 1f, color);
            return;
        }
        float s = 0.7f;
        int max = Math.round((w - 3) / s);
        String fit = net.evadeclient.hud.HudDraw.trim(label, max, smoothFont.get());
        drawLabel(ctx, styled(fit), x + w / 2f, y + (h - 8 * s) / 2f, s, color);
    }

    private void button(DrawContext ctx, int slot, String label, boolean down, String sub, int x, int y, int w, int h, float dt) {
        float t = anim(slot, down, dt);
        box(ctx, x, y, w, h, t);
        int color = Theme.lerpColor(labelColor(), pressedLabelColor(), t);
        float ty = sub == null ? y + (h - 8) / 2f : y + 3;
        drawLabel(ctx, styled(label), x + w / 2f, ty, 1f, color);
        if (sub != null) {
            int subColor = Theme.lerpColor(Theme.alpha(labelColor(), 0.8f), pressedLabelColor(), t);
            drawLabel(ctx, styled(sub), x + w / 2f, y + 13, 0.7f, subColor);
        }
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

    private void box(DrawContext ctx, int x, int y, int w, int h, float t) {
        if (background.get()) {
            int kc = keyColor.get();
            if (kc != 0) Theme.round(ctx, x, y, x + w, y + h, Theme.alpha(kc | 0xFF000000, 0.75f * Theme.hudOpacity), Theme.R_HUD);
            // The Evade style's accent edge on every key would be busy: keys get its glass fill only.
            else if ("Evade".equals(Theme.hudStyle)) Theme.round(ctx, x, y, x + w, y + h, Theme.hudFill(), Theme.R_HUD);
            else Theme.hudBox(ctx, x, y, w, h);
        }
        if (t > 0) {
            int pc = pressedColor.get() != 0 ? pressedColor.get() | 0xFF000000 : Theme.RED | 0xFF000000;
            Theme.round(ctx, x, y, x + w, y + h, Theme.alpha(pc, 0.85f * t), Theme.R_HUD);
        }
    }

    private float anim(int slot, boolean down, float dt) {
        float ms = fadeTime.getFloat();
        float v = down ? 1f : ms <= 0 ? 0f : Math.max(0f, fade[slot] - dt * 1000f / ms); // instant press, smooth release
        fade[slot] = v;
        return v;
    }
}
