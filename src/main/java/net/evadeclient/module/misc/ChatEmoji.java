package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.chat.Emoji;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.StringSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Emoji in chat: type a shortcode such as {@code :skull:} and it turns into 💀 as you finish it, or pick one from the
 * ☺ button at the end of the chat box (your recent ones first). Plain Unicode the game's own font draws, so everyone
 * sees them. Only your chat messages: commands (/ and .) are never touched, and exactly what you typed is sent, with
 * the codes swapped for their emoji and nothing else changed.
 *
 * <p>Hooks: ChatScreenV25Mixin (live conversion, conversion on send, the picker).
 */
public class ChatEmoji extends Module {
    private static final int RECENTS = 9;

    private final BooleanSetting convert = add(new BooleanSetting("Convert Shortcodes", true)
            .describe("Turn :skull:, :fire:, :heart: and other codes into their emoji as you type them and when you send."));
    private final BooleanSetting picker = add(new BooleanSetting("Picker Button", true)
            .describe("A smiley button at the end of the chat box that opens a grid of emoji to click."));
    private final StringSetting recent = add(new StringSetting("Recent Emoji", "", "").maxLength(200).hidden());

    public ChatEmoji() {
        super("Emoji", "Type :skull: in chat for a skull emoji, or pick emoji from a grid. Plain Unicode everyone sees.", Category.MISC);
    }

    public static ChatEmoji active() {
        if (EvadeClient.modules == null) return null;
        ChatEmoji e = EvadeClient.modules.get(ChatEmoji.class);
        return e != null && e.isEnabled() ? e : null;
    }

    /** Whether chat box text is a message (not a / or . command), the only kind that is ever converted. */
    public static boolean isMessage(String text) {
        return text != null && !text.startsWith("/") && !text.startsWith(".");
    }

    /** The message to send: shortcodes converted when on, anything else exactly as typed. */
    public static String onSend(String text) {
        ChatEmoji e = active();
        if (e == null || !e.convert.get() || !isMessage(text)) return text;
        return Emoji.convert(text);
    }

    public boolean converts() { return convert.get(); }

    public boolean showsPicker() { return picker.get(); }

    /** Your most recently picked emoji, newest first. */
    public List<String> recents() {
        List<String> out = new ArrayList<>();
        String raw = recent.get() == null ? "" : recent.get();
        for (String code : raw.split(",")) {
            String e = Emoji.forCode(code.strip());
            if (e != null && !out.contains(e)) out.add(e);
        }
        return out;
    }

    /** Remembers {@code emoji} as just used. */
    public void used(String emoji) {
        String code = Emoji.codeOf(emoji);
        if (code == null) return;
        List<String> codes = new ArrayList<>();
        codes.add(code);
        for (String e : recents()) {
            String c = Emoji.codeOf(e);
            if (c != null && !codes.contains(c) && codes.size() < RECENTS) codes.add(c);
        }
        recent.set(String.join(",", codes));
        if (EvadeClient.config != null) EvadeClient.config.save();
    }
}
