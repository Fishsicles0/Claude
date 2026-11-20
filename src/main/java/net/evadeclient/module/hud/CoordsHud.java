package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

public class CoordsHud extends TextHudModule {
    private final BooleanSetting direction = add(new BooleanSetting("Direction", true));
    private final BooleanSetting biome = add(new BooleanSetting("Biome", true));
    private final BooleanSetting nether = add(new BooleanSetting("Nether Coords", false));

    public CoordsHud() {
        super("Coordinates", "Your position, facing and biome.", 4, 168);
        setEnabled(true);
    }

    @Override
    protected List<String> lines(boolean preview) {
        var p = mc.player;
        List<String> out = new ArrayList<>();
        out.add(String.format("X %.1f  Y %.1f  Z %.1f", p.getX(), p.getY(), p.getZ()));
        if (nether.get()) {
            boolean inNether = mc.world.getRegistryKey() == net.minecraft.world.World.NETHER;
            double f = inNether ? 8 : 1 / 8.0;
            out.add(String.format("%s %.0f, %.0f", inNether ? "Overworld" : "Nether", p.getX() * f, p.getZ() * f));
        }
        if (direction.get()) {
            Direction d = p.getHorizontalFacing();
            String axis = switch (d) {
                case NORTH -> "-Z";
                case SOUTH -> "+Z";
                case EAST -> "+X";
                default -> "-X";
            };
            out.add(cap(d.asString()) + " (" + axis + ")");
        }
        if (biome.get()) {
            mc.world.getBiome(p.getBlockPos()).getKey().ifPresent(k -> out.add(pretty(k.getValue().getPath())));
        }
        return out;
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String pretty(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("_")) sb.append(cap(part)).append(' ');
        return sb.toString().trim();
    }
}
