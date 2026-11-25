package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.command.CommandManager.CommandError;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.social.SocialClient;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Projection;
import net.evadeclient.util.ServerRules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Party pings: press the Ping Key (middle mouse by default) to mark where you're looking, up to 256 blocks away (the
 * block you look at, or the mob or player in the way). Party members on the same server see a marker there for
 * 10 seconds with your name, distance and coordinates, a toast and a sound, and a dot on their minimap. Hold Sneak
 * for a danger ping; {@code .ping [danger|loot]} pings from chat.
 *
 * <p>Location sharing both players chose: evadeclient.com only relays a ping to members of the sender's party who are
 * online on the same server, and never stores it. A server can switch Party Ping off through {@code evade:rules}.
 *
 * <p>The Ping Key only does anything while you're in a party, so middle-click pick block works as usual otherwise.
 * In a party, Keep Pick Block In Creative (on by default) leaves the key to pick block in creative while you look at a
 * block within reach; bind Ping Key to a side button (mouse 4/5) to keep both everywhere.
 */
public class PartyPing extends Module {
    /** A ping on screen: who sent it (the sender is you for your own), where and when it arrived. */
    public record Ping(UUID from, String name, double x, double y, double z, String dimension, String kind, long at) {}

    /** A ping as the minimap draws it: world x/z and colour (with its fade). */
    public record MapDot(double x, double z, int color) {}

    public static final long LIFE_MS = 10_000, FADE_MS = 2_000;
    private static final double RANGE = 256;
    public static final int COLOR_LOCATION = 0xFF5AC8FA, COLOR_DANGER = 0xFFFF4D5E, COLOR_LOOT = 0xFFFFC233;
    private static final UUID SELF = new UUID(0, 0);

    public final KeySetting pingKey = add(new KeySetting("Ping Key", KeySetting.mouseCode(GLFW.GLFW_MOUSE_BUTTON_MIDDLE), true)
            .describe("Pings where you look. Hold Sneak for a danger ping. Only while you're in a party."));
    public final BooleanSetting keepPickBlock = add(new BooleanSetting("Keep Pick Block In Creative", true)
            .describe("In creative, looking at a block within reach, the key picks the block as usual instead of pinging."));
    public final BooleanSetting beams = add(new BooleanSetting("Beams", true)
            .describe("A beam of light above each ping, so it's easy to spot from far away."));
    public final BooleanSetting sound = add(new BooleanSetting("Ping Sound", true)
            .describe("A short sound when a party member pings."));
    public final BooleanSetting toasts = add(new BooleanSetting("Ping Toasts", true)
            .describe("A toast saying who pinged and where."));
    public final BooleanSetting minimap = add(new BooleanSetting("Show On Minimap", true)
            .describe("Pings as dots on the Minimap (when it's on)."));
    public final BooleanSetting showOwn = add(new BooleanSetting("Show My Pings", true)
            .describe("Your own pings show for you too, the way your party sees them."));
    public final NumberSetting labelScale = add(new NumberSetting("Label Scale", 1.0, 0.5, 2.0, 0.1)
            .describe("Size of the text label above each ping."));

    /** Pings on screen (render thread), at most one per sender. */
    private static final List<Ping> active = new ArrayList<>();
    private boolean keyWasDown;
    private long lastSent;

    public PartyPing() {
        super("Party Ping", "Ping where you look for your party: a marker, toast and minimap dot on the same server.", Category.MISC);
        setEnabled(true);
    }

