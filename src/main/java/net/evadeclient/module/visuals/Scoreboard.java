package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.Hud;
import net.evadeclient.module.Category;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.Setting;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.scoreboard.number.StyledNumberFormat;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Tidies the sidebar scoreboard: hide the red numbers, move, resize or restyle it, or hide it altogether.
 *
 * <p>The sidebar is still vanilla's own drawing: {@code InGameHudMixin} moves, scales and restyles it as vanilla
 * draws it. This element only gives it a box in the HUD editor, so it can be dragged and resized like Evade's own
 * widgets. At its default spot it stays exactly where vanilla puts it (right edge, about the middle, following the
 * number of lines) and scales around vanilla's anchor there. It draws nothing itself in game; the HUD editor shows a
 * sample sidebar while no real one is on screen.
 */
public class Scoreboard extends HudElementModule {
    /** Default y: vanilla's own spot. Saved like any other position, but dragging never produces it. */
    public static final int VANILLA_SPOT = Integer.MIN_VALUE;
    /** How long a drawn sidebar counts as on screen (a few frames even at a low frame rate). */
    private static final long LIVE_NS = 500_000_000L;

    public final BooleanSetting hideNumbers = add(new BooleanSetting("Hide Numbers", true)
            .describe("Hide the red score numbers. Text a server shows in their place stays."));
    public final BooleanSetting hide = add(new BooleanSetting("Hide Scoreboard", false));
    public final BooleanSetting textShadow = add(new BooleanSetting("Text Shadow", false)
            .describe("Draw the sidebar's text with a shadow."));

    // The sidebar vanilla drew last (GUI pixels, unscaled): its size, and its top-left corner relative to vanilla's
    // anchor (the right edge, half way down). The sample's while no real sidebar is on screen. Render thread only.
    private int boxW = 60, boxH = 55;
    private float relLeft = -61, relTop = -40;
    /** When vanilla last drew the sidebar (System.nanoTime), 0 for never. */
    private long seenAt;
    /** The sample sidebar's box width with numbers shown / hidden (0 until measured). */
    private final int[] sampleW = new int[2];

    private static final Text SAMPLE_TITLE = Text.literal("Your Server").formatted(Formatting.YELLOW, Formatting.BOLD);
    private static final Text[] SAMPLE_LINES = {
            Text.literal("Players: ").append(Text.literal("12").formatted(Formatting.GREEN)),
            Text.literal("Mode: ").append(Text.literal("Survival").formatted(Formatting.GREEN)),
            Text.literal(""),
            Text.literal("Coins: ").append(Text.literal("340").formatted(Formatting.GOLD)),
            Text.literal("evadeclient.com").formatted(Formatting.YELLOW),
    };

    public Scoreboard() {
        super("Scoreboard", "Hide the red numbers, or move, resize, restyle or hide the sidebar.", Category.VISUALS,
                -1, VANILLA_SPOT);
        setVisible(true); // a tweak to vanilla's sidebar: listed in the module list like the other visual mods
        // The sidebar keeps vanilla's look, so Evade's panel styles and smooth font don't apply to it. Background and
        // Background Opacity do: they switch and fade vanilla's own dark background.
        background.describe("Draw the sidebar's dark background.");
        backgroundStyle.hidden();
        smoothFont.hidden();
        // This module's own switches first, then the placement and visibility ones every HUD element has.
        List<Setting<?>> settings = getSettings();
        List<Setting<?>> own = List.of(hideNumbers, hide, textShadow);
        settings.removeAll(own);
        settings.addAll(0, own);
    }

    public static Scoreboard active() {
        if (EvadeClient.modules == null) return null;
        Scoreboard s = EvadeClient.modules.get(Scoreboard.class);
        return s != null && s.isEnabled() ? s : null;
    }

    // ------------------------------------------------------------------ vanilla's sidebar (InGameHudMixin)

    /** Whether vanilla may draw the sidebar now: Hide Scoreboard, Hide In Menus and Only In Combat (not in the HUD editor). */
    public boolean sidebarAllowed() {
        if (hide.get()) return false;
        return mc.currentScreen instanceof net.evadeclient.gui.HudEditorScreen || shouldShow();
    }

    /** Whether a score drawn with {@code format} (the score's own, else the objective's) loses its number. */
    public boolean hidesNumber(NumberFormat format) {
        // Only real numbers: a fixed text a server puts in the score column (a tick, a label) is content, and a
        // blank format already draws nothing.
        return hideNumbers.get() && format instanceof StyledNumberFormat;
    }

    /** Vanilla's background colour {@code color} faded by Background Opacity. */
    public int backgroundColor(int color) {
        float o = opacity.getFloat() / 100f;
        if (o >= 0.999f) return color;
        int a = Math.round((color >>> 24) * o);
        return a << 24 | color & 0xFFFFFF;
    }

    /**
     * Called as vanilla starts drawing the sidebar at its own spot; {@code left, top, w, h} is the sidebar's box there
     * in GUI pixels. Moves and scales everything vanilla draws next onto this element's spot.
     *
     * @return whether a matrix was pushed (the caller pops it once vanilla is done)
     */
    public boolean beginSidebar(DrawContext ctx, int left, int top, int w, int h) {
        int gw = ctx.getScaledWindowWidth(), gh = ctx.getScaledWindowHeight();
        boxW = w;
        boxH = h;
        relLeft = left - gw;
        relTop = top - gh / 2f;
        seenAt = System.nanoTime();
        float k = Hud.scale(), s = scale.getFloat();
        updateSize(k);
        float tx = atDefaultSpot() ? vanillaLeft(gw) : super.resolveX(Math.round(gw / k)) * k;
        float ty = y == VANILLA_SPOT ? vanillaTop(gh) : super.resolveY(Math.round(gh / k)) * k;
        if (Math.abs(s - 1f) < 1e-3f && Math.abs(tx - left) < 1e-3f && Math.abs(ty - top) < 1e-3f) return false;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(tx, ty);
        m.scale(s, s);
        m.translate(-left, -top);
        return true;
    }

