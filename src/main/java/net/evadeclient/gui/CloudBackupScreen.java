package net.evadeclient.gui;

import com.google.gson.JsonObject;
import net.evadeclient.config.CloudBackup;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Progress;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.concurrent.CompletableFuture;

/**
 * Cloud Backup: back up, restore or delete your Evade setup on evadeclient.com, locked with a passphrase you type
 * here (masked, never saved). Nothing is uploaded until you press Back up.
 */
public final class CloudBackupScreen extends EvadeDialog {
    private static final String PASS = "pass";
    private JsonObject info;
    private String note, error;
    private boolean busy, loaded;
    private Act backup, restore, delete;

    public CloudBackupScreen(Screen parent) {
        super("Cloud Backup", parent);
    }

    @Override protected int prefWidth() { return 440; }
    @Override protected int prefHeight() { return 300; }
    @Override protected String titleIcon() { return "server"; }

    private String pass() { return SearchField.text(kit, PASS); }

    @Override
    protected void init() {
        frame();
        delete = action("Delete", Button.Variant.DANGER, () -> run(CloudBackup.delete(), d -> "Cloud backup deleted")).left();
        action("Done", Button.Variant.GHOST, this::close);
        restore = action("Restore", Button.Variant.SECONDARY, () -> {
            if (client == null) return;
            client.setScreen(new ConfirmScreen(yes -> {
                client.setScreen(this);
                if (yes) run(CloudBackup.restore(pass().toCharArray()), n -> "Restored " + n + " files. Your old ones are in config/evade/before-restore.zip");
            }, Text.literal("Restore your cloud backup?"), Text.literal("Your settings, HUD layout, profiles and waypoints are replaced by the backup. A copy of the current ones is kept on this PC.")));
        });
        backup = action("Back up", Button.Variant.PRIMARY, () -> run(CloudBackup.backup(pass().toCharArray(), net.minecraft.client.MinecraftClient.getInstance().getSession().getUsername()),
                n -> "Backed up (" + Math.max(1, (Integer) n / 1024) + " KB, encrypted)"));
        kit.focus(PASS, false);
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
        boolean okPass = pass().length() >= CloudBackup.MIN_PASSPHRASE;
        backup.active = !busy && okPass;
        restore.active = !busy && okPass && exists();
        delete.active = !busy && exists();
    }

    private <T> void run(CompletableFuture<T> f, java.util.function.Function<T, String> done) {
        busy = true;
        error = null;
        note = "Working…";
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
    protected void renderBody(DrawContext ctx, int mx, int my) {
        int y = para("Keeps your settings, HUD layout, profiles and waypoints on evadeclient.com, tied to your Minecraft account. They're encrypted on this PC with your passphrase first, so Evade can't read them, and nobody can restore them without it.",
                bx, by, bw, Tokens.N800, Role.BODY);
        y += 10;
        // The backup's state, as a sunken row.
        Paint.surface(ctx, bx, y, bx + bw, y + 30, Tokens.R_SM, Tokens.SUNKEN.withFill(Tokens.N150), 0, Float.NaN);
        String state, icon = "info";
        int ic = Tokens.N600;
        if (busy && info == null) state = "Checking…";
        else if (info == null) state = "Not checked";
        else if (exists()) {
            state = "Backup from " + CloudBackup.date(info.get("updated").getAsLong()) + "  ·  "
                    + Math.max(1, info.get("size").getAsInt() * 3 / 4 / 1024) + " KB  ·  kept until " + CloudBackup.date(info.get("expires").getAsLong());
            icon = "check";
            ic = Tokens.OK;
        } else state = "No backup yet. Type a passphrase and press Back up.";
        if (busy) Progress.circle(kit, bx + 15, y + 15, 5, Float.NaN);
        else Icons.draw(ctx, icon, bx + 9, y + 9, 12, ic);
        kit.textCentredY(kit.ellipsize(state, Role.SMALL, bw - 36), bx + 28, y + 15, Tokens.N900, Role.SMALL);
        y += 42;
        label("Passphrase", bx, y);
        int len = pass().length();
        String need = len >= CloudBackup.MIN_PASSPHRASE ? "" : (CloudBackup.MIN_PASSPHRASE - len) + " more";
        if (!need.isEmpty()) kit.text(need, bx + bw - kit.width(need, Role.SMALL), y, Tokens.N600, Role.SMALL);
        y += 14;
        field(PASS, bx, y, bw, "8 or more characters, only you know it", true, 128);
        y += SearchField.H + 8;
        if (error != null) note("warn", Tokens.WARN, error, y);
        else if (note != null) note("check", Tokens.OK, note, y);
        else note("info", Tokens.N600, "Forget the passphrase and the backup can't be opened. It isn't saved anywhere.", y);
    }
}
