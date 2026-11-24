package net.evadeclient.module.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your position, facing and biome, plus optional chunk, region and targeted-block lines, and keys (or
 * {@code .coords}) to copy your position or type it into chat. Respects the server's "reduced debug info" rule:
 * where the server hides coordinates from F3, this hides them too, and won't copy or share them either.
 */
public class CoordsHud extends TextHudModule {
    /** The eight directions in yaw order (yaw 0 faces south, 90 west), with the axes that grow that way. */
    private static final String[] DIRS = {"South", "South-West", "West", "North-West", "North", "North-East", "East", "South-East"};
    private static final String[] DIRS_SHORT = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final String[] DIR_AXES = {"+Z", "-X +Z", "-X", "-X -Z", "-Z", "+X -Z", "+X", "+X +Z"};
    /** Axis Colors: the debug axes' red, green and blue (RGB, the line's own alpha is kept). */
    private static final int AXIS_X = 0xFF4D5E, AXIS_Y = 0x3BE37A, AXIS_Z = 0x3D7BFF;
    private static final String LABELS_COLON = "X: 12", LABELS_NONE = "None";

    private final ModeSetting layout = add(new ModeSetting("Layout", "Horizontal", "Horizontal", "Vertical")
            .describe("Your position on one line, or X, Y and Z on a line each."));
    private final NumberSetting decimals = add(new NumberSetting("Decimals", 1, 0, 3, 1)
            .describe("Digits after the point. At 0 it shows the block you're standing in."));
    private final ModeSetting axisLabels = add(new ModeSetting("Axis Labels", "X 12", "X 12", LABELS_COLON, LABELS_NONE)
            .describe("How the X, Y and Z labels are written, or None for just the numbers."));
    private final ColorSetting labelColor = add(new ColorSetting("Label Color", 0xFFB8B8C0)
            .describe("Colour of the X, Y and Z labels. The numbers use Text Color."));
    private final BooleanSetting axisColors = add(new BooleanSetting("Axis Colors", false)
            .describe("Red X, green Y and blue Z labels instead of Label Color."));
    private final ColorSetting extraLineColor = add(new ColorSetting("Extra Line Color", 0xFFFFFFFF)
            .describe("Colour of the lines under your position: direction, biome, chunk and so on."));
    private final BooleanSetting direction = add(new BooleanSetting("Direction", true));
    private final ModeSetting directionFormat = add(new ModeSetting("Direction Format", "North", "North", "N", "North-East", "NE")
            .describe("Four or eight directions, written out or short."));
    private final BooleanSetting showAxis = add(new BooleanSetting("Show Axis", true)
            .describe("Which way your coordinates grow as you walk forward, e.g. North (-Z)."));
    private final BooleanSetting showAngles = add(new BooleanSetting("Show Angles", false)
            .describe("Your yaw and pitch in degrees."));
    private final BooleanSetting biome = add(new BooleanSetting("Biome", true));
    private final BooleanSetting nether = add(new BooleanSetting("Nether Coords", false)
            .describe("The matching Nether position in the Overworld, and the Overworld position in the Nether."));
    private final ModeSetting chunkInfo = add(new ModeSetting("Chunk Info", "Off", "Off", "Chunk", "Chunk + Block", "Region")
            .describe("The chunk you're in, your block within it, or the region file it's saved in."));
    private final BooleanSetting targetedBlock = add(new BooleanSetting("Targeted Block", false)
            .describe("The position of the block under your crosshair, as F3 shows it."));
    private final KeySetting copyKey = add(new KeySetting("Copy Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Copies your block position to the clipboard."));
    private final KeySetting shareKey = add(new KeySetting("Share Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Opens chat with your position typed in. Nothing is sent until you press Enter."));
    private final ModeSetting copyFormat = add(new ModeSetting("Copy Format", "12 64 -3", "12 64 -3", "12, 64, -3", "X 12 Y 64 Z -3")
            .describe("How Copy Key and .coords copy write your position."));

    private boolean copyWasDown, shareWasDown;

    public CoordsHud() {
        super("Coordinates", "Your position, facing and biome.", 4, 36);
        setEnabled(true);
    }

    // ---- lines -------------------------------------------------------------------------------------------

    /** Plain text of {@link #styledLines} (what tests and anything colour-blind read). */
    @Override
    protected List<String> lines(boolean preview) {
        List<Text> styled = build(preview);
        List<String> out = new ArrayList<>(styled.size());
        for (Text t : styled) out.add(t.getString());
        return out;
    }

    @Override
    protected List<Text> styledLines(boolean preview) { return build(preview); }

