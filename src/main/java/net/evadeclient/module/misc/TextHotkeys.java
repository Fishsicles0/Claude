package net.evadeclient.module.misc;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Up to nine keys that each send a chat message or command you typed yourself ("/spawn", "gg"). One press sends it
 * once: holding the key never repeats it, and at most one message goes out every 1.5 seconds whichever keys you
 * press, so a hotkey can never flood a server. Nothing is ever sent on its own, only on your key press, and only
 * while no menu or chat is open.
 */
public class TextHotkeys extends Module {
    public static final int SLOTS = 9;
    /** At most one message per this many milliseconds, across every hotkey. */
    public static final long MIN_GAP_MS = 1500;
    /** Longest message a player can type in chat. */
    private static final int MAX_CHARS = 256;

    private final KeySetting[] keys = new KeySetting[SLOTS];
    private final StringSetting[] texts = new StringSetting[SLOTS];
    private final BooleanSetting confirm = add(new BooleanSetting("Confirmation", true)
            .describe("A small notification with what was sent, or why a press was skipped."));
    private final BooleanSetting addToHistory = add(new BooleanSetting("Add To Chat History", true)
            .describe("Put sent messages in your chat history, so the up arrow in chat brings them back."));

    private final boolean[] wasDown = new boolean[SLOTS];
    private long lastSent = Long.MIN_VALUE / 2;
    /** Test hook: what was last sent (with the slash for commands), or null. */
    public String lastSentForTest;

    public TextHotkeys() {
        super("Text Hotkeys", "Keys that send a chat message or command you typed, once per press.", Category.SERVER);
        for (int i = 0; i < SLOTS; i++) {
            int n = i + 1;
            keys[i] = add(new KeySetting("Hotkey " + n + " Key", GLFW.GLFW_KEY_UNKNOWN, true)
                    .describe("The key that sends Hotkey " + n + "'s text. None turns this hotkey off."));
            texts[i] = add(new StringSetting("Hotkey " + n + " Text", "", "message or /command").maxLength(MAX_CHARS)
                    .describe("What Hotkey " + n + " sends: a chat message (gg) or a command starting with / (/spawn)."));
        }
    }

    @Override
    protected void onEnable() {
        // Keys already held when the module turns on don't count as a press.
        for (int i = 0; i < SLOTS; i++) wasDown[i] = down(keys[i].get());
    }

    @Override
    public void onTick() { poll(); }

    /** Polled every frame too, so a tap shorter than a tick still counts (once). */
    @Override
    public void onRender(RenderContext ctx) { poll(); }

    private void poll() {
        boolean inGame = mc.currentScreen == null && mc.player != null;
        for (int i = 0; i < SLOTS; i++) {
            boolean d = down(keys[i].get());
            boolean pressed = d && !wasDown[i];
            wasDown[i] = d;
            // A key going down while a menu or chat is open is typing, not a hotkey.
            if (pressed && inGame) fire(i);
        }
    }

    private void fire(int slot) {
        String text = texts[slot].get() == null ? "" : texts[slot].get().strip();
        String label = "Hotkey " + (slot + 1);
        if (text.isEmpty() || text.equals("/")) {
            if (confirm.get()) Notifications.push(getName(), label + " has no text yet", false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSent < MIN_GAP_MS) {
            if (confirm.get()) Notifications.push(getName(), "Wait a moment before sending again", false);
            return;
        }
        ClientPlayNetworkHandler net = mc.getNetworkHandler();
        if (net == null) return;
        lastSent = now;
        lastSentForTest = text;
        if (addToHistory.get()) mc.inGameHud.getChatHud().addToMessageHistory(text);
        if (text.startsWith("/")) net.sendChatCommand(text.substring(1));
        else net.sendChatMessage(text);
        if (confirm.get()) Notifications.push(getName(), "Sent " + shorten(text), true);
    }

    private static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 39) + "…";
    }

    private boolean down(int code) {
        if (code == GLFW.GLFW_KEY_UNKNOWN || mc.getWindow() == null) return false;
        if (KeySetting.isMouse(code))
            return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), KeySetting.mouseButton(code)) == GLFW.GLFW_PRESS;
        return InputUtil.isKeyPressed(mc.getWindow(), code);
    }

    /** Test hook: acts as if hotkey {@code slot} (0-based) was pressed in game. */
    public void pressForTest(int slot) { fire(slot); }

    /** Test hook: the text setting of hotkey {@code slot} (0-based). */
    public StringSetting textSetting(int slot) { return texts[slot]; }
}
