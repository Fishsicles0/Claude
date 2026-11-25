package net.evadeclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.model.ModelPart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A cosmetic's cuboid model (format {@code evade-cosmetic-model/1}, documented in site-src/cosmetics/README.md),
 * baked into one {@link ModelPart} per bone. Cube coordinates are in model pixels in the bone's attach space; each
 * bone turns about its pivot. Parts are built once and never changed afterwards: animation goes on the matrix stack,
 * so deferred draws of several players can share them.
 */
public final class CosmeticModel {
    public static final String FORMAT = "evade-cosmetic-model/1";
    /** The most bones and cubes a model may have (a downloaded model can't make the game build millions of quads). */
    static final int MAX_BONES = 32, MAX_CUBES = 256;

    public enum Attach { ROOT, BODY, HEAD, RIGHT_ARM, LEFT_ARM, ITEM }

    /**
     * A bone's animation (see README): type and its numbers. {@code phase} shifts it by a fraction of a cycle,
     * {@code ease} is "sine" or "flap" (fast down, slow up), {@code fold} and {@code lift} are wing extras.
     */
    public record Anim(String type, float side, float base, float amp, float speed, float phase, boolean flap, float fold, float lift) {
        static Anim parse(JsonElement e) {
            if (e == null || !e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString().toLowerCase(Locale.ROOT) : "";
            return new Anim(type, num(o, "side", 1), num(o, "base", 0), num(o, "amp", 0), num(o, "speed", 1),
                    num(o, "phase", 0), "flap".equals(str(o, "ease", "sine").toLowerCase(Locale.ROOT)),
                    clamp(num(o, "fold", 28), 0, 120), clamp(num(o, "lift", 0), -45, 45));
        }
    }

    /** Per-face UV rectangles of a cube (texture units), in Direction order DOWN UP WEST NORTH EAST SOUTH (null = no face). */
    record Faces(float[][] uv, int[] rotation) {}

    public static final class Bone {
        public final String name;
        public final Attach attach;
        public final float px, py, pz;
        public final float pitch, yaw, roll;   // degrees
        public final Anim anim;
        public final boolean emissive, tint, glass;
        public final float alpha;
        /** The bone this one moves with (named by "parent"), or null. */
        public final String parentName;
        Bone parent;
        final List<float[]> cubes = new ArrayList<>();   // x, y, z, w, h, d, u, v, inflate, mirror
        final List<Faces> faces = new ArrayList<>();     // per cube, null = box UV
        ModelPart part;

        Bone(JsonObject o) {
            name = str(o, "name", "bone");
            attach = switch (str(o, "attach", "body").toLowerCase(Locale.ROOT)) {
                case "root" -> Attach.ROOT;
                case "head" -> Attach.HEAD;
                case "right_arm" -> Attach.RIGHT_ARM;
                case "left_arm" -> Attach.LEFT_ARM;
                case "item" -> Attach.ITEM;
                default -> Attach.BODY;
            };
            float[] p = vec(o.get("pivot")), r = vec(o.get("rotation"));
            px = p[0]; py = p[1]; pz = p[2];
            pitch = r[0]; yaw = r[1]; roll = r[2];
            anim = Anim.parse(o.get("anim"));
            emissive = o.has("emissive") && o.get("emissive").getAsBoolean();
            tint = o.has("tint") && o.get("tint").getAsBoolean();
            alpha = Math.max(0f, Math.min(1f, num(o, "alpha", 1f)));
            glass = o.has("glass") && o.get("glass").getAsBoolean();
            parentName = o.has("parent") && o.get("parent").isJsonPrimitive() ? o.get("parent").getAsString() : null;
            JsonArray cs = o.getAsJsonArray("cubes");
            if (cs != null) for (JsonElement ce : cs) {
                JsonObject c = ce.getAsJsonObject();
                float[] org = vec(c.get("origin")), size = vec(c.get("size")), uv = vec2(c.get("uv"));
                for (float v : org) if (!Float.isFinite(v) || Math.abs(v) > 64) throw new IllegalArgumentException("cube out of range");
                for (float v : size) if (!(v >= 0) || v > 64) throw new IllegalArgumentException("cube too big");
                faces.add(c.has("faces") && c.get("faces").isJsonObject() ? parseFaces(c.getAsJsonObject("faces")) : null);
                cubes.add(new float[]{org[0], org[1], org[2], size[0], size[1], size[2], uv[0], uv[1],
                        Math.max(-1f, Math.min(2f, num(c, "inflate", 0f))), c.has("mirror") && c.get("mirror").getAsBoolean() ? 1 : 0});
            }
        }

