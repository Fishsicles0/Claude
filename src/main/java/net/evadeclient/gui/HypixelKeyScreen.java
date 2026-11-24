package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.HypixelApi;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Where you paste your personal Hypixel API key for Hypixel Stats. The field is masked (scrambled glyphs of the same
 * widths, so the cursor stays where it should) unless you choose Show, and
 * the key is handed straight to the module (which keeps it in the config only). A link opens developer.hypixel.net,
 * through the game's usual "open this link?" prompt.
 */
public final class HypixelKeyScreen extends Screen {
    private static final String DEV_SITE = "https://developer.hypixel.net/";

    private final Screen parent;
    private final String initial;
    private final Consumer<String> onSave;
    private TextFieldWidget field;
    private boolean reveal;
    private int px, py, pw, ph;

    public HypixelKeyScreen(Screen parent, String current, Consumer<String> onSave) {
        super(Text.literal("Hypixel API Key"));
        this.parent = parent;
        this.initial = current == null ? "" : current;
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        pw = Math.min(340, width - 20);
        ph = 150;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        String text = field == null ? initial : field.getText();
        field = new TextFieldWidget(textRenderer, px + 14, py + 62, pw - 28 - 54, 18, Text.literal("API key"));
        field.setMaxLength(64);
        field.setText(text);
        field.setPlaceholder(Text.literal("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        field.addFormatter((s, start) -> reveal ? null : OrderedText.styledForwardsVisitedString(s, Style.EMPTY.withObfuscated(true)));
        addDrawableChild(field);
        setInitialFocus(field);
        addDrawableChild(ButtonWidget.builder(Text.literal(reveal ? "Hide" : "Show"), b -> {
            reveal = !reveal;
            b.setMessage(Text.literal(reveal ? "Hide" : "Show"));
        }).dimensions(px + pw - 14 - 50, py + 61, 50, 20).build());

        int bw = (pw - 28 - 3 * 4) / 4, by = py + ph - 30;
        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> save()).dimensions(px + 14, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), b -> field.setText("")).dimensions(px + 14 + (bw + 4), by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Get a key"), b -> ConfirmLinkScreen.open(this, DEV_SITE))
                .dimensions(px + 14 + (bw + 4) * 2, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close()).dimensions(px + 14 + (bw + 4) * 3, by, bw, 20).build());
    }

    private void save() {
        String key = field.getText().strip();
        if (!key.isEmpty() && !HypixelApi.validKey(key)) {
            Notifications.push("Hypixel API Key", "That doesn't look like an API key", false);
            return;
        }
        onSave.accept(key);
        Notifications.push("Hypixel API Key", key.isEmpty() ? "Key removed" : "Key saved", true);
        close();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        if (s == null || s.blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xB0000000, 0xD0000000);
        Theme.shadow(ctx, Theme.SH_WINDOW, px, py, px + pw, py + ph, 0x90000000);
        Theme.round(ctx, px, py, px + pw, py + ph, Theme.BG, Theme.R_WIN);
        Theme.stroke(ctx, px, py, px + pw, py + ph, Theme.BORDER, Theme.R_WIN);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        boolean smooth = s == null || s.smoothFont.get();
        UiFont.draw(ctx, textRenderer, "Hypixel API Key", px + 14, py + 12, Theme.TEXT, Role.TITLE, smooth);
        UiFont.draw(ctx, textRenderer, "A personal key from developer.hypixel.net (free, sign in with your account).",
                px + 14, py + 30, Theme.TEXT_DIM, Role.SMALL, smooth);
        UiFont.draw(ctx, textRenderer, "Kept in your Evade config only, sent only to api.hypixel.net.",
                px + 14, py + 41, Theme.TEXT_DIM, Role.SMALL, smooth);
        String k = field.getText().strip();
        String hint = k.isEmpty() ? "Paste with Ctrl+V. Saving an empty field removes the key."
                : HypixelApi.validKey(k) ? "Looks right. Press Save or Enter." : "Keys look like 8-4-4-4-12 hex digits.";
        int hc = k.isEmpty() ? Theme.TEXT_FAINT : HypixelApi.validKey(k) ? Theme.OK : Theme.WARN;
        UiFont.draw(ctx, textRenderer, hint, px + 14, py + 88, hc, Role.SMALL, smooth);
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
