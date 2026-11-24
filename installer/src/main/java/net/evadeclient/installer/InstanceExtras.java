package net.evadeclient.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * An instance's advanced settings, kept beside the mod's files in {@code <instance>/.evade/launcher.json}: its own
 * Java (over the launcher-wide one), extra Java arguments, the game window size, an icon and notes. They reach the
 * game through the instance's Minecraft Launcher profile ({@code javaDir}, {@code javaArgs}, {@code resolution},
 * {@code icon}), patched in right after the launcher writes the profile. What was written last is remembered so a
 * cleared setting is taken out again, and the player's own edits to the profile are otherwise left alone.
 */
final class InstanceExtras {
    static final String FILE = "launcher.json";

    /** Icon glyphs (drawn by LauncherView.icon) and tile colours for the icon picker. */
    static final String[] GLYPHS = {"cube", "layers", "globe", "users", "book", "clip", "home", "gear"};
    static final int[] COLORS = {0xE11D2E, 0xF5A524, 0x3DDC84, 0x22B8CF, 0x3D8BFF, 0x8B5CF6, 0xEC4899, 0x6B6B76};
    /** Window sizes offered (0×0: the game's default). */
    static final int[][] SIZES = {{0, 0}, {1280, 720}, {1600, 900}, {1920, 1080}};

    String javaDir = "";
    String javaArgs = "";
    int width, height;
    /** Icon: -1 the default tile, else an index into GLYPHS, with a colour from COLORS. */
    int glyph = -1, color;
    String notes = "";
    // bookkeeping
    String javaDirWritten, argsWritten;
    boolean sizeWritten, iconWritten;
    /** The heartbeat start time of the last session the launcher has dealt with, and the newest report it showed. */
    long handledSession, handledReport;

    static Path file(Path instance) { return GameState.dir(instance).resolve(FILE); }

    static InstanceExtras load(Path instance) {
        InstanceExtras x = new InstanceExtras();
        JsonObject o = InstallerCore.readJsonObject(file(instance));
        x.javaDir = s(o, "javaDir");
        x.javaArgs = s(o, "javaArgs");
        x.width = (int) n(o, "width");
        x.height = (int) n(o, "height");
        x.glyph = o.has("glyph") ? (int) n(o, "glyph") : -1;
        if (x.glyph >= GLYPHS.length) x.glyph = -1;
        x.color = Math.floorMod((int) n(o, "color"), COLORS.length);
        x.notes = s(o, "notes");
        x.javaDirWritten = InstallerCore.str(o, "javaDirWritten");
        x.argsWritten = InstallerCore.str(o, "argsWritten");
        x.sizeWritten = o.has("sizeWritten") && o.get("sizeWritten").getAsBoolean();
        x.iconWritten = o.has("iconWritten") && o.get("iconWritten").getAsBoolean();
        x.handledSession = n(o, "handledSession");
        x.handledReport = n(o, "handledReport");
        return x;
    }

    void save(Path instance) {
        JsonObject o = new JsonObject();
        o.addProperty("javaDir", javaDir);
        o.addProperty("javaArgs", javaArgs);
        o.addProperty("width", width);
        o.addProperty("height", height);
        o.addProperty("glyph", glyph);
        o.addProperty("color", color);
        o.addProperty("notes", notes);
        if (javaDirWritten != null) o.addProperty("javaDirWritten", javaDirWritten);
        if (argsWritten != null) o.addProperty("argsWritten", argsWritten);
        o.addProperty("sizeWritten", sizeWritten);
        o.addProperty("iconWritten", iconWritten);
        o.addProperty("handledSession", handledSession);
        o.addProperty("handledReport", handledReport);
        try {
            Files.createDirectories(GameState.dir(instance));
            InstallerCore.writeAtomic(file(instance), InstallerCore.GSON.toJson(o));
        } catch (IOException e) {
            System.err.println("Couldn't save instance settings: " + e.getMessage());
        }
    }

    private static String s(JsonObject o, String k) {
        String v = InstallerCore.str(o, k);
        return v == null ? "" : v;
    }

    private static long n(JsonObject o, String k) {
        try { return o.has(k) ? o.get(k).getAsLong() : 0; } catch (RuntimeException e) { return 0; }
    }

    // ------------------------------------------------------------------ java arguments

