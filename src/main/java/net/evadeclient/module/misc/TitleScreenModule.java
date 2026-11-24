package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.StringSetting;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * An Evade-styled main menu in place of the vanilla title screen: the Evade logo over the panorama, Singleplayer,
 * Multiplayer, a one-click rejoin of the last server, Evade Clips, Settings and Quit (plus Mods when Mod Menu is
 * installed). Off: the vanilla title screen, untouched.
 *
 * <p>The swap happens in {@code MinecraftClientTitleScreenMixin}: whenever the game opens a plain vanilla
 * {@link TitleScreen} while this module is on (and not in demo mode), {@link net.evadeclient.gui.EvadeTitleScreen}
 * opens instead. Subclasses of TitleScreen from other mods are left alone.
 */
public class TitleScreenModule extends Module {
    public final BooleanSetting lastServerButton = add(new BooleanSetting("Last Server Button", true)
            .describe("A button that joins the last server you played on again, in one click."));
    public final BooleanSetting panorama = add(new BooleanSetting("Panorama", true)
            .describe("The game's rotating panorama behind the menu. Off: a plain dark background."));
    public final BooleanSetting showVersion = add(new BooleanSetting("Show Version", true)
            .describe("Minecraft and Evade versions in the bottom corner."));
    /** "name|address" of the last multiplayer server joined (never shown). */
    public final StringSetting lastServer = add(new StringSetting("Last Server", "", "").maxLength(320).hidden());

    private static TitleScreenModule INSTANCE;

    public TitleScreenModule() {
        super("Title Screen", "An Evade main menu with quick rejoin. Off: the vanilla title screen.", Category.MISC);
        INSTANCE = this;
        setEnabled(true); // the Evade main menu is the default start screen
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> remember(client)));
    }

    /** Works on the title screen, before any world. */
    @Override
    protected boolean alwaysRunHooks() { return true; }

    /** The module, if it's on. */
    public static TitleScreenModule active() {
        TitleScreenModule m = INSTANCE;
        return m != null && m.isEnabled() ? m : null;
    }

    /**
     * MinecraftClientTitleScreenMixin: the screen to open instead of {@code screen}. Only an exact vanilla
     * TitleScreen is replaced, and never in demo mode (which keeps vanilla's demo buttons).
     */
    public static Screen replace(Screen screen) {
        if (screen == null || screen.getClass() != TitleScreen.class) return screen;
        if (active() == null) return screen;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isDemo()) return screen;
        return new net.evadeclient.gui.EvadeTitleScreen();
    }

    /** Remembers the multiplayer server just joined (its server list name and address). */
    private void remember(MinecraftClient client) {
        if (client.isInSingleplayer()) return;
        ServerInfo entry = client.getCurrentServerEntry();
        if (entry == null || entry.isRealm() || entry.address == null || entry.address.isBlank()) return;
        String address = entry.address.strip();
        String name = entry.name == null || entry.name.isBlank() ? address : entry.name.strip();
        String value = name.replace("|", " ") + "|" + address;
        if (value.equals(lastServer.get())) return;
        lastServer.set(value);
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    /** The last server as {name, address}, or null when there is none (or it can't be joined). */
    public String[] lastServerEntry() {
        String value = lastServer.get();
        int bar = value == null ? -1 : value.lastIndexOf('|');
        if (bar < 0) return null;
        String name = value.substring(0, bar).strip(), address = value.substring(bar + 1).strip();
        if (address.isEmpty() || !ServerAddress.isValid(address)) return null;
        return new String[]{name.isEmpty() ? address : name, address};
    }
}
