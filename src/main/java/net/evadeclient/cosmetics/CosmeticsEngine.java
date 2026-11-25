package net.evadeclient.cosmetics;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.Cosmetics;
import net.evadeclient.util.Combat;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires the cosmetics into the game: the player feature layer, the per-frame render-state data, the Wardrobe preview
 * renderer, win detection, and the rules for what is drawn at all:
 * <ul>
 *   <li>the module on and allowed by the server ("Cosmetics" server rule), and Show My / Show Others' Cosmetics;</li>
 *   <li>never on invisible players or spectators, and nothing while you're in combat with Hide In Combat on;</li>
 *   <li>others' only within Render Distance (fading out near it);</li>
 *   <li>a helmet or head item hides hats and masks, an elytra hides capes, wings and back items, so armour stays
 *       visible; the vanilla cape steps aside for an Evade cape (on your screen only).</li>
 * </ul>
 */
public final class CosmeticsEngine {
    public static final RenderStateDataKey<RenderData> KEY = RenderStateDataKey.create(() -> "evade:cosmetics");
    private static Cosmetics module;
    private static final Map<UUID, RenderData> DATA = new ConcurrentHashMap<>();
    private static boolean registered;

    private CosmeticsEngine() {}

    /** Startup (from the module's constructor): before entity renderers are built. */
    public static void init(Cosmetics m) {
        module = m;
        if (registered) return;
        registered = true;
        CosmeticsCatalog.loadBundled();
        Wardrobe.load();
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, ctx) -> {
            if (renderer instanceof PlayerEntityRenderer<?> pr) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> c = (FeatureRendererContext) pr;
                helper.register(new Layer(c));
            }
        });
        SpecialGuiElementRegistry.register(ctx -> new WardrobePreview.Renderer(ctx.vertexConsumers()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) CosmeticFx.onMessage(message.getString());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(() -> {
            PlayerCosmetics.clear();
            Emotes.stop();
            CosmeticFx.clear();
            DATA.clear();
        }));
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.execute(() -> {
            refreshCatalog();
            Wardrobe.refresh(true);
        }));
    }

    public static Cosmetics module() { return module; }

    /** Cosmetics may show at all: the module is on (the server rule switches it off). */
    public static boolean allowed() { return module != null && module.isEnabled(); }

    /** Asks evadeclient.com for the full catalog (new items, prices); the bundled set works without it. */
    public static void refreshCatalog() {
        CosmeticsApi.catalog().whenComplete((r, err) -> {
            if (r != null) MinecraftClient.getInstance().execute(() -> {
                try { CosmeticsCatalog.mergeRemote(r); } catch (RuntimeException e) { EvadeClient.LOG.warn("Cosmetics: bad catalog", e); }
            });
        });
    }

    // ------------------------------------------------------------------ per frame

    /** From PlayerEntityRenderer.updateRenderState: what this player shows this frame (null data = nothing). */
    public static void extract(Entity entity, PlayerEntityRenderState s) {
        long t0 = System.nanoTime();
        RenderData d = null;
        try {
            d = compute(entity, s);
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Cosmetics: skipped a player", e);
        }
        ((FabricRenderState) s).setData(KEY, d);
        if (d != null && d.shows(Slot.CAPE)) s.capeVisible = false;
        CosmeticsPerf.add(System.nanoTime() - t0);
    }

    private static RenderData compute(Entity entity, PlayerEntityRenderState s) {
        if (!allowed() || !(entity instanceof AbstractClientPlayerEntity p)) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean self = p == mc.player || (mc.player != null && p.getUuid().equals(mc.player.getUuid()));
        if (self ? !module.showMine.get() : !module.showOthers.get()) return null;
        if (p.isInvisible() || p.isSpectator() || s.invisible || s.spectator) return null;
        if (module.hideInCombat.get() && Combat.active()) return null;
        Loadout l = self ? Wardrobe.renderLoadout() : PlayerCosmetics.get(p.getUuid());
        if (l == null || l.isEmpty()) return null;
        float alpha = 1f;
        if (!self) {
            Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
            double max = module.distance.get();
            double dist = cam == null ? 0 : Math.sqrt(cam.squaredDistanceTo(p));
            if (dist > max) return null;
            alpha = (float) Math.min(1, (max - dist) / Math.max(1, max * 0.15));
        }
        RenderData d = DATA.computeIfAbsent(p.getUuid(), u -> new RenderData());
        d.loadout = l;
        d.alpha = alpha;
        d.self = self;
        d.hidden = 0;
        d.time = s.age / 20f;
        if (!s.equippedHeadStack.isEmpty()) d.hide(Slot.HAT).hide(Slot.MASK);
        if (s.equippedChestStack.contains(DataComponentTypes.GLIDER)) d.hide(Slot.CAPE).hide(Slot.WINGS).hide(Slot.BACK);
        d.emote = self ? Emotes.current() : null;
        net.minecraft.component.type.EquippableComponent eq = s.equippedChestStack.get(DataComponentTypes.EQUIPPABLE);
        d.chestArmour = eq != null && eq.slot() == net.minecraft.entity.EquipmentSlot.CHEST && eq.assetId().isPresent()
                && !s.equippedChestStack.contains(DataComponentTypes.GLIDER);
        Loadout.Worn capeWorn = l.get(Slot.CAPE) != null ? l.get(Slot.CAPE) : l.get(Slot.BACK);
        CosmeticMotion.update(d, p, s, capeWorn == null ? null : CosmeticsCatalog.get(capeWorn.id()));
        return d;
    }

    /** The latest per-frame data for a player (tests: motion state), or null. */
    public static RenderData dataFor(UUID player) { return DATA.get(player); }

    public static RenderData data(PlayerEntityRenderState s) {
        return ((FabricRenderState) s).getData(KEY);
    }

    /** The cosmetics layer on every player renderer. */
    static final class Layer extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
        Layer(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> ctx) { super(ctx); }

        @Override
        public void render(MatrixStack m, OrderedRenderCommandQueue q, int light, PlayerEntityRenderState s, float yaw, float pitch) {
            RenderData d = data(s);
            if (d == null) return;
            long t0 = System.nanoTime();
            try {
                CosmeticDraw.player(d, getContextModel(), s, m, new CosmeticDraw.QueueSink(q), light);
                CosmeticsPerf.drew();
            } catch (RuntimeException e) {
                EvadeClient.LOG.warn("Cosmetics: draw failed", e);
            }
            CosmeticsPerf.add(System.nanoTime() - t0);
        }
    }

    // ------------------------------------------------------------------ item skin

    /** Your item skin for {@code stack}, if it applies (your own swords only), else null. */
    public static CosmeticItem itemSkinFor(Entity holder, ItemStack stack) {
        if (!allowed() || !module.showMine.get() || stack == null || stack.isEmpty()) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || holder == null || holder.getId() != mc.player.getId()) return null;
        if (!stack.isIn(ItemTags.SWORDS)) return null;
        if (module.hideInCombat.get() && Combat.active()) return null;
        Loadout.Worn w = Wardrobe.renderLoadout().get(Slot.ITEM_SKIN);
        CosmeticItem item = w == null ? null : CosmeticsCatalog.get(w.id());
        return item != null && item.slot == Slot.ITEM_SKIN ? item : null;
    }

    /** Draws the skin instead of the item; false when it can't (still loading), so vanilla draws the item. */
    public static boolean drawItemSkin(Entity holder, ItemStack stack, ItemDisplayContext ctx, MatrixStack m, OrderedRenderCommandQueue q, int light) {
        if (ctx != ItemDisplayContext.FIRST_PERSON_LEFT_HAND && ctx != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                && ctx != ItemDisplayContext.THIRD_PERSON_LEFT_HAND && ctx != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) return false;
        CosmeticItem item = itemSkinFor(holder, stack);
        if (item == null) return false;
        long t0 = System.nanoTime();
        try {
            return CosmeticDraw.itemSkin(item, ctx, m, q, light, stack.hasGlint());
        } finally {
            CosmeticsPerf.add(System.nanoTime() - t0);
        }
    }

    // ------------------------------------------------------------------ commands

    public static CosmeticItem findEmote(String q) {
        String n = q.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (CosmeticItem i : CosmeticsCatalog.forSlot(Slot.EMOTE)) {
            if (!Wardrobe.owns(i.id)) continue;
            if (i.id.replace("_", "").equals(n) || i.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").equals(n)) return i;
        }
        return null;
    }

    public static void redeem(String code) {
        String c = code.strip();
        if (c.isEmpty() || c.length() > 64) { Notifications.push("Wardrobe", "That isn't a code", false); return; }
        CosmeticsApi.redeem(c).whenComplete((r, err) -> MinecraftClient.getInstance().execute(() -> {
            if (err != null) { Notifications.push("Wardrobe", "Code not redeemed: " + CosmeticsApi.reason(err), false); return; }
            Notifications.push("Wardrobe", "Code redeemed! Check your Wardrobe", true);
            Wardrobe.refresh(true);
        }));
    }
}
