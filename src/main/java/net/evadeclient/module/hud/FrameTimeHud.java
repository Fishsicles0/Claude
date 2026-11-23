package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.FrameStats;
import net.evadeclient.util.Theme;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.Locale;

/**
 * Frame time graph: one bar per frame (green within a 60 FPS budget, yellow up to 30 FPS, red beyond),
 * with average FPS, the 1% low and the worst frame over the last ten seconds. Stutters show up as spikes.
 *
 * <p>Built to cost almost nothing: the bars are painted into a small texture once per tick (20/s) and drawn
 * with a single call each frame, and the stats are recomputed once per tick.
 */
public class FrameTimeHud extends HudElementModule {
    private static final Identifier TEX_ID = Identifier.of("evade", "frame_time_graph");
    private static final int GRAPH_H = 32;

    public final NumberSetting bars = add(new NumberSetting("Frames Shown", 120, 60, 240, 30));

    private float[] summary;
    private String header = "";
    private net.minecraft.text.Text headerText = net.minecraft.text.Text.empty();
    private long cacheTick = -1;
    private NativeImageBackedTexture texture;
    private int texW;

    public FrameTimeHud() { super("Frame Time Graph", "Live frame times with average FPS, 1% low and worst frame.", -1, 130); }

    @Override
    protected void onDisable() {
        if (texture != null) { mc.getTextureManager().destroyTexture(TEX_ID); texture = null; }
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var tr = mc.textRenderer;
        int n = bars.getInt();
        long tick = EvadeClient.ticks;
        if (summary == null || tick != cacheTick || preview) {
            summary = FrameStats.summary(FrameStats.SIZE);
            header = preview && FrameStats.count() == 0 ? "avg 144 FPS  1% low 98  max 12.4ms"
                    : String.format(Locale.ROOT, "avg %d FPS  1%% low %d  max %.1fms", Math.round(summary[0]), Math.round(summary[1]), summary[2]);
            headerText = styled(header);
            paintGraph(n);
            cacheTick = tick;
        }
        width = Math.max(n + 8, tr.getWidth(headerText) + 8);
        height = 50;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        ctx.drawTextWithShadow(tr, headerText, left + 4, top + 3, 0xFFFFFFFF);
        // One draw call per frame (measured ~4x cheaper than drawing a bar per frame).
        int gx = left + (width - n) / 2;
        if (texture != null) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, TEX_ID, gx, top + 14, 0, 0, n, GRAPH_H, n, GRAPH_H);
    }

    /** Paint the last {@code n} frame times into the graph texture (newest on the right). */
    private void paintGraph(int n) {
        if (texture == null || texW != n) {
            if (texture != null) mc.getTextureManager().destroyTexture(TEX_ID);
            texture = new NativeImageBackedTexture(() -> "evade_frame_time_graph", n, GRAPH_H, false);
            mc.getTextureManager().registerTexture(TEX_ID, texture);
            texW = n;
        }
        NativeImage img = texture.getImage();
        if (img == null) return;
        float scale = GRAPH_H / 50f; // 50 ms fills the graph
        int budgetRow = GRAPH_H - Math.round(16.7f * scale);
        int shown = Math.min(n, FrameStats.count());
        for (int x = 0; x < n; x++) {
            int i = n - 1 - x;           // frame index back from the newest
            int h = 0, color = 0;
            if (i < shown) {
                float ms = FrameStats.recent(i);
                h = Math.max(1, Math.min(GRAPH_H, Math.round(ms * scale)));
                color = ms <= 16.7f ? 0xFF22C55E : ms <= 33.3f ? 0xFFEAB308 : 0xFFE11D2E;
            }
            for (int y = 0; y < GRAPH_H; y++) {
                int argb = y >= GRAPH_H - h ? color : (y == budgetRow ? 0x50FFFFFF : 0);
                img.setColorArgb(x, y, argb);
            }
        }
        texture.upload();
    }
}