    private List<Text> build(boolean preview) {
        var p = mc.player;
        if (p == null || mc.world == null) return preview ? sample() : new ArrayList<>();
        // The server's flag, not the player's own Reduced Debug Info option: only a server can hide coordinates.
        if (p.hasReducedDebugInfo()) {
            if (preview) return sample();
            List<Text> out = new ArrayList<>(1);
            out.add(extra("Coords hidden by server"));
            return out;
        }
        if (net.evadeclient.module.misc.StreamerMode.hidesCoordinates()) {
            if (preview) return sample();
            List<Text> out = new ArrayList<>(1);
            out.add(extra("Coords hidden (Streamer Mode)"));
            return out;
        }
        List<Text> out = new ArrayList<>(8);
        position(out, p.getX(), p.getY(), p.getZ());
        if (nether.get()) {
            var key = mc.world.getRegistryKey();
            if (key == World.OVERWORLD || key == World.NETHER) {
                boolean inNether = key == World.NETHER;
                double f = inNether ? 8 : 1 / 8.0;
                out.add(extra((inNether ? "Overworld " : "Nether ") + MathHelper.floor(p.getX() * f) + ", " + MathHelper.floor(p.getZ() * f)));
            }
        }
        String dir = directionLine(p.getYaw(), p.getPitch(), p.getHorizontalFacing());
        if (dir != null) out.add(extra(dir));
        BlockPos pos = p.getBlockPos();
        String chunk = chunkLine(pos);
        if (chunk != null) out.add(extra(chunk));
        if (targetedBlock.get() && mc.crosshairTarget instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
            BlockPos t = bh.getBlockPos();
            out.add(extra("Looking at " + t.getX() + ", " + t.getY() + ", " + t.getZ()));
        }
        if (biome.get()) mc.world.getBiome(pos).getKey().ifPresent(k -> out.add(extra(biomeName(k.getValue()))));
        return out;
    }

    /** What the HUD editor shows where there's no position to show. */
    private List<Text> sample() {
        List<Text> out = new ArrayList<>(4);
        position(out, 123.5, 64, -45.25);
        String dir = directionLine(180f, 0f, Direction.NORTH);
        if (dir != null) out.add(extra(dir));
        return out;
    }

