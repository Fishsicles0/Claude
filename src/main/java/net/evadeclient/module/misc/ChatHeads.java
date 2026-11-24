package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.chat.ChatLines;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * The sender's face in front of their chat lines. The sender is the account that signed the message when the server
 * says so, otherwise the name at the start of the line ("&lt;Steve&gt;", "[VIP] Steve:", "Party &gt; Steve:") if that
 * player is in the tab list. Works with BetterChat's timestamps and stacking and with Friends' message lines. Only how
 * chat is drawn changes.
 *
 * <p>Hooks: ChatHudMixin (raw text), ChatHudV25Mixin (space and records), ChatLineDrawMixin (the face).
 */
public class ChatHeads extends Module {
    private final NumberSetting size = add(new NumberSetting("Size", 8, 6, 9, 1)
            .describe("How big the faces are, in pixels. Chat lines are 9 high."));
    private final BooleanSetting onlyPlayers = add(new BooleanSetting("Only Players", true)
            .describe("Only lines from a player are moved over for a face. Off: every line is, so chat stays lined up."));

    public ChatHeads() {
        super("Chat Heads", "The sender's face in front of their chat messages.", Category.MISC);
        // Signed chat names its sender: remember it for the line that is added right after this event.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, receivedAt) ->
                ChatLines.signedSender(sender == null ? null : sender.id(), message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> ChatLines.signedSender(null, null));
    }

    public static ChatHeads active() {
        if (EvadeClient.modules == null) return null;
        ChatHeads h = EvadeClient.modules.get(ChatHeads.class);
        return h != null && h.isEnabled() ? h : null;
    }

    public int size() { return size.getInt(); }

    public boolean onlyPlayers() { return onlyPlayers.get(); }

    /** Spaces (4 pixels each in the chat font) that leave room for a face and a small gap. */
    public String spacer() { return size.getInt() <= 6 ? "  " : "   "; }
}
