package net.evadeclient.clips.core;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which game is running, so clips get the right name and the replay buffer can start by itself.
 * <p>
 * Instead of relying only on a fixed list, it recognises <b>where</b> a program lives: anything inside a Steam,
 * Epic, Riot, Xbox/Game Pass, GOG, EA, Ubisoft, Battle.net, Rockstar or itch library is a game, named after its
 * install folder. That covers every game in those libraries (tens of thousands) without updates. A curated list
 * adds well-known games installed elsewhere and gives the proper name, and Minecraft is recognised from its Java
 * command line (vanilla, Fabric, Forge, Lunar-style launchers, Evade).
 */
public final class GameDetector {
    /** A detected game: display name, process id. */
    public record Game(String name, long pid) {}

    private static final Map<String, String> KNOWN = new HashMap<>();

    static {
        String[] pairs = {
                "cs2", "Counter-Strike 2", "csgo", "Counter-Strike 2", "valorant-win64-shipping", "Valorant",
                "leagueoflegends", "League of Legends", "league of legends", "League of Legends",
                "fortniteclient-win64-shipping", "Fortnite", "r5apex", "Apex Legends", "r5apex_dx12", "Apex Legends",
                "overwatch", "Overwatch 2", "dota2", "Dota 2", "rocketleague", "Rocket League",
                "cod", "Call of Duty", "codmw2", "Call of Duty", "blackops6", "Call of Duty: Black Ops 6",
                "modernwarfare", "Call of Duty", "rainbowsix", "Rainbow Six Siege", "rainbowsix_vulkan", "Rainbow Six Siege",
                "rainbowsixsiege", "Rainbow Six Siege", "pubg", "PUBG", "tslgame", "PUBG", "eft", "Escape from Tarkov",
                "escapefromtarkov", "Escape from Tarkov", "gta5", "Grand Theft Auto V", "gta5_enhanced", "Grand Theft Auto V",
                "rdr2", "Red Dead Redemption 2", "robloxplayerbeta", "Roblox", "eldenring", "Elden Ring",
                "destiny2", "Destiny 2", "warframe.x64", "Warframe", "rust", "Rust", "rustclient", "Rust",
                "deadbydaylight-win64-shipping", "Dead by Daylight", "thefinals", "The Finals", "marvel-win64-shipping", "Marvel Rivals",
                "deadlock", "Deadlock", "helldivers2", "Helldivers 2", "palworld-win64-shipping", "Palworld",
                "terraria", "Terraria", "stardew valley", "Stardew Valley", "rocketleague.exe", "Rocket League",
                "fallguys_client_game", "Fall Guys", "fallguys_client", "Fall Guys", "splitgate", "Splitgate",
                "battlefield2042", "Battlefield 2042", "bf2042", "Battlefield 2042", "bf1", "Battlefield 1", "bfv", "Battlefield V",
                "hunt", "Hunt: Showdown", "huntgame", "Hunt: Showdown", "paladins", "Paladins", "smite", "Smite",
                "halo infinite", "Halo Infinite", "haloinfinite", "Halo Infinite", "mcc-win64-shipping", "Halo: MCC",
                "forzahorizon5", "Forza Horizon 5", "forzahorizon4", "Forza Horizon 4", "fifa23", "EA Sports FC",
                "fc24", "EA Sports FC 24", "fc25", "EA Sports FC 25", "nba2k25", "NBA 2K25", "nba2k24", "NBA 2K24",
                "re4", "Resident Evil 4", "cyberpunk2077", "Cyberpunk 2077", "witcher3", "The Witcher 3",
                "bg3", "Baldur's Gate 3", "bg3_dx11", "Baldur's Gate 3", "starfield", "Starfield", "skyrimse", "Skyrim",
                "fallout4", "Fallout 4", "sekiro", "Sekiro", "darksoulsiii", "Dark Souls III", "monsterhunterwilds", "Monster Hunter Wilds",
                "monsterhunterworld", "Monster Hunter: World", "ffxiv_dx11", "Final Fantasy XIV", "wow", "World of Warcraft",
                "wowclassic", "WoW Classic", "diablo iv", "Diablo IV", "hearthstone", "Hearthstone", "sc2", "StarCraft II",
                "lostark", "Lost Ark", "pathofexile", "Path of Exile", "pathofexile_x64", "Path of Exile", "pathofexile2", "Path of Exile 2",
                "genshinimpact", "Genshin Impact", "starrail", "Honkai: Star Rail", "zenlesszonezero", "Zenless Zone Zero",
                "osu!", "osu!", "beatsaber", "Beat Saber", "vrchat", "VRChat", "among us", "Among Us",
                "phasmophobia", "Phasmophobia", "lethal company", "Lethal Company", "valheim", "Valheim", "7daystodie", "7 Days to Die",
                "arma3", "Arma 3", "arma3_x64", "Arma 3", "dayz_x64", "DayZ", "squad", "Squad", "squadgame", "Squad",
                "insurgencyclient-win64-shipping", "Insurgency: Sandstorm", "readyornot", "Ready or Not",
                "brawlhalla", "Brawlhalla", "multiversus", "MultiVersus", "streetfighter6", "Street Fighter 6",
                "tekken8", "Tekken 8", "mk12", "Mortal Kombat 1", "rivals2", "Rivals of Aether II",
                "tf2", "Team Fortress 2", "hl2", "Half-Life 2", "left4dead2", "Left 4 Dead 2", "portal2", "Portal 2",
                "deeprockgalactic", "Deep Rock Galactic", "fsd-win64-shipping", "Deep Rock Galactic", "seaofthieves", "Sea of Thieves",
                "mordhau", "Mordhau", "chivalry2", "Chivalry 2", "for honor", "For Honor", "forhonor", "For Honor",
                "warthunder", "War Thunder", "aces", "War Thunder", "worldoftanks", "World of Tanks", "wotblitz", "WoT Blitz",
                "crossout", "Crossout", "xdefiant", "XDefiant", "spectre", "Spectre Divide", "bloodstrike", "Blood Strike",
                "minecraft.windows", "Minecraft Bedrock", "minecraft", "Minecraft", "geometrydash", "Geometry Dash",
                "hollow_knight", "Hollow Knight", "hollowknight", "Hollow Knight", "celeste", "Celeste", "cuphead", "Cuphead",
                "dontstarve_steam", "Don't Starve", "factorio", "Factorio", "satisfactory", "Satisfactory", "subnautica", "Subnautica",
                "nms", "No Man's Sky", "eurotrucks2", "Euro Truck Simulator 2", "f1_24", "F1 24", "f1_23", "F1 23", "acc", "Assetto Corsa Competizione",
                "iracingsim64dx11", "iRacing", "gt7", "Gran Turismo 7", "rocketleague_dx11", "Rocket League",
        };
        for (int i = 0; i + 1 < pairs.length; i += 2) KNOWN.put(pairs[i], pairs[i + 1]);
    }

