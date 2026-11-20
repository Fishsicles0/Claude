package net.evadeclient;

import net.evadeclient.command.CommandManager;
import net.evadeclient.config.ConfigManager;
import net.evadeclient.hud.Hud;
import net.evadeclient.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EvadeClient implements ClientModInitializer {
    public static final String NAME = "Evade";
    public static final String VERSION = "0.1.0";
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
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> Hud.render(ctx));
        ClientSendMessageEvents.ALLOW_CHAT.register(commands::handleChat);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> config.save());

        LOG.info("{} {} loaded with {} modules", NAME, VERSION, modules.all().size());
    }
}