        public ModelPart part() { return part; }

        /** Drawn translucent (sorted) rather than cut-out. */
        public boolean translucent() { return alpha < 1f || emissive || glass; }

        /** The bone this one moves with, or null. */
        public Bone parent() { return parent; }

        /** Its cubes as {x, y, z, w, h, d} (the attach part's units, before the bone's own turn), for checks. */
        public List<float[]> boxes() {
            List<float[]> out = new ArrayList<>();
            for (float[] c : cubes) out.add(new float[]{c[0], c[1], c[2], c[3], c[4], c[5]});
            return out;
        }
    }

    private static final String[] FACE_NAMES = {"up", "down", "west", "north", "east", "south"};   // visual names, Direction order

    private static Faces parseFaces(JsonObject f) {
        float[][] uv = new float[6][];
        int[] rot = new int[6];
        for (int i = 0; i < 6; i++) {
            if (!f.has(FACE_NAMES[i]) || !f.get(FACE_NAMES[i]).isJsonObject()) continue;
            JsonObject o = f.getAsJsonObject(FACE_NAMES[i]);
            JsonArray a = o.getAsJsonArray("uv");
            if (a == null || a.size() != 4) throw new IllegalArgumentException("face uv needs 4 numbers");
            uv[i] = new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
            for (float v : uv[i]) if (!Float.isFinite(v) || v < -1 || v > 1024) throw new IllegalArgumentException("face uv out of range");
            rot[i] = Math.floorMod(Math.round(num(o, "rotation", 0) / 90f), 4);
        }
        return new Faces(uv, rot);
    }

    /** One display transform of an item-space model (Minecraft item-model style: degrees, pixels, scale). */
    public record Display(float rx, float ry, float rz, float tx, float ty, float tz, float sx, float sy, float sz) {}

    public final String itemId;
    public final boolean itemSpace;
    public final int texW, texH;
    public final List<Bone> bones = new ArrayList<>();
    public final Display thirdPerson, firstPerson;
    /** The texture, registered with the game's texture manager once loaded (see CosmeticAssets). */
    public final Identifier texture;
    /** The texture's frames (null until registered), and the optional full-bright glow layer (emissive.png). */
    AnimatedTexture tex, glow;
    /** Bones in draw order: cut-out first, translucent after (blended over what's behind them). */
    final List<Bone> drawOrder = new ArrayList<>();

    CosmeticModel(String itemId, JsonObject o, Identifier texture) {
        this.itemId = itemId;
        this.texture = texture;
        if (!FORMAT.equals(str(o, "format", ""))) throw new IllegalArgumentException("unknown model format");
        itemSpace = "item".equals(str(o, "space", "entity"));
        float[] tex = vec2(o.get("texture"));
        texW = (int) tex[0];
        texH = (int) tex[1];
        if (texW <= 0 || texH <= 0 || texW > 256 || texH > 256) throw new IllegalArgumentException("bad texture size");
        int cubes = 0;
        for (JsonElement be : o.getAsJsonArray("bones")) {
            Bone b = new Bone(be.getAsJsonObject());
            cubes += b.cubes.size();
            bones.add(b);
        }
        if (bones.size() > MAX_BONES || cubes > MAX_CUBES) throw new IllegalArgumentException("model too complex");
        for (int i = 0; i < bones.size(); i++) {
            Bone b = bones.get(i);
            if (b.parentName == null) continue;
            for (int j = 0; j < i; j++) if (bones.get(j).name.equals(b.parentName)) b.parent = bones.get(j);
            if (b.parent == null) throw new IllegalArgumentException("bone " + b.name + ": parent must be an earlier bone");
        }
        for (Bone b : bones) if (!b.translucent()) drawOrder.add(b);
        for (Bone b : bones) if (b.translucent()) drawOrder.add(b);
        JsonObject d = o.has("display") ? o.getAsJsonObject("display") : null;
        thirdPerson = display(d, "thirdperson_righthand", new Display(0, -90, 55, 0, 4, 0.5f, 0.85f, 0.85f, 0.85f));
        firstPerson = display(d, "firstperson_righthand", new Display(0, -90, 25, 1.13f, 3.2f, 1.13f, 0.68f, 0.68f, 0.68f));
    }

