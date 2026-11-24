package net.evadeclient.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.SafeStart;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown on the title screen after a safe start: which Evade modules were switched off, why (the last starts didn't
 * reach the title screen, or the launcher asked), and the suspect from the crash report. Each module can be turned
 * back on one at a time (play a little after each to find the one that breaks), or all at once. "Keep them off"
 * closes the list and leaves the rest off; nothing is turned on without a click here.
 */
public final class SafeStartScreen extends Screen {
    private static final int ROW = 22, MAX_ROWS = 7;

    private final Screen parent;
    private List<String> waiting = new ArrayList<>();
    private String reason = "", suspect = null;
    private List<String> suspects = List.of();
    private int scroll;
    private int px, py, pw, ph, listY;

    public SafeStartScreen(Screen parent) {
        super(Text.literal("Safe Start"));
        this.parent = parent;
    }

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
        int rows = Math.min(MAX_ROWS, Math.max(1, waiting.size()));
        pw = Math.min(360, width - 20);
        ph = 96 + rows * ROW + 34;
        px = (width - pw) / 2;
        py = Math.max(6, (height - ph) / 2);
        listY = py + 84;
        scroll = Math.max(0, Math.min(scroll, waiting.size() - MAX_ROWS));
        for (int i = 0; i < rows && i + scroll < waiting.size(); i++) {
            String name = waiting.get(i + scroll);
            addDrawableChild(ButtonWidget.builder(Text.literal("Turn on"), b -> {
                SafeStart.restore(name);
                Notifications.push(name, "Turned back on", true);
                if (SafeStart.waitingModules().isEmpty()) close(); else clearAndInit();
            }).dimensions(px + pw - 14 - 70, listY + i * ROW + 1, 70, 18).build());
        }
        int bw = (pw - 28 - 6) / 2, by = py + ph - 30;
        addDrawableChild(ButtonWidget.builder(Text.literal("Keep them off"), b -> {
            SafeStart.finish();
            close();
        }).dimensions(px + 14, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Turn all back on"), b -> {
            int n = waiting.size();
            SafeStart.restoreAll();
            Notifications.push("Safe Start", n + (n == 1 ? " module" : " modules") + " turned back on", true);
            close();
        }).dimensions(px + 14 + bw + 6, by, bw, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (waiting.size() <= MAX_ROWS) return false;
        int next = Math.max(0, Math.min(waiting.size() - MAX_ROWS, scroll - (int) Math.signum(v)));
        if (next != scroll) { scroll = next; clearAndInit(); }
        return true;
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
        UiFont.draw(ctx, textRenderer, "Safe Start: Evade's modules are off", px + 14, py + 12, Theme.TEXT, Role.TITLE, smooth);
        UiFont.draw(ctx, textRenderer, reason, px + 14, py + 30, Theme.TEXT_DIM, Role.SMALL, smooth);
        String sus = suspect != null ? "Suspect: " + suspect + (suspects.isEmpty() ? "" : " (" + String.join(", ", suspects) + ")")
                : !suspects.isEmpty() ? "Suspect: " + String.join(", ", suspects) : "The crash report didn't name an Evade module.";
        UiFont.draw(ctx, textRenderer, sus, px + 14, py + 44, suspect != null || !suspects.isEmpty() ? Theme.WARN : Theme.TEXT_FAINT, Role.SMALL, smooth);
        UiFont.draw(ctx, textRenderer, "Turn them back on one at a time to find the one that breaks.", px + 14, py + 62,
                Theme.TEXT_FAINT, Role.SMALL, smooth);
        int rows = Math.min(MAX_ROWS, waiting.size());
        for (int i = 0; i < rows && i + scroll < waiting.size(); i++) {
            String name = waiting.get(i + scroll);
            int y = listY + i * ROW;
            Theme.round(ctx, px + 10, y - 1, px + pw - 10, y + ROW - 3, Theme.ROW, Theme.R);
            boolean sus2 = suspects.contains(name);
            UiFont.draw(ctx, textRenderer, name + (sus2 ? "  (suspect)" : ""), px + 18, y + 5, sus2 ? Theme.WARN : Theme.TEXT, Role.BODY, smooth);
        }
        if (waiting.isEmpty())
            UiFont.draw(ctx, textRenderer, "Every module is back on.", px + 18, listY + 5, Theme.TEXT_DIM, Role.BODY, smooth);
        else if (waiting.size() > MAX_ROWS)
            UiFont.draw(ctx, textRenderer, (scroll + 1) + "-" + Math.min(waiting.size(), scroll + MAX_ROWS) + " of " + waiting.size() + " (scroll)",
                    px + 14, listY + rows * ROW + 2, Theme.TEXT_FAINT, Role.SMALL, smooth);
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() {
        if (client != null) client.setScreen(parent);
    }
}
