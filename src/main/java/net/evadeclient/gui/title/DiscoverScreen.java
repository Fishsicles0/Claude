package net.evadeclient.gui.title;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeDialog;
import net.evadeclient.social.SocialJoin;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.network.NetworkingBackend;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Discover list: curated servers with a live ping (players and latency, from the game's own pinger), an
 * "Evade-enhanced" badge for servers running the Evade Server API, Join (asks first) and Add (to your server list).
 */
public final class DiscoverScreen extends EvadeDialog {
    private static final int ROWS = 5, ROW_H = 34;
    private static final ExecutorService PINGS = Executors.newFixedThreadPool(2, new ThreadFactoryBuilder()
            .setNameFormat("Evade Discover Pinger #%d").setDaemon(true).build());
    private final MultiplayerServerListPinger pinger = new MultiplayerServerListPinger();
    private final Map<String, ServerInfo> infos = new HashMap<>();
    private final Map<String, Long> pingedAt = new HashMap<>();
    private List<Discover.Server> servers;
    private boolean failed;
    private int page;

    public DiscoverScreen(Screen parent) {
        super("Discover servers", parent);
    }

    @Override protected int prefWidth() { return 420; }
    @Override protected int prefHeight() { return 40 + ROWS * ROW_H + 40; }

    @Override
    protected void init() {
        frame();
        if (servers == null) {
            Discover.list().whenComplete((l, e) -> client.execute(() -> {
                servers = l == null ? List.of() : l;
                failed = servers.isEmpty();
                clearAndInit();
            }));
        }
        List<Discover.Server> list = servers == null ? List.of() : servers;
        int pages = Math.max(1, (list.size() + ROWS - 1) / ROWS);
        page = Math.min(page, pages - 1);
        for (int i = page * ROWS; i < Math.min(list.size(), (page + 1) * ROWS); i++) {
            Discover.Server s = list.get(i);
            int y = py + 36 + (i - page * ROWS) * ROW_H;
            button("Join", px + pw - 14 - 50, y + 4, 50, b -> join(s)).active = client.isMultiplayerEnabled();
            button("Add", px + pw - 14 - 50 - 4 - 40, y + 4, 40, b -> addToList(s));
            ping(s);
        }
        int by = py + ph - 30, bw = (pw - 28 - 8) / 3;
        ButtonWidget prev = button("< Page", px + 14, by, bw, b -> { page--; clearAndInit(); });
        prev.active = page > 0;
        ButtonWidget next = button("Page >", px + 14 + bw + 4, by, bw, b -> { page++; clearAndInit(); });
        next.active = page < pages - 1;
        button("Done", px + 14 + (bw + 4) * 2, by, bw, b -> close());
    }

    private void ping(Discover.Server s) {
        long now = System.currentTimeMillis();
        if (now - pingedAt.getOrDefault(s.address(), 0L) < 60_000) return;
        pingedAt.put(s.address(), now);
        ServerInfo info = infos.computeIfAbsent(s.address(), a -> new ServerInfo(s.name(), a, ServerInfo.ServerType.OTHER));
        info.setStatus(ServerInfo.Status.PINGING);
        NetworkingBackend backend = NetworkingBackend.remote(client.options.shouldUseNativeTransport());
        PINGS.submit(() -> {
            try {
                pinger.add(info, () -> {}, () -> info.setStatus(info.protocolVersion == SharedConstants.getGameVersion().protocolVersion()
                        ? ServerInfo.Status.SUCCESSFUL : ServerInfo.Status.INCOMPATIBLE), backend);
            } catch (Exception e) {
                info.setStatus(ServerInfo.Status.UNREACHABLE);
            }
        });
    }

    /** Asks first (with the address), then connects: never on its own. */
    private void join(Discover.Server s) {
        if (!client.options.skipMultiplayerWarning) {
            client.setScreen(new MultiplayerWarningScreen(this));
            return;
        }
        client.setScreen(new ConfirmScreen(yes -> {
            if (yes) SocialJoin.connect(s.address());
            else client.setScreen(this);
        }, Text.literal("Join " + s.name() + "?"), Text.literal("Connect to " + s.address() + "."), Text.literal("Join"), Text.literal("Cancel")));
    }

    private void addToList(Discover.Server s) {
        try {
            ServerList list = new ServerList(client);
            list.loadFile();
            if (list.get(s.address()) != null) {
                Notifications.push("Discover", s.name() + " is already in your server list", false);
                return;
            }
            list.add(new ServerInfo(s.name(), s.address(), ServerInfo.ServerType.OTHER), false);
            list.saveFile();
            Notifications.push("Discover", "Added " + s.name() + " to your servers", true);
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Discover: couldn't add to the server list", e);
            Notifications.push("Discover", "Couldn't update your server list", false);
        }
    }

    @Override
    public void tick() {
        pinger.tick();
        long now = System.currentTimeMillis();
        for (var e : infos.entrySet()) {
            if (e.getValue().getStatus() == ServerInfo.Status.PINGING && now - pingedAt.getOrDefault(e.getKey(), now) > 12_000)
                e.getValue().setStatus(ServerInfo.Status.UNREACHABLE);
        }
    }

    @Override
    public void removed() { pinger.cancel(); }

    private static String status(ServerInfo info) {
        if (info == null) return "";
        return switch (info.getStatus()) {
            case INITIAL, PINGING -> "Pinging...";
            case UNREACHABLE -> "Offline";
            case INCOMPATIBLE -> info.version == null ? "Another version" : "Runs " + info.version.getString();
            case SUCCESSFUL -> (info.players != null ? TitleArt.compact(info.players.online()) + " online" : "Online")
                    + (info.ping > 0 ? "  ·  " + info.ping + " ms" : "");
        };
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        title(ctx, "Discover servers");
        line(ctx, "Picked by Evade. You choose if and when to join.", px + 14, py + 24, Theme.TEXT_FAINT);
        if (servers == null) { line(ctx, "Loading...", px + 14, py + 44, Theme.TEXT_DIM); return; }
        if (failed) { line(ctx, "Couldn't load the list from evadeclient.com. Try again later.", px + 14, py + 44, Theme.WARN); return; }
        for (int i = page * ROWS; i < Math.min(servers.size(), (page + 1) * ROWS); i++) {
            Discover.Server s = servers.get(i);
            int y = py + 36 + (i - page * ROWS) * ROW_H;
            Theme.round(ctx, px + 10, y, px + pw - 10, y + ROW_H - 4, Theme.alpha(0xFFFFFFFF, 0.04f), 6);
            int x = px + 16;
            net.evadeclient.util.UiFont.draw(ctx, textRenderer, s.name(), x, y + 4, Theme.TEXT, net.evadeclient.util.UiFont.Role.STRONG, smooth);
            int nx = x + net.evadeclient.util.UiFont.width(textRenderer, s.name(), net.evadeclient.util.UiFont.Role.STRONG, smooth) + 6;
            if (s.evade()) line(ctx, "Evade-enhanced", nx, y + 5, Theme.OK);
            ServerInfo info = infos.get(s.address());
            ArrayList<String> bits = new ArrayList<>();
            if (!s.description().isEmpty()) bits.add(s.description());
            line(ctx, String.join("  ·  ", bits), x, y + 16, Theme.TEXT_DIM);
            String st = status(info);
            int sx = px + pw - 14 - 50 - 4 - 40 - 8 - net.evadeclient.util.UiFont.width(textRenderer, st, net.evadeclient.util.UiFont.Role.SMALL, smooth);
            line(ctx, st, sx, y + 5, info != null && info.getStatus() == ServerInfo.Status.SUCCESSFUL ? Theme.OK : Theme.TEXT_FAINT);
        }
    }
}
