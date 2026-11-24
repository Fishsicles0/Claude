package net.evadeclient.gui;

import net.evadeclient.api.ApiPrompts;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/**
 * A server's prompt (Evade Server API): a text box ({@code prompt.input}) or "this server wants to send you to ..."
 * ({@code prompt.transfer}). Always says it comes from the server, can always be cancelled (Escape too), and a
 * transfer only connects on Join.
 */
public final class ServerPromptScreen extends EvadeDialog {
    private final ApiPrompts.Prompt prompt;
    private TextFieldWidget field;
    private boolean answered;

    public ServerPromptScreen(ApiPrompts.Prompt prompt) {
        super(prompt.transfer() ? "Server transfer" : "Server prompt", null);
        this.prompt = prompt;
    }

    @Override protected int prefHeight() { return 150; }

    @Override
    protected void init() {
        frame();
        int by = py + ph - 30, bw = (pw - 28 - 4) / 2;
        if (prompt.transfer()) {
            button("Join", px + 14, by, bw, b -> answer(null, true));
            button("Stay here", px + 14 + bw + 4, by, bw, b -> answer(null, false));
        } else {
            String text = field == null ? "" : field.getText();
            field = new TextFieldWidget(textRenderer, px + 14, py + 78, pw - 28, 18, Text.literal(prompt.title()));
            field.setMaxLength(prompt.max());
            field.setText(text);
            if (!prompt.placeholder().isEmpty()) field.setPlaceholder(Text.literal(prompt.placeholder()).formatted(Formatting.DARK_GRAY));
            addDrawableChild(field);
            setInitialFocus(field);
            button("Send", px + 14, by, bw, b -> answer(field.getText(), true));
            button("Cancel", px + 14 + bw + 4, by, bw, b -> answer(null, false));
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
            answer(field.getText(), true);
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
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        if (prompt.transfer()) {
            title(ctx, prompt.title().isEmpty() ? "Switch servers?" : "Switch to " + prompt.title() + "?");
            int y = para(ctx, "This server wants to send you to " + prompt.address() + ".", py + 34, Theme.TEXT);
            if (!prompt.text().isEmpty()) y = para(ctx, prompt.text(), y + 4, Theme.TEXT_DIM);
            para(ctx, "You'll leave this server. Nothing happens unless you click Join.", y + 4, Theme.TEXT_FAINT);
        } else {
            title(ctx, prompt.title().isEmpty() ? "The server asks" : prompt.title());
            int y = para(ctx, prompt.text().isEmpty() ? "Type your answer." : prompt.text(), py + 34, Theme.TEXT_DIM);
            line(ctx, "Sent only to this server. Cancel to skip.", px + 14, Math.max(y + 2, py + 100), Theme.TEXT_FAINT);
        }
    }
}
