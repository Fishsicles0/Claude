package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;

import java.util.ArrayList;
import java.util.List;

/** The server you're playing on: its address (without the port by default), its server-list name, or the Realm. */
public class ServerHud extends TextHudModule {
    private final BooleanSetting hidePort = add(new BooleanSetting("Hide Port", true)
            .describe("Show play.example.net instead of play.example.net:25565."));
    private final BooleanSetting hideAddress = add(new BooleanSetting("Hide Address", false)
            .describe("Show the server's name from your server list instead of its address."));

    public ServerHud() { super("Server Address", "The server you're playing on.", 4, 200); }

    @Override
    protected List<String> lines(boolean preview) {
        var entry = mc.getCurrentServerEntry();
        if (entry == null) return new ArrayList<>(List.of("Singleplayer"));
        String s;
        if (entry.isRealm()) s = entry.name;
        else if (hideAddress.get() && entry.name != null && !entry.name.isBlank()) s = entry.name;
        else s = hidePort.get() ? withoutPort(entry.address) : entry.address;
        return new ArrayList<>(List.of(s == null || s.isBlank() ? "Multiplayer" : s.strip()));
    }

    /**
     * {@code address} without a trailing ":port". A bracketed IPv6 address keeps its brackets ("[::1]:25565" is
     * "[::1]"); a bare IPv6 address (several colons, no brackets) has no port to strip.
     */
    static String withoutPort(String address) {
        if (address == null) return "";
        String a = address.strip();
        int colon = a.lastIndexOf(':');
        if (colon <= 0 || colon == a.length() - 1) return a;
        for (int i = colon + 1; i < a.length(); i++) if (!Character.isDigit(a.charAt(i))) return a;
        String host = a.substring(0, colon);
        if (host.startsWith("[")) return host.endsWith("]") ? host : a;
        return host.indexOf(':') >= 0 ? a : host;
    }
}
