package net.evadeclient.cosmetics;

import com.mojang.blaze3d.systems.RenderSystem;
import net.evadeclient.mixin.DrawContextStateAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.SpecialGuiElementRenderer;
import net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4fStack;

/**
 * The Wardrobe's 3D preview: your own skin (the default skin outside a world) wearing a loadout, turned to any angle,
 * with the selected emote playing. Drawn like the game's own skin preview, so it works on the title screen too.
 */
public final class WardrobePreview {
    private static PlayerEntityModel wide, slim;

    private WardrobePreview() {}

    public record State(PlayerEntityModel model, Identifier skin, PlayerEntityRenderState player, RenderData data,
                        float yaw, float pitch, int x1, int y1, int x2, int y2, float scale,
                        @Nullable ScreenRect scissorArea, @Nullable ScreenRect bounds) implements SpecialGuiElementRenderState {}

    /** Adds the preview to the frame. {@code time} drives animations (seconds). */
    public static void draw(DrawContext ctx, Loadout loadout, Emotes.Playing emote, float yaw, float pitch, float time,
                            int x1, int y1, int x2, int y2) {
        MinecraftClient mc = MinecraftClient.getInstance();
        SkinTextures skin = mc.player != null ? mc.player.getSkin()
                : DefaultSkinHelper.getSkinTextures(mc.getSession().getUuidOrNull() != null ? mc.getSession().getUuidOrNull() : new java.util.UUID(0, 0));
        if (wide == null) {
            wide = new PlayerEntityModel(mc.getLoadedEntityModels().getModelPart(EntityModelLayers.PLAYER), false);
            slim = new PlayerEntityModel(mc.getLoadedEntityModels().getModelPart(new net.minecraft.client.render.entity.model.EntityModelLayer(Identifier.ofVanilla("player_slim"), "main")), true);
        }
        PlayerEntityRenderState ps = new PlayerEntityRenderState();
        ps.age = time * 20f;
        RenderData d = new RenderData();
        d.loadout = loadout;
        d.time = time;
        d.self = true;
        d.emote = emote;
        ((FabricRenderState) ps).setData(CosmeticsEngine.KEY, d);
        // Fit the player plus what sticks out above the head: tall hats, pets beside/above it, arched wings.
        float scale = 0.97f * (y2 - y1) / (2.125f + headroom(loadout));
        ScreenRect bounds = SpecialGuiElementRenderState.createBounds(x1, y1, x2, y2, null);
        ((DrawContextStateAccessor) ctx).evade$state().addSpecialElement(new State(skin.model() == PlayerSkinType.SLIM ? slim : wide,
                skin.body().texturePath(), ps, d, yaw, pitch, x1, y1, x2, y2, scale, null, bounds));
    }

    /** Extra height (blocks) to leave above the head for what's worn, so nothing is cut off at the top. */
    static float headroom(Loadout loadout) {
        if (loadout == null) return 0f;
        float h = 0f;
        if (loadout.get(Slot.PET) != null) h = Math.max(h, 1.0f);
        if (loadout.get(Slot.HAT) != null) h = Math.max(h, 0.8f);
        if (loadout.get(Slot.WINGS) != null) h = Math.max(h, 0.55f);
        if (loadout.get(Slot.BACK) != null) h = Math.max(h, 0.35f);
        if (loadout.get(Slot.MASK) != null) h = Math.max(h, 0.15f);
        return h;
    }

    public static final class Renderer extends SpecialGuiElementRenderer<State> {
        public Renderer(VertexConsumerProvider.Immediate buffers) { super(buffers); }

        @Override public Class<State> getElementClass() { return State.class; }

        @Override protected String getName() { return "evade wardrobe"; }

        @Override
        protected void render(State st, MatrixStack m) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.gameRenderer.getDiffuseLighting().setShaderLights(DiffuseLighting.Type.PLAYER_SKIN);
            int k = mc.getWindow().getScaleFactor();
            Matrix4fStack view = RenderSystem.getModelViewStack();
            view.pushMatrix();
            float f = st.scale() * k;
            view.rotateAround(RotationAxis.POSITIVE_X.rotationDegrees(st.pitch()), 0f, f * 1.0625f, 0f);
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-st.yaw()));
            // Shift down by half the headroom so the feet stay at the bottom and the free space is all above the head.
            m.translate(0f, -1.601f + headroom(st.data().loadout) / 2f, 0f);
            long t0 = System.nanoTime();
            try {
                PlayerEntityModel model = st.model();
                model.setAngles(st.player());
                int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
                model.render(m, vertexConsumers.getBuffer(model.getLayer(st.skin())), light, OverlayTexture.DEFAULT_UV);
                CosmeticDraw.player(st.data(), model, st.player(), m, new CosmeticDraw.BufferSink(vertexConsumers), light);
                vertexConsumers.draw();
            } finally {
                view.popMatrix();
                CosmeticsPerf.add(System.nanoTime() - t0);
            }
        }
    }
}
