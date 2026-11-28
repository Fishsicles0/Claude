package net.evadeclient.gui;

import net.evadeclient.api.ApiPrompts;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

/**
 * A server's prompt (Evade Server API): a text box ({@code prompt.input}) or "this server wants to send you to ..."
 * ({@code prompt.transfer}). Always says it comes from the server, can always be cancelled (Escape too), and a
 * transfer only connects on Join.
 */
public final class ServerPromptScreen extends EvadeDialog {
    private static final String FIELD = "answer";
    private final ApiPrompts.Prompt prompt;
    private boolean answered;

    public ServerPromptScreen(ApiPrompts.Prompt prompt) {
        super(prompt.transfer() ? "Server transfer" : "Server prompt", null);
        this.prompt = prompt;
    }

    @Override protected int prefWidth() { return 420; }
    @Override protected int prefHeight() { return prompt.transfer() ? 200 : 196; }
    @Override protected String titleIcon() { return "server"; }

    @Override
    protected String heading() {
        if (prompt.transfer()) return prompt.title().isEmpty() ? "Switch servers?" : "Switch to " + prompt.title() + "?";
        return prompt.title().isEmpty() ? "The server asks" : prompt.title();
    }

    @Override
    protected void init() {
        frame();
        if (prompt.transfer()) {
            action("Stay here", Button.Variant.GHOST, () -> answer(null, false));
            action("Join", Button.Variant.PRIMARY, () -> answer(null, true));
        } else {
            action("Cancel", Button.Variant.GHOST, () -> answer(null, false));
            action("Send", Button.Variant.PRIMARY, () -> answer(SearchField.text(kit, FIELD), true));
            kit.focus(FIELD, false);
        }
    }

    private void answer(String value, boolean yes) {
        if (answered) return;
        answered = true;
        if (client != null) client.setScreen(null);
        ApiPrompts.answered(prompt, prompt.transfer() ? null : (yes ? value : null), yes);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (!prompt.transfer() && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            answer(SearchField.text(kit, FIELD), true);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() { answer(null, false); }

    @Override
    public void removed() {
        // Closed some other way (a new screen, leaving the server): counts as cancelled.
        if (!answered) {
            answered = true;
            ApiPrompts.answered(prompt, null, false);
        }
    }

    @Override
    protected void renderBody(DrawContext ctx, int mx, int my) {
        int y = by;
        if (prompt.transfer()) {
            y = para("This server wants to send you to " + prompt.address() + ".", bx, y, bw, Tokens.N950, Role.BODY);
            if (!prompt.text().isEmpty()) y = para(prompt.text(), bx, y + 4, bw, Tokens.N800, Role.BODY);
            note("info", Tokens.INFO, "You'll leave this server. Nothing happens unless you click Join.", y + 8);
        } else {
            y = para(prompt.text().isEmpty() ? "Type your answer." : prompt.text(), bx, y, bw, Tokens.N800, Role.BODY);
            y += 8;
            String placeholder = prompt.placeholder().isEmpty() ? "Your answer" : prompt.placeholder();
            String v = field(FIELD, bx, y, bw, placeholder, false, Math.max(1, prompt.max()));
            String count = v.length() + " / " + prompt.max();
            y += SearchField.H + 6;
            kit.text(count, bx + bw - width(count), y, Tokens.N600, Role.SMALL);
            note("server", Tokens.N600, "Sent only to this server. Cancel to skip.", y);
        }
    }

    private int width(String s) { return kit.width(s, Role.SMALL); }
}
