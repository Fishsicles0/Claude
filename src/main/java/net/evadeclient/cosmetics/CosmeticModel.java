package net.evadeclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** A bone's animation (see README): type and its numbers. */
    public record Anim(String type, float side, float base, float amp, float speed) {
        static Anim parse(JsonElement e) {
            if (e == null || !e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString().toLowerCase(Locale.ROOT) : "";
            return new Anim(type, num(o, "side", 1), num(o, "base", 0), num(o, "amp", 0), num(o, "speed", 1));
        }
    }

    public static final class Bone {
        public final String name;
        public final Attach attach;
        public final float px, py, pz;
        public final float pitch, yaw, roll;   // degrees
        public final Anim anim;
        public final boolean emissive, tint;
        public final float alpha;
        final List<float[]> cubes = new ArrayList<>();   // x, y, z, w, h, d, u, v, inflate, mirror
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
            JsonArray cs = o.getAsJsonArray("cubes");
            if (cs != null) for (JsonElement ce : cs) {
                JsonObject c = ce.getAsJsonObject();
                float[] org = vec(c.get("origin")), size = vec(c.get("size")), uv = vec2(c.get("uv"));
                for (float v : org) if (!Float.isFinite(v) || Math.abs(v) > 64) throw new IllegalArgumentException("cube out of range");
                for (float v : size) if (v < 0 || v > 64) throw new IllegalArgumentException("cube too big");
                cubes.add(new float[]{org[0], org[1], org[2], size[0], size[1], size[2], uv[0], uv[1],
                        Math.max(-1f, Math.min(2f, num(c, "inflate", 0f))), c.has("mirror") && c.get("mirror").getAsBoolean() ? 1 : 0});
            }
        }

        public ModelPart part() { return part; }
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

    CosmeticModel(String itemId, JsonObject o, Identifier texture) {
        this.itemId = itemId;
        this.texture = texture;
        if (!FORMAT.equals(str(o, "format", ""))) throw new IllegalArgumentException("unknown model format");
        itemSpace = "item".equals(str(o, "space", "entity"));
        float[] tex = vec2(o.get("texture"));
        texW = (int) tex[0];
        texH = (int) tex[1];
        if (texW <= 0 || texH <= 0 || texW > 1024 || texH > 1024) throw new IllegalArgumentException("bad texture size");
        int cubes = 0;
        for (JsonElement be : o.getAsJsonArray("bones")) {
            Bone b = new Bone(be.getAsJsonObject());
            cubes += b.cubes.size();
            bones.add(b);
        }
        if (bones.size() > MAX_BONES || cubes > MAX_CUBES) throw new IllegalArgumentException("model too complex");
        JsonObject d = o.has("display") ? o.getAsJsonObject("display") : null;
        thirdPerson = display(d, "thirdperson_righthand", new Display(0, -90, 55, 0, 4, 0.5f, 0.85f, 0.85f, 0.85f));
        firstPerson = display(d, "firstperson_righthand", new Display(0, -90, 25, 1.13f, 3.2f, 1.13f, 0.68f, 0.68f, 0.68f));
    }

    /** Builds the parts (render thread; cheap, once per model). */
    void bake() {
        for (Bone b : bones) {
            ModelData data = new ModelData();
            ModelPartBuilder builder = ModelPartBuilder.create();
            int i = 0;
            for (float[] c : b.cubes) {
                builder.mirrored(c[9] != 0);
                builder.cuboid("c" + i++, c[0], c[1], c[2], (int) c[3], (int) c[4], (int) c[5], new Dilation(c[8]), (int) c[6], (int) c[7]);
            }
            data.getRoot().addChild("bone", builder, ModelTransform.NONE);
            b.part = TexturedModelData.of(data, texW, texH).createModel().getChild("bone");
        }
    }

    public boolean baked() { return bones.isEmpty() || bones.get(0).part != null; }

    private static Display display(JsonObject d, String key, Display fallback) {
        if (d == null || !d.has(key)) return fallback;
        JsonObject o = d.getAsJsonObject(key);
        float[] r = vec(o.get("rotation")), t = vec(o.get("translation"));
        float[] s = o.has("scale") ? vec(o.get("scale")) : new float[]{1, 1, 1};
        return new Display(r[0], r[1], r[2], t[0], t[1], t[2], s[0], s[1], s[2]);
    }

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
