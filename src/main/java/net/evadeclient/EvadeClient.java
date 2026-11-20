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
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.evadeclient.render.RenderContext;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EvadeClient implements ClientModInitializer {
    public static final String NAME = "Evade";
    public static final String VERSION = "0.3.0";
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    public static ModuleManager modules;
    public static CommandManager commands;
    public static ConfigManager config;

    public static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    @Override
    public void onInitializeClient() {
        modules = new ModuleManager();
        commands = new CommandManager();
        config = new ConfigManager();
        config.load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> modules.onTick());
        HudElementRegistry.addLast(Identifier.of("evade", "hud"), (ctx, tickCounter) -> Hud.render(ctx));
        // Custom crosshair and potion HUD replace the vanilla ones while enabled.
        HudElementRegistry.replaceElement(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CROSSHAIR, vanilla -> (ctx, tc) -> {
            var ch = modules.get(net.evadeclient.module.mods.Crosshair.class);
            if (ch != null && ch.isEnabled()) ch.draw(ctx); else vanilla.render(ctx, tc);
        });
        HudElementRegistry.replaceElement(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.STATUS_EFFECTS, vanilla -> (ctx, tc) -> {
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