    public static PartyPing get() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(PartyPing.class);
    }

    // ------------------------------------------------------------------ receiving

    /**
     * A party member's ping arrived (render thread). Dropped while Party Ping is off or the server switched it off,
     * and outside multiplayer (evadeclient.com already sends pings only to members on the sender's server).
     */
    public static void receive(Ping p) {
        PartyPing m = get();
        if (m == null || !m.isEnabled() || ServerRules.isDisabled(m) || mc.player == null || mc.world == null) return;
        if (!SocialClient.isLoopback() && SocialClient.currentServer(mc) == null) return;
        m.show(p);
        if (m.sound.get()) mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_BELL, "danger".equals(p.kind()) ? 0.8f : 1.6f));
        if (m.toasts.get()) {
            String where = mc.player.hasReducedDebugInfo() || StreamerMode.hidesCoordinates()
                    ? Math.round(Math.sqrt(mc.player.squaredDistanceTo(p.x(), p.y(), p.z()))) + "m away"
                    : MathHelper.floor(p.x()) + ", " + MathHelper.floor(p.y()) + ", " + MathHelper.floor(p.z());
            String dim = p.dimension().equals(dimension()) ? "" : " (" + dimensionName(p.dimension()) + ")";
            String verb = switch (p.kind()) { case "danger" -> " warned at "; case "loot" -> " found loot at "; default -> " pinged "; };
            Notifications.push("Party Ping", p.name() + verb + where + dim, "danger".equals(p.kind()) ? Notifications.Level.WARN : Notifications.Level.SUCCESS, 4000);
        }
    }

    private void show(Ping p) {
        active.removeIf(q -> q.from().equals(p.from()));
        active.add(p);
        while (active.size() > 16) active.removeFirst();
    }

    /** The pings drawn right now (render thread; for tests and the Friends page). */
    public static List<Ping> active() {
        prune();
        return List.copyOf(active);
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        active.removeIf(p -> now - p.at() > LIFE_MS || now < p.at() - 1000);
    }

    /** Opacity of a ping at {@code now}: full, then fading out over its last two seconds. */
    static float alpha(Ping p, long now) {
        long left = LIFE_MS - (now - p.at());
        return left <= 0 ? 0f : Math.min(1f, left / (float) FADE_MS);
    }

    public static int color(String kind) {
        return switch (kind) { case "danger" -> COLOR_DANGER; case "loot" -> COLOR_LOOT; default -> COLOR_LOCATION; };
    }

    /** Pings in your dimension for the Minimap, or none when Party Ping or Show On Minimap is off. Render thread. */
    public static List<MapDot> minimapDots() {
        PartyPing m = get();
        if (m == null || !m.isEnabled() || !m.minimap.get() || active.isEmpty() || mc.world == null) return List.of();
        prune();
        long now = System.currentTimeMillis();
        String dim = dimension();
        List<MapDot> out = new ArrayList<>();
        for (Ping p : active) if (p.dimension().equals(dim)) out.add(new MapDot(p.x(), p.z(), ColorUtil.withAlpha(color(p.kind()), Math.round(255 * alpha(p, now)))));
        return out;
    }

    // ------------------------------------------------------------------ sending

    /** Watches the Ping Key every frame (a quick click between two ticks still counts). */
    private void pollKey() {
        boolean down = pingKey.isDown();
        boolean pressed = down && !keyWasDown;
        keyWasDown = down;
        if (!pressed || mc.player == null || !SocialClient.state().inParty() && !SocialClient.isLoopback()) return;
        // Pick block keeps the middle button in creative while it would pick something within reach.
        if (keepPickBlock.get() && mc.player.isCreative() && pingKey.get() == KeySetting.mouseCode(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)
                && mc.crosshairTarget != null && mc.crosshairTarget.getType() != HitResult.Type.MISS) return;
        pingAtCrosshair(mc.player.isSneaking() ? "danger" : "location", false);
    }

    /** Raycasts where you look (blocks, then any mob or player in front of that) and sends a ping there. */
    public void pingAtCrosshair(String kind, boolean fromCommand) {
        if (mc.player == null || mc.world == null) return;
        if (ServerRules.isDisabled(this)) { say(fromCommand, "Party Ping is disabled by this server"); return; }
        long now = System.currentTimeMillis();
        if (now - lastSent < 1500) { say(fromCommand, "Wait a moment between pings"); return; }
        Vec3d hit = lookTarget(mc.getRenderTickCounter().getTickProgress(true));
        if (hit == null) { say(fromCommand, "Nothing to ping there (256 blocks at most)"); return; }
        lastSent = now;
        String dim = dimension();
        if (showOwn.get()) show(new Ping(SELF, "You", hit.x, hit.y, hit.z, dim, kind, now));
        SocialClient.sendPing(hit.x, hit.y, hit.z, dim, kind).whenComplete((r, e) -> mc.execute(() -> {
            if (e == null) return;
            active.removeIf(p -> p.from().equals(SELF) && p.at() == now);
            String msg = SocialClient.message(e);
            if (fromCommand) CommandManager.print("§c" + msg);
            else Notifications.push("Party Ping", msg, Notifications.Level.WARN, 2500);
        }));
    }

    private static void say(boolean chat, String msg) {
        if (chat) CommandManager.print("§c" + msg);
        else Notifications.push("Party Ping", msg, false);
    }

    /** Where the crosshair points within 256 blocks: the block face hit (as its block), or an entity's feet. */
    public static Vec3d lookTarget(float td) {
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.world == null) return null;
        Vec3d start = cam.getCameraPosVec(td), dir = cam.getRotationVec(td), end = start.add(dir.multiply(RANGE));
        BlockHitResult bh = mc.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, cam));
        boolean block = bh != null && bh.getType() == HitResult.Type.BLOCK;
        double maxSq = block ? bh.getPos().squaredDistanceTo(start) : RANGE * RANGE;
        Box area = cam.getBoundingBox().stretch(dir.multiply(RANGE)).expand(1);
        EntityHitResult eh = ProjectileUtil.raycast(cam, start, block ? bh.getPos() : end, area,
                e -> !e.isSpectator() && e.canHit() && e != mc.player, maxSq);
        if (eh != null) {
            Entity e = eh.getEntity();
            return new Vec3d(e.getX(), e.getY(), e.getZ());
        }
        if (!block) return null;
        var pos = bh.getBlockPos();
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static String dimension() {
        return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
    }

    private static String dimensionName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dim.contains(":") ? dim.substring(dim.indexOf(':') + 1) : dim;
        };
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void onRender(RenderContext ctx) {
        pollKey();
        if (active.isEmpty()) return;
        prune();
        long now = System.currentTimeMillis();
        String dim = dimension();
        int top = mc.world.getTopYInclusive() + 1;
        for (Ping p : active) {
            if (!p.dimension().equals(dim)) continue;
            float a = alpha(p, now);
            int c = color(p.kind());
            double x = Math.floor(p.x()), y = Math.floor(p.y()), z = Math.floor(p.z());
            ctx.boxOutline(x, y, z, x + 1, y + 1, z + 1, ColorUtil.withAlpha(c, Math.round(255 * a)), 2f);
            ctx.boxFilled(new Box(x, y, z, x + 1, y + 1, z + 1), ColorUtil.withAlpha(c, Math.round(0x30 * a)));
            if (beams.get() && top > y + 1)
                ctx.boxFilled(new Box(x + .4, y + 1, z + .4, x + .6, top, z + .6), ColorUtil.withAlpha(c, Math.round(0x70 * a)));
        }
    }

    @Override
    public void onHud(DrawContext ctx, float td) {
        if (active.isEmpty() || mc.player == null) return;
        long now = System.currentTimeMillis();
        String dim = dimension();
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        boolean hideCoords = mc.player.hasReducedDebugInfo();
        Vec3d me = mc.player.getEntityPos();
        float scale = labelScale.getFloat();
        for (Ping p : active) {
            if (!p.dimension().equals(dim)) continue;
            float a = alpha(p, now);
            if (a <= 0.02f) continue;
            double[] sp = Projection.toScreen(new Vec3d(Math.floor(p.x()) + 0.5, Math.floor(p.y()) + 1.6, Math.floor(p.z()) + 0.5));
            if (sp == null || sp[0] < -50 || sp[0] > sw + 50 || sp[1] < -20 || sp[1] > sh + 20) continue;
            long dist = Math.round(me.distanceTo(new Vec3d(p.x(), p.y(), p.z())));
            String line1 = p.name() + " §7" + dist + "m";
            String line2 = hideCoords ? null : MathHelper.floor(p.x()) + ", " + MathHelper.floor(p.y()) + ", " + MathHelper.floor(p.z());
            label(ctx, sp[0], sp[1], line1, line2, color(p.kind()), a, scale);
        }
    }

    /** A dark two-line label like the Waypoints one, centred on (sx, sy), faded by {@code a}. */
    private void label(DrawContext ctx, double sx, double sy, String line1, String line2, int color, float a, float scale) {
        var tr = mc.textRenderer;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate((float) sx, (float) sy);
            if (Math.abs(scale - 1f) > 0.001f) m.scale(scale, scale);
            int w1 = tr.getWidth(line1), w2 = line2 == null ? 0 : tr.getWidth(line2);
            int w = Math.max(w1, w2), h = line2 == null ? 10 : 20;
            int x = -w / 2, y = -h;
            int alpha8 = Math.max(4, Math.round(255 * a));
            ctx.fill(x - 3, y - 2, x + w + 3, y + h, ColorUtil.withAlpha(0xFF000000, Math.round(0xA0 * a)));
            ctx.fill(x - 3, y + h, x + w + 3, y + h + 1, ColorUtil.withAlpha(color, alpha8));
            ctx.drawTextWithShadow(tr, line1, -w1 / 2, y, ColorUtil.withAlpha(0xFFFFFFFF, alpha8));
            if (line2 != null) ctx.drawTextWithShadow(tr, "§8" + line2, -w2 / 2, y + 10, ColorUtil.withAlpha(0xFFFFFFFF, alpha8));
        } finally {
            m.popMatrix();
        }
    }

    @Override
    protected void onDisable() {
        active.clear();
        keyWasDown = false;
    }

    // ------------------------------------------------------------------ .ping

    @Override public List<String> commandAliases() { return List.of("ping"); }

    @Override public List<String> commandHelp() { return List.of("§d.ping§7 [danger|loot] - ping where you look, for your party"); }

    @Override
    public void command(String[] a) {
        Friends f = Friends.get();
        if (f != null) f.activate();
        String kind = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "location";
        if (!kind.equals("location") && !kind.equals("danger") && !kind.equals("loot")) throw new CommandError("Usage: .ping [danger|loot]");
        if (!isEnabled()) throw new CommandError(ServerRules.isDisabled(this) ? "Party Ping is disabled by this server" : "Party Ping is off");
        if (!SocialClient.isLoopback() && !SocialClient.state().inParty()) throw new CommandError("Join a party to ping: .party invite <friend>");
        pingAtCrosshair(kind, true);
    }
}
