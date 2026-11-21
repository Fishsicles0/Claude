package net.evadeclient.module.visuals;

import net.evadeclient.mixin.GameRendererAccessor;
import net.evadeclient.mixin.OverlayTextureAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;

/** Changes the colour mobs and players flash when they're hit (vanilla is red). */
public class HitColor extends Module {
    private static final int VANILLA = 0xB2FF0000;
    public final ColorSetting color = add(new ColorSetting("Color", 0xFF3B82F6));
    public final NumberSetting opacity = add(new NumberSetting("Opacity %", 70, 10, 100, 5));
    private int applied = 0;

    public HitColor() {
        super("Hit Color", "Customise the flash colour when you hit players or mobs.", Category.VISUALS);
    }

    @Override protected void onEnable() { applied = 0; }
    @Override protected void onDisable() { paint(VANILLA); applied = 0; }

    @Override
    public void onTick() {
        int argb = (((int) Math.round(opacity.get() * 2.55)) << 24) | (color.get() & 0xFFFFFF);
        if (argb != applied) { paint(argb); applied = argb; }
    }

    /** The colour currently painted into the hurt overlay (for tests). */
    public int current() {
        var tex = ((OverlayTextureAccessor) ((GameRendererAccessor) mc.gameRenderer).evade$overlay()).evade$texture();
        return tex.getImage().getColorArgb(0, 0);
    }

    /** Rows 0-7 of the 16x16 overlay texture are the hurt tint; repaint them and re-upload. */
    private void paint(int argb) {
        if (mc.gameRenderer == null) return;
        var tex = ((OverlayTextureAccessor) ((GameRendererAccessor) mc.gameRenderer).evade$overlay()).evade$texture();
        var img = tex.getImage();
        if (img == null) return;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) img.setColorArgb(x, y, argb);
        tex.upload();
    }
}
