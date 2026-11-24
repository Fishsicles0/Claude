package net.evadeclient.module.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.util.Theme;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The server you're playing on: its address (trimmed of the port and common prefixes), its server-list name, the
 * Realm or LAN world, with its icon. Optionally the players online, the server software, how long you've been on
 * and the server's ticks per second, one per line or all on one line.
 */
public class ServerHud extends TextHudModule {
    private final ModeSetting show = add(new ModeSetting("Show", "Address", "Address", "Server Name", "Name + Address")
            .describe("The server's address, its name from your server list (handy on stream), or both."));
    private final ModeSetting trim = add(new ModeSetting("Trim", "Port", "None", "Port", "Port + Prefix")
            .describe("Shorten the address: drop the :port, and also play., mc., hub. and similar prefixes."));
    private final ModeSetting singleplayer = add(new ModeSetting("Singleplayer", "World Name", "World Name", "Singleplayer", "Hide")
            .describe("What to show in a singleplayer world: its name, the word Singleplayer, or nothing."));
    private final BooleanSetting serverIcon = add(new BooleanSetting("Server Icon", true)
            .describe("The server's icon from your server list beside the text."));
    private final ModeSetting iconSize = add(new ModeSetting("Icon Size", "Auto", "Small", "Large", "Auto")
            .describe("Small is one line tall, Large two; Auto follows the number of lines."));
    private final BooleanSetting playerCount = add(new BooleanSetting("Player Count", false)
            .describe("Players online (from the tab list), with the server's limit when your server list knows it."));
    private final BooleanSetting serverBrand = add(new BooleanSetting("Server Brand", false)
            .describe("The server software it reports, e.g. Paper."));
    private final BooleanSetting sessionTime = add(new BooleanSetting("Session Time", false)
            .describe("How long you've been connected."));
    private final ModeSetting layout = add(new ModeSetting("Layout", "Lines", "Lines", "Inline")
            .describe("One line per item, or everything on one line separated by |."));
    private final BooleanSetting showTps = add(new BooleanSetting("Show TPS", false)
            .describe("The server's ticks per second (20 at full speed), measured from its time updates."));
    private final BooleanSetting tpsColors = add(new BooleanSetting("TPS Colors", true)
            .describe("TPS in green at full speed, yellow when the server slows down and red when it lags."));

    private static final String SAMPLE = "play.example.net";
    private static final String[] PREFIXES = {"play.", "mc.", "join.", "hub.", "www.", "eu.", "us.", "na."};
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final int BRAND_MAX = 24;
    private static final Identifier ICON_ID = Identifier.of("evade", "server_icon");

    /** TPS ring: the last {@link #SAMPLES} time updates, as (arrival nanoTime, server game time). */
    private static final int SAMPLES = 6;
    /** A server silent for longer than this (it normally sends its time every second) counts as stalled. */
    private static final double STALL_SECONDS = 2.5;
    private final long[] sampleNs = new long[SAMPLES], sampleTime = new long[SAMPLES];
    private int sampleCount, newest = -1;
    /** The connection and world the samples came from; weak, so a world that was left is never kept alive. */
    private WeakReference<Object> sampleConnection = new WeakReference<>(null), sampleWorld = new WeakReference<>(null);

    /** When this connection began (System.currentTimeMillis), or 0 while not connected. */
    private static volatile long joinedAt;
    private static boolean listenersRegistered;

    /** The favicon bytes last looked at (compared by reference first, then content), and whether one is uploaded. */
    private byte[] iconBytes;
    private boolean iconUploaded;
    private ItemStack grass;
    /** Lines in the text as last built, for the Auto icon size. */
    private int lineCount = 1;

    private record Part(String text, int color) {
        Part(String text) { this(text, 0); }
    }

    public ServerHud() {
        super("Server Address", "The server you're playing on.", 4, 200);
        registerListeners();
    }

