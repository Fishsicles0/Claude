package net.evadeclient.gui;

import com.google.gson.JsonObject;
import net.evadeclient.config.CloudBackup;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.concurrent.CompletableFuture;

/**
 * Cloud Backup: back up, restore or delete your Evade setup on evadeclient.com, locked with a passphrase you type
 * here (masked, never saved). Nothing is uploaded until you press Back up.
 */
public final class CloudBackupScreen extends EvadeDialog {
    private TextFieldWidget pass;
    private JsonObject info;
    private String note, error;
    private boolean busy, loaded;
    private ButtonWidget backup, restore, delete;

    public CloudBackupScreen(Screen parent) {
        super("Cloud Backup", parent);
    }

    @Override protected int prefWidth() { return 380; }
    @Override protected int prefHeight() { return 200; }

    @Override
    protected void init() {
        frame();
        String text = pass == null ? "" : pass.getText();
        pass = new TextFieldWidget(textRenderer, px + 14, py + 112, pw - 28, 18, Text.literal("Passphrase"));
        pass.setMaxLength(128);
        pass.setText(text);
        pass.setPlaceholder(Text.literal("Passphrase (8+ characters, only you know it)").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        pass.addFormatter((s, start) -> OrderedText.styledForwardsVisitedString("*".repeat(s.length()), Style.EMPTY));
        addDrawableChild(pass);
        setInitialFocus(pass);
        int by = py + ph - 30, bw = (pw - 28 - 12) / 4;
        backup = button("Back up", px + 14, by, bw, b -> run(CloudBackup.backup(pass.getText().toCharArray(), net.minecraft.client.MinecraftClient.getInstance().getSession().getUsername()),
                n -> "Backed up (" + Math.max(1, (Integer) n / 1024) + " KB, encrypted)"));
        restore = button("Restore", px + 14 + bw + 4, by, bw, b -> {
            if (client == null) return;
            client.setScreen(new ConfirmScreen(yes -> {
                client.setScreen(this);
                if (yes) run(CloudBackup.restore(pass.getText().toCharArray()), n -> "Restored " + n + " files. Your old ones are in config/evade/before-restore.zip");
            }, Text.literal("Restore your cloud backup?"), Text.literal("Your settings, HUD layout, profiles and waypoints are replaced by the backup. A copy of the current ones is kept on this PC.")));
        });
        delete = button("Delete", px + 14 + (bw + 4) * 2, by, bw, b -> run(CloudBackup.delete(), d -> "Cloud backup deleted"));
        button("Done", px + 14 + (bw + 4) * 3, by, bw, b -> close());
        update();
        if (!loaded) {
            loaded = true;
            busy = true;
            CloudBackup.info().whenComplete((r, e) -> client.execute(() -> {
                busy = false;
                if (e != null) error = CloudBackup.message(e); else info = r;
                update();
            }));
        }
    }

    private boolean exists() { return info != null && info.has("exists") && info.get("exists").getAsBoolean(); }

    private void update() {
        if (backup == null) return;
        boolean okPass = pass.getText().length() >= CloudBackup.MIN_PASSPHRASE;
        backup.active = !busy && okPass;
        restore.active = !busy && okPass && exists();
        delete.active = !busy && exists();
    }

    private <T> void run(CompletableFuture<T> f, java.util.function.Function<T, String> done) {
        busy = true;
        error = null;
        note = "Working...";
        update();
        f.whenComplete((r, e) -> client.execute(() -> {
            busy = false;
            if (e != null) {
                error = CloudBackup.message(e);
                note = null;
            } else {
                note = done.apply(r);
                Notifications.push("Cloud Backup", note, true);
                CloudBackup.info().whenComplete((i, e2) -> client.execute(() -> { if (i != null) info = i; update(); }));
            }
            update();
        }));
    }

    @Override
    public void tick() { update(); }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        title(ctx, "Cloud Backup");
        int y = para(ctx, "Keeps your settings, HUD layout, profiles and waypoints on evadeclient.com, tied to your Minecraft account. They're encrypted on this PC with your passphrase first, so Evade can't read them, and nobody can restore them without it.", py + 32, Theme.TEXT_DIM);
        String state;
        int color = Theme.TEXT;
        if (busy && info == null) state = "Checking...";
        else if (info == null) state = "";
        else if (exists()) state = "Backup from " + CloudBackup.date(info.get("updated").getAsLong()) + "  ·  "
                + Math.max(1, info.get("size").getAsInt() * 3 / 4 / 1024) + " KB  ·  kept until " + CloudBackup.date(info.get("expires").getAsLong()) + " unless used";
        else state = "No backup yet. Type a passphrase and press Back up.";
        line(ctx, state, px + 14, Math.max(y + 4, py + 96), color);
        if (error != null) line(ctx, error, px + 14, py + 136, Theme.WARN);
        else if (note != null) line(ctx, note, px + 14, py + 136, Theme.OK);
        else line(ctx, "Forget the passphrase and the backup can't be opened. It isn't saved anywhere.", px + 14, py + 136, Theme.TEXT_FAINT);
    }
}