    /** Whether vanilla has drawn the sidebar in the last few frames. */
    public boolean sidebarShown() {
        return seenAt != 0 && System.nanoTime() - seenAt < LIVE_NS;
    }

    // ------------------------------------------------------------------ the HUD element

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        boolean live = refresh();
        if (!preview) {
            // Vanilla draws the real thing; this only tells elements stacked on it whether it is there.
            empty = !live;
            return;
        }
        if (live) return; // the real sidebar is on screen behind the editor, already at this spot
        float k = Hud.scale();
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(left, top);
        ctx.getMatrices().scale(1f / k, 1f / k); // the sample is drawn in GUI pixels, the same size as the real one
        drawSample(ctx);
        ctx.getMatrices().popMatrix();
    }

    @Override
    public int resolveX(int screenW) {
        refresh();
        if (!atDefaultSpot()) return super.resolveX(screenW);
        var win = mc.getWindow();
        return win == null ? 0 : Math.round(vanillaLeft(win.getScaledWidth()) / Hud.scale());
    }

    @Override
    public int resolveY(int screenH) {
        refresh();
        // Checked on y alone (not atDefaultSpot), like beginSidebar, so a hand-edited x with vanilla's y still works.
        if (y != VANILLA_SPOT) return super.resolveY(screenH);
        var win = mc.getWindow();
        return win == null ? 0 : Math.round(vanillaTop(win.getScaledHeight()) / Hud.scale());
    }

    /** Left edge (GUI pixels) at vanilla's spot: scaled around the right edge, kept on screen. */
    private float vanillaLeft(int gw) {
        float s = scale.getFloat();
        return Math.max(0, Math.min(gw - boxW * s, gw + relLeft * s));
    }

    /** Top edge (GUI pixels) at vanilla's spot: scaled around the middle of the screen, kept on screen. */
    private float vanillaTop(int gh) {
        float s = scale.getFloat();
        return Math.max(0, Math.min(gh - boxH * s, gh / 2f + relTop * s));
    }

    /** Use the sample's box while no real sidebar is on screen, and size the element to it. @return whether one is */
    private boolean refresh() {
        boolean live = sidebarShown();
        if (!live) {
            int n = SAMPLE_LINES.length;
            boxW = sampleWidth();
            boxH = n * 9 + 10;
            // Where vanilla would put a sidebar this size: 1 pixel from the right edge, a third of the lines below the middle.
            relLeft = -(boxW + 1);
            relTop = -(6 * n + 10);
        }
        updateSize(Hud.scale());
        return live;
    }

    /** Element size in HUD units from the box in GUI pixels. */
    private void updateSize(float k) {
        width = Math.max(1, Math.round(boxW / k));
        height = Math.max(1, Math.round(boxH / k));
    }

    // ------------------------------------------------------------------ the sample sidebar (HUD editor)

    private static Text sampleScore(int line) {
        return Text.literal(String.valueOf(SAMPLE_LINES.length - line)).formatted(Formatting.RED);
    }

    /** Width of the sample's box, laid out as vanilla lays out a sidebar. */
    private int sampleWidth() {
        int i = hideNumbers.get() ? 1 : 0;
        TextRenderer tr = mc.textRenderer;
        if (sampleW[i] == 0 && tr != null) {
            int w = tr.getWidth(SAMPLE_TITLE), colon = tr.getWidth(": ");
            for (int l = 0; l < SAMPLE_LINES.length; l++) {
                int sw = hideNumbers.get() ? 0 : tr.getWidth(sampleScore(l));
                w = Math.max(w, tr.getWidth(SAMPLE_LINES[l]) + (sw > 0 ? colon + sw : 0));
            }
            sampleW[i] = w + 4;
        }
        return sampleW[i] == 0 ? 80 : sampleW[i];
    }

    /** The sample sidebar at (0, 0) in GUI pixels, in vanilla's layout and colours with this module's settings. */
    private void drawSample(DrawContext ctx) {
        TextRenderer tr = mc.textRenderer;
        int n = SAMPLE_LINES.length, w = sampleWidth(), h = n * 9 + 10, inner = w - 4;
        if (background.get()) {
            ctx.fill(0, 0, w, 9, backgroundColor(mc.options.getTextBackgroundColor(0.4f)));
            ctx.fill(0, 9, w, h, backgroundColor(mc.options.getTextBackgroundColor(0.3f)));
        }
        boolean shadow = textShadow.get();
        ctx.drawText(tr, SAMPLE_TITLE, 2 + inner / 2 - tr.getWidth(SAMPLE_TITLE) / 2, 1, -1, shadow);
        for (int l = 0; l < n; l++) {
            int y = 10 + l * 9;
            ctx.drawText(tr, SAMPLE_LINES[l], 2, y, -1, shadow);
            if (hideNumbers.get()) continue;
            Text score = sampleScore(l);
            ctx.drawText(tr, score, w - tr.getWidth(score), y, -1, shadow);
        }
    }
}
