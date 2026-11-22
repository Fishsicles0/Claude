package net.evadeclient.clips.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edit presets: a look plus what to do at every marker (kills, aces, pops...), applied in one click. Built-in
 * ones, your own (saved from the current edit), files shared as {@code .evadepreset}, and community presets on
 * the share server.
 * <p>
 * A preset never carries times or files, only settings and per-marker actions, so it fits any clip and can't
 * point at anything on your PC.
 */
public final class EditPresets {
    private EditPresets() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String EXT = "evadepreset";

    /** What a preset does at each marker. */
    public static final class AtMarker {
        /** Speed ramp into this speed at the marker (0 = no ramp), easing over these seconds before and after. */
        public double rampSpeed, rampBefore = 1.2, rampAfter = 1.0;
        /** Effects at the marker ("shake", "punch", "flash", "hitmarker", "sound"), with their sounds. */
        public List<FxSpec> effects = new ArrayList<>();
        /** Zoom in on the centre at the marker (1 = none), easing in and back out. */
        public double zoom = 1;
    }

    public static final class FxSpec {
        public String type = "shake", sound = "";
        public double strength = 0.6, volume = 1, offset;
        public FxSpec() {}
        public FxSpec(String type, String sound) { this.type = type; this.sound = sound; }
    }

    public static final class Preset {
        public String name = "", description = "", author = "";
        /** Look: null fields are left as they are. */
        public String effect, aspect, fitMode, smoothing, captionStyle;
        public Double fadeIn, fadeOut, camShare;
        public AtMarker atMarker;
        /** Community id, when it came from the share server. */
        public transient String communityId;
        public transient boolean builtIn;
        public transient int downloads;

        @Override public String toString() { return name; }
    }

    // ------------------------------------------------------------------ built-in

    public static List<Preset> builtIns() {
        List<Preset> out = new ArrayList<>();
        Preset p = make("Sniper ramp", "Slows into every kill and back out, with a hitmarker and a crack.");
        p.smoothing = "blend";
        p.atMarker = new AtMarker();
        p.atMarker.rampSpeed = 0.3;
        p.atMarker.effects.add(new FxSpec("hitmarker", "Sniper shot"));
        out.add(p);

        p = make("Montage punch", "Vibrant colour; every marker hits with a flash, a zoom punch and a boom.");
        p.effect = "vibrant";
        p.atMarker = new AtMarker();
        p.atMarker.effects.add(new FxSpec("flash", ""));
        p.atMarker.effects.add(new FxSpec("punch", "Boom"));
        out.add(p);

        p = make("TikTok facecam", "Vertical with your facecam on top and the gameplay below. Punchy colour.");
        p.aspect = "9:16"; p.fitMode = "split"; p.effect = "vibrant"; p.captionStyle = "pop";
        out.add(p);

        p = make("Clean vertical", "Vertical 9:16 that keeps the whole frame over a blurred background.");
        p.aspect = "9:16"; p.fitMode = "blur"; p.fadeIn = 0.2;
        out.add(p);

        p = make("Meme", "Every marker: screen shake and an air horn.");
        p.atMarker = new AtMarker();
        FxSpec shake = new FxSpec("shake", "Air horn");
        shake.strength = 0.9;
        p.atMarker.effects.add(shake);
        out.add(p);

        p = make("Cinematic", "Film look with letterbox bars, soft fades and the smoothest slow motion.");
        p.effect = "cinematic"; p.fadeIn = 0.6; p.fadeOut = 0.8; p.smoothing = "flow";
        out.add(p);

        p = make("Kill cam zoom", "Glides in on every kill and back out, with a whoosh.");
        p.atMarker = new AtMarker();
        p.atMarker.zoom = 1.6;
        FxSpec w = new FxSpec("sound", "Whoosh");
        w.offset = -0.3;
        p.atMarker.effects.add(w);
        out.add(p);
        for (Preset x : out) x.builtIn = true;
        return out;
    }

    private static Preset make(String name, String description) {
        Preset p = new Preset();
        p.name = name;
        p.description = description;
        p.author = "Evade Clips";
        return p;
    }

    // ------------------------------------------------------------------ applying

