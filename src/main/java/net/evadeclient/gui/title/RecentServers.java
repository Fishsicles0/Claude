package net.evadeclient.gui.title;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.TitleScreenModule;
import net.evadeclient.util.Theme;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.WorldIcon;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.network.NetworkingBackend;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Identifier;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The servers the main menu offers to continue on: the last few joined (newest first, remembered by the Title
 * Screen module), then the top of the server list (servers.dat), up to {@link #MAX}. Each one gets its favicon from
 * servers.dat and a live status (players, ping) from the game's own {@link MultiplayerServerListPinger}, pinged on a
 * small background pool exactly as the multiplayer screen does; the pinger's connections are ticked by the menu.
 * Results are kept for a minute, so going back and forth to the menu doesn't ping again.
 *
 * <p>One instance lives for the session (see {@link #get}). Render thread only, apart from the pings, which write the
 * ServerInfo's status fields from the network threads the way vanilla's do.
 */
public final class RecentServers {
    public static final int MAX = 3;
    private static final long REPING_MS = 60_000;
    private static final ExecutorService PINGS = Executors.newFixedThreadPool(2, new ThreadFactoryBuilder()
            .setNameFormat("Evade Title Pinger #%d").setDaemon(true).build());
    private static RecentServers instance;
    /** Whether status pings are on (Server Status, multiplayer allowed): otherwise rows show the address. */
    private static boolean live = true;

    /** One server on the menu: its list entry, favicon texture and when it was last pinged. */
    public static final class Entry {
        public final ServerInfo info;
        final WorldIcon icon;
        private byte[] uploaded;
        private boolean hasIcon;
        long pingedAt = Long.MIN_VALUE / 2;
        /** Cached "1.2K online" (rebuilt only when the count changes). */
        private int shownOnline = -1, shownMax = -1;
        private long shownPing = -1;
        private String statusText = "", shortText = "";
        private ServerInfo.Status shownStatus;

        Entry(ServerInfo info, WorldIcon icon) {
            this.info = info;
            this.icon = icon;
        }

        /** The favicon texture, or null (the caller draws an icon instead). */
        public Identifier favicon() {
            return hasIcon ? icon.getTextureId() : null;
        }

        public String name() {
            return info.name == null || info.name.isBlank() ? info.address : info.name;
        }

        /** Status line: "Pinging…", "1.2K online · 38 ms", "Offline", "Needs 1.21.4". */
        public String status() {
            ServerInfo.Status st = info.getStatus();
            int online = info.players == null ? -1 : info.players.online(), max = info.players == null ? -1 : info.players.max();
            if (!live && (st == ServerInfo.Status.INITIAL || st == ServerInfo.Status.PINGING)) return info.address;
            if (st == shownStatus && online == shownOnline && max == shownMax && info.ping == shownPing) return statusText;
            shownStatus = st;
            shownOnline = online;
            shownMax = max;
            shownPing = info.ping;
            shortText = st == ServerInfo.Status.SUCCESSFUL && online >= 0 ? TitleArt.compact(online) + " online"
                    : st == ServerInfo.Status.UNREACHABLE ? "Offline" : "";
            statusText = switch (st) {
                case INITIAL, PINGING -> "Pinging…";
                case UNREACHABLE -> "Offline";
                case INCOMPATIBLE -> info.version == null ? "Incompatible" : "Runs " + info.version.getString();
                case SUCCESSFUL -> (online >= 0 ? TitleArt.compact(online) + " online" : "Online")
                        + (info.ping > 0 ? "  ·  " + info.ping + " ms" : "");
            };
            return statusText;
        }

        /** Short note for the Rejoin button: "1.2K online", or nothing while unknown. */
        public String shortStatus() {
            status();
            return shortText;
        }

        /** Status dot colour: green online, amber incompatible, red offline, grey (pulsing) while pinging. */
        public int dot() {
            return switch (info.getStatus()) {
                case SUCCESSFUL -> Theme.OK;
                case INCOMPATIBLE -> Theme.WARN;
                case UNREACHABLE -> Theme.DANGER;
                default -> {
                    if (!live) yield Theme.TEXT_FAINT;
                    float p = 0.5f + 0.5f * (float) Math.sin(Intro.now() / 180.0);
                    yield Theme.alpha(0xFF8A8A96, 0.35f + 0.5f * p);
                }
            };
        }

        void syncIcon() {
            byte[] bytes = info.getFavicon();
            if (Arrays.equals(bytes, uploaded) && (bytes == null) != hasIcon) return;
            uploaded = bytes;
            hasIcon = false;
            if (icon.isClosed()) return;
            try {
                if (bytes == null) icon.destroy();
                else {
                    icon.load(NativeImage.read(bytes));
                    hasIcon = true;
                }
            } catch (Throwable t) {
                EvadeClient.LOG.debug("Title screen: bad favicon for {}", info.address, t);
            }
        }
    }

    private final MultiplayerServerListPinger pinger = new MultiplayerServerListPinger();
    private final List<Entry> entries = new ArrayList<>(MAX);
    private ServerList list;

    private RecentServers() {}

    public static RecentServers get() {
        if (instance == null) instance = new RecentServers();
        return instance;
    }

    public List<Entry> entries() { return entries; }

    /** Re-reads servers.dat and the remembered servers, keeping the entries (and their status) that stay. */
    public void refresh(TitleScreenModule mod) {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            list = new ServerList(mc);
            list.loadFile();
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Title screen couldn't read the server list", e);
            list = null;
        }
        List<String[]> wanted = new ArrayList<>(MAX);
        if (mod != null) {
            for (String[] r : mod.recentServerEntries()) {
                if (wanted.size() >= MAX) break;
                if (!contains(wanted, r[1])) wanted.add(r);
            }
        }
        if (list != null) {
            for (int i = 0; i < list.size() && wanted.size() < MAX; i++) {
                ServerInfo s = list.get(i);
                if (s == null || s.address == null || s.address.isBlank() || !ServerAddress.isValid(s.address)) continue;
                if (!contains(wanted, s.address)) wanted.add(new String[]{s.name, s.address});
            }
        }
        List<Entry> next = new ArrayList<>(MAX);
        for (String[] w : wanted) {
            Entry keep = find(w[1]);
            if (keep != null) {
                ServerInfo fromList = list == null ? null : list.get(w[1]);
                if (fromList != null) {
                    keep.info.name = fromList.name;
                    if (fromList.getFavicon() != null) keep.info.setFavicon(fromList.getFavicon());
                }
                next.add(keep);
                continue;
            }
            ServerInfo fromList = list == null ? null : list.get(w[1]);
            ServerInfo info = new ServerInfo(fromList != null ? fromList.name : w[0], w[1], ServerInfo.ServerType.OTHER);
            if (fromList != null) {
                info.setFavicon(fromList.getFavicon());
                info.setResourcePackPolicy(fromList.getResourcePackPolicy());
            }
            next.add(new Entry(info, WorldIcon.forServer(mc.getTextureManager(), w[1])));
        }
        for (Entry e : entries) if (!next.contains(e)) e.icon.close();
        entries.clear();
        entries.addAll(next);
        for (Entry e : entries) e.syncIcon();
    }

    private static boolean contains(List<String[]> l, String address) {
        for (String[] s : l) if (s[1].equalsIgnoreCase(address)) return true;
        return false;
    }

    private Entry find(String address) {
        for (Entry e : entries) if (e.info.address.equalsIgnoreCase(address)) return e;
        return null;
    }

    /** The entry for {@code address}, if it is one of the menu's. */
    public Entry entry(String address) {
        return address == null ? null : find(address);
    }

    /** Pings every entry not pinged in the last minute (or whose ping was cut off). */
    public void pingStale(boolean allowed) {
        live = allowed;
        if (!allowed) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.currentTimeMillis();
        for (Entry e : entries) {
            ServerInfo s = e.info;
            boolean stale = now - e.pingedAt > REPING_MS;
            if (!stale && s.getStatus() != ServerInfo.Status.INITIAL) continue;
            e.pingedAt = now;
            s.setStatus(ServerInfo.Status.PINGING);
            NetworkingBackend backend = NetworkingBackend.remote(mc.options.shouldUseNativeTransport());
            PINGS.submit(() -> {
                try {
                    pinger.add(s, () -> mc.execute(() -> onFavicon(e)), () -> s.setStatus(
                            s.protocolVersion == SharedConstants.getGameVersion().protocolVersion()
                                    ? ServerInfo.Status.SUCCESSFUL : ServerInfo.Status.INCOMPATIBLE), backend);
                } catch (UnknownHostException ex) {
                    s.setStatus(ServerInfo.Status.UNREACHABLE);
                } catch (Exception ex) {
                    s.setStatus(ServerInfo.Status.UNREACHABLE);
                }
            });
        }
    }

    /** A ping brought a new favicon: show it and keep it in servers.dat, as the server list does. */
    private void onFavicon(Entry e) {
        e.syncIcon();
        if (list == null) return;
        ServerInfo inList = list.get(e.info.address);
        if (inList != null) {
            inList.setFavicon(e.info.getFavicon());
            try {
                list.saveFile();
            } catch (RuntimeException ex) {
                EvadeClient.LOG.debug("Title screen couldn't save the server list", ex);
            }
        }
    }

    /** Ticks the pinger's connections (the menu's tick). A ping that ended without an answer reads as offline. */
    public void tick() {
        pinger.tick();
        for (Entry e : entries) {
            if (e.info.getStatus() != ServerInfo.Status.PINGING) continue;
            boolean failed = e.info.label != null && e.info.label.getContent() instanceof TranslatableTextContent t
                    && t.getKey().equals("multiplayer.status.cannot_connect");
            if (failed || System.currentTimeMillis() - e.pingedAt > 12_000) e.info.setStatus(ServerInfo.Status.UNREACHABLE);
        }
    }

    /** The menu closed: drop open pings; the ones that didn't finish are pinged again next time. */
    public void cancel() {
        pinger.cancel();
        for (Entry e : entries) {
            if (e.info.getStatus() == ServerInfo.Status.PINGING) {
                e.info.setStatus(ServerInfo.Status.INITIAL);
                e.pingedAt = Long.MIN_VALUE / 2;
            }
        }
    }
}
