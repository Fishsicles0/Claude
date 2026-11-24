package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.visuals.Waypoints;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.Alignment;
import net.minecraft.client.font.DrawnTextConsumer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates in chat ("120 64 -300", "X 120, Y 64, Z -300", "x: 120 y: 64 z: -300") become clickable while chat is
 * open: hovering underlines them and a click saves a waypoint there. This is Evade's own click handling on the chat
 * screen: messages are never changed, and nothing is sent.
 */
public final class ChatCoordinates {
    private ChatCoordinates() {}

    /** Three whole numbers, optionally labelled X/Y/Z, separated by spaces and/or commas; Y in a sane range. */
    private static final Pattern COORDS = Pattern.compile(
            "(?i)(?<![\\w.\\-])(?:x\\s*[:=]?\\s*)?(-?\\d{1,8})(?:\\s*,\\s*|\\s+)(?:y\\s*[:=]?\\s*)?(-?\\d{1,4})(?:\\s*,\\s*|\\s+)(?:z\\s*[:=]?\\s*)?(-?\\d{1,8})(?![\\w.])");
    /** "[Overworld] Name: X ..." as Evade's own coordinate shares write it. */
    private static final Pattern LABEL = Pattern.compile("\\[(Overworld|Nether|End)\\]\\s*([^:\\[\\]]{1,32}):\\s*$");

    /** Coordinates under the mouse: the line's pose and left edge, the match's x range on the line and the values. */
    public record Hit(Matrix3x2fc pose, int lineX, int lineY, float x1, float x2, int x, int y, int z, String name, String dim) {}

    /** What is under ({@code mx}, {@code my}) in the open chat, or null. */
    public static Hit at(MinecraftClient mc, TextRenderer tr, double mx, double my) {
        if (mc.inGameHud == null || mc.world == null) return null;
        Probe probe = new Probe(tr, (float) mx, (float) my);
        try {
            mc.inGameHud.getChatHud().render(probe, mc.getWindow().getScaledHeight(), mc.inGameHud.getTicks(), true);
        } catch (RuntimeException e) {
            return null;
        }
        if (probe.text == null) return null;
        // The line as plain text, with where each character starts.
        StringBuilder s = new StringBuilder();
        List<Float> xs = new ArrayList<>();
        List<Style> styles = new ArrayList<>();
        float[] cur = {0};
        probe.text.accept((index, style, cp) -> {
            String ch = new String(Character.toChars(cp));
            for (int i = 0; i < ch.length(); i++) { xs.add(cur[0]); styles.add(style); }
            s.append(ch);
            cur[0] += tr.getTextHandler().getWidth(StringVisitable.styled(ch, style));
            return true;
        });
        xs.add(cur[0]);
        String line = s.toString();
        Matcher m = COORDS.matcher(line);
        while (m.find()) {
            float x1 = xs.get(m.start()), x2 = xs.get(m.end());
            if (probe.localX < x1 || probe.localX >= x2) continue;
            // A link or command the message already has keeps its own click.
            if (styles.get(m.start()).getClickEvent() != null) return null;
            int y;
            try { y = Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { return null; }
            if (y < -2048 || y > 4096) return null;
            int x = Integer.parseInt(m.group(1)), z = Integer.parseInt(m.group(3));
            if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return null;
            String name = null, dim = null;
            Matcher l = LABEL.matcher(line.substring(0, m.start()));
            if (l.find()) {
                name = l.group(2).strip();
                dim = switch (l.group(1).toLowerCase(Locale.ROOT)) {
                    case "nether" -> "minecraft:the_nether";
                    case "end" -> "minecraft:the_end";
                    default -> "minecraft:overworld";
                };
            }
            return new Hit(new Matrix3x2f(probe.pose), probe.lineX, probe.lineY, x1, x2, x, y, z, name, dim);
        }
        return null;
    }

    /** Underline the coordinates under the mouse and say what a click does. */
    public static void renderHover(DrawContext ctx, MinecraftClient mc, TextRenderer tr, int mx, int my) {
        Hit h = at(mc, tr, mx, my);
        if (h == null) return;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.mul(h.pose());
            int x1 = Math.round(h.lineX() + h.x1()), x2 = Math.round(h.lineX() + h.x2());
            ctx.fill(x1, h.lineY() + 8, x2, h.lineY() + 9, Theme.RED_BRIGHT);
        } finally {
            m.popMatrix();
        }
        ctx.drawTooltip(tr, List.of(Text.literal("§fSave as a waypoint"),
                Text.literal("§7" + h.x() + ", " + h.y() + ", " + h.z() + (h.name() != null ? " §8(" + h.name() + ")" : ""))), mx, my);
    }

    /** Save the hit as a waypoint (named after its label when it has one). Returns whether it was handled. */
    public static boolean save(Hit h) {
        Waypoints wp = EvadeClient.modules == null ? null : EvadeClient.modules.get(Waypoints.class);
        if (wp == null || MinecraftClient.getInstance().world == null) return false;
        String base = h.name() != null && !h.name().isBlank() ? h.name() : "Chat";
        String name = wp.freeName(base);
        var p = wp.addAt(name, h.dim(), new BlockPos(h.x(), h.y(), h.z()));
        if (p == null) return false;
        Notifications.push("Waypoint", "Saved " + p.name() + " from chat", true);
        CommandManager.print("Saved waypoint §d" + p.name() + "§7. Edit it with §d.wp gui");
        return true;
    }

    /** Finds which chat line the mouse is on, the way the game's own click handler walks the lines. */
    private static final class Probe implements DrawnTextConsumer {
        private final TextRenderer tr;
        private final float mx, my;
        private Transformation transformation = new Transformation(new Matrix3x2f());
        OrderedText text;
        Matrix3x2fc pose;
        int lineX, lineY;
        float localX;

        Probe(TextRenderer tr, float mx, float my) {
            this.tr = tr;
            this.mx = mx;
            this.my = my;
        }

        @Override public Transformation getTransformation() { return transformation; }
        @Override public void setTransformation(Transformation t) { this.transformation = t; }

        @Override
        public void text(Alignment alignment, int x, int y, Transformation t, OrderedText text) {
            if (t.opacity() <= 0.01f) return;
            int ax = alignment.getAdjustedX(x, tr, text);
            Matrix3x2f inv = new Matrix3x2f(t.pose());
            if (Math.abs(inv.determinant()) < 1e-9f) return;
            inv.invert();
            Vector2f local = inv.transformPosition(new Vector2f(mx, my));
            if (local.y < y || local.y >= y + tr.fontHeight) return;
            int w = tr.getWidth(text);
            if (local.x < ax || local.x >= ax + w) return;
            if (t.scissor() != null && !t.scissor().contains((int) mx, (int) my)) return;
            this.text = text;
            this.pose = new Matrix3x2f(t.pose());
            this.lineX = ax;
            this.lineY = y;
            this.localX = local.x - ax;
        }

        @Override
        public void marqueedText(Text text, int x, int left, int right, int top, int bottom, Transformation t) {}
    }
}
