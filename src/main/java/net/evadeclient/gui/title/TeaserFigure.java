package net.evadeclient.gui.title;

import com.mojang.blaze3d.systems.RenderSystem;
import net.evadeclient.cosmetics.CosmeticDraw;
import net.evadeclient.cosmetics.CosmeticsEngine;
import net.evadeclient.cosmetics.Loadout;
import net.evadeclient.cosmetics.RenderData;
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
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4fStack;

/**
 * The main menu's 3D figure: you, in your own skin and the cosmetics you wear, drawn the way the Wardrobe's preview
 * draws you (the same model and {@link CosmeticDraw}, used read-only) but with the picture kept between frames. It is
 * re-rendered only when what it shows changes: at most {@link #FPS} times a second while it turns, and once when it
 * stands still (Light effects or Reduce Motion), so on weak graphics it costs one textured quad a frame.
 */
public final class TeaserFigure {
    private TeaserFigure() {}

    /** Re-renders a second while turning: smooth at this size, a fraction of the frame rate. */
    static final int FPS = 30;
    private static PlayerEntityModel wide, slim;
    private static boolean registered;
    /** Pictures rendered so far (for the gametest: a still figure stops rendering). */
    static int renders;

    public record State(PlayerEntityModel model, Identifier skin, PlayerEntityRenderState player, RenderData data,
                        float yaw, float pitch, long key, int x1, int y1, int x2, int y2, float scale,
                        @Nullable ScreenRect scissorArea, @Nullable ScreenRect bounds) implements SpecialGuiElementRenderState {}

    /** Registers the renderer (once, at start-up, before the game builds its GUI renderer). */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry.register(ctx -> new Renderer(ctx.vertexConsumers()));
    }

    /** Rendered pictures so far (gametest). */
    public static int renders() { return renders; }

    /**
     * Adds the figure to the frame. {@code time} drives the cosmetics' own animation (seconds); it and the yaw are
     * stepped to {@link #FPS}, so frames in between reuse the last picture.
     */
    public static void draw(DrawContext ctx, SkinTextures skin, Loadout loadout, float yaw, float pitch, float time,
                            int x1, int y1, int x2, int y2) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (wide == null) {
            wide = new PlayerEntityModel(mc.getLoadedEntityModels().getModelPart(EntityModelLayers.PLAYER), false);
            slim = new PlayerEntityModel(mc.getLoadedEntityModels().getModelPart(new EntityModelLayer(Identifier.ofVanilla("player_slim"), "main")), true);
        }
        yaw = Math.round(yaw); // whole degrees: a turning figure re-renders at most once per degree
        long step = (long) Math.floor(time * FPS);
        float t = step / (float) FPS;
        PlayerEntityRenderState ps = new PlayerEntityRenderState();
        ps.age = t * 20f;
        RenderData d = new RenderData();
        d.loadout = loadout;
        d.time = t;
        d.self = true;
        ((FabricRenderState) ps).setData(CosmeticsEngine.KEY, d);
        Identifier tex = skin.body().texturePath();
        long key = 31L * (31L * (31L * step + Float.floatToIntBits(yaw)) + loadout.hashCode()) + tex.hashCode() + (skin.model() == PlayerSkinType.SLIM ? 7 : 0);
        float scale = 0.97f * (y2 - y1) / 2.125f;
        ScreenRect bounds = SpecialGuiElementRenderState.createBounds(x1, y1, x2, y2, null);
        ((DrawContextStateAccessor) ctx).evade$state().addSpecialElement(new State(skin.model() == PlayerSkinType.SLIM ? slim : wide,
                tex, ps, d, yaw, pitch, key, x1, y1, x2, y2, scale, null, bounds));
    }

    public static final class Renderer extends SpecialGuiElementRenderer<State> {
        private long lastKey = Long.MIN_VALUE;

        public Renderer(VertexConsumerProvider.Immediate buffers) { super(buffers); }

        @Override public Class<State> getElementClass() { return State.class; }

        @Override protected String getName() { return "evade title figure"; }

        /** The same picture as last frame: reuse it (vanilla still re-renders when the size changed). */
        @Override
        protected boolean shouldBypassScaling(State st) {
            return st.key() == lastKey;
        }

        @Override
        protected void render(State st, MatrixStack m) {
            lastKey = st.key();
            renders++;
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.gameRenderer.getDiffuseLighting().setShaderLights(DiffuseLighting.Type.PLAYER_SKIN);
            int k = mc.getWindow().getScaleFactor();
            Matrix4fStack view = RenderSystem.getModelViewStack();
            view.pushMatrix();
            float f = st.scale() * k;
            view.rotateAround(RotationAxis.POSITIVE_X.rotationDegrees(st.pitch()), 0f, f * 1.0625f, 0f);
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-st.yaw()));
            m.translate(0f, -1.601f, 0f);
            try {
                PlayerEntityModel model = st.model();
                model.setAngles(st.player());
                int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
                model.render(m, vertexConsumers.getBuffer(model.getLayer(st.skin())), light, OverlayTexture.DEFAULT_UV);
                CosmeticDraw.player(st.data(), model, st.player(), m, new CosmeticDraw.BufferSink(vertexConsumers), light);
                vertexConsumers.draw();
            } finally {
                view.popMatrix();
            }
        }
    }
}
