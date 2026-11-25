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
 * An Evade-styled main menu in place of the vanilla title screen: the Evade logo over a slowly turning panorama with
 * parallax and drifting embers, Singleplayer, Multiplayer, a one-click rejoin of the last server, Evade Clips, the
 * Evade menu, Settings and Quit (plus Mods when Mod Menu is installed), and on wide screens side cards with the account
 * and friends online, what's new, a turning Wardrobe preview and servers to continue on (with live status). Off: the
 * vanilla title screen, untouched.
 *
 * <p>The swap happens in {@code MinecraftClientTitleScreenMixin}: whenever the game opens a plain vanilla
 * {@link TitleScreen} (or closes a screen to "nothing" with no world loaded) while this module is on (and not in demo
 * mode), {@link net.evadeclient.gui.EvadeTitleScreen} opens instead. Subclasses of TitleScreen from other mods are
 * left alone.
 */
public class TitleScreenModule extends Module {
    public final BooleanSetting lastServerButton = add(new BooleanSetting("Last Server Button", true)
            .describe("A button that joins the last server you played on again, in one click."));
    public final net.evadeclient.setting.ModeSetting background = add(new net.evadeclient.setting.ModeSetting("Background", "Panorama",
            "Panorama", "Evade Animated", "Evade Grid", "Solid Color", "Custom Image")
            .describe("Behind the menu: the game's panorama, Evade's animated or plain backdrop, one colour, or your own picture."));
    public final net.evadeclient.setting.ColorSetting backgroundColor = add(new net.evadeclient.setting.ColorSetting("Background Color", 0xFF101015)
            .opaque().describe("The colour of Background: Solid Color."));
    /** The picture chosen for Custom Image (a file name in config/evade/backgrounds), "" for the first one there. */
    public final StringSetting imageFile = add(new StringSetting("Background Image File", "", "").maxLength(200).hidden());
    public final net.evadeclient.setting.StatusSetting image = add(new net.evadeclient.setting.StatusSetting("Background Image",
            this::imageStatus, "click: next picture").onClick(this::nextImage)
            .describe("The picture for Custom Image: a .png in config/evade/backgrounds. Click to switch to the next one."));
    public final net.evadeclient.setting.StatusSetting openFolder = add(new net.evadeclient.setting.StatusSetting("Backgrounds Folder",
            () -> "config/evade/backgrounds", "click: open folder").onClick(() ->
            net.minecraft.util.Util.getOperatingSystem().open(net.evadeclient.gui.title.TitleBackground.folder()))
            .describe("Opens the folder Custom Image reads .png pictures from."));
    public final BooleanSetting blur = add(new BooleanSetting("Blur Panorama", false)
            .describe("Softens the panorama with the game's menu blur (its strength is Menu Background Blur in Accessibility)."));
    public final BooleanSetting sidePanels = add(new BooleanSetting("Side Panels", true)
            .describe("Cards beside the buttons on wide screens: your account, servers to continue on and what's new in Evade."));
    public final BooleanSetting serverStatus = add(new BooleanSetting("Server Status", true)
            .describe("Pings the servers in Continue playing for players online and ping, like the server list does."));
    public final BooleanSetting showVersion = add(new BooleanSetting("Show Version", true)
            .describe("Minecraft and Evade versions in the bottom corner."));
    public final BooleanSetting embers = add(new BooleanSetting("Drifting Embers", true)
            .describe("Soft red embers drifting slowly up over the background."));
    public final BooleanSetting parallax = add(new BooleanSetting("Parallax", true)
            .describe("The panorama and embers shift gently with your mouse, for a sense of depth."));
    public final BooleanSetting cosmeticsPreview = add(new BooleanSetting("Wardrobe Preview", true)
            .describe("On wide screens, a small turning preview of you in your equipped cosmetics, with a Wardrobe button."));
    public final BooleanSetting friendsOnline = add(new BooleanSetting("Friends Online", true)
            .describe("Shows which of your Evade friends are online on the account card (when Friends is on)."));
    public final net.evadeclient.setting.ModeSetting effects = add(new net.evadeclient.setting.ModeSetting("Menu Effects", "Auto",
            "Auto", "Full", "Light")
            .describe("Light draws fewer layers, embers and a still preview for weaker graphics. Auto picks Light on built-in graphics."));
    public final BooleanSetting reduceMotion = add(new BooleanSetting("Reduce Motion", false)
            .describe("A still menu: no intro, no moving background, embers or parallax, and no turning preview."));
    /**
     * False only in configs saved before the Evade main menu became the default start screen (v2.4): those saved the
     * module as off without anyone choosing it, so it is switched on once (see {@link #onConfigLoaded}).
     */
    private final BooleanSetting defaultApplied = add(new BooleanSetting("Default Start Screen", true).hidden());
    /** "name|address" of the last multiplayer server joined (never shown). */
    public final StringSetting lastServer = add(new StringSetting("Last Server", "", "").maxLength(320).hidden());
    /** The last few servers joined, newest first: "name|address" entries joined by {@link #SEP} (never shown). */
    public final StringSetting recentServers = add(new StringSetting("Recent Servers", "", "").maxLength(1400).hidden());

