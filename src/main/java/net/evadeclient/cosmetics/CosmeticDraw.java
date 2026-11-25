package net.evadeclient.cosmetics;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import org.joml.Quaternionf;

/**
 * Draws cosmetic models: on a player (the feature layer and the Wardrobe preview share this) and the item skin in your
 * hand. Each bone is one prebuilt ModelPart drawn at its pivot; animation only moves the matrix stack.
 */
public final class CosmeticDraw {
    private static final float DEG = (float) (Math.PI / 180.0);
    private static final float TAU = (float) (Math.PI * 2);
    /** Slots drawn on the body, in order (aura first, under everything else). */
    private static final Slot[] BODY_SLOTS = {Slot.AURA, Slot.CAPE, Slot.BACK, Slot.WINGS, Slot.HAT, Slot.MASK, Slot.PET};

    /** Where parts go: the world's command queue, or straight into buffers (GUI preview). */
    public interface Sink {
        void part(ModelPart part, MatrixStack m, RenderLayer layer, int light, int color, boolean glint);
    }

    public record QueueSink(OrderedRenderCommandQueue queue) implements Sink {
        @Override
        public void part(ModelPart part, MatrixStack m, RenderLayer layer, int light, int color, boolean glint) {
            queue.submitModelPart(part, m, layer, light, OverlayTexture.DEFAULT_UV, null, false, glint, color, null, 0);
        }
    }

    public record BufferSink(VertexConsumerProvider buffers) implements Sink {
        @Override
        public void part(ModelPart part, MatrixStack m, RenderLayer layer, int light, int color, boolean glint) {
            part.render(m, buffers.getBuffer(layer), light, OverlayTexture.DEFAULT_UV, color);
        }
    }

    private CosmeticDraw() {}

    /** Everything {@code d} shows on {@code model} (posed for this frame). */
    public static void player(RenderData d, PlayerEntityModel model, PlayerEntityRenderState s, MatrixStack m, Sink sink, int light) {
        for (Slot slot : BODY_SLOTS) {
            if (!d.shows(slot)) continue;
            Loadout.Worn worn = d.loadout.get(slot);
            CosmeticItem item = CosmeticsCatalog.get(worn.id());
            if (item == null || item.slot != slot) continue;
            CosmeticModel cm = CosmeticAssets.model(item);
            if (cm == null || cm.itemSpace) continue;
            int rgb = item.colorFor(worn);
            for (CosmeticModel.Bone b : cm.bones) {
                if (b.part() == null) continue;
                m.push();
                attach(model, b.attach, m);
                bone(b, s, d.time, m);
                int a = Math.round(255 * Math.max(0, Math.min(1, d.alpha * b.alpha)));
                if (a > 3) {
                    int color = a << 24 | (b.tint && rgb >= 0 ? rgb : 0xFFFFFF);
                    sink.part(b.part(), m, layer(cm, b), b.emissive ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light, color, false);
                }
                m.pop();
            }
        }
    }

    private static RenderLayer layer(CosmeticModel cm, CosmeticModel.Bone b) {
        return b.emissive ? RenderLayers.entityTranslucentEmissive(cm.texture) : RenderLayers.entityTranslucent(cm.texture);
    }

    private static void attach(PlayerEntityModel model, CosmeticModel.Attach at, MatrixStack m) {
        if (at == CosmeticModel.Attach.ROOT || at == CosmeticModel.Attach.ITEM) {
            model.getRootPart().applyTransform(m);
            return;
        }
        model.getRootPart().applyTransform(m);
        switch (at) {
            case HEAD -> model.head.applyTransform(m);
            case RIGHT_ARM -> model.rightArm.applyTransform(m);
            case LEFT_ARM -> model.leftArm.applyTransform(m);
            default -> model.body.applyTransform(m);
        }
    }

    /** The bone's pivot, rest rotation and animation, on the stack. {@code s} may be null (item skins). */
    static void bone(CosmeticModel.Bone b, PlayerEntityRenderState s, float t, MatrixStack m) {
        float px = b.px / 16f, py = b.py / 16f, pz = b.pz / 16f;
        float pitch = b.pitch, yaw = b.yaw, roll = b.roll, dy = 0;
        CosmeticModel.Anim an = b.anim;
        m.translate(px, py, pz);
        if (an != null) {
            switch (an.type()) {
                case "cape" -> {
                    float f36 = s == null ? 0 : s.field_53536, f37 = s == null ? 0 : s.field_53537, f38 = s == null ? 0 : s.field_53538;
                    // the same swing a vanilla cape has (it hangs from the shoulders and follows movement)
                    m.multiply(new Quaternionf().rotateX((6f + f37 / 2f + f36) * DEG).rotateZ(f38 / 2f * DEG).rotateY((180f - f38 / 2f) * DEG));
                }
                case "wing" -> {
                    float base = an.base(), amp = an.amp();
                    if (s != null && s.sneaking) { base += 28; amp *= 0.3f; }
                    if (s != null && s.isGliding) { base *= 0.35f; amp *= 0.4f; }
                    yaw += an.side() * (base + amp * (float) Math.sin(t * an.speed() * TAU));
                }
                case "spin" -> yaw += (t * an.speed()) % 360f;
                case "bob" -> dy = (float) Math.sin(t * an.speed() * TAU) * an.amp();
                case "sway" -> roll += (float) Math.sin(t * an.speed() * TAU) * an.amp();
                case "pet" -> {
                    dy = (float) Math.sin(t * an.speed() * TAU * 0.5f) * an.amp() * 1.4f;
                    yaw += (float) Math.sin(t * 0.37f * TAU) * 18f;
                    roll += (float) Math.sin(t * 0.61f * TAU) * 4f;
                }
                default -> { }
            }
        }
        if (dy != 0) m.translate(0, dy / 16f, 0);
        if (pitch != 0 || yaw != 0 || roll != 0) m.multiply(new Quaternionf().rotationZYX(roll * DEG, yaw * DEG, pitch * DEG));
        m.translate(-px, -py, -pz);
    }

    /**
     * Your item skin in place of the item, held in {@code ctx} (first or third person). The matrix stack is at the
     * hand, before the item's own display transform, exactly where vanilla would draw the item model.
     */
    public static boolean itemSkin(CosmeticItem item, ItemDisplayContext ctx, MatrixStack m, OrderedRenderCommandQueue q, int light, boolean glint) {
        CosmeticModel cm = CosmeticAssets.model(item);
        if (cm == null || !cm.itemSpace) return false;
        boolean left = ctx.isLeftHand();
        boolean first = ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        CosmeticModel.Display d = first ? cm.firstPerson : cm.thirdPerson;
        m.push();
        // like vanilla's item Transformation.apply: mirrored for the left hand, pixels to blocks
        float ry = left ? -d.ry() : d.ry(), rz = left ? -d.rz() : d.rz();
        m.translate((left ? -1 : 1) * d.tx() / 16f, d.ty() / 16f, d.tz() / 16f);
        m.multiply(new Quaternionf().rotationXYZ(d.rx() * DEG, ry * DEG, rz * DEG));
        m.scale(d.sx(), d.sy(), d.sz());
        m.translate(-0.5f, -0.5f, -0.5f);
        float t = (float) Emotes.clock();
        Sink sink = new QueueSink(q);
        for (CosmeticModel.Bone b : cm.bones) {
            if (b.part() == null) continue;
            m.push();
            bone(b, null, t, m);
            sink.part(b.part(), m, layer(cm, b), b.emissive ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light, 0xFFFFFFFF, glint && !b.emissive);
            m.pop();
        }
        m.pop();
        CosmeticsPerf.drew();
        return true;
    }
}
