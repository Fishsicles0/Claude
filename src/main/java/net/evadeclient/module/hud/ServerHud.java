package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;

import java.util.ArrayList;
import java.util.List;

public class ServerHud extends TextHudModule {
    public ServerHud() { super("Server Address", "The server you're playing on.", 4, 216); }

    @Override
    protected List<String> lines(boolean preview) {
        var entry = mc.getCurrentServerEntry();
        return new ArrayList<>(List.of(entry == null ? "Singleplayer" : entry.address));
    }
}
