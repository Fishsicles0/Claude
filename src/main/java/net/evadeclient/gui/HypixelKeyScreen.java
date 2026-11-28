package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.HypixelApi;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Where you paste your personal Hypixel API key for Hypixel Stats. The field is masked (scrambled glyphs of the same
 * widths, so the cursor stays where it should) unless you choose Show, and
 * the key is handed straight to the module (which keeps it in the config only). A link opens developer.hypixel.net,
 * through the game's usual "open this link?" prompt.
 */
public final class HypixelKeyScreen extends EvadeDialog {
    private static final String DEV_SITE = "https://developer.hypixel.net/";
    private static final String FIELD = "key";

    private final String initial;
    private final Consumer<String> onSave;
    private boolean reveal, seeded;

    public HypixelKeyScreen(Screen parent, String current, Consumer<String> onSave) {
        super("Hypixel API Key", parent);
        this.initial = current == null ? "" : current;
        this.onSave = onSave;
    }

    @Override protected int prefWidth() { return 420; }
    @Override protected int prefHeight() { return 226; }
    @Override protected String titleIcon() { return "lock"; }

    @Override
    protected void init() {
        frame();
        if (!seeded) { seeded = true; SearchField.set(kit, FIELD, initial); }
        action("Get a key", Button.Variant.GHOST, () -> ConfirmLinkScreen.open(this, DEV_SITE)).left();
        action("Clear", Button.Variant.GHOST, () -> SearchField.set(kit, FIELD, ""));
        action("Cancel", Button.Variant.SECONDARY, this::close);
        action("Save", Button.Variant.PRIMARY, this::save);
        kit.focus(FIELD, false);
    }

    private void save() {
        String key = SearchField.text(kit, FIELD).strip();
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
    protected void renderBody(DrawContext ctx, int mx, int my) {
        int y = para("A personal key from developer.hypixel.net (free, sign in with your account). Kept in your Evade config only, sent only to api.hypixel.net.",
                bx, by, bw, Tokens.N800, Role.BODY);
        y += 10;
        label("API key", bx, y);
        y += 14;
        int sw = 28;
        field(FIELD, bx, y, bw - sw - Tokens.S2, "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", !reveal, 64);
        Button.icon(kit, "reveal", bx + bw - sw, y - 2, sw, "visuals", reveal ? "Hide the key" : "Show the key", true, () -> reveal = !reveal);
        if (!reveal) { // a slash through the eye: hidden
            var m = ctx.getMatrices();
            m.pushMatrix();
            m.translate(bx + bw - sw / 2f, y + 12);
            m.rotate((float) Math.toRadians(45));
            Paint.rect(ctx, -8, -0.75f, 8, 0.75f, 0.75f, Tokens.N800);
            m.popMatrix();
        }
        y += 32;
        String k = SearchField.text(kit, FIELD).strip();
        if (k.isEmpty()) note("info", Tokens.N600, "Paste with Ctrl+V. Saving an empty field removes the key.", y);
        else if (HypixelApi.validKey(k)) note("check", Tokens.OK, "Looks right. Press Save or Enter.", y);
        else note("warn", Tokens.WARN, "Keys look like 8-4-4-4-12 hex digits.", y);
    }
}
