package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.LauncherBridge;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.Text;

/**
 * Shown on the title screen when the player clicked Join (a server) or Continue (a world) in the Evade Launcher a
 * moment ago: "Joining X (requested from the launcher)" with a three-second countdown, Cancel and Join now. Cancel
 * or Escape goes back to the title screen and nothing happens.
 */
public final class LauncherJoinScreen extends Screen {
    static final long COUNTDOWN_MS = 3000;

    private final Screen parent;
    private final LauncherBridge.Request request;
    private final long opened = System.currentTimeMillis();
    private boolean done;
    private int px, py, pw, ph;

    public LauncherJoinScreen(Screen parent, LauncherBridge.Request request) {
        super(Text.literal((request.world() ? "Opening " : "Joining ") + request.name()));
        this.parent = parent;
        this.request = request;
    }

    public LauncherBridge.Request request() { return request; }

    @Override
    protected void init() {
        pw = Math.min(320, width - 20);
        ph = 104;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int bw = (pw - 28 - 6) / 2, by = py + ph - 30;
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close()).dimensions(px + 14, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(request.world() ? "Open now" : "Join now"), b -> go())
                .dimensions(px + 14 + bw + 6, by, bw, 20).build());
    }

    @Override
    public void tick() {
        if (!done && System.currentTimeMillis() - opened >= COUNTDOWN_MS) go();
    }

    private void go() {
        if (done || client == null) return;
        done = true;
        if (request.world()) {
            if (!client.getLevelStorage().levelExists(request.target())) {
                Notifications.push("Evade Launcher", "World \"" + request.name() + "\" isn't in this instance", false);
                client.setScreen(parent);
                return;
            }
            client.createIntegratedServerLoader().start(request.target(), () -> client.setScreen(parent));
            return;
        }
        if (!client.isMultiplayerEnabled()) {
            Notifications.push("Evade Launcher", "Multiplayer is turned off for this account", false);
            client.setScreen(parent);
            return;
        }
        if (!client.options.skipMultiplayerWarning) {
            client.setScreen(new MultiplayerWarningScreen(parent));
            return;
        }
        ServerInfo info = null;
        try {
            ServerList list = new ServerList(client);
            list.loadFile();
            info = list.get(request.target());
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Launcher join couldn't read the server list", e);
        }
        if (info == null) info = new ServerInfo(request.name(), request.target(), ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(new MultiplayerScreen(parent), client, ServerAddress.parse(request.target()), info, false, null);
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
        String head = (request.world() ? "Opening " : "Joining ") + request.name();
        UiFont.draw(ctx, textRenderer, head, px + 14, py + 12, Theme.TEXT, Role.TITLE, smooth);
        UiFont.draw(ctx, textRenderer, "Requested from the Evade Launcher" + (request.world() ? "" : "  ·  " + request.target()),
                px + 14, py + 30, Theme.TEXT_DIM, Role.SMALL, smooth);
        long left = Math.max(0, COUNTDOWN_MS - (System.currentTimeMillis() - opened));
        UiFont.draw(ctx, textRenderer, "Starting in " + (left + 999) / 1000 + "…  Cancel to stay here.",
                px + 14, py + 44, Theme.TEXT_FAINT, Role.SMALL, smooth);
        int bx = px + 14, bw = pw - 28, by = py + 60;
        ctx.fill(bx, by, bx + bw, by + 3, Theme.TRACK);
        ctx.fill(bx, by, bx + Math.round(bw * (1f - left / (float) COUNTDOWN_MS)), by + 3, Theme.RED);
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() {
        done = true;
        if (client != null) client.setScreen(parent);
    }
}
