package net.evadeclient;

import net.evadeclient.command.CommandManager;
import net.evadeclient.config.ConfigManager;
import net.evadeclient.hud.Hud;
import net.evadeclient.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.evadeclient.render.RenderContext;
import net.evadeclient.util.Notifications;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EvadeClient implements ClientModInitializer {
    public static final String NAME = "Evade";
    public static final String VERSION = "1.18.1";
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    public static ModuleManager modules;
    public static CommandManager commands;
    public static ConfigManager config;
    /** Client ticks since launch (20 per second). HUD text is recomputed once per tick, not every frame. */
    public static long ticks;
    public static net.evadeclient.config.ProfileManager profiles;

    public static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    @Override
    public void onInitializeClient() {
        modules = new ModuleManager();
        commands = new CommandManager();
        config = new ConfigManager();
        config.load();
        profiles = new net.evadeclient.config.ProfileManager(config);
        config.profiles = profiles;
        profiles.init();
        modules.get(net.evadeclient.module.client.ClientSettings.class).applyTheme();

        ClientTickEvents.END_CLIENT_TICK.register(client -> { ticks++; modules.onTick(); });
        // Evade's widgets sit just under chat, so chat, the tab list and subtitles stay readable on top of them.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("evade", "hud"), (ctx, tickCounter) -> Hud.render(ctx));
        // Toasts go over everything in game; while a screen is open they are drawn over the screen instead (below).
        HudElementRegistry.addLast(Identifier.of("evade", "notifications"), (ctx, tickCounter) -> {
            if (mc().currentScreen == null) Notifications.render(ctx);
        });
        // The low-health vignette goes under the hotbar and hearts, and doesn't vanish with F3.
        HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR, Identifier.of("evade", "damage_tint"), (ctx, tickCounter) -> {
            var dt = modules.get(net.evadeclient.module.visuals.DamageTint.class);
            if (dt == null || !dt.isEnabled()) return;
            try { dt.draw(ctx); } catch (Exception e) { ModuleManager.crashed(dt, e, "HUD"); }
        });
        // Custom crosshair and potion HUD replace the vanilla ones while enabled.
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (ctx, tc) -> {
            var ch = modules.get(net.evadeclient.module.mods.Crosshair.class);
            if (ch == null || !ch.isEnabled()) { vanilla.render(ctx, tc); return; }
            try { ch.draw(ctx); } catch (Exception e) { ModuleManager.crashed(ch, e, "HUD"); vanilla.render(ctx, tc); }
        });
        HudElementRegistry.replaceElement(VanillaHudElements.STATUS_EFFECTS, vanilla -> (ctx, tc) -> {
            var ph = modules.get(net.evadeclient.module.hud.PotionHud.class);
            if (ph == null || !ph.hidesVanilla()) vanilla.render(ctx, tc);
        });
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, state) -> {
            var bo = modules.get(net.evadeclient.module.mods.BlockOutline.class);
            return bo == null || !bo.isEnabled() || bo.draw(ctx, state);
        });
        WorldRenderEvents.END_MAIN.register(EvadeClient::onWorldRender);
        ClientSendMessageEvents.ALLOW_CHAT.register(commands::handleChat);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> config.save());
        // Profiles linked to a server switch in automatically when you join it.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            var entry = client.getCurrentServerEntry();
            if (entry == null || client.isInSingleplayer()) return;
            String switched = profiles.onJoinServer(entry.address);
            if (switched != null) Notifications.push("Profile", "Switched to " + switched + " for this server", true);
        }));
        ClientTickEvents.END_CLIENT_TICK.register(net.evadeclient.module.misc.AutoReconnect::clientTick);
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, type, lines) ->
                net.evadeclient.module.visuals.BetterTooltips.append(stack, lines));
        // Fabric gives a screen fresh per-screen events on every init and resize, so these are added each time.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof net.minecraft.client.gui.screen.DisconnectedScreen)
                ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) ->
                        net.evadeclient.module.misc.AutoReconnect.renderOverlay(ctx, s.width));
            // Menu feedback ("Code copied", "Profile imported") shows over the menu and its blur.
            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> Notifications.render(ctx));
        });
        // Only system (game) messages: end-of-game announcements come this way, ordinary player chat does not.
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                net.evadeclient.module.misc.AutoGG.onMessage(message.getString());
                net.evadeclient.module.misc.Clips.onMessage(message.getString());
            }
        });

        LOG.info("{} {} loaded with {} modules", NAME, VERSION, modules.all().size());
    }

    private static long lastFrame = System.nanoTime();

    private static void onWorldRender(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context) {
        long now = System.nanoTime();
        float frameTime = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        MinecraftClient mc = mc();
        float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
        VertexConsumerProvider consumers = context.consumers();
        modules.onRender(new RenderContext(context.matrices(), consumers, tickDelta, frameTime));
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(net.evadeclient.render.EvadeRenderLayers.QUADS);
            immediate.draw(net.evadeclient.render.EvadeRenderLayers.LINES);
        }
    }
}
