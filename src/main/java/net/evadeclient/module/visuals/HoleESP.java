package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.HoleUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/** Shows 1x1 safe holes around you: green when walled by bedrock, yellow for obsidian (can be mined). */
public class HoleESP extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 8, 3, 16, 1));
    private final NumberSetting height = add(new NumberSetting("Height", 0.15, 0, 1, 0.05));
    private final List<BlockPos> bedrock = new ArrayList<>(), obsidian = new ArrayList<>();
    private int refresh;

    public HoleESP() {
        super("HoleESP", "Highlights safe holes near you.", Category.VISUALS);
    }

    @Override
    public void onTick() {
        if (refresh-- > 0) return;
        refresh = 5;
        bedrock.clear();
        obsidian.clear();
        int r = range.getInt();
        for (BlockPos p : HoleUtil.near(mc.player.getBlockPos(), r, Math.min(r, 6)))
            (HoleUtil.type(p) == HoleUtil.Type.BEDROCK ? bedrock : obsidian).add(p);
    }

    @Override
    public void onRender(RenderContext ctx) {
        for (BlockPos p : bedrock) draw(ctx, p, 0xFF3DDC84);
        for (BlockPos p : obsidian) draw(ctx, p, 0xFFFFB020);
    }

    private void draw(RenderContext ctx, BlockPos p, int c) {
        Box b = new Box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + Math.max(0.01, height.get()), p.getZ() + 1);
        ctx.boxFilled(b, (c & 0xFFFFFF) | 0x40000000);
        ctx.boxOutline(b, c, 1.5f);
    }

    public int count() { return bedrock.size() + obsidian.size(); }

    @Override
    public String getSuffix() { return String.valueOf(count()); }
}
