package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * Hold (or tap, in Toggle mode) a key to look around your player without changing where you're facing or walking.
 * Some servers forbid this; on those it stays off, whatever the settings say.
 */
public class Freelook extends Module {
    /** Servers whose rules forbid freelook (each domain also covers its subdomains). Always applied. */
    private static final List<String> FORBIDDEN = List.of("hypixel.net", "playhive.com");

    private final KeySetting key = add(new KeySetting("Freelook Key", GLFW.GLFW_KEY_LEFT_ALT, true));
    private final ModeSetting mode = add(new ModeSetting("Mode", "Hold", "Hold", "Toggle")
            .describe("Hold the key to look around, or tap it to start and tap again to stop."));
    private final BooleanSetting thirdPerson = add(new BooleanSetting("Third Person", true));
    private final BooleanSetting invert = add(new BooleanSetting("Invert Pitch", false));
    private final StringSetting blockedServers = add(new StringSetting("Blocked Servers", "", "domains, comma-separated")
            .describe("More servers to keep Freelook off on, e.g. example.net (subdomains included)."));

    private boolean active, toggled, keyWasDown;
    private float yaw, pitch;
    private Perspective savedPerspective;
    /** Whether we switched to third person for this freelook (only then is the old perspective put back). */
    private boolean switchedPerspective;

    /** Cached server check: the address and Blocked Servers text it was worked out for. */
    private String checkedAddress, checkedExtra;
    private boolean blocked;
    /** The server address the "disabled on this server" note was last shown for. */
    private String warnedAddress;

    public Freelook() {
        super("Freelook", "Look around without turning. Disabled on servers that forbid it.", Category.MODS);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            if (active) stop();
            toggled = false;
            keyWasDown = false;
            return;
        }
        boolean down = key.isDown();
        boolean pressed = down && !keyWasDown;
        keyWasDown = down;
        if (blockedOnServer()) {
            if (active) stop();
            toggled = false;
            if (pressed) warnBlocked();
            return;
        }
        boolean want;
        if (mode.is("Toggle")) {
            if (pressed) toggled = !toggled;
            want = toggled;
        } else {
            toggled = false;
            want = down;
        }
        if (want && !active) start();
        else if (!want && active) stop();
    }

    @Override
    protected void onDisable() {
        if (active) stop();
        toggled = false;
        keyWasDown = false;
        warnedAddress = null; // also runs when leaving a world: the note shows again on the next visit
    }

    private void start() {
        active = true;
        yaw = mc.player.getYaw();
        pitch = mc.player.getPitch();
        savedPerspective = mc.options.getPerspective();
        switchedPerspective = false;
        if (thirdPerson.get() && savedPerspective.isFirstPerson()) {
            mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            switchedPerspective = true;
        }
    }

    private void stop() {
        active = false;
        // Only undo our own switch: if the player changed the view themselves meanwhile, leave theirs.
        if (switchedPerspective && savedPerspective != null && mc.options.getPerspective() == Perspective.THIRD_PERSON_BACK) {
            mc.options.setPerspective(savedPerspective);
        }
        switchedPerspective = false;
    }

    /** Whether the camera is free right now (never on a server that forbids it, never while spectating a mob). */
    public boolean active() {
        return active && isEnabled() && !blockedOnServer() && mc.player != null && mc.getCameraEntity() == mc.player;
    }

    /** Receives mouse movement while active (same units vanilla passes to changeLookDirection). */
    public void look(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = MathHelper.clamp(pitch + (float) (dy * 0.15) * (invert.get() ? -1 : 1), -90f, 90f);
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    // ------------------------------------------------------------------ servers that forbid freelook

    /** Whether the server we're playing on forbids freelook (built-in list plus Blocked Servers). */
    public boolean blockedOnServer() {
        ServerInfo info = mc.getCurrentServerEntry();
        String address = info == null || mc.isInSingleplayer() ? null : info.address;
        String extra = blockedServers.get();
        if (!java.util.Objects.equals(address, checkedAddress) || !java.util.Objects.equals(extra, checkedExtra)) {
            checkedAddress = address;
            checkedExtra = extra;
            blocked = address != null && isForbidden(host(address), extra);
        }
        return blocked;
    }

    private void warnBlocked() {
        String address = checkedAddress;
        if (address != null && address.equals(warnedAddress)) return;
        warnedAddress = address;
        Notifications.push("Freelook", "Disabled on this server", false);
    }

    /** Whether {@code host} is one of the forbidden domains or a subdomain of one. */
    public static boolean isForbidden(String host, String extra) {
        if (host.isEmpty()) return false;
        for (String d : FORBIDDEN) if (matches(host, d)) return true;
        if (extra != null) {
            for (String part : extra.split("[,;\\s]+")) {
                String d = cleanDomain(part);
                if (!d.isEmpty() && matches(host, d)) return true;
            }
        }
        return false;
    }

    private static boolean matches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** "Play.Example.net.:25565" -> "play.example.net"; bracketed IPv6 keeps no brackets. */
    public static String host(String address) {
        String raw = address == null ? "" : address.trim();
        if (raw.isEmpty()) return "";
        String host;
        try {
            host = ServerAddress.parse(raw).getAddress();
            if (host.equals("server.invalid") && !raw.toLowerCase(Locale.ROOT).startsWith("server.invalid")) host = stripPort(raw);
        } catch (RuntimeException e) {
            host = stripPort(raw);
        }
        return cleanDomain(host);
    }

    private static String stripPort(String raw) {
        if (raw.startsWith("[")) {
            int end = raw.indexOf(']');
            return end > 0 ? raw.substring(1, end) : raw;
        }
        int colon = raw.indexOf(':');
        return colon >= 0 && colon == raw.lastIndexOf(':') ? raw.substring(0, colon) : raw;
    }

    /** Lower case, no surrounding dots or spaces, no "*." wildcard prefix. */
    private static String cleanDomain(String s) {
        String d = s.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("*.")) d = d.substring(2);
        while (d.startsWith(".")) d = d.substring(1);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        return d;
    }
}
