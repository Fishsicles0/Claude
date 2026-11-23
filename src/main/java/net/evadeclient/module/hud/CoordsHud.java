package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.util.Util;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your position, facing and biome. Respects the server's "reduced debug info" rule: where the server hides
 * coordinates from F3, this hides them too.
 */
public class CoordsHud extends TextHudModule {
    private final BooleanSetting direction = add(new BooleanSetting("Direction", true));
    private final BooleanSetting biome = add(new BooleanSetting("Biome", true));
    private final BooleanSetting nether = add(new BooleanSetting("Nether Coords", false)
            .describe("The matching Nether position in the Overworld, and the Overworld position in the Nether."));
    private final NumberSetting decimals = add(new NumberSetting("Decimals", 1, 0, 3, 1));
    private final BooleanSetting rotation = add(new BooleanSetting("Rotation", false)
            .describe("Your yaw and pitch in degrees."));
    private final BooleanSetting chunk = add(new BooleanSetting("Chunk", false)
            .describe("The chunk you're in, and your block within it."));

    public CoordsHud() {
        super("Coordinates", "Your position, facing and biome.", 4, 36);
        setEnabled(true);
    }

    @Override
    protected List<String> lines(boolean preview) {
        var p = mc.player;
        if (p == null || mc.world == null) return new ArrayList<>();
        // The server's flag, not the player's own Reduced Debug Info option: only a server can hide coordinates.
        if (p.hasReducedDebugInfo()) {
            if (!preview) return new ArrayList<>(List.of("Coords hidden by server"));
            return new ArrayList<>(List.of(format(123.5, 64, -45.25), "North (-Z)"));
        }
        List<String> out = new ArrayList<>();
        out.add(format(p.getX(), p.getY(), p.getZ()));
        if (nether.get()) {
            var key = mc.world.getRegistryKey();
            if (key == World.OVERWORLD || key == World.NETHER) {
                boolean inNether = key == World.NETHER;
                double f = inNether ? 8 : 1 / 8.0;
                out.add(String.format(Locale.ROOT, "%s %.0f, %.0f", inNether ? "Overworld" : "Nether", p.getX() * f, p.getZ() * f));
            }
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
        if (rotation.get()) {
            out.add(String.format(Locale.ROOT, "Yaw %.1f  Pitch %.1f", MathHelper.wrapDegrees(p.getYaw()), p.getPitch()));
        }
        if (chunk.get()) {
            var pos = p.getBlockPos();
            out.add(String.format(Locale.ROOT, "Chunk %d, %d (%d, %d)", pos.getX() >> 4, pos.getZ() >> 4, pos.getX() & 15, pos.getZ() & 15));
        }
        if (biome.get()) {
            mc.world.getBiome(p.getBlockPos()).getKey().ifPresent(k -> {
                String tk = Util.createTranslationKey("biome", k.getValue());
                out.add(I18n.hasTranslation(tk) ? I18n.translate(tk) : pretty(k.getValue().getPath()));
            });
        }
        return out;
    }

    private String format(double x, double y, double z) {
        String n = "%." + decimals.getInt() + "f";
        return String.format(Locale.ROOT, "X " + n + "  Y " + n + "  Z " + n, x, y, z);
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String pretty(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("_")) sb.append(cap(part)).append(' ');
        return sb.toString().trim();
    }
}