    /** Builds the parts (render thread; cheap, once per model). Per-face cubes get their own quads. */
    void bake() {
        for (Bone b : bones) {
            List<ModelPart.Cuboid> cuboids = new ArrayList<>();
            for (int i = 0; i < b.cubes.size(); i++) {
                float[] c = b.cubes.get(i);
                Faces f = b.faces.get(i);
                if (f == null) {
                    if (c[3] != Math.floor(c[3]) || c[4] != Math.floor(c[4]) || c[5] != Math.floor(c[5]))
                        throw new IllegalArgumentException("box-UV cube sizes must be whole numbers");
                    cuboids.add(new ModelPart.Cuboid((int) c[6], (int) c[7], c[0], c[1], c[2], c[3], c[4], c[5], c[8], c[8], c[8],
                            c[9] != 0, texW, texH, EnumSet.allOf(Direction.class)));
                } else cuboids.add(faceCuboid(c, f));
            }
            b.part = new ModelPart(cuboids, Map.of());
        }
    }

    /** A cube whose faces carry their own UV rectangles, oriented like the same face of a box-UV cube. */
    private ModelPart.Cuboid faceCuboid(float[] c, Faces f) {
        Set<Direction> dirs = EnumSet.noneOf(Direction.class);
        for (int i = 0; i < 6; i++) if (f.uv()[i] != null) dirs.add(DIR_ORDER[i]);
        // geometry from the game's own cuboid (UVs replaced below), so faces and normals match box-UV cubes exactly
        ModelPart.Cuboid cub = new ModelPart.Cuboid(0, 0, c[0], c[1], c[2], c[3], c[4], c[5], c[8], c[8], c[8], false, texW, texH, dirs);
        int k = 0;
        for (int i = 0; i < 6; i++) {
            float[] uv = f.uv()[i];
            if (uv == null) continue;
            ModelPart.Quad q = cub.sides[k];
            ModelPart.Vertex[] v = q.vertices();
            // the corner UVs a box-UV face gets, in vertex order: (u2,v1) (u1,v1) (u1,v2) (u2,v2), turned by rotation
            float[][] corners = {{uv[2], uv[1]}, {uv[0], uv[1]}, {uv[0], uv[3]}, {uv[2], uv[3]}};
            int r = f.rotation()[i];
            ModelPart.Vertex[] nv = new ModelPart.Vertex[4];
            for (int j = 0; j < 4; j++) {
                float[] cuv = corners[(j + r) % 4];
                nv[j] = v[j].remap(cuv[0] / texW, cuv[1] / texH);
            }
            cub.sides[k++] = new ModelPart.Quad(nv, q.direction());
        }
        return cub;
    }

    private static final Direction[] DIR_ORDER = {Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH};

    /** The textures' current frame on the GPU (once per item per change, whoever asks). Render thread. */
    void updateTextures() {
        if (tex != null) tex.update();
        if (glow != null) glow.update();
    }

    public AnimatedTexture texture() { return tex; }
    public AnimatedTexture glow() { return glow; }

    public boolean baked() { return bones.isEmpty() || bones.get(0).part != null; }

    private static Display display(JsonObject d, String key, Display fallback) {
        if (d == null || !d.has(key)) return fallback;
        JsonObject o = d.getAsJsonObject(key);
        float[] r = vec(o.get("rotation")), t = vec(o.get("translation"));
        float[] s = o.has("scale") ? vec(o.get("scale")) : new float[]{1, 1, 1};
        return new Display(r[0], r[1], r[2], t[0], t[1], t[2], s[0], s[1], s[2]);
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    static float num(JsonObject o, String k, float def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : def;
    }

    static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static float[] vec(JsonElement e) {
        float[] v = new float[3];
        if (e != null && e.isJsonArray()) for (int i = 0; i < 3 && i < e.getAsJsonArray().size(); i++) v[i] = e.getAsJsonArray().get(i).getAsFloat();
        return v;
    }

    private static float[] vec2(JsonElement e) {
        float[] v = new float[2];
        if (e != null && e.isJsonArray()) for (int i = 0; i < 2 && i < e.getAsJsonArray().size(); i++) v[i] = e.getAsJsonArray().get(i).getAsFloat();
        return v;
    }
}
