package net.evadeclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.SafeStart;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown on the title screen after a safe start: which Evade modules were switched off, why (the last starts didn't
 * reach the title screen, or the launcher asked), and the suspect from the crash report. Each module can be turned
 * back on one at a time (play a little after each to find the one that breaks), or all at once. "Keep them off"
 * closes the list and leaves the rest off; nothing is turned on without a click here.
 */
public final class SafeStartScreen extends EvadeDialog {
    private static final int ROW = 30, MAX_ROWS = 7;

    private List<String> waiting = new ArrayList<>();
    private String reason = "", suspect = null;
    private List<String> suspects = List.of();
    private int scroll;

    public SafeStartScreen(Screen parent) {
        super("Safe Start", parent);
    }

    @Override protected int prefWidth() { return 440; }
    @Override protected int prefHeight() { return 196 + Math.min(MAX_ROWS, Math.max(1, waiting.size())) * ROW; }
    @Override protected String titleIcon() { return "warn"; }
    @Override protected String heading() { return "Evade's modules are off"; }

    @Override
    protected void init() {
        waiting = SafeStart.waitingModules();
        JsonObject st = SafeStart.state();
        if (st != null) {
            String why = st.has("reason") && !st.get("reason").isJsonNull() ? st.get("reason").getAsString() : "";
            int failed = st.has("failedStarts") ? st.get("failedStarts").getAsInt() : 0;
            reason = "launcher".equals(why) ? "You asked the Evade Launcher for a safe start."
                    : "The last " + Math.max(2, failed) + " starts stopped before the title screen.";
            suspect = st.has("suspectMixin") ? st.get("suspectMixin").getAsString() : null;
            List<String> sm = new ArrayList<>();
            if (st.has("suspectModules") && st.get("suspectModules").isJsonArray()) {
                JsonArray a = st.getAsJsonArray("suspectModules");
                for (var e : a) sm.add(e.getAsString());
            }
            suspects = sm;
        }
        frame();
        scroll = Math.max(0, Math.min(scroll, waiting.size() - MAX_ROWS));
        action("Keep them off", Button.Variant.GHOST, () -> {
            SafeStart.finish();
            close();
        });
        action("Turn all back on", Button.Variant.PRIMARY, () -> {
            int n = waiting.size();
            SafeStart.restoreAll();
            Notifications.push("Safe Start", n + (n == 1 ? " module" : " modules") + " turned back on", true);
            close();
        });
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (waiting.size() <= MAX_ROWS) return false;
        scroll = Math.max(0, Math.min(waiting.size() - MAX_ROWS, scroll - (int) Math.signum(v)));
        return true;
    }

    @Override
    protected void renderBody(DrawContext ctx, int mx, int my) {
        int y = by;
        kit.text(reason, bx, y, Tokens.N800, Role.BODY);
        y += 16;
        boolean named = suspect != null || !suspects.isEmpty();
        String sus = suspect != null ? "Suspect: " + suspect + (suspects.isEmpty() ? "" : " (" + String.join(", ", suspects) + ")")
                : !suspects.isEmpty() ? "Suspect: " + String.join(", ", suspects) : "The crash report didn't name an Evade module.";
        y = note(named ? "warn" : "info", named ? Tokens.WARN : Tokens.N600, sus, y) + 2;
        y = note("info", Tokens.N600, "Turn them back on one at a time and play a little after each to find the one that breaks.", y) + 8;
        int rows = Math.min(MAX_ROWS, waiting.size());
        if (waiting.isEmpty()) {
            note("check", Tokens.OK, "Every module is back on.", y + 4);
            return;
        }
        for (int i = 0; i < rows && i + scroll < waiting.size(); i++) {
            String name = waiting.get(i + scroll);
            int ry = y + i * ROW;
            boolean sus2 = suspects.contains(name);
            Paint.surface(ctx, bx, ry, bx + bw, ry + ROW - 2, Tokens.R_SM, Tokens.E2.noAmbient(), 0, Float.NaN);
            float mid = ry + (ROW - 2) / 2f;
            int tx = bx + 10;
            if (sus2) { Icons.draw(ctx, "warn", tx, Math.round(mid - 6), 12, Tokens.WARN); tx += 18; }
            kit.textCentredY(name, tx, mid, Tokens.N950, Role.STRONG);
            if (sus2) Badge.draw(kit, tx + kit.width(name, Role.STRONG) + Tokens.S3, Math.round(mid - Badge.H / 2f), "Suspect", Badge.Tone.WARN);
            int w = Button.width(kit, "Turn on", Button.Size.SM, null, false);
            Button.draw(kit, "on:" + name, bx + bw - 6 - w, Math.round(mid - 10), w, Button.Size.SM, Button.Variant.SECONDARY, "Turn on", null, true, () -> {
                SafeStart.restore(name);
                Notifications.push(name, "Turned back on", true);
                if (SafeStart.waitingModules().isEmpty()) close(); else clearAndInit();
            });
        }
        if (waiting.size() > MAX_ROWS)
            kit.text((scroll + 1) + "–" + Math.min(waiting.size(), scroll + MAX_ROWS) + " of " + waiting.size() + " · scroll for more",
                    bx, y + rows * ROW + 2, Tokens.N600, Role.SMALL);
    }
}
