package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.cosmetics.CosmeticAssets;
import net.evadeclient.cosmetics.CosmeticItem;
import net.evadeclient.cosmetics.CosmeticModel;
import net.evadeclient.cosmetics.CosmeticsCatalog;
import net.evadeclient.cosmetics.Slot;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pets keep clear of their owner: every point of every pet, posed at rest, lies outside the player's head (with the
 * outer skin layer, whether you look straight ahead, up or down; the pet turns with your head, so this holds whichever
 * way you face), body and arms; and each pet is a whole creature (a body that follows you, a head that looks about,
 * eyelids that blink).
 */
public final class PetsTest {
    private PetsTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    // entity space (y down), outer skin layer included: {minX, minY, minZ, maxX, maxY, maxZ}
    private static final float[] HEAD = {-4.5f, -8.5f, -4.5f, 4.5f, 0.5f, 4.5f};
    private static final float[] BODY = {-4.25f, -0.25f, -2.25f, 4.25f, 12.25f, 2.25f};
    private static final float[] LEFT_ARM = {3.75f, -0.25f, -2.25f, 8.25f, 12.25f, 2.25f};
    private static final float[] RIGHT_ARM = {-8.25f, -0.25f, -2.25f, -3.75f, 12.25f, 2.25f};

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        List<String> ids = ctx.computeOnClient(c -> {
            List<String> out = new ArrayList<>();
            for (CosmeticItem it : CosmeticsCatalog.all()) if (it.slot == Slot.PET) { out.add(it.id); CosmeticAssets.request(it); }
            return out;
        });
        check(ids.size() >= 7, "Pets: the catalog lists the pets (" + ids + ")");
        for (int i = 0; i < 100 && !ctx.computeOnClient(c -> ids.stream().allMatch(id -> CosmeticAssets.model(CosmeticsCatalog.get(id)) != null)); i++)
            ctx.waitTicks(2);
        for (String id : ids) {
            String r = ctx.computeOnClient(c -> clearance(CosmeticAssets.model(CosmeticsCatalog.get(id))));
            check(r.startsWith("ok"), "Pets: " + id + " sits clear of your head, body and arms at rest (" + r + ")");
            String parts = ctx.computeOnClient(c -> parts(CosmeticAssets.model(CosmeticsCatalog.get(id))));
            check(parts.equals("pet,pet_head,blink"), "Pets: " + id + " has a following body, a head that looks about and blinking eyes (" + parts + ")");
        }
    }

    /** Which of the pet animations the model uses, in a fixed order. */
    private static String parts(CosmeticModel m) {
        if (m == null) return "not loaded";
        List<String> out = new ArrayList<>();
        for (String t : new String[]{"pet", "pet_head", "blink"})
            if (m.bones.stream().anyMatch(b -> b.anim != null && t.equals(b.anim.type()))) out.add(t);
        return String.join(",", out);
    }

    /** "ok <closest gap>" or what it hits. */
    private static String clearance(CosmeticModel m) {
        if (m == null) return "not loaded";
        Map<CosmeticModel.Bone, Matrix4f> rest = new HashMap<>();
        List<Vector3f> pts = new ArrayList<>();
        for (CosmeticModel.Bone b : m.bones) {
            if (b.attach != CosmeticModel.Attach.ROOT && b.parent() == null) return "bone " + b.name + " isn't on the model root";
            Matrix4f mat = new Matrix4f(b.parent() != null ? rest.get(b.parent()) : new Matrix4f())
                    .translate(b.px, b.py, b.pz)
                    .rotateZYX((float) Math.toRadians(b.roll), (float) Math.toRadians(b.yaw), (float) Math.toRadians(b.pitch))
                    .translate(-b.px, -b.py, -b.pz);
            rest.put(b, mat);
            for (float[] c : b.boxes()) {
                for (int i = 0; i <= 4; i++) for (int j = 0; j <= 4; j++) for (int k = 0; k <= 4; k++) {
                    if (i % 4 != 0 && j % 4 != 0 && k % 4 != 0) continue;          // the surface only
                    pts.add(mat.transformPosition(new Vector3f(c[0] + c[3] * i / 4f, c[1] + c[4] * j / 4f, c[2] + c[5] * k / 4f)));
                }
            }
        }
        float worst = Float.MAX_VALUE;
        for (Vector3f p : pts) {
            // the head, looking anywhere from straight up to straight down (it turns about the neck at the origin)
            for (int pitch = -90; pitch <= 90; pitch += 15) {
                Vector3f q = new Vector3f(p).rotateX((float) Math.toRadians(-pitch));
                float d = gap(q, HEAD);
                if (d <= 0) return String.format("inside the head looking %d at (%.2f, %.2f, %.2f)", pitch, p.x, p.y, p.z);
                worst = Math.min(worst, d);
            }
            for (float[] box : new float[][]{BODY, LEFT_ARM, RIGHT_ARM}) {
                float d = gap(p, box);
                if (d <= 0) return String.format("inside the %s at (%.2f, %.2f, %.2f)", box == BODY ? "body" : "arm", p.x, p.y, p.z);
                worst = Math.min(worst, d);
            }
        }
        return String.format("ok %.2f px", worst);
    }

    private static float gap(Vector3f p, float[] b) {
        float dx = Math.max(Math.max(b[0] - p.x, p.x - b[3]), 0), dy = Math.max(Math.max(b[1] - p.y, p.y - b[4]), 0),
                dz = Math.max(Math.max(b[2] - p.z, p.z - b[5]), 0);
        if (dx > 0 || dy > 0 || dz > 0) return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return -Math.min(Math.min(Math.min(p.x - b[0], b[3] - p.x), Math.min(p.y - b[1], b[4] - p.y)), Math.min(p.z - b[2], b[5] - p.z));
    }
}
