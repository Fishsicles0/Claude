package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.StatusSetting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;

/**
 * Shortcut to Simple Voice Chat (by henkelmax), which the Evade Installer downloads from Modrinth.
 * Evade has no voice code of its own; this only shows status and opens Simple Voice Chat's menu.
 */
public class VoiceChat extends Module {
    private static final Identifier SVC_CHANNEL = Identifier.of("voicechat", "request_secret");

    public final StatusSetting status = add((StatusSetting) new StatusSetting("Voice Chat", VoiceChat::statusLabel,
            "click: voice menu").onClick(VoiceChat::openMenu));

    public VoiceChat() {
        super("Voice Chat", "Opens Simple Voice Chat and shows if this server supports it.", Category.MISC);
        setVisible(false);
    }

    public static boolean installed() { return FabricLoader.getInstance().isModLoaded("voicechat"); }

    /** Does the server we're on run Simple Voice Chat? */
    public static boolean serverHasVoice() {
        try { return installed() && ClientPlayNetworking.canSend(SVC_CHANNEL); } catch (Exception e) { return false; }
    }

    private static String statusLabel() {
        if (!installed()) return "Not installed";
        if (EvadeClient.mc().getNetworkHandler() == null) return "Ready";
        return serverHasVoice() ? "On this server" : "Not on this server";
    }

    /** Open Simple Voice Chat's menu by triggering its own "Voice Chat Menu" keybinding. */
    public static void openMenu() {
        var mc = EvadeClient.mc();
        KeyBinding menu = null;
        if (mc.options != null) for (KeyBinding k : mc.options.allKeys) if ("key.voice_chat".equals(k.getId())) menu = k;
        if (menu == null) {
            net.evadeclient.command.CommandManager.print("§7Voice chat isn't installed. Run the Evade Installer with §cVoice chat§7 on to add Simple Voice Chat.");
            return;
        }
        if (mc.currentScreen != null) mc.setScreen(null);
        KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(menu));
    }

    @Override
    public void toggle() { openMenu(); } // clicking the card opens the voice menu rather than toggling anything
}
