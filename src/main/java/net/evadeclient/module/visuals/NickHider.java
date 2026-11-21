package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.StringSetting;

/**
 * Hides your real username everywhere it's drawn on your screen (chat, tab list, nametags, scoreboard)
 * and can swap your skin for a default one. Local only: other players still see your real name.
 */
public class NickHider extends Module {
    public final StringSetting nick = add(new StringSetting("Nickname", "You", ".nick <name>"));
    public final BooleanSetting hideSkin = add(new BooleanSetting("Hide Skin", false));

    public NickHider() {
        super("Nick Hider", "Masks your username and skin on your screen for streaming.", Category.VISUALS);
    }

    public static NickHider active() {
        if (EvadeClient.modules == null) return null;
        NickHider n = EvadeClient.modules.get(NickHider.class);
        return n != null && n.isEnabled() ? n : null;
    }

    /** Swap the local username for the nickname in a piece of text, if it appears. */
    public static String replace(String text) {
        if (text == null || text.isEmpty()) return text;
        NickHider n = active();
        if (n == null) return text;
        var session = EvadeClient.mc().getSession();
        if (session == null) return text;
        String real = session.getUsername();
        if (real == null || real.isEmpty() || !text.contains(real)) return text;
        return text.replace(real, n.nick.get());
    }
}