    /** Game libraries: the folder right after the match is the game. */
    private static final List<Pattern> LIBRARIES = List.of(
            Pattern.compile("(?i)[/\\\\]steamapps[/\\\\]common[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]Epic Games[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]Riot Games[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]XboxGames[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]WindowsApps[/\\\\]([^/\\\\_]+)_"),
            Pattern.compile("(?i)[/\\\\]GOG Galaxy[/\\\\]Games[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]GOG Games[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]EA Games[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]Origin Games[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]Ubisoft Game Launcher[/\\\\]games[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]Rockstar Games[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]itch[/\\\\]apps[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]Program Files \\(x86\\)[/\\\\](Overwatch|Diablo IV|World of Warcraft|StarCraft II|Hearthstone|Call of Duty)[/\\\\]"),
            Pattern.compile("(?i)[/\\\\]Lutris[/\\\\]games?[/\\\\]([^/\\\\]+)"),
            Pattern.compile("(?i)[/\\\\]Heroic[/\\\\]([^/\\\\]+)"));

    /** Things that live in game libraries but aren't the game. */
    private static final Set<String> NOT_GAMES = Set.of("steam", "steamwebhelper", "steamservice", "steamerrorreporter",
            "epicgameslauncher", "epicwebhelper", "epiconlinservices", "riotclientservices", "riotclientux", "riotclientcrashhandler",
            "unitycrashhandler64", "unitycrashhandler32", "crashreportclient", "crashpad_handler", "easyanticheat", "easyanticheat_eos",
            "beservice", "battleye", "vanguard", "vgc", "vgtray", "eadesktop", "eabackgroundservice", "origin", "upc", "uplaywebcore",
            "galaxyclient", "battle.net", "agent", "launcher", "updater", "setup", "unins000", "dxsetup", "vc_redist.x64",
            "vcredist_x64", "dotnetfx", "uninstall", "proton", "wineserver", "reaper", "pressure-vessel-wrap", "steam-runtime-launcher-service");

    private GameDetector() {}

    /** The game a process belongs to, or null. {@code path} is the executable, {@code cmdline} its full command line. */
    public static String identify(String path, String cmdline) {
        if (path == null) return null;
        String file = path.replace('\\', '/');
        file = file.substring(file.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String exe = file.endsWith(".exe") ? file.substring(0, file.length() - 4) : file;
        if (NOT_GAMES.contains(exe) || exe.contains("crash") || exe.contains("launcher") || exe.contains("updater")
                || exe.contains("anticheat") || exe.contains("helper")) return null;
        String cl = cmdline == null ? "" : cmdline.toLowerCase(Locale.ROOT);
        // Minecraft Java runs as java/javaw.
        if (exe.equals("java") || exe.equals("javaw")) {
            if (cl.contains("net.minecraft") || cl.contains(".minecraft") || cl.contains("net.fabricmc") || cl.contains("cpw.mods")
                    || cl.contains("minecraft") || cl.contains("evadeclient")) return "Minecraft";
            return null;
        }
        String known = KNOWN.get(exe);
        if (known != null) return known;
        for (Pattern p : LIBRARIES) {
            Matcher m = p.matcher(path + "/");
            if (m.find()) return m.groupCount() >= 1 && m.group(1) != null ? tidy(m.group(1)) : null;
        }
        return null;
    }

    /** "CounterStrikeGlobalOffensive" style folder names stay as they are; underscores and trailing junk go. */
    static String tidy(String folder) {
        String n = folder.replace('_', ' ').replaceAll("(?i)\\s*(win64|x64|-shipping)$", "").trim();
        return n.isEmpty() ? folder : n;
    }

    /** Running games, most recently started first. */
    public static Optional<Game> detect() {
        Game best = null;
        java.time.Instant newest = java.time.Instant.MIN;
        long self = ProcessHandle.current().pid();
        for (ProcessHandle ph : (Iterable<ProcessHandle>) ProcessHandle.allProcesses()::iterator) {
            if (ph.pid() == self) continue;
            ProcessHandle.Info info = ph.info();
            Optional<String> cmd = info.command();
            if (cmd.isEmpty()) continue;
            String name = identify(cmd.get(), info.commandLine().orElse(""));
            if (name == null) continue;
            java.time.Instant started = info.startInstant().orElse(java.time.Instant.EPOCH);
            if (best == null || started.isAfter(newest)) { best = new Game(name, ph.pid()); newest = started; }
        }
        return Optional.ofNullable(best);
    }
}
