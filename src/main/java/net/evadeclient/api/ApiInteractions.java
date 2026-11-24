package net.evadeclient.api;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Server entries on player cards ({@code interaction.add / remove / clear}): a label and one of the server's own
 * commands, e.g. "Trade" running {@code /trade {player}}. They show on the card of any player in the tab list; one click
 * sends that one command, as if you had typed it. Nothing runs without the click, and a card never sends more than one.
 */
public final class ApiInteractions {
    /** One entry: {@code command} has no leading slash and may contain {@code {player}}. */
    public record Entry(String id, String label, String command) {
        /** The command for {@code player} (a Minecraft name), without the slash. */
        public String commandFor(String player) { return command.replace("{player}", player); }
    }

    static final int MAX = 8, MAX_COMMAND = 128;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Map<String, Entry> entries = new LinkedHashMap<>();

    private ApiInteractions() {}

    static boolean handle(String type, JsonObject o) {
        switch (type) {
            case "interaction.add" -> {
                String id = ApiJson.id(o);
                String label = ApiJson.str(o, "label", "", 32);
                String cmd = ApiJson.str(o, "command", "", MAX_COMMAND + 1);
                if (cmd.startsWith("/")) cmd = cmd.substring(1).strip();
                if (id == null || label.isEmpty() || cmd.isEmpty() || cmd.length() > MAX_COMMAND) return false;
                // One plain command: no section signs or control characters.
                for (int i = 0; i < cmd.length(); i++) if (cmd.charAt(i) < 32 || cmd.charAt(i) == '§' || cmd.charAt(i) == 127) return false;
                if (!entries.containsKey(id) && entries.size() >= MAX) return false;
                entries.put(id, new Entry(id, label, cmd));
                return true;
            }
            case "interaction.remove" -> {
                String id = ApiJson.id(o);
                return id != null && entries.remove(id) != null;
            }
            case "interaction.clear" -> {
                entries.clear();
                return true;
            }
            default -> { return false; }
        }
    }

    /** The server's entries, in the order it added them. */
    public static List<Entry> entries() { return new ArrayList<>(entries.values()); }

    /** Runs {@code e} for {@code player} (one command, sent like a typed one). Render thread. Returns whether it was sent. */
    public static boolean run(Entry e, String player) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (player == null || !NAME.matcher(player).matches() || mc.getNetworkHandler() == null) return false;
        String cmd = e.commandFor(player);
        if (cmd.length() > 256) return false;
        mc.getNetworkHandler().sendChatCommand(cmd);
        return true;
    }

    static void clear() { entries.clear(); }
}
