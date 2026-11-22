package net.evadeclient.module.hud;

import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.ClickTracker;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;

import java.util.HashMap;
import java.util.Map;

/** WASD, mouse buttons with CPS, and space bar. Keys fade red when pressed. */
public class KeystrokesHud extends HudElementModule {
    private final BooleanSetting mouse = add(new BooleanSetting("Mouse Buttons", true));
    private final BooleanSetting cps = add(new BooleanSetting("Show CPS", true));
    private final BooleanSetting space = add(new BooleanSetting("Space Bar", true));

    private final Map<String, Float> fade = new HashMap<>();
    private long lastFrame = System.nanoTime();

    public KeystrokesHud() {
        super("Keystrokes", "Shows WASD, mouse and space presses.", -1, 120);
        setEnabled(true);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        var o = mc.options;
        int k = 22, gap = 2;
        width = k * 3 + gap * 2;
        int y = top;
        key(ctx, "W", o.forwardKey, left + k + gap, y, k, k, dt);
        y += k + gap;
        key(ctx, "A", o.leftKey, left, y, k, k, dt);
        key(ctx, "S", o.backKey, left + k + gap, y, k, k, dt);
        key(ctx, "D", o.rightKey, left + 2 * (k + gap), y, k, k, dt);
        y += k + gap;
        if (mouse.get()) {
            int mw = (width - gap) / 2;
            button(ctx, "LMB", ClickTracker.leftDown(), cps.get() ? ClickTracker.leftCps() + " CPS" : null, left, y, mw, k, dt);
            button(ctx, "RMB", ClickTracker.rightDown(), cps.get() ? ClickTracker.rightCps() + " CPS" : null, left + mw + gap, y, mw, k, dt);
            y += k + gap;
        }
        if (space.get()) {
            float t = anim("SPACE", o.jumpKey.isPressed(), dt);
            box(ctx, left, y, width, 12, t);
            int bar = Theme.lerpColor(Theme.TEXT, 0xFF000000, t);
            ctx.fill(left + width / 2 - 14, y + 5, left + width / 2 + 14, y + 7, bar);
            y += 12;
        }
        height = y - top;
    }

    private void key(DrawContext ctx, String label, KeyBinding bind, int x, int y, int w, int h, float dt) {
        float t = anim(label, bind.isPressed(), dt);
        box(ctx, x, y, w, h, t);
        var tr = mc.textRenderer;
        ctx.drawTextWithShadow(tr, label, x + (w - tr.getWidth(label)) / 2, y + (h - 8) / 2,
                Theme.lerpColor(Theme.TEXT, 0xFFFFFFFF, t));
    }

    private void button(DrawContext ctx, String label, boolean down, String sub, int x, int y, int w, int h, float dt) {
        float t = anim(label, down, dt);
        box(ctx, x, y, w, h, t);
        var tr = mc.textRenderer;
        int ty = sub == null ? y + (h - 8) / 2 : y + 3;
        ctx.drawTextWithShadow(tr, label, x + (w - tr.getWidth(label)) / 2, ty, Theme.TEXT);
        if (sub != null) {
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(x + w / 2f, y + 13);
            ctx.getMatrices().scale(0.7f, 0.7f);
            ctx.drawTextWithShadow(tr, sub, -tr.getWidth(sub) / 2, 0, 0xFFCCCCD4);
            ctx.getMatrices().popMatrix();
        }
    }

    private void box(DrawContext ctx, int x, int y, int w, int h, float t) {
        int bg = background.get() ? Theme.hudFill() : 0x00000000;
        Theme.round(ctx, x, y, x + w, y + h, Theme.lerpColor(bg, Theme.alpha(Theme.RED, 0.85f), t));
    }

    private float anim(String id, boolean down, float dt) {
        float v = fade.getOrDefault(id, 0f);
        v = down ? 1f : Math.max(0f, v - dt * 6f); // instant press, smooth release
        fade.put(id, v);
        return v;
    }
}