    /**
     * The extra Java arguments as they'll be written: memory flags are left out (the memory setting owns them), and
     * anything that isn't an option starting with "-" is dropped. {@code notes} collects what was changed and why.
     */
    static String cleanArgs(String raw, List<String> notes) {
        List<String> keep = new ArrayList<>();
        for (String a : raw == null ? new String[0] : raw.strip().split("\\s+")) {
            if (a.isEmpty()) continue;
            String l = a.toLowerCase(Locale.ROOT);
            if (l.startsWith("-xmx") || l.startsWith("-xms")) { notes.add(a + " left out: set memory with the chips above"); continue; }
            if (!a.startsWith("-")) { notes.add(a + " left out: Java options start with -"); continue; }
            if (l.equals("-xx:+usezgc") || l.equals("-xx:+useshenandoahgc") || l.equals("-xx:+useparallelgc") || l.equals("-xx:+useserialgc")) {
                notes.add(a + " clashes with Evade's G1 settings: Java won't start with two collectors");
                continue;
            }
            if (keep.contains(a)) continue;
            keep.add(a);
        }
        return String.join(" ", keep);
    }

    // ------------------------------------------------------------------ the profile

    /**
     * Puts these settings into the instance's profile (both profile lists, where they exist). {@code globalJava} is
     * the launcher-wide Java override: an instance's own Java wins over it, and clearing the instance's one hands
     * back to it.
     */
    void applyToProfile(Path mc, String key, String globalJava, Path instance) throws IOException {
        for (Path f : List.of(mc.resolve("launcher_profiles.json"), mc.resolve("launcher_profiles_microsoft_store.json"))) {
            if (!Files.exists(f)) continue;
            JsonObject root;
            try {
                JsonElement e = JsonParser.parseString(Files.readString(f));
                if (!e.isJsonObject()) continue;
                root = e.getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            if (!root.has("profiles") || !root.get("profiles").isJsonObject()) continue;
            JsonElement pe = root.getAsJsonObject("profiles").get(key);
            if (pe == null || !pe.isJsonObject()) continue;
            patch(pe.getAsJsonObject(), globalJava);
            InstallerCore.writeAtomic(f, InstallerCore.GSON.toJson(root));
        }
        save(instance);
    }

    /** The profile changes themselves (no files), so they can be tested. */
    void patch(JsonObject prof, String globalJava) throws IOException {
        // Java: the instance's own, else the launcher-wide one, else whatever the profile had before Evade's.
        String want = javaDir.strip();
        if (!want.isEmpty()) {
            prof.addProperty("javaDir", want);
            javaDirWritten = want;
        } else if (javaDirWritten != null) {
            String g = globalJava == null ? "" : globalJava.strip();
            if (g.isEmpty()) prof.remove("javaDir");
            else prof.addProperty("javaDir", g);
            javaDirWritten = null;
        }
        // extra arguments: take out what we added last time, add the current ones at the end
        String args = InstallerCore.str(prof, "javaArgs");
        if (args == null) args = "";
        if (argsWritten != null && !argsWritten.isBlank()) {
            int at = args.lastIndexOf(argsWritten);
            if (at >= 0) args = (args.substring(0, at) + args.substring(at + argsWritten.length())).replaceAll("\\s{2,}", " ").strip();
        }
        String extra = cleanArgs(javaArgs, new ArrayList<>());
        if (!extra.isEmpty()) args = (args + " " + extra).strip();
        prof.addProperty("javaArgs", args);
        argsWritten = extra.isEmpty() ? null : extra;
        // window size
        if (width > 0 && height > 0) {
            JsonObject r = new JsonObject();
            r.addProperty("width", width);
            r.addProperty("height", height);
            prof.add("resolution", r);
            sizeWritten = true;
        } else if (sizeWritten) {
            prof.remove("resolution");
            sizeWritten = false;
        }
        // icon
        if (glyph >= 0) {
            prof.addProperty("icon", iconDataUri(glyph, color));
            iconWritten = true;
        } else if (iconWritten) {
            iconWritten = false; // the launcher's next profile write puts Evade's icon back
        }
    }

    /** The picked icon as a 128 px PNG data URI (the Minecraft Launcher shows it next to the profile). */
    static String iconDataUri(int glyph, int color) throws IOException {
        BufferedImage img = renderIcon(glyph, color, 128);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static BufferedImage renderIcon(int glyph, int color, int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        paintIcon(g, glyph, color, 0, 0, s);
        g.dispose();
        return img;
    }

    /** A rounded tile in the picked colour (darkened to the launcher's range) with the glyph in white. */
    static void paintIcon(Graphics2D g, int glyph, int color, int x, int y, int s) {
        Color c = new Color(COLORS[Math.floorMod(color, COLORS.length)]);
        g.setPaint(new java.awt.GradientPaint(x, y, c, x + s, y + s, c.darker().darker()));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, s * 0.6, s * 0.6));
        g.setColor(new Color(255, 255, 255, 34));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, s - 1, s - 1, s * 0.6, s * 0.6));
        LauncherView.icon(g, GLYPHS[Math.floorMod(glyph, GLYPHS.length)], x + s / 2, y + s / 2, Math.round(s * 0.5f), Color.WHITE);
    }
}