    /** The X, Y and Z parts on one line (Horizontal) or a line each (Vertical). */
    private void position(List<Text> out, double x, double y, double z) {
        MutableText[] parts = {axis("X", x, AXIS_X), axis("Y", y, AXIS_Y), axis("Z", z, AXIS_Z)};
        if (layout.is("Vertical")) {
            for (MutableText part : parts) out.add(part);
            return;
        }
        String sep = axisLabels.is(LABELS_NONE) ? ", " : "  ";
        MutableText line = Text.empty();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) line.append(sep);
            line.append(parts[i]);
        }
        out.add(line);
    }

    /** One coordinate: its label in Label Color (or its axis colour) and the number in Text Color. */
    private MutableText axis(String name, double v, int axisColor) {
        MutableText t = Text.empty();
        if (!axisLabels.is(LABELS_NONE)) {
            String label = axisLabels.is(LABELS_COLON) ? name + ": " : name + " ";
            t.append(Text.literal(label).withColor(axisColors.get() ? axisColor : labelColor.get() & 0xFFFFFF));
        }
        // No colour of its own, so it takes Text Color (and Chroma) from the line.
        return t.append(Text.literal(fmt(v)));
    }

    private String fmt(double v) {
        int d = decimals.getInt();
        return d <= 0 ? String.valueOf(MathHelper.floor(v)) : String.format(Locale.ROOT, "%." + d + "f", v);
    }

    private MutableText extra(String s) { return Text.literal(s).withColor(extraLineColor.get() & 0xFFFFFF); }

    /** "North (-Z)", "NE (+X -Z)  -135.0 / 12.5" and so on; null when neither Direction nor Show Angles is on. */
    private String directionLine(float yaw, float pitch, Direction facing) {
        boolean dir = direction.get();
        String angles = showAngles.get()
                ? String.format(Locale.ROOT, "%.1f / %.1f", MathHelper.wrapDegrees(yaw), MathHelper.wrapDegrees(pitch))
                : null;
        if (!dir) return angles;
        int idx;
        String f = directionFormat.get();
        if (f.equals("North") || f.equals("N")) {
            idx = switch (facing) {
                case SOUTH -> 0;
                case WEST -> 2;
                case NORTH -> 4;
                default -> 6;
            };
        } else {
            idx = Math.floorMod(Math.round(MathHelper.wrapDegrees(yaw) / 45f), 8);
        }
        boolean shortName = f.equals("N") || f.equals("NE");
        StringBuilder sb = new StringBuilder(shortName ? DIRS_SHORT[idx] : DIRS[idx]);
        // A diagonal's axes are the signs of the look vector (-sin yaw, cos yaw), fixed within each 45-degree slice.
        if (showAxis.get()) sb.append(" (").append(DIR_AXES[idx]).append(')');
        if (angles != null) sb.append("  ").append(angles);
        return sb.toString();
    }

    private String chunkLine(BlockPos pos) {
        ChunkPos cp = new ChunkPos(pos);
        return switch (chunkInfo.get()) {
            case "Chunk" -> "Chunk " + cp.x + ", " + cp.z;
            case "Chunk + Block" -> "Chunk " + cp.x + ", " + cp.z + " [" + (pos.getX() & 15) + ", " + (pos.getZ() & 15) + "]";
            case "Region" -> "r." + cp.getRegionX() + "." + cp.getRegionZ() + " [" + cp.getRegionRelativeX() + ", " + cp.getRegionRelativeZ() + "]";
            default -> null;
        };
    }

    /** The biome's name in your language, or its id tidied up ("cherry_grove" to "Cherry Grove") for modded ones. */
    private static String biomeName(Identifier id) {
        String key = Util.createTranslationKey("biome", id);
        Language lang = Language.getInstance();
        return lang.hasTranslation(key) ? lang.get(key) : pretty(id.getPath());
    }

    // ---- copy and share ----------------------------------------------------------------------------------

    @Override
    protected void onEnable() {
        // A key held while the HUD turns on doesn't count as a press.
        copyWasDown = copyKey.isDown();
        shareWasDown = shareKey.isDown();
    }

    @Override
    public void onTick() {
        // KeySetting.isDown is false while any screen is open, so typing in chat never copies or shares.
        boolean copy = copyKey.isDown(), share = shareKey.isDown();
        if (copy && !copyWasDown) {
            String problem = unavailable();
            if (problem != null) Notifications.push("Coordinates", problem, false);
            else copy(mc.player.getBlockPos(), false);
        }
        if (share && !shareWasDown) {
            String problem = unavailable();
            if (problem != null) Notifications.push("Coordinates", problem, false);
            else mc.setScreen(new ChatScreen(shareText(mc.player.getBlockPos()), false));
        }
        copyWasDown = copy;
        shareWasDown = share;
    }

    /** Why there's no position to copy or share right now, or null when there is. */
    private static String unavailable() {
        if (mc.player == null || mc.world == null) return "Join a world first";
        if (mc.player.hasReducedDebugInfo()) return "This server hides coordinates";
        if (net.evadeclient.module.misc.StreamerMode.hidesCoordinates()) return "Streamer Mode is hiding your coordinates";
        return null;
    }

    private void copy(BlockPos pos, boolean fromChat) {
        String text = switch (copyFormat.get()) {
            case "12, 64, -3" -> pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
            case "X 12 Y 64 Z -3" -> "X " + pos.getX() + " Y " + pos.getY() + " Z " + pos.getZ();
            default -> pos.getX() + " " + pos.getY() + " " + pos.getZ();
        };
        mc.keyboard.setClipboard(text);
        if (fromChat) CommandManager.print("§7Copied §f" + text);
        else Notifications.push("Coordinates", "Copied " + text, true);
    }

    /** "[Overworld] X 12, Y 64, Z -3": typed into chat for you to send (or not). */
    private static String shareText(BlockPos pos) {
        return "[" + dimensionName() + "] X " + pos.getX() + ", Y " + pos.getY() + ", Z " + pos.getZ();
    }

    private static String dimensionName() {
        var key = mc.world.getRegistryKey();
        if (key == World.OVERWORLD) return "Overworld";
        if (key == World.NETHER) return "Nether";
        if (key == World.END) return "End";
        return pretty(key.getValue().getPath());
    }

    @Override public List<String> commandAliases() { return List.of("coords"); }

    @Override
    public List<String> commandHelp() { return List.of("§d.coords§7 [copy|share] - copy or share your coordinates"); }

    @Override
    public void command(String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        if (!sub.isEmpty() && !sub.equals("copy") && !sub.equals("share")) {
            throw new CommandManager.CommandError("Usage: .coords [copy|share]");
        }
        String problem = unavailable();
        if (problem != null) throw new CommandManager.CommandError(problem + ".");
        BlockPos pos = mc.player.getBlockPos();
        switch (sub) {
            case "copy" -> copy(pos, true);
            // After the chat screen this was typed in has closed, or it would close the new one straight away.
            case "share" -> {
                String text = shareText(pos);
                mc.send(() -> mc.setScreen(new ChatScreen(text, false)));
            }
            default -> CommandManager.print("§7" + shareText(pos) + " §8- §d.coords copy§7 or §d.coords share");
        }
    }

    // ---- config ------------------------------------------------------------------------------------------

    /**
     * Older configs had Chunk (on/off, chunk and block) and Rotation (on/off, its own yaw/pitch line), and drew
     * every line in Text Color. Map them onto Chunk Info, Show Angles and Extra Line Color. The old keys are only
     * in configs saved before this version (saving drops them), and a new setting is only changed while it is
     * still at its default, so imported profile codes migrate the same way.
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (isTrue(settings, "Chunk") && chunkInfo.isDefault()) chunkInfo.set("Chunk + Block");
        if (isTrue(settings, "Rotation") && showAngles.isDefault()) showAngles.set(true);
        boolean legacy = settings.has("Chunk") || settings.has("Rotation") || !settings.has(extraLineColor.getName());
        if (legacy && extraLineColor.isDefault()) extraLineColor.set(color.get());
    }

    private static boolean isTrue(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() && e.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- text helpers ------------------------------------------------------------------------------------

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String pretty(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("[_/]")) if (!part.isEmpty()) sb.append(cap(part)).append(' ');
        return sb.toString().trim();
    }
}
