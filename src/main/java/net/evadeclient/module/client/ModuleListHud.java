package net.evadeclient.module.client;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.Hud;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The list of mods you have on, longest name first, with an accent bar on the screen-edge side. Top-right under
 * the watermark by default; drag it anywhere in the HUD editor. Rows line up with the edge it is anchored to.
 */
public class ModuleListHud extends HudElementModule {
    /** Same spot as before it became a movable element: flush with the right edge, under the watermark. */
    public static final int DEFAULT_X = -1, DEFAULT_Y = 17;
    /** Top of the list at its default spot when the watermark is off or moved away. */
    private static final int TOP_WITHOUT_WATERMARK = 1;
    private static final int ROW_H = 10, PAD_TEXT = 3, PAD_BAR = 4;

    public final ModeSetting colorMode = add(new ModeSetting("List Color", "Accent Wave", "Accent Wave", "Rainbow", "Accent", "White"));
    public final ModeSetting align = add(new ModeSetting("Align", "Auto", "Auto", "Left", "Right"));
    public final BooleanSetting smoothFont = add(new BooleanSetting("Smooth Font", false));

    private static final Style UI_FONT = Style.EMPTY
            .withFont(new net.minecraft.text.StyleSpriteSource.Font(net.minecraft.util.Identifier.of("evade", "ui")));
    private static final String[][] SAMPLE = {{"Toggle Sprint", "Toggled"}, {"Performance", null}, {"Fullbright", null}, {"Zoom", null}};

    private record Row(Text text, int width) {}

    // Rows are rebuilt and re-sorted once per client tick, not every frame.
    private List<Row> rows = List.of();
    private long rowsTick = -1;
    private final Hud.ToastDodge dodge = new Hud.ToastDodge();

    public ModuleListHud() {
        super("Module List", "The mods you have on, listed in the corner of the screen.", DEFAULT_X, DEFAULT_Y);
        setEnabled(true);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        TextRenderer tr = mc.textRenderer;
        List<Row> list = rows(tr, preview);
        if (list.isEmpty()) { width = 40; height = ROW_H; return; }
        int maxW = 0;
        for (Row r : list) maxW = Math.max(maxW, r.width);
        width = maxW + PAD_TEXT + PAD_BAR;
        height = list.size() * ROW_H;

        boolean right = alignRight();
        boolean upward = y < 0; // anchored to the bottom: longest row at the bottom, growing upward
        int fill = rowFill();
        int n = list.size();
        for (int i = 0; i < n; i++) {
            int rank = upward ? n - 1 - i : i;
            Row r = list.get(rank);
            int rowW = r.width + PAD_TEXT + PAD_BAR;
            int x0 = right ? left + width - rowW : left, x1 = x0 + rowW;
            int y0 = top + i * ROW_H;
            int color = color(rank);
            if (fill != 0) ctx.fill(x0, y0, x1, y0 + ROW_H, fill);
            if (right) ctx.fill(x1 - 1, y0, x1, y0 + ROW_H, color);
            else ctx.fill(x0, y0, x0 + 1, y0 + ROW_H, color);
            ctx.drawText(tr, r.text, right ? x0 + PAD_TEXT : x0 + PAD_BAR, y0 + 1, color, true);
        }
    }

    @Override
    public void renderScaled(DrawContext ctx, int screenW, int screenH, boolean preview) {
        int dy = 0;
        if (!preview) {
            WatermarkHud wm = stackedUnder();
            dy = wm != null ? wm.toastOffset(screenW, screenH) : dodge.update(this, screenW, screenH);
        }
        if (dy == 0) { super.renderScaled(ctx, screenW, screenH, preview); return; }
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(0, dy);
        try {
            super.renderScaled(ctx, screenW, screenH, preview);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    /** At its default spot the list follows the watermark: right under it, or at the top when it is off. */
    @Override
    public int resolveY(int screenH) {
        if (x != DEFAULT_X || y != DEFAULT_Y) return super.resolveY(screenH);
        WatermarkHud wm = stackedUnder();
        return wm != null ? wm.stackY(screenH) : TOP_WITHOUT_WATERMARK;
    }

    /** The watermark this list is stacked under, or null when either has been moved or the watermark is off. */
    private WatermarkHud stackedUnder() {
        if (x != DEFAULT_X || y != DEFAULT_Y || EvadeClient.modules == null) return null;
        WatermarkHud wm = EvadeClient.modules.get(WatermarkHud.class);
        return wm != null && wm.isEnabled() && wm.atDefaultSpot() ? wm : null;
    }

    /** Auto lines rows up with the edge the list is anchored to (right when it sits on the right half). */
    private boolean alignRight() {
        return switch (align.get()) {
            case "Left" -> false;
            case "Right" -> true;
            default -> x < 0;
        };
    }

    private List<Row> rows(TextRenderer tr, boolean preview) {
        boolean cached = rowsTick == EvadeClient.ticks && !net.evadeclient.module.TextHudModule.NO_CACHE;
        if (!cached) {
            List<Row> out = new ArrayList<>();
            for (Module m : EvadeClient.modules.all()) {
                if (m.isEnabled() && m.isVisible()) out.add(row(tr, m.getName(), m.getSuffix()));
            }
            out.sort(Comparator.comparingInt(Row::width).reversed());
            rows = out;
            rowsTick = EvadeClient.ticks;
        }
        if (!preview || !rows.isEmpty()) return rows;
        List<Row> sample = new ArrayList<>();
        for (String[] s : SAMPLE) sample.add(row(tr, s[0], s[1]));
        sample.sort(Comparator.comparingInt(Row::width).reversed());
        return sample;
    }

    private Row row(TextRenderer tr, String name, String suffix) {
        MutableText t = Text.empty();
        if (smoothFont.get()) t.setStyle(UI_FONT);
        t.append(Text.literal(name));
        if (suffix != null && !suffix.isEmpty()) t.append(Text.literal(" " + suffix).formatted(Formatting.GRAY));
        return new Row(t, tr.getWidth(t));
    }

    /** Row backing for the element's Background options; Evade style is kept dark enough to read over sky. */
    private int rowFill() {
        if (!background.get()) return 0;
        return switch (Theme.hudStyle) {
            case "Outline" -> 0;
            case "Solid", "Glass" -> Theme.hudFill();
            default -> Theme.alpha(Theme.HUD_BG | 0xFF000000, 0.82f * Theme.hudOpacity);
        };
    }

    private int color(int rank) {
        return switch (colorMode.get()) {
            case "Rainbow" -> ColorUtil.rainbow(rank * 150L, 0.6f, 1f);
            case "Accent" -> Theme.RED;
            case "White" -> 0xFFFFFFFF;
            default -> accentWave(rank);
        };
    }

    /** The accent colour pulsing towards its bright tint down the list (never towards black, so it stays readable). */
    private static int accentWave(int rank) {
        double t = (Math.sin((System.currentTimeMillis() / 600.0) - rank * 0.45) + 1) / 2;
        return Theme.lerpColor(Theme.RED, Theme.RED_BRIGHT, (float) t);
    }
}