    private static synchronized void registerListeners() {
        if (listenersRegistered) return;
        listenersRegistered = true;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> joinedAt = System.currentTimeMillis());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> joinedAt = 0);
    }

    // ---- content -----------------------------------------------------------------------------------------

    /** What to show, in order; empty to hide the element. */
    private List<Part> parts(boolean preview) {
        List<Part> out = new ArrayList<>();
        ServerInfo entry = mc.getCurrentServerEntry();
        boolean connected = mc.getNetworkHandler() != null;
        if (entry != null) {
            if (entry.isRealm()) out.add(new Part("Realms: " + clean(entry.name, "Realm")));
            else if (entry.isLocal()) out.add(new Part("LAN World"));
            else addServer(out, entry);
        } else if (mc.getServer() != null) {
            switch (singleplayer.get()) {
                case "Hide" -> { return preview ? List.of(new Part(SAMPLE)) : out; }
                case "Singleplayer" -> out.add(new Part("Singleplayer"));
                default -> out.add(new Part(worldName()));
            }
        } else if (connected) {
            out.add(new Part("Multiplayer"));
        } else {
            return preview ? List.of(new Part(SAMPLE)) : out;
        }
        if (connected) addExtras(out, entry);
        return out;
    }

    private void addServer(List<Part> out, ServerInfo entry) {
        String address = trimAddress(entry.address);
        String name = serverName(entry);
        if (address.isEmpty()) address = name != null ? name : "Multiplayer";
        if (net.evadeclient.module.misc.StreamerMode.hidesServerAddress()) {
            // Streamer Mode: the list name only, unless that name is itself the address.
            boolean nameIsAddress = name == null || entry.address == null || name.equalsIgnoreCase(address)
                    || name.equalsIgnoreCase(entry.address.strip());
            out.add(new Part(nameIsAddress ? "Multiplayer" : name));
            return;
        }
        switch (show.get()) {
            case "Server Name" -> out.add(new Part(name != null ? name : address));
            case "Name + Address" -> {
                if (name != null && !name.equalsIgnoreCase(address)) out.add(new Part(name));
                out.add(new Part(address));
            }
            default -> out.add(new Part(address));
        }
    }

    private void addExtras(List<Part> out, ServerInfo entry) {
        var handler = mc.getNetworkHandler();
        if (handler == null) return;
        if (playerCount.get()) {
            String count = String.valueOf(handler.getListedPlayerListEntries().size());
            if (entry != null && entry.players != null && entry.players.max() > 0) count += "/" + entry.players.max();
            out.add(new Part(count + " online"));
        }
        if (serverBrand.get()) {
            String brand = handler.getBrand();
            if (brand != null) {
                brand = Formatting.strip(brand).strip();
                if (brand.length() > BRAND_MAX) brand = brand.substring(0, BRAND_MAX - 3).stripTrailing() + "...";
                if (!brand.isEmpty()) out.add(new Part(brand));
            }
        }
        long since = joinedAt;
        if (sessionTime.get() && since > 0) out.add(new Part("Session " + duration(System.currentTimeMillis() - since)));
        if (showTps.get()) out.add(tpsPart());
    }

    /** The server-list name, or null when it's blank or still the default "Minecraft Server". */
    private static String serverName(ServerInfo entry) {
        String n = clean(entry.name, "");
        if (n.isEmpty() || n.equals("Minecraft Server") || n.equals(I18n.translate("selectServer.defaultName"))) return null;
        return n;
    }

    private static String clean(String s, String fallback) {
        if (s == null) return fallback;
        String c = Formatting.strip(s);
        c = c == null ? "" : c.strip();
        return c.isEmpty() ? fallback : c;
    }

    private static String worldName() {
        var server = mc.getServer();
        if (server == null) return "Singleplayer";
        try {
            String name = server.getSaveProperties().getLevelName();
            return name == null || name.isBlank() ? "Singleplayer" : name.strip();
        } catch (RuntimeException e) {
            return "Singleplayer";
        }
    }

    private String trimAddress(String address) {
        String a = address == null ? "" : address.strip();
        if (trim.is("None")) return a;
        a = withoutPort(a);
        return trim.is("Port + Prefix") ? withoutPrefix(a) : a;
    }

    /**
     * {@code address} without a trailing ":port". The port is only stripped when the host has exactly one colon,
     * or is a bracketed IPv6 address ("[::1]:25565" is "[::1]"); a bare IPv6 address has no port to strip.
     */
    public static String withoutPort(String address) {
        if (address == null) return "";
        String a = address.strip();
        int colon = a.lastIndexOf(':');
        if (colon <= 0 || colon == a.length() - 1) return a;
        for (int i = colon + 1; i < a.length(); i++) if (!Character.isDigit(a.charAt(i))) return a;
        String host = a.substring(0, colon);
        if (host.startsWith("[")) return host.endsWith("]") ? host : a;
        return host.indexOf(':') >= 0 ? a : host;
    }

    /**
     * {@code host} without leading "play.", "mc." and similar labels, as long as a domain with a dot is left
     * ("mc.hypixel.net" is "hypixel.net", "play.net" stays). IP addresses are never touched.
     */
    public static String withoutPrefix(String host) {
        if (host == null) return "";
        String h = host.strip();
        if (h.startsWith("[") || h.indexOf(':') >= 0 || IPV4.matcher(h).matches()) return h;
        boolean changed = true;
        while (changed) {
            changed = false;
            String lower = h.toLowerCase(Locale.ROOT);
            for (String p : PREFIXES) {
                if (lower.startsWith(p) && h.indexOf('.', p.length()) > p.length()) {
                    h = h.substring(p.length());
                    changed = true;
                    break;
                }
            }
        }
        return h;
    }

    private static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = s / 60 % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    // ---- TPS ---------------------------------------------------------------------------------------------

    @Override
    public void onServerPacket(Packet<?> packet) {
        if (!(packet instanceof WorldTimeUpdateS2CPacket p)) return;
        long now = System.nanoTime();
        Object connection = mc.getNetworkHandler();
        boolean reset = sampleConnection.get() != connection || sampleCount > 0 && p.time() < sampleTime[newest];
        if (!reset && sampleCount > 0 && sampleWorld.get() != mc.world && mc.world != null) {
            // A new world on the same connection: a dimension change keeps the server's clock running, but a proxy
            // moving you to another server makes it jump. Keep the samples unless it jumped.
            double seconds = (now - sampleNs[newest]) / 1e9;
            reset = p.time() - sampleTime[newest] > seconds * mc.world.getTickManager().getTickRate() * 2 + 100;
        }
        if (reset) {
            sampleCount = 0;
            newest = -1;
            sampleConnection = new WeakReference<>(connection);
        }
        sampleWorld = new WeakReference<>(mc.world);
        newest = (newest + 1) % SAMPLES;
        sampleNs[newest] = now;
        sampleTime[newest] = p.time();
        if (sampleCount < SAMPLES) sampleCount++;
    }

    /** The server's ticks per second, or NaN until enough time updates have arrived. */
    private float tps() {
        var world = mc.world;
        if (world == null) return Float.NaN;
        float rate = world.getTickManager().getTickRate();
        var server = mc.getServer();
        if (server != null) {
            float ms = server.getAverageTickTime();
            return ms <= 0 ? rate : Math.min(rate, 1000f / ms);
        }
        if (sampleCount < 2 || sampleConnection.get() != mc.getNetworkHandler()) return Float.NaN;
        int oldest = (newest - sampleCount + 1 + SAMPLES) % SAMPLES;
        long now = System.nanoTime();
        double span = (sampleNs[newest] - sampleNs[oldest]) / 1e9;
        // A server that has stopped sending its time is stalled: the silence counts as time without ticks.
        if ((now - sampleNs[newest]) / 1e9 > STALL_SECONDS) span = (now - sampleNs[oldest]) / 1e9;
        if (span < 0.25) return Float.NaN;
        double tps = (sampleTime[newest] - sampleTime[oldest]) / span;
        return (float) Math.max(0, Math.min(rate, tps));
    }

    private Part tpsPart() {
        var world = mc.world;
        float rate = world == null ? 20f : world.getTickManager().getTickRate();
        boolean frozen = world != null && world.getTickManager().isFrozen();
        float tps = tps();
        String text = (Float.isNaN(tps) ? "--" : String.format(Locale.ROOT, "%.1f", tps)) + " TPS" + (frozen ? " (frozen)" : "");
        int color = 0;
        if (tpsColors.get() && !Float.isNaN(tps)) {
            color = frozen ? Theme.WARN : tps >= rate * 0.95f ? Theme.OK : tps >= rate * 0.75f ? Theme.WARN : Theme.DANGER;
        }
        return new Part(text, color);
    }

    // ---- lines -------------------------------------------------------------------------------------------

    @Override
    protected List<String> lines(boolean preview) {
        List<Part> parts = parts(preview);
        List<String> out = new ArrayList<>(parts.size());
        if (parts.isEmpty()) return out;
        if (layout.is("Inline")) {
            StringBuilder sb = new StringBuilder();
            for (Part p : parts) {
                if (!sb.isEmpty()) sb.append(" | ");
                sb.append(p.text());
            }
            out.add(sb.toString());
        } else {
            for (Part p : parts) out.add(p.text());
        }
        return out;
    }

    @Override
    protected List<Text> styledLines(boolean preview) {
        List<Part> parts = parts(preview);
        List<Text> out = new ArrayList<>(parts.size());
        if (layout.is("Inline") && !parts.isEmpty()) {
            MutableText line = Text.empty();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) line.append(Text.literal(" | "));
                line.append(text(parts.get(i)));
            }
            out.add(line);
        } else {
            for (Part p : parts) out.add(text(p));
        }
        lineCount = out.size();
        return out;
    }

    private static Text text(Part p) {
        MutableText t = Text.literal(p.text());
        return p.color() != 0 ? t.withColor(p.color() & 0xFFFFFF) : t;
    }

    // ---- icon --------------------------------------------------------------------------------------------

    private int iconPx() {
        return switch (iconSize.get()) {
            case "Small" -> 9;
            case "Large" -> 19;
            default -> Math.max(9, Math.min(29, lineCount * 10 - 1));
        };
    }

    @Override
    protected int leadingWidth() { return serverIcon.get() && lineCount > 0 ? iconPx() + 2 : 0; }

    @Override
    protected int minHeight() { return serverIcon.get() && lineCount > 0 ? iconPx() + 4 : 0; }

    @Override
    protected void drawLeading(DrawContext ctx, int x, int y, int h) {
        if (!serverIcon.get()) return;
        int s = iconPx();
        int iy = y + Math.max(0, (h - s) / 2);
        if (favicon()) {
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, ICON_ID, x, iy, 0, 0, s, s, s, s);
        } else {
            if (grass == null) grass = new ItemStack(Items.GRASS_BLOCK);
            var m = ctx.getMatrices();
            m.pushMatrix();
            try {
                m.translate(x, iy);
                m.scale(s / 16f, s / 16f);
                ctx.drawItem(grass, 0, 0);
            } finally {
                m.popMatrix();
            }
        }
    }

    /** Uploads the current server's favicon when it changed; whether one is ready to draw. Render thread only. */
    private boolean favicon() {
        ServerInfo entry = mc.getCurrentServerEntry();
        byte[] bytes = entry == null || entry.isRealm() ? null : entry.getFavicon();
        if (bytes != iconBytes) {
            if (!Arrays.equals(bytes, iconBytes)) uploadIcon(bytes);
            iconBytes = bytes;
        }
        return iconUploaded;
    }

    private void uploadIcon(byte[] bytes) {
        var textures = mc.getTextureManager();
        if (bytes == null) {
            if (iconUploaded) textures.destroyTexture(ICON_ID);
            iconUploaded = false;
            return;
        }
        NativeImage image = null;
        try {
            image = NativeImage.read(bytes);
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "Evade server icon", image);
            image = null; // owned by the texture now
            textures.registerTexture(ICON_ID, texture); // closes the one it replaces
            iconUploaded = true;
        } catch (IOException | RuntimeException e) {
            if (iconUploaded) textures.destroyTexture(ICON_ID);
            iconUploaded = false;
            EvadeClient.LOG.debug("Server Address couldn't read the server icon", e);
        } finally {
            if (image != null) image.close();
        }
    }

    // ---- config ------------------------------------------------------------------------------------------

    /**
     * Older configs had Hide Port (on by default) and Hide Address (show the server-list name). Map them onto Trim
     * and Show; the old keys are only in configs saved before this version, and a new setting is only changed
     * while it is still at its default.
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (settings.has("Hide Port") && !settings.has(trim.getName()) && trim.isDefault())
            trim.set(isTrue(settings, "Hide Port") ? "Port" : "None");
        if (isTrue(settings, "Hide Address") && !settings.has(show.getName()) && show.isDefault())
            show.set("Server Name");
    }

    private static boolean isTrue(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() && e.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
