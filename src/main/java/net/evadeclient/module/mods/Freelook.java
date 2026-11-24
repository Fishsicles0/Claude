package net.evadeclient.module.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * Hold (or tap, in Toggle mode) a key to look around your player without changing where you're facing or walking.
 * Some servers forbid this; on those it stays off, whatever the settings say.
 *
 * <p>While freelook is on ({@link #capturing()}) mouse movement turns only the camera. When it ends the camera
 * either snaps back or, with Return Smooth, swings back to where you aim over Return Time ({@link #returning()});
 * aiming works again the moment the key is let go. {@link #active()} covers both, i.e. whenever the camera is not
 * looking where the player aims.
 */
public class Freelook extends Module {
    /** Servers whose rules forbid freelook (each domain also covers its subdomains). Always applied. */
    private static final List<String> FORBIDDEN = List.of("hypixel.net", "playhive.com");

    private final KeySetting key = add(new KeySetting("Freelook Key", GLFW.GLFW_KEY_LEFT_ALT, true));
    private final ModeSetting mode = add(new ModeSetting("Mode", "Hold", "Hold", "Toggle")
            .describe("Hold the key to look around, or tap it to start and tap again to stop."));
    private final BooleanSetting stayInScreens = add(new BooleanSetting("Stay Active In Screens", true)
            .describe("Keep looking around while a menu such as chat or your inventory is open. Off: opening one ends freelook."));
    private final ModeSetting perspective = add(new ModeSetting("Perspective", "Third Person Back",
            "Keep Current", "First Person", "Third Person Back", "Third Person Front")
            .describe("The view to switch to while freelooking; your own view comes back afterwards."));
    private final NumberSetting cameraDistance = add(new NumberSetting("Camera Distance", 100, 25, 100, 5)
            .describe("How far back the third-person camera sits while freelooking, in % of its normal distance. It still stops in front of walls."));
    private final NumberSetting sensitivity = add(new NumberSetting("Sensitivity", 1.0, 0.1, 3.0, 0.05)
            .describe("How fast the camera turns compared with normal aiming."));
    private final BooleanSetting invertYaw = add(new BooleanSetting("Invert Yaw", false)
            .describe("Moving the mouse right turns the camera left."));
    private final BooleanSetting invert = add(new BooleanSetting("Invert Pitch", false)
            .describe("Moving the mouse up tilts the camera down."));
    private final NumberSetting yawLimit = add(new NumberSetting("Yaw Limit", 180, 30, 180, 5)
            .describe("How far left or right of where you face the camera can turn, in degrees. 180: all the way round."));
    private final ModeSetting returnMode = add(new ModeSetting("Return", "Instant", "Instant", "Smooth")
            .describe("When freelook ends, snap straight back to where you aim, or swing back smoothly."));
    private final NumberSetting returnTime = add(new NumberSetting("Return Time", 200, 50, 600, 25)
            .describe("How long the smooth return takes, in milliseconds."));
    private final StringSetting blockedServers = add(new StringSetting("Blocked Servers", "", "domains, comma-separated")
            .describe("More servers to keep Freelook off on, e.g. example.net (subdomains included)."));

    /** Freelook is on (key held or toggled): the mouse turns the camera. */
    private boolean active;
    private boolean toggled, keyWasDown;
    /** The camera is swinging back to where the player aims (Return Smooth), from startYaw/startPitch. */
    private boolean returning;
    private double returnStart;
    private float startYaw, startPitch;
    /** The camera's rotation: free while active, the animated angles while returning. */
    private float yaw, pitch;
    private Perspective savedPerspective, appliedPerspective;
    /** Whether we changed the view for this freelook (only then is the old view put back). */
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
            endNow();
            toggled = false;
            keyWasDown = false;
            return;
        }
        boolean noScreen = mc.currentScreen == null;
        boolean down = physicallyDown();
        // Presses only count in game: typing in chat or a key used inside a menu never starts freelook.
        boolean pressed = down && !keyWasDown && noScreen;
        keyWasDown = down;
        if (blockedOnServer()) {
            endNow();
            toggled = false;
            if (pressed) warnBlocked();
            return;
        }
        boolean stay = stayInScreens.get();
        boolean want;
        if (mode.is("Toggle")) {
            if (pressed) toggled = !toggled;
            if (!noScreen && !stay) toggled = false;
            want = toggled;
        } else {
            toggled = false;
            // Starting needs the game in focus; with Stay Active In Screens a held key keeps it going in a menu.
            want = down && (noScreen || (stay && active));
        }
        if (want && !active) start();
        else if (!want && active) stop();

        // The return is advanced from the camera every frame; finish it if no frame drew it (e.g. the world isn't
        // being rendered), so the view always comes back.
        if (returning) {
            double elapsed = EvadeMenu.clock.getAsDouble() - returnStart;
            if (elapsed < 0 || elapsed > returnTime.get() + 250) finishReturn();
        }
    }

    @Override
    protected void onDisable() {
        endNow();
        toggled = false;
        keyWasDown = false;
        warnedAddress = null; // also runs when leaving a world: the note shows again on the next visit
    }

    /** Whether the freelook key is physically held, menus open or not. */
    private boolean physicallyDown() {
        int code = key.get();
        if (code == GLFW.GLFW_KEY_UNKNOWN || mc.getWindow() == null) return false;
        if (KeySetting.isMouse(code)) {
            return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), KeySetting.mouseButton(code)) == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(mc.getWindow(), code);
    }

    private void start() {
        active = true;
        if (returning) {
            // Pick up from where the return animation is now, in the view freelook already switched to.
            returning = false;
            return;
        }
        yaw = mc.player.getYaw();
        pitch = mc.player.getPitch();
        savedPerspective = mc.options.getPerspective();
        switchedPerspective = false;
        Perspective want = wantedPerspective();
        if (want != null && want != savedPerspective) {
            mc.options.setPerspective(want);
            appliedPerspective = want;
            switchedPerspective = true;
        }
    }

    /** Freelook ends: snap back, or start the smooth return (aiming works again either way). */
    private void stop() {
        active = false;
        if (returnMode.is("Smooth") && mc.player != null && mc.getCameraEntity() == mc.player) {
            returning = true;
            returnStart = EvadeMenu.clock.getAsDouble();
            startYaw = yaw;
            startPitch = pitch;
        } else {
            finishReturn();
        }
    }

    /** Ends freelook and any return at once, putting the view back. */
    private void endNow() {
        active = false;
        finishReturn();
    }

    private void finishReturn() {
        returning = false;
        // Only undo our own switch: if the player changed the view themselves meanwhile, leave theirs.
        if (switchedPerspective && savedPerspective != null && mc.options != null
                && mc.options.getPerspective() == appliedPerspective) {
            mc.options.setPerspective(savedPerspective);
        }
        switchedPerspective = false;
    }

    private Perspective wantedPerspective() {
        return switch (perspective.get()) {
            case "First Person" -> Perspective.FIRST_PERSON;
            case "Third Person Back" -> Perspective.THIRD_PERSON_BACK;
            case "Third Person Front" -> Perspective.THIRD_PERSON_FRONT;
            default -> null; // Keep Current
        };
    }

    /** Whether freelook may touch the camera at all (never on a server that forbids it, never while spectating a mob). */
    private boolean allowed() {
        return isEnabled() && mc.player != null && mc.getCameraEntity() == mc.player && !blockedOnServer();
    }

    /** Whether the mouse turns the camera instead of the player right now (key held or toggled on). */
    public boolean capturing() {
        return active && allowed();
    }

    /** Whether the camera is swinging back to where the player aims after freelook ended (Return Smooth). */
    public boolean returning() {
        return returning && !active && allowed();
    }

    /** Whether the camera is not looking where the player aims: freelooking or returning from it. */
    public boolean active() {
        return (active || returning) && allowed();
    }

    /** Receives mouse movement while capturing (same units vanilla passes to changeLookDirection). */
    public void look(double dx, double dy) {
        double s = 0.15 * sensitivity.get();
        yaw += (float) (dx * s) * (invertYaw.get() ? -1 : 1);
        pitch = MathHelper.clamp(pitch + (float) (dy * s) * (invert.get() ? -1 : 1), -90f, 90f);
        float limit = yawLimit.getFloat();
        if (limit < 180 && mc.player != null) {
            float playerYaw = mc.player.getYaw();
            yaw = playerYaw + MathHelper.clamp(MathHelper.wrapDegrees(yaw - playerYaw), -limit, limit);
        }
    }

    /**
     * Advances the smooth return for this frame towards where the player aims now ({@code targetYaw/targetPitch},
     * vanilla's own camera angles), leaving the result in {@link #yaw()}/{@link #pitch()}. Called by CameraMixin
     * while {@link #returning()}; at the end it puts the player's own view back.
     */
    public void animateReturn(float targetYaw, float targetPitch) {
        float t = returnProgress();
        if (t >= 1) {
            yaw = targetYaw;
            pitch = targetPitch;
            finishReturn();
            return;
        }
        float e = ease(t);
        yaw = startYaw + MathHelper.wrapDegrees(targetYaw - startYaw) * e;
        pitch = MathHelper.lerp(e, startPitch, targetPitch);
    }

    /**
     * Factor for the third-person camera distance (Camera Distance %, never above 1): applied while freelooking and
     * eased back to 1 during a smooth return. CameraMixin multiplies the wanted distance by it.
     */
    public float distanceFactor() {
        float pct = cameraDistance.getFloat() / 100f;
        if (pct >= 1) return 1f;
        if (capturing()) return pct;
        if (returning()) return MathHelper.lerp(ease(Math.min(1f, returnProgress())), pct, 1f);
        return 1f;
    }

    /** 0..1 through the smooth return (1 or more when done; a clock that jumped back counts as done). */
    private float returnProgress() {
        double elapsed = EvadeMenu.clock.getAsDouble() - returnStart;
        double total = returnTime.get();
        if (elapsed < 0 || total <= 0) return 1f;
        return (float) (elapsed / total);
    }

    /** Smoothstep: eases in and out. */
    private static float ease(float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    /**
     * Configs from before Perspective existed had a "Third Person" switch: on became Third Person Back (the old
     * behaviour), off became Keep Current.
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        JsonElement old = settings.get("Third Person");
        if (old == null || settings.has(perspective.getName())) return;
        try {
            perspective.set(old.getAsBoolean() ? "Third Person Back" : "Keep Current");
        } catch (RuntimeException ignored) {
            // unreadable: keep the default
        }
    }

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
