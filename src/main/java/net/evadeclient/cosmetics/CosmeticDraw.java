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
import net.minecraft.util.Identifier;
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
            model(d, model, s, m, sink, light, item, slot, item.colorFor(worn));
        }
        // an emote's prop (a kitten to pat): the emote's own model, shown only while it plays
        if (d.emote != null && d.emote.item().hasModel()) model(d, model, s, m, sink, light, d.emote.item(), Slot.EMOTE, -1);
    }

    private static void model(RenderData d, PlayerEntityModel model, PlayerEntityRenderState s, MatrixStack m, Sink sink, int light,
                              CosmeticItem item, Slot slot, int rgb) {
        CosmeticModel cm = CosmeticAssets.model(item);
        if (cm == null || cm.itemSpace) return;
        cm.updateTextures();
        // body armour: back pieces sit out over the chestplate the way vanilla moves its cape (no z-fighting)
        boolean outward = d.chestArmour && (slot == Slot.CAPE || slot == Slot.BACK || slot == Slot.WINGS);
        Identifier glow = cm.glow == null ? null : cm.glow.id;
        if (slot == Slot.PET) {
            // the pet rides beside your head: it turns with your head and drops with your shoulders when you sneak
            d.petHeadYaw = model.head.yaw;
            d.petDrop = model.body.originY;
        }
        for (CosmeticModel.Bone b : cm.drawOrder) {
            if (b.part() == null) continue;
            int a = Math.round(255 * Math.max(0, Math.min(1, d.alpha * b.alpha)));
            if (a <= 3) continue;
            m.push();
            CosmeticModel.Bone root = b;
            while (root.parent != null) root = root.parent;
            attach(model, root.attach, m);
            if (outward && root.attach == CosmeticModel.Attach.BODY) m.translate(0f, -0.053125f, 0.06875f);
            chain(b, s, d, m);
            int color = a << 24 | (b.tint && rgb >= 0 ? rgb : 0xFFFFFF);
            boolean translucent = b.translucent() || a < 255 || cm.tex.partialAlpha;
            sink.part(b.part(), m, layer(cm, b, translucent), b.emissive ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light, color, false);
            if (glow != null) {
                // emissive.png: added on top at full brightness (additive, so black = no glow); fades with the bone
                int g = a;
                int c = 0xFF << 24 | (g << 16) | (g << 8) | g;
                sink.part(b.part(), m, RenderLayers.eyes(glow), LightmapTextureManager.MAX_LIGHT_COORDINATE, c, false);
                CosmeticsPerf.glowParts++;
            }
            m.pop();
        }
    }

    /** Parents first, then the bone itself. */
    private static void chain(CosmeticModel.Bone b, PlayerEntityRenderState s, RenderData d, MatrixStack m) {
        if (b.parent != null) chain(b.parent, s, d, m);
        bone(b, s, d, d == null ? 0 : d.time, m);
    }

    private static RenderLayer layer(CosmeticModel cm, CosmeticModel.Bone b, boolean translucent) {
        if (b.emissive) return RenderLayers.entityTranslucentEmissive(cm.texture);
        // cut-out for solid parts (never sorts wrong, no blending cost); translucent, sorted back to front, for glass,
        // see-through parts and fading
        return translucent ? RenderLayers.entityTranslucent(cm.texture) : RenderLayers.entityCutoutNoCull(cm.texture);
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

    /** Smooth 0..1 easing (slow in, slow out). */
    private static float smooth(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    /** A beat wave in -1..1: a sine, or with {@code flap} a quick downstroke and a slower, eased upstroke. */
    static float wave(float cycles, boolean flap) {
        float f = cycles - (float) Math.floor(cycles);
        if (!flap) return (float) Math.sin(f * TAU);
        // down (from +1 to -1) in the first 38% of the cycle, back up over the rest, both eased
        return f < 0.38f ? 1 - 2 * smooth(f / 0.38f) : -1 + 2 * smooth((f - 0.38f) / 0.62f);
    }

    /**
     * The bone's pivot, rest rotation and animation, on the stack. {@code s} and {@code d} may be null (item skins);
     * without motion data (the Wardrobe preview) animations run on time alone.
     */
    static void bone(CosmeticModel.Bone b, PlayerEntityRenderState s, RenderData d, float t, MatrixStack m) {
        float px = b.px / 16f, py = b.py / 16f, pz = b.pz / 16f;
        float pitch = b.pitch, yaw = b.yaw, roll = b.roll, dy = 0;
        CosmeticModel.Anim an = b.anim;
        boolean motion = d != null && d.motion;
        if (an != null && "pet".equals(an.type()) && b.parent == null && d != null) {
            // the pet turns about your own vertical axis with your head (so it stays beside it, never in it), trailing a
            // few degrees behind when you turn; it sits lower with your shoulders when you sneak
            if (d.petDrop != 0) m.translate(0, d.petDrop / 16f, 0);
            float orbit = d.petHeadYaw - (motion ? Math.max(-CosmeticMotion.PET_LAG, Math.min(CosmeticMotion.PET_LAG, d.petLag)) * DEG : 0);
            if (orbit != 0) m.multiply(new Quaternionf().rotationY(orbit));
        }
        float sy = 1;
        m.translate(px, py, pz);
        if (an != null) {
            float phase = an.phase();
            switch (an.type()) {
                case "cape" -> {
                    float f36, f37, f38;
                    if (motion) { f36 = d.capeLift; f37 = d.capeSwing; f38 = d.capeSide; }
                    else { f36 = s == null ? 0 : s.field_53536; f37 = s == null ? 0 : s.field_53537; f38 = s == null ? 0 : s.field_53538; }
                    // the same swing a vanilla cape has (it hangs from the shoulders and follows movement), sprung
                    m.multiply(new Quaternionf().rotateX((6f + f37 / 2f + f36) * DEG).rotateZ(f38 / 2f * DEG).rotateY((180f - f38 / 2f) * DEG));
                }
                case "wing" -> {
                    float base = an.base(), amp = an.amp(), clock, fold;
                    if (motion) { clock = d.wingClock; amp *= d.wingAmp; fold = smooth(d.fold); }
                    else { clock = t; fold = s != null && s.sneaking ? 1 : 0; }
                    if (s != null && s.isGliding) { base *= 0.35f; amp *= 0.4f; }
                    // folded: swept back by `fold` degrees, beating only a little
                    base += an.fold() * fold;
                    amp *= 1 - 0.75f * fold;
                    float w = wave(clock * an.speed() + phase, an.flap());
                    yaw += an.side() * (base + amp * w);
                    pitch += an.lift() * Math.max(0, w) - fold * 12f;
                    roll += an.side() * fold * an.fold() * 0.35f;
                }
                case "spin" -> yaw += (t * an.speed() + phase * 360f) % 360f;
                case "bob" -> dy = (float) Math.sin((t * an.speed() + phase) * TAU) * an.amp();
                case "sway" -> roll += (float) Math.sin((t * an.speed() + phase) * TAU) * an.amp();
                case "pet" -> {
                    // bobs up from where it sits (never down into your shoulder); every few seconds a little hop, then
                    // later a sway; leans a touch into your turns
                    dy = -(0.5f + 0.5f * (float) Math.sin((t * an.speed() * 0.5f + phase) * TAU)) * an.amp() * 1.4f;
                    float cyc = (t + phase * 7f) % 7f;
                    if (cyc < 0.5f) dy -= (float) Math.sin(cyc / 0.5f * Math.PI) * 2.5f;
                    else if (cyc > 3.5f && cyc < 4.6f) roll += (float) Math.sin((cyc - 3.5f) / 1.1f * Math.PI) * 3f;
                    if (motion) {
                        pitch += -d.petLookPitch * 0.15f;
                        roll += Math.max(-2f, Math.min(2f, d.petYawV * 0.02f));
                    } else {
                        yaw += (float) Math.sin(t * 0.37f * TAU) * 5f;
                        roll += (float) Math.sin(t * 0.61f * TAU) * 1.5f;
                    }
                }
                case "pet_head" -> {
                    // glances about (quick turns, little holds), now and then a curious tilt; nods with your look
                    yaw += glance(t * 0.19f + phase) * (an.amp() > 0 ? an.amp() : 14f);
                    float cyc = (t + 2.3f + phase * 9f) % 9f;
                    if (cyc < 1.2f) roll += (float) Math.sin(cyc / 1.2f * Math.PI) * 7f;
                    if (motion) pitch += -d.petLookPitch * 0.35f;
                    else pitch += (float) Math.sin(t * 0.43f * TAU) * 5f;
                }
                case "blink" -> {
                    // eyelids: squashed flat (open) except for a quick close every 1 / speed seconds, sometimes twice
                    float f = t * an.speed() + phase;
                    float c = blink(f - (float) Math.floor(f));
                    if (((int) Math.floor(f)) % 3 == 1) c = Math.max(c, blink(f - (float) Math.floor(f) - 0.1f));
                    sy = Math.max(0.02f, c);
                }
                default -> { }
            }
        }
        if (dy != 0) m.translate(0, dy / 16f, 0);
        if (pitch != 0 || yaw != 0 || roll != 0) m.multiply(new Quaternionf().rotationZYX(roll * DEG, yaw * DEG, pitch * DEG));
        if (sy != 1) m.scale(1f, sy, 1f);
        m.translate(-px, -py, -pz);
    }

    /** A glance in -1..1: turns quickly and holds at each side (a shaped sine). */
    static float glance(float cycles) {
        float s = (float) Math.sin(cycles * TAU);
        return Math.signum(s) * (float) Math.pow(Math.abs(s), 0.35);
    }

    /** How closed an eyelid is (0..1) at {@code f} of the blink cycle: shut and open again in the first 7%. */
    static float blink(float f) {
        return f < 0 || f > 0.07f ? 0 : (float) Math.sin(f / 0.07f * Math.PI);
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
        cm.updateTextures();
        for (CosmeticModel.Bone b : cm.drawOrder) {
            if (b.part() == null) continue;
            m.push();
            itemChain(b, t, m);
            sink.part(b.part(), m, layer(cm, b, b.translucent() || cm.tex.partialAlpha), b.emissive ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light, 0xFFFFFFFF, glint && !b.emissive);
            if (cm.glow != null) sink.part(b.part(), m, RenderLayers.eyes(cm.glow.id), LightmapTextureManager.MAX_LIGHT_COORDINATE, 0xFFFFFFFF, false);
            m.pop();
        }
        m.pop();
        CosmeticsPerf.drew();
        return true;
    }

    private static void itemChain(CosmeticModel.Bone b, float t, MatrixStack m) {
        if (b.parent != null) itemChain(b.parent, t, m);
        bone(b, null, null, t, m);
    }
}
