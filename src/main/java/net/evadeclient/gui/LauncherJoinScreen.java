package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.LauncherBridge;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.Text;

/**
 * Shown on the title screen when the player clicked Join (a server) or Continue (a world) in the Evade Launcher a
 * moment ago: "Join X? (requested from the launcher)" with Cancel and Join. Nothing connects until the player clicks
 * Join here (never automatically); Cancel or Escape goes back to the title screen.
 */
public final class LauncherJoinScreen extends EvadeDialog {
    private final LauncherBridge.Request request;
    private boolean done;

    public LauncherJoinScreen(Screen parent, LauncherBridge.Request request) {
        super((request.world() ? "Open " : "Join ") + request.name(), parent);
        this.request = request;
    }

    @Override protected int prefWidth() { return 400; }
    @Override protected int prefHeight() { return 172; }
    @Override protected String titleIcon() { return request.world() ? "map" : "server"; }
    @Override protected String heading() { return (request.world() ? "Open " : "Join ") + request.name() + "?"; }

    public LauncherBridge.Request request() { return request; }

    @Override
    protected void init() {
        frame();
        action("Cancel", Button.Variant.GHOST, this::close);
        action(request.world() ? "Open world" : "Join server", Button.Variant.PRIMARY, this::go);
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
    protected void renderBody(DrawContext ctx, int mx, int my) {
        int y = by;
        kit.text("Requested from the Evade Launcher", bx, y, Tokens.N800, Role.BODY);
        y += 16;
        if (!request.world()) {
            Paint.rect(ctx, bx, y, bx + bw, y + 22, Tokens.R_XS, Tokens.N150);
            Icons.draw(ctx, "server", bx + 6, y + 5, 12, Tokens.N700);
            kit.textCentredY(kit.ellipsize(request.target(), Role.BODY, bw - 30), bx + 24, y + 11, Tokens.N950, Role.BODY);
            y += 30;
        }
        note("info", Tokens.N600, "Nothing happens until you click " + (request.world() ? "Open world." : "Join server."), y);
    }

    @Override public void close() {
        done = true;
        if (client != null) client.setScreen(parent);
    }
}
