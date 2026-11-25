package net.evadeclient.cosmetics;

import net.evadeclient.module.misc.Cosmetics;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The emote wheel: your emotes around a ring. Point at one and click it, or let go of the wheel key over it. The game
 * keeps running underneath (it doesn't pause), and the emote stops as soon as you move.
 */
public final class EmoteWheelScreen extends Screen {
    private static final int SLOTS = 8;
    private final List<CosmeticItem> emotes = new ArrayList<>();
    private final long opened = System.nanoTime();
    private int hovered = -1;

    private EmoteWheelScreen() {
        super(Text.literal("Emotes"));
        for (CosmeticItem i : CosmeticsCatalog.forSlot(Slot.EMOTE)) {
            if (Wardrobe.owns(i.id) && emotes.size() < SLOTS) { emotes.add(i); CosmeticAssets.request(i); }
        }
    }

    public static void open() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !CosmeticsEngine.allowed()) return;
        mc.setScreen(new EmoteWheelScreen());
    }

    public List<CosmeticItem> emotes() { return emotes; }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        int cx = width / 2, cy = height / 2, R = 78, r = 30;
        float a = Math.min(1f, (System.nanoTime() - opened) / 1.2e8f);
        ctx.fill(0, 0, width, height, Theme.alpha(0x80000000, a));
        double dx = mx - cx, dy = my - cy, dist = Math.sqrt(dx * dx + dy * dy);
        int n = Math.max(1, emotes.size());
        hovered = -1;
        if (dist > r * 0.8 && !emotes.isEmpty()) {
            double ang = (Math.atan2(dy, dx) + Math.PI * 2.5) % (Math.PI * 2);
            hovered = (int) (ang / (Math.PI * 2 / n)) % n;
        }
        Theme.disc(ctx, cx - R - 22, cy - R - 22, (R + 22) * 2, Theme.alpha(Theme.BG, 0.9f * a));
        if (emotes.isEmpty()) {
            String s = "No emotes yet: find them in the Wardrobe";
            UiFont.draw(ctx, textRenderer, s, cx - UiFont.width(textRenderer, s, UiFont.Role.BODY, true) / 2, cy - 4, Theme.TEXT_DIM, UiFont.Role.BODY, true);
            return;
        }
        for (int i = 0; i < emotes.size(); i++) {
            double ang = -Math.PI / 2 + (i + 0.5) * Math.PI * 2 / n;
            int ix = cx + (int) Math.round(Math.cos(ang) * (R - 12)), iy = cy + (int) Math.round(Math.sin(ang) * (R - 12));
            boolean on = i == hovered;
            Theme.disc(ctx, ix - 22, iy - 22, 44, on ? Theme.alpha(Theme.RED, 0.85f * a) : Theme.alpha(Theme.CARD, 0.95f * a));
            Identifier thumb = CosmeticAssets.thumb(emotes.get(i));
            if (thumb != null) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, thumb, ix - 18, iy - 18, 0, 0, 36, 36, 128, 128, 128, 128, -1);
        }
        String name = hovered >= 0 ? emotes.get(hovered).name : "Pick an emote";
        UiFont.draw(ctx, textRenderer, name, cx - UiFont.width(textRenderer, name, UiFont.Role.STRONG, true) / 2, cy - 4,
                Theme.TEXT, UiFont.Role.STRONG, true);
    }

    @Override
    public void tick() {
        // let go of the wheel key over an emote: play it
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

    /** Plays emote {@code i} of the wheel and closes it. */
    public void pick(int i) {
        if (i < 0 || i >= emotes.size()) return;
        CosmeticItem e = emotes.get(i);
        close();
        Emotes.play(e);
    }
}