    private static final String SEP = "\u00A6";
    private static final int RECENT_MAX = 4;

    private static TitleScreenModule INSTANCE;
    /** An old config turned the module off before it was the default: switch it on at the next screen change. */
    private static boolean switchOnPending;

    public TitleScreenModule() {
        super("Title Screen", "An Evade main menu with quick rejoin and your recent servers. Off: the vanilla title screen.", Category.MISC);
        INSTANCE = this;
        setEnabled(true); // the Evade main menu is the default start screen
        net.evadeclient.gui.title.TeaserFigure.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> remember(client)));
    }

    /** The Background mode in use, with Custom Image falling back to Evade Grid when no picture can be shown. */
    public String backgroundMode() { return background.get(); }

    private String imageStatus() {
        String n = net.evadeclient.gui.title.TitleBackground.resolve(imageFile.get());
        return n == null ? "No .png in the folder" : n;
    }

    /** Custom Image: step to the next picture in the folder (wrapping round). */
    public void nextImage() {
        var all = net.evadeclient.gui.title.TitleBackground.images();
        if (all.isEmpty()) {
            net.evadeclient.util.Notifications.push(getName(), "Put .png pictures in config/evade/backgrounds", false);
            return;
        }
        String cur = net.evadeclient.gui.title.TitleBackground.resolve(imageFile.get());
        int i = cur == null ? -1 : all.indexOf(cur);
        imageFile.set(all.get((i + 1) % all.size()));
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    /** Configs from before Background: "Panorama" off meant Evade's plain grid backdrop. */
    @Override
    public void onConfigLoaded(com.google.gson.JsonObject saved) {
        // Configs from before the Evade menu was the default start screen saved "off" for everyone; every build
        // since has the Background setting. The saved enabled state is applied after this, so the switch happens
        // at the next screen change (applyPending). A choice made since (with Background saved) is kept.
        if (saved != null && saved.size() > 0 && !saved.has(defaultApplied.getName()) && !saved.has("Background")) switchOnPending = true;
        if (saved == null || saved.has("Background") || !saved.has("Panorama")) return;
        var old = saved.get("Panorama");
        if (old.isJsonPrimitive() && old.getAsJsonPrimitive().isBoolean() && !old.getAsBoolean()) background.set("Evade Grid");
    }

    /** Works on the title screen, before any world. */
    @Override
    protected boolean alwaysRunHooks() { return true; }

    /**
     * Runs the one-time switch-on for configs from before the Evade menu was the default (see {@link #defaultApplied}).
     * Called on the render thread at each screen change, never while a config is being applied.
     */
    public static void applyPending() {
        TitleScreenModule m = INSTANCE;
        if (!switchOnPending || m == null) return;
        switchOnPending = false;
        m.defaultApplied.set(true);
        if (!m.isEnabled()) {
            EvadeClient.LOG.info("Title Screen: switched on (the Evade main menu is the default start screen since v2.4)");
            m.setEnabled(true);
            // It was off in that config (the old default, or your choice): say so once, with the way back.
            net.evadeclient.util.Notifications.push("Title Screen",
                    "Evade's main menu is now your start screen. Prefer the vanilla one? Turn off Title Screen in the Evade menu.",
                    net.evadeclient.util.Notifications.Level.INFO, 12_000);
        }
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    /** Test hook: behave as if an old config had just been loaded. */
    public static void simulateOldConfigForTest() { switchOnPending = true; }

    /** Whether menus should hold still: this module's Reduce Motion, or Client Settings' Animations off. */
    public static boolean reduceMotion() {
        TitleScreenModule m = INSTANCE;
        return m != null && m.reduceMotion.get();
    }

    /** Whether the menu should draw its light variant: Menu Effects Light, or Auto on built-in or software graphics. */
    public static boolean lightEffects() {
        TitleScreenModule m = INSTANCE;
        String e = m == null ? "Auto" : m.effects.get();
        if (e.equals("Light")) return true;
        if (e.equals("Full")) return false;
        var kind = gpuKind();
        return kind == net.evadeclient.util.GpuInfo.Kind.INTEGRATED || kind == net.evadeclient.util.GpuInfo.Kind.SOFTWARE;
    }

    private static net.evadeclient.util.GpuInfo.Kind kindCache;

    /** GpuInfo's kind, read once (it doesn't change while the game runs), except while a test forces a renderer. */
    private static net.evadeclient.util.GpuInfo.Kind gpuKind() {
        if (net.evadeclient.util.GpuInfo.forcedRenderer != null) return net.evadeclient.util.GpuInfo.kind();
        if (kindCache == null) kindCache = net.evadeclient.util.GpuInfo.kind();
        return kindCache;
    }

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
        applyPending();
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
        String value = name.replace("|", " ").replace(SEP, " ") + "|" + address;
        StringBuilder recent = new StringBuilder(value);
        int kept = 1;
        for (String[] r : recentServerEntries()) {
            if (kept >= RECENT_MAX) break;
            if (r[1].equalsIgnoreCase(address)) continue;
            recent.append(SEP).append(r[0].replace("|", " ").replace(SEP, " ")).append('|').append(r[1]);
            kept++;
        }
        if (value.equals(lastServer.get()) && recent.toString().equals(recentServers.get())) return;
        lastServer.set(value);
        recentServers.set(recent.toString());
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    /** The servers joined lately as {name, address}, newest first (the last server first even from older configs). */
    public java.util.List<String[]> recentServerEntries() {
        java.util.List<String[]> out = new java.util.ArrayList<>(RECENT_MAX);
        String[] last = lastServerEntry();
        if (last != null) out.add(last);
        String all = recentServers.get();
        if (all != null && !all.isEmpty()) {
            for (String part : all.split(SEP)) {
                String[] e = parse(part);
                if (e == null) continue;
                boolean dup = false;
                for (String[] o : out) if (o[1].equalsIgnoreCase(e[1])) { dup = true; break; }
                if (!dup) out.add(e);
            }
        }
        return out;
    }

    /** The last server as {name, address}, or null when there is none (or it can't be joined). */
    public String[] lastServerEntry() {
        return parse(lastServer.get());
    }

    private static String[] parse(String value) {
        int bar = value == null ? -1 : value.lastIndexOf('|');
        if (bar < 0) return null;
        String name = value.substring(0, bar).strip(), address = value.substring(bar + 1).strip();
        if (address.isEmpty() || !ServerAddress.isValid(address)) return null;
        return new String[]{name.isEmpty() ? address : name, address};
    }
}
