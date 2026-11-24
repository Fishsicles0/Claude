package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.QuickPlayScreen;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Hypixel;
import net.evadeclient.util.Notifications;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Quick Play: a picker of Hypixel games and modes that sends the matching {@code /play}, {@code /lobby} or
 * {@code /skyblock} command when you click one, exactly as if you had typed it. One command per click, with a short
 * cooldown so a double click never queues twice. Only commands from the built-in {@link #GAMES} list are ever sent
 * (saved favourites and recents are matched against it), and only while connected to Hypixel.
 */
public class QuickPlay extends Module {
    /** One clickable entry: what it is called and the command (without the slash) it sends. */
    public record Mode(String game, String label, String command) {
        /** "BedWars Solo", for the favourites and recent rows. */
        public String fullName() { return label.startsWith(game) ? label : game + " " + label; }
    }

    /** A game: its name, a short tag for its icon and its modes (the lobby, where there is one, last). */
    public record Game(String name, String tag, int color, List<Mode> modes) {}

    /** Every game and mode, in menu order. The /play ids are Hypixel's documented names. */
    public static final List<Game> GAMES = List.of(
            game("BedWars", "BW", 0xFFE5484D,
                    "Solo", "play bedwars_eight_one",
                    "Doubles", "play bedwars_eight_two",
                    "3v3v3v3", "play bedwars_four_three",
                    "4v4v4v4", "play bedwars_four_four",
                    "4v4", "play bedwars_two_four",
                    "Lobby", "lobby bedwars"),
            game("SkyWars", "SW", 0xFF4FB3FF,
                    "Solo Normal", "play solo_normal",
                    "Solo Insane", "play solo_insane",
                    "Doubles Normal", "play teams_normal",
                    "Doubles Insane", "play teams_insane",
                    "Lobby", "lobby skywars"),
            game("Duels", "DU", 0xFFFFB020,
                    "Classic", "play duels_classic_duel",
                    "Sumo", "play duels_sumo_duel",
                    "Bridge", "play duels_bridge_duel",
                    "UHC", "play duels_uhc_duel",
                    "OP", "play duels_op_duel",
                    "SkyWars", "play duels_sw_duel",
                    "Bow", "play duels_bow_duel",
                    "Combo", "play duels_combo_duel",
                    "NoDebuff", "play duels_potion_duel",
                    "Boxing", "play duels_boxing_duel",
                    "Blitz", "play duels_blitz_duel",
                    "Lobby", "lobby duels"),
            game("Murder Mystery", "MM", 0xFFB46CFF,
                    "Classic", "play murder_classic",
                    "Double Up", "play murder_double_up",
                    "Assassins", "play murder_assassins",
                    "Infection", "play murder_infection",
                    "Lobby", "lobby murder_mystery"),
            game("Build Battle", "BB", 0xFF3DDC84,
                    "Solo", "play build_battle_solo_normal",
                    "Teams", "play build_battle_teams_normal",
                    "Pro", "play build_battle_solo_pro",
                    "Guess The Build", "play build_battle_guess_the_build",
                    "Lobby", "lobby build_battle"),
            game("Arcade", "AR", 0xFFFF7AB6,
                    "Party Games", "play arcade_party_games_1",
                    "Hole in the Wall", "play arcade_hole_in_the_wall",
                    "Mini Walls", "play arcade_mini_walls",
                    "Farm Hunt", "play arcade_farm_hunt",
                    "Pixel Party", "play arcade_pixel_party",
                    "Zombies: Dead End", "play arcade_zombies_dead_end",
                    "Lobby", "lobby arcade"),
            game("The Pit", "PT", 0xFFFFD84D,
                    "Join The Pit", "play pit"),
            game("SkyBlock", "SB", 0xFF5EE6E6,
                    "Join SkyBlock", "skyblock"),
            game("Housing", "HO", 0xFFFF9E57,
                    "Lobby", "lobby housing"),
            game("Main Lobby", "ML", 0xFFA8A8B2,
                    "Main Lobby", "lobby main"));

    private static final int MAX_FAVOURITES = 12, MAX_RECENT = 6;
    /** Shortest time between two commands sent from Quick Play. */
    private static final long COOLDOWN_MS = 1500;

    private final KeySetting openKey = add(new KeySetting("Open Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Opens the Quick Play picker in game. Unbound by default."));
    private final BooleanSetting closeOnPick = add(new BooleanSetting("Close On Pick", true)
            .describe("Close the picker after you click a mode. Off keeps it open (one command per click either way)."));
    private final BooleanSetting showRecent = add(new BooleanSetting("Show Recent", true)
            .describe("Show the modes you played last as a row at the top of the picker."));
    /** Pinned modes, as commands split by '|' (saved state, edited from the picker). */
    private final StringSetting favourites = add(new StringSetting("Favourites", "", "").hidden());
    /** Last modes played, newest first, as commands split by '|' (saved state). */
    private final StringSetting recent = add(new StringSetting("Recent", "", "").hidden());
    /** The game whose modes the picker showed last (saved state). */
    private final StringSetting lastGame = add(new StringSetting("Last Game", "BedWars", "").hidden());

    private boolean keyWasDown;
    private long lastSent;

    public QuickPlay() {
        super("Quick Play", "A picker for Hypixel games and modes: one click sends the /play or /lobby command.", Category.SERVER);
        setEnabled(true);
    }

    private static Game game(String name, String tag, int color, String... labelsAndCommands) {
        List<Mode> modes = new ArrayList<>();
        for (int i = 0; i + 1 < labelsAndCommands.length; i += 2)
            modes.add(new Mode(name, labelsAndCommands[i], labelsAndCommands[i + 1]));
        return new Game(name, tag, color, List.copyOf(modes));
    }

    public static QuickPlay instance() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(QuickPlay.class);
    }

    // ------------------------------------------------------------------ opening

    /** Opens the picker over the current screen (or over the game); Esc goes back there. For menu buttons. */
    public static void open() {
        MinecraftClient mc = MinecraftClient.getInstance();
        open(mc.currentScreen);
    }

    /** Opens the picker; Esc goes back to {@code parent} (null: the game). */
    public static void open(Screen parent) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new QuickPlayScreen(parent));
    }

    @Override
    public void onTick() {
        boolean down = openKey.isDown() && mc.currentScreen == null;
        if (down && !keyWasDown) open(null);
        keyWasDown = down;
    }

    @Override
    public List<String> commandAliases() { return List.of("qp", "quickplay"); }

    @Override
    public void command(String[] args) {
        // Opened after chat has closed, or the chat screen closing would close the picker straight away.
        MinecraftClient.getInstance().send(() -> open(null));
    }

    @Override
    public List<String> commandHelp() { return List.of("§d.qp§7 - open Quick Play (Hypixel games and modes)"); }

    // ------------------------------------------------------------------ playing

    /** Whether the picker can send commands right now (connected to Hypixel). */
    public static boolean available() { return Hypixel.connected(); }

    /**
     * Sends {@code mode}'s command, if it is one of {@link #GAMES}, you are on Hypixel and the cooldown has passed.
     * Returns whether it was sent. Elsewhere it only shows a message.
     */
    public boolean play(Mode mode) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mode == null || find(mode.command()) == null) return false;
        if (!available()) {
            Notifications.push("Quick Play", "Only works on Hypixel", false);
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSent < COOLDOWN_MS) return false;
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        lastSent = now;
        mc.getNetworkHandler().sendChatCommand(mode.command());
        remember(mode);
        return true;
    }

    public boolean closeOnPick() { return closeOnPick.get(); }
    public boolean showRecent() { return showRecent.get(); }

    public String lastGame() { return lastGame.get(); }

    public void setLastGame(String name) {
        if (name == null || name.equals(lastGame.get())) return;
        lastGame.set(name);
        save();
    }

    /** The mode that sends {@code command}, or null when it isn't one of {@link #GAMES}. */
    public static Mode find(String command) {
        if (command == null) return null;
        String c = command.strip().toLowerCase(Locale.ROOT);
        for (Game g : GAMES) for (Mode m : g.modes()) if (m.command().equals(c)) return m;
        return null;
    }

    /** The game called {@code name}, or the first game. */
    public static Game gameNamed(String name) {
        for (Game g : GAMES) if (g.name().equalsIgnoreCase(name)) return g;
        return GAMES.getFirst();
    }

    // ------------------------------------------------------------------ favourites and recent

    public List<Mode> favourites() { return modes(favourites.get()); }
    public List<Mode> recent() { return modes(recent.get()); }

    public boolean isFavourite(Mode m) { return m != null && favourites().contains(m); }

    /** Pins or unpins {@code m}; returns whether it is now a favourite. */
    public boolean toggleFavourite(Mode m) {
        if (m == null || find(m.command()) == null) return false;
        List<Mode> list = new ArrayList<>(favourites());
        boolean now;
        if (list.remove(m)) now = false;
        else if (list.size() >= MAX_FAVOURITES) {
            Notifications.push("Quick Play", "Up to " + MAX_FAVOURITES + " favourites", false);
            return false;
        } else {
            list.add(m);
            now = true;
        }
        favourites.set(join(list));
        save();
        return now;
    }

    private void remember(Mode m) {
        List<Mode> list = new ArrayList<>(recent());
        list.remove(m);
        list.addFirst(m);
        while (list.size() > MAX_RECENT) list.removeLast();
        recent.set(join(list));
        save();
    }

    /** Modes in a saved '|' list that are still in {@link #GAMES}, without duplicates. */
    private static List<Mode> modes(String saved) {
        Map<String, Mode> out = new LinkedHashMap<>();
        if (saved != null) for (String part : saved.split("\\|")) {
            Mode m = find(part);
            if (m != null) out.putIfAbsent(m.command(), m);
        }
        return List.copyOf(out.values());
    }

    private static String join(List<Mode> modes) {
        StringBuilder sb = new StringBuilder();
        for (Mode m : modes) {
            if (!sb.isEmpty()) sb.append('|');
            sb.append(m.command());
        }
        return sb.toString();
    }

    private static void save() {
        if (EvadeClient.config != null) EvadeClient.config.save();
    }
}