    /**
     * Applies a preset to an edit. Per-marker actions go at each of the clip's markers that's in a kept part.
     * Returns how many markers it used.
     */
    public static int apply(Preset p, EditSpec e, Clip clip) {
        e.fill();
        if (p.effect != null) e.effect = p.effect;
        if (p.aspect != null) e.aspect = p.aspect;
        if (p.fitMode != null) e.fitMode = p.fitMode;
        if (p.smoothing != null) e.smoothing = p.smoothing;
        if (p.fadeIn != null) e.fadeIn = clampD(p.fadeIn, 0, 3);
        if (p.fadeOut != null) e.fadeOut = clampD(p.fadeOut, 0, 3);
        if (p.camShare != null) e.camShare = clampD(p.camShare, 0.15, 0.7);
        if (p.captionStyle != null) for (EditSpec.Caption c : e.captions) if (!c.style.equals("subtitle")) c.style = p.captionStyle;
        int used = 0;
        if (p.atMarker != null && clip != null) {
            List<Double> marks = new ArrayList<>(clip.markers);
            marks.sort(Double::compare);
            for (double m : marks) {
                if (e.segmentAt(m) < 0) continue;
                used++;
                AtMarker a = p.atMarker;
                for (FxSpec f : a.effects) {
                    double t = Math.max(0, Math.min(clip.duration, m + f.offset));
                    EditSpec.Effect fx = new EditSpec.Effect(known(f.type), t, f.sound == null ? "" : f.sound);
                    fx.strength = clampD(f.strength, 0, 1);
                    fx.volume = clampD(f.volume, 0, 2);
                    e.effects.add(fx);
                }
                if (a.zoom > 1.01) {
                    double z = clampD(a.zoom, 1, 4);
                    e.zooms.add(new EditSpec.ZoomKey(Math.max(0, m - 0.6), 1, 0.5, 0.5));
                    e.zooms.add(new EditSpec.ZoomKey(m, z, 0.5, 0.5));
                    e.zooms.add(new EditSpec.ZoomKey(Math.min(clip.duration, m + 0.8), z, 0.5, 0.5));
                    e.zooms.add(new EditSpec.ZoomKey(Math.min(clip.duration, m + 1.4), 1, 0.5, 0.5));
                }
                if (a.rampSpeed > 0.05 && a.rampSpeed < 1) e.rampAround(m, clampD(a.rampSpeed, 0.1, 0.95), clampD(a.rampBefore, 0.3, 3), clampD(a.rampAfter, 0.3, 3));
            }
        }
        e.sort();
        return used;
    }

    private static String known(String type) {
        return switch (type == null ? "" : type) { case "shake", "punch", "flash", "hitmarker", "sound" -> type; default -> "sound"; };
    }

    private static double clampD(double v, double lo, double hi) { return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    /** A preset from the current edit's look (and, optionally, what was done at its first marker). */
    public static Preset fromEdit(String name, EditSpec e, Clip clip) {
        Preset p = new Preset();
        p.name = name;
        p.effect = e.effect; p.aspect = e.aspect; p.fitMode = e.fitMode; p.smoothing = e.smoothing;
        p.fadeIn = e.fadeIn; p.fadeOut = e.fadeOut; p.camShare = e.camShare;
        if (!e.captions.isEmpty()) p.captionStyle = e.captions.get(0).style;
        // effects near markers become per-marker actions
        if (clip != null && !clip.markers.isEmpty() && !e.effects.isEmpty()) {
            AtMarker a = new AtMarker();
            double m = clip.markers.get(0);
            for (EditSpec.Effect f : e.effects) {
                if (Math.abs(f.t - m) > 1.5) continue;
                FxSpec s = new FxSpec(f.type, f.sound);
                s.strength = f.strength; s.volume = f.volume; s.offset = f.t - m;
                a.effects.add(s);
            }
            int i = e.segmentAt(m);
            if (i >= 0 && e.segments.get(i).speedAt(m) < 0.95) a.rampSpeed = e.segments.get(i).speedAt(m);
            if (!a.effects.isEmpty() || a.rampSpeed > 0) p.atMarker = a;
        }
        return p;
    }

    // ------------------------------------------------------------------ files

    public static Path dir() { return Settings.appDir().resolve("presets"); }

    public static List<Preset> mine() {
        List<Preset> out = new ArrayList<>();
        try (var s = Files.list(dir())) {
            for (Path f : s.filter(x -> x.toString().endsWith("." + EXT)).sorted().toList()) {
                try { out.add(read(f)); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return out;
    }

    public static Path save(Preset p) throws IOException {
        Files.createDirectories(dir());
        Path f = dir().resolve(Library.safe(p.name.isBlank() ? "Preset" : p.name) + "." + EXT);
        write(p, f);
        return f;
    }

    public static void delete(Preset p) throws IOException {
        Files.deleteIfExists(dir().resolve(Library.safe(p.name) + "." + EXT));
    }

    public static void write(Preset p, Path f) throws IOException {
        JsonObject o = GSON.toJsonTree(p).getAsJsonObject();
        o.addProperty("format", "evade-clips-preset");
        o.addProperty("version", 1);
        Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
    }

    public static Preset read(Path f) throws IOException {
        if (Files.size(f) > 64 * 1024) throw new IOException("That preset file is too big to be a preset.");
        return parse(Files.readString(f, StandardCharsets.UTF_8));
    }

    /** Reads a preset from JSON, keeping only what presets can contain. */
    public static Preset parse(String json) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("preset") && o.get("preset").isJsonObject()) o = o.getAsJsonObject("preset"); // community download wrapper
            Preset p = GSON.fromJson(o, Preset.class);
            if (p == null) throw new IOException("Empty preset");
            p.name = trim(p.name, 60, "Preset");
            p.description = trim(p.description, 300, "");
            p.author = trim(p.author, 40, "");
            if (p.aspect != null && !List.of("original", "16:9", "9:16", "1:1", "4:5").contains(p.aspect)) p.aspect = null;
            if (p.fitMode != null && !List.of("crop", "blur", "split").contains(p.fitMode)) p.fitMode = null;
            if (p.smoothing != null && !List.of("off", "blend", "flow").contains(p.smoothing)) p.smoothing = null;
            if (p.effect != null && Exporter.effectFilter(p.effect).isEmpty() && !p.effect.equals("none")) p.effect = null;
            if (p.captionStyle != null && !List.of("clean", "subtitle", "meme", "pop", "highlight").contains(p.captionStyle)) p.captionStyle = null;
            if (p.atMarker != null) {
                if (p.atMarker.effects == null) p.atMarker.effects = new ArrayList<>();
                if (p.atMarker.effects.size() > 8) p.atMarker.effects = new ArrayList<>(p.atMarker.effects.subList(0, 8));
                for (FxSpec f : p.atMarker.effects) {
                    f.type = known(f.type);
                    f.sound = trim(f.sound, 60, "");
                    f.offset = clampD(f.offset, -3, 3);
                }
            }
            return p;
        } catch (RuntimeException e) {
            throw new IOException("That isn't an Evade Clips preset.");
        }
    }

    private static String trim(String s, int max, String dflt) {
        if (s == null || s.isBlank()) return dflt;
        s = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    public static String toJson(Preset p) { return GSON.toJson(p); }

    /** A one-line summary for lists: what the preset changes. */
    public static String summary(Preset p) {
        List<String> bits = new ArrayList<>();
        if (p.aspect != null && !p.aspect.equals("original")) bits.add(p.aspect + ("split".equals(p.fitMode) ? " facecam split" : "blur".equals(p.fitMode) ? " blurred" : ""));
        if (p.effect != null && !p.effect.equals("none")) bits.add(p.effect.toLowerCase(Locale.ROOT) + " look");
        if (p.atMarker != null) {
            List<String> m = new ArrayList<>();
            if (p.atMarker.rampSpeed > 0) m.add("ramp to " + Library.fmt(p.atMarker.rampSpeed).replaceAll("0+$", "").replaceAll("\\.$", "") + "x");
            if (p.atMarker.zoom > 1.01) m.add("zoom");
            for (FxSpec f : p.atMarker.effects) m.add(f.type.equals("sound") ? f.sound : f.type + (f.sound.isBlank() ? "" : " + " + f.sound));
            if (!m.isEmpty()) bits.add("each marker: " + String.join(", ", m));
        }
        return bits.isEmpty() ? "Look only" : String.join(" · ", bits);
    }

    // ------------------------------------------------------------------ community (share server)

    /** Community presets, newest or most downloaded first. */
    public static List<Preset> community(CloudShare cloud, String sort) throws IOException, InterruptedException {
        JsonObject r = cloud.getJson("/api/presets?sort=" + ("popular".equals(sort) ? "popular" : "new"));
        List<Preset> out = new ArrayList<>();
        JsonArray arr = r.getAsJsonArray("presets");
        if (arr == null) return out;
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            Preset p = new Preset();
            p.name = trim(str(o, "name"), 60, "Preset");
            p.description = trim(str(o, "description"), 300, "");
            p.author = trim(str(o, "author"), 40, "");
            p.communityId = str(o, "id");
            p.downloads = o.has("downloads") ? o.get("downloads").getAsInt() : 0;
            out.add(p);
        }
        return out;
    }

    /** Downloads a community preset in full. */
    public static Preset fetch(CloudShare cloud, String id) throws IOException, InterruptedException {
        JsonObject r = cloud.getJson("/api/presets/" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8));
        Preset p = parse(r.toString());
        p.communityId = id;
        return p;
    }

    /** Shares a preset with everyone. Returns its id. */
    public static String publish(CloudShare cloud, Preset p) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("name", p.name);
        body.addProperty("description", p.description);
        body.addProperty("author", p.author);
        body.add("preset", GSON.toJsonTree(p));
        JsonObject r = cloud.postJson("/api/presets", body);
        return str(r, "id");
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}
