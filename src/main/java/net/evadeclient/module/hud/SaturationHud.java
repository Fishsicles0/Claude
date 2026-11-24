package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your hidden hunger saturation (how long until your food bar starts dropping), as text and marked on the hunger
 * bar, with a preview of what the food in your hand would restore.
 *
 * <p>The server only resends saturation when your food or health changes (or saturation reaches zero), so while you
 * sprint and jump the value the client has goes stale. Sync Mode can mark that, or estimate the drain from the same
 * movement, jumps, attacks, mining and Hunger effect the server counts.
 */
public class SaturationHud extends TextHudModule {
    private final BooleanSetting showText = add(new BooleanSetting("Show Text", true)
            .describe("Show the saturation readout. Off keeps only the marks on the hunger bar."));
    private final ModeSetting format = add(new ModeSetting("Format", "Value", "Value", "Labelled", "Of Cap", "Percent")
            .describe("7.5 Sat, Saturation: 7.5, 7.5 / 18 (it can't go above your food level) or a percentage of that cap."));
    private final NumberSetting decimals = add(new NumberSetting("Decimals", 1, 0, 2, 1)
            .describe("Digits after the decimal point."));
    private final BooleanSetting showFood = add(new BooleanSetting("Show Food Level", false)
            .describe("Also show your food level (0 to 20)."));
    private final ModeSetting syncMode = add(new ModeSetting("Sync Mode", "Mark Stale", "Server Only", "Mark Stale", "Estimate")
            .describe("The server only resends saturation when your food or health changes. Mark Stale dims the value once it's likely out of date; Estimate counts sprinting, jumps, swimming, mining and hits to keep it current (it may under-count by up to one point)."));
    private final BooleanSetting dynamicColor = add(new BooleanSetting("Dynamic Color", false)
            .describe("Colour the value from red (empty) through yellow to green (full)."));
    private final NumberSetting warnBelow = add(new NumberSetting("Warn Below", 2, 0, 10, 0.5)
            .describe("Show the value in Warn Color below this much saturation (0 turns it off)."));
    private final ColorSetting warnColor = add(new ColorSetting("Warn Color", 0xFFFF4D5E)
            .describe("The value's colour below Warn Below."));
    private final BooleanSetting emptyAlert = add(new BooleanSetting("Empty Alert", false)
            .describe("A toast and a soft note when saturation runs out and your food bar starts to drop."));
    private final BooleanSetting hideInCreative = add(new BooleanSetting("Hide In Creative", true)
            .describe("Hidden in Creative and Spectator, where there is no hunger."));
    private final ModeSetting barOverlay = add(new ModeSetting("Bar Overlay", "Outline", "Off", "Outline", "Underline")
            .describe("Mark your saturation on the hunger bar: a frame around each shank it covers, or a line under the bar."));
    private final ColorSetting overlayColor = add(new ColorSetting("Overlay Color", 0xFFFFD21F)
            .describe("Colour of the saturation marks on the hunger bar."));
    private final ModeSetting foodPreview = add(new ModeSetting("Food Preview", "Both", "Off", "Text", "Bar", "Both")
            .describe("While you hold food: what eating it would restore, as text, as fading shanks on the hunger bar, or both."));

    private static final Identifier OVERLAY_ID = Identifier.of("evade", "saturation_overlay");
    private static final Identifier FOOD_FULL = Identifier.ofVanilla("hud/food_full");
    private static final Identifier FOOD_HALF = Identifier.ofVanilla("hud/food_half");
    private static final Identifier FOOD_FULL_HUNGER = Identifier.ofVanilla("hud/food_full_hunger");
    private static final Identifier FOOD_HALF_HUNGER = Identifier.ofVanilla("hud/food_half_hunger");
    private static final String[] NUMBER_FORMATS = {"%.0f", "%.1f", "%.2f"};
    /** Vanilla drains one saturation point per 4 exhaustion. */
    private static final float EXHAUSTION_PER_POINT = 4f;
    /** Estimated exhaustion since the last sync from which the value counts as likely out of date. */
    private static final float STALE_EXHAUSTION = 2f;
    /** Moving further than this in one tick (squared, in blocks) is a teleport, which costs no hunger. */
    private static final double TELEPORT_SQ = 8 * 8;
    private static final long PULSE_MS = 1400;

    /** The instance the hunger-bar hook and the block-break mixin report to. */
    private static SaturationHud instance;
    private static boolean overlayRegistered;

    /** Exhaustion the server has most likely added since it last sent saturation. */
    private float estExhaustion;
    /** The player whose movement is being followed, and the player the last health update was for. */
    private WeakReference<PlayerEntity> tracked = new WeakReference<>(null), synced = new WeakReference<>(null);
    private double lastX, lastY, lastZ;
    private boolean hasLast, wasOnGround;
    /** Saturation last tick, or -1 until the server has sent this player's first value (so joins never alert). */
    private float prevSat = -1;

    private record Line(String main, String warn) {
        String plain() { return warn == null ? main : main + warn; }
    }

    public SaturationHud() {
        super("Saturation", "Your hunger saturation, as text and on the hunger bar.", 4, 184);
        instance = this;
        registerOverlay();
    }

    private static synchronized void registerOverlay() {
        if (overlayRegistered) return;
        overlayRegistered = true;
        try {
            HudElementRegistry.attachElementAfter(VanillaHudElements.FOOD_BAR, OVERLAY_ID, (ctx, tickCounter) -> {
                SaturationHud s = instance;
                if (s != null) s.drawOverlay(ctx);
            });
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Saturation couldn't attach its overlay to the hunger bar", e);
        }
    }

    /** The saturation the client has, exactly as the server last sent it. */
    public float saturation() { return mc.player == null ? 0 : mc.player.getHungerManager().getSaturationLevel(); }

    /** The value shown: the client's value, less the points the estimate says the server has drained since. */
    private float shownSaturation() {
        float raw = saturation();
        if (!syncMode.is("Estimate")) return raw;
        return Math.max(0, raw - (float) Math.floor(estExhaustion / EXHAUSTION_PER_POINT));
    }

    /**
     * Whether the server has likely drained saturation without saying so since its last update. Zero is never
     * stale: the server always reports reaching it, and it can't drain further.
     */
    private boolean stale() { return estExhaustion >= STALE_EXHAUSTION && saturation() > 0; }

    // ---- tracking ----------------------------------------------------------------------------------------

    @Override
    protected void onEnable() {
        prevSat = -1;
        hasLast = false;
    }

    @Override
    public void onServerPacket(Packet<?> packet) {
        if (!(packet instanceof HealthUpdateS2CPacket)) return;
        // Fresh from the server (the client's value is now exactly this packet's): nothing is drained since.
        estExhaustion = 0;
        synced = new WeakReference<>(mc.player);
    }

    @Override
    public void onTick() {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        if (tracked.get() != p) {
            // Joined or respawned: nothing carries over from the old player.
            tracked = new WeakReference<>(p);
            estExhaustion = 0;
            hasLast = false;
            prevSat = -1;
        }
        trackExhaustion(p);
        checkEmpty(p);
    }

    /** Adds what this tick cost in exhaustion, mirroring the server's rules for movement, jumps and Hunger. */
    private void trackExhaustion(ClientPlayerEntity p) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        boolean ground = p.isOnGround();
        if (hasLast && !p.getAbilities().invulnerable) {
            double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
            if (!p.hasVehicle() && dx * dx + dz * dz < TELEPORT_SQ) {
                estExhaustion += movementExhaustion(p, dx, dy, dz);
                if (wasOnGround && !ground && dy > 0) estExhaustion += p.isSprinting() ? 0.2f : 0.05f;
            }
            StatusEffectInstance hunger = p.getStatusEffect(StatusEffects.HUNGER);
            if (hunger != null) estExhaustion += 0.005f * (hunger.getAmplifier() + 1);
            estExhaustion = Math.min(estExhaustion, 400f);
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        wasOnGround = ground;
        hasLast = true;
    }

    /** The server's travel cost for one move: swimming or underwater, wading, sprinting on the ground. */
    private static float movementExhaustion(PlayerEntity p, double dx, double dy, double dz) {
        if (dx == 0 && dy == 0 && dz == 0) return 0;
        if (p.isSwimming() || p.isSubmergedIn(FluidTags.WATER)) return 0.01f * cm(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.01f;
        if (p.isTouchingWater()) return 0.01f * cm(Math.sqrt(dx * dx + dz * dz)) * 0.01f;
        if (p.isClimbing()) return 0;
        if (p.isOnGround() && p.isSprinting()) return 0.1f * cm(Math.sqrt(dx * dx + dz * dz)) * 0.01f;
        return 0;
    }

    private static int cm(double blocks) { return Math.round((float) blocks * 100.0F); }

    @Override
    public void onAttack(Entity target) {
        ClientPlayerEntity p = mc.player;
        if (p != null && !p.getAbilities().invulnerable) estExhaustion += 0.1f;
    }

    /** A block you broke (from the interaction-manager mixin): vanilla charges 0.005 exhaustion for it. */
    public static void onBlockBroken() {
        SaturationHud s = instance;
        if (s == null || !s.isEnabled()) return;
        ClientPlayerEntity p = mc.player;
        if (p != null && !p.getAbilities().invulnerable) s.estExhaustion += 0.005f;
    }

    private void checkEmpty(ClientPlayerEntity p) {
        if (synced.get() != p) {
            prevSat = -1;
            return;
        }
        float sat = saturation();
        if (emptyAlert.get() && prevSat > 0 && sat <= 0 && !p.getAbilities().invulnerable) {
            Notifications.push("Saturation empty", "Hunger will now drop", false);
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, 0.5f));
        }
        prevSat = sat;
    }

    // ---- text --------------------------------------------------------------------------------------------

    private String number(float v) {
        int d = Math.max(0, Math.min(2, decimals.getInt()));
        return String.format(Locale.ROOT, NUMBER_FORMATS[d], v);
    }

    private String valueText(float sat, int food, boolean approx) {
        String prefix = approx ? "~" : "";
        String v = prefix + number(sat);
        boolean withFood = showFood.get();
        String text = switch (format.get()) {
            case "Labelled" -> "Saturation: " + v;
            case "Of Cap" -> withFood ? v + " Sat / " + food + " Food" : v + " / " + food;
            case "Percent" -> prefix + (food <= 0 ? 0 : Math.round(Math.min(1f, sat / food) * 100)) + "% Sat";
            default -> v + " Sat";
        };
        if (withFood && !format.is("Of Cap")) text += format.is("Labelled") ? " · Food: " + food : " · " + food + " Food";
        return text;
    }

    /** The food in your main hand, else your offhand, or null. */
    private static FoodComponent heldFood(PlayerEntity p) {
        for (Hand hand : Hand.values()) {
            FoodComponent fc = p.getStackInHand(hand).get(DataComponentTypes.FOOD);
            if (fc != null) return fc;
        }
        return null;
    }

    /** "+6 food +9.6 sat", with "(wastes 2)" for points over a full bar, "Full", or null when not holding food. */
    private Line foodLine(PlayerEntity p) {
        if (!(foodPreview.is("Text") || foodPreview.is("Both"))) return null;
        FoodComponent fc = heldFood(p);
        if (fc == null) return null;
        if (!p.canConsume(fc.canAlwaysEat())) return new Line("Full", null);
        int food = Math.max(0, Math.min(20, p.getHungerManager().getFoodLevel()));
        float sat = Math.min(shownSaturation(), food);
        int nutrition = Math.max(0, fc.nutrition());
        int newFood = Math.min(20, food + nutrition);
        float newSat = Math.min(newFood, sat + Math.max(0, fc.saturation()));
        String main = "+" + (newFood - food) + " food +" + number(Math.max(0, newSat - sat)) + " sat";
        int wasted = food + nutrition - 20;
        return new Line(main, wasted > 0 ? " (wastes " + wasted + ")" : null);
    }

    private List<Line> build(boolean preview) {
        List<Line> out = new ArrayList<>(2);
        if (!showText.get()) return out;
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            if (preview) out.add(new Line(valueText(7.5f, 18, false), null));
            return out;
        }
        if (!preview && hideInCreative.get() && mc.interactionManager != null && !mc.interactionManager.hasStatusBars()) return out;
        int food = p.getHungerManager().getFoodLevel();
        out.add(new Line(valueText(shownSaturation(), food, syncMode.is("Estimate") && stale()), null));
        Line fl = foodLine(p);
        if (fl != null) out.add(fl);
        return out;
    }

    @Override
    protected List<String> lines(boolean preview) {
        List<Line> built = build(preview);
        List<String> out = new ArrayList<>(built.size());
        for (Line l : built) out.add(l.plain());
        return out;
    }

    @Override
    protected List<Text> styledLines(boolean preview) {
        List<Line> built = build(preview);
        List<Text> out = new ArrayList<>(built.size());
        for (Line l : built) {
            MutableText t = Text.literal(l.main());
            if (l.warn() != null) t.append(Text.literal(l.warn()).withColor(Theme.DANGER & 0xFFFFFF));
            out.add(t);
        }
        return out;
    }

    @Override
    protected int lineColor(int index, int base) {
        if (index != 0 || mc.player == null) return base;
        float s = shownSaturation();
        int c = base;
        if (s < warnBelow.get()) c = warnColor.get();
        else if (dynamicColor.get()) c = ColorUtil.healthColor(s / 20f);
        if (syncMode.is("Mark Stale") && stale()) c = dim(c);
        return c;
    }

    private static int dim(int c) {
        int r = (c >> 16 & 0xFF) * 6 / 10, g = (c >> 8 & 0xFF) * 6 / 10, b = (c & 0xFF) * 6 / 10;
        return c & 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ---- hunger bar --------------------------------------------------------------------------------------

    /** Drawn by Fabric right after vanilla's hunger bar, so only while vanilla shows the status bars. */
    private void drawOverlay(DrawContext ctx) {
        if (!isEnabled()) return;
        try {
            drawOverlayInner(ctx);
        } catch (RuntimeException e) {
            ModuleManager.crashed(this, e, "hunger bar");
        }
    }

    private void drawOverlayInner(DrawContext ctx) {
        boolean bar = !barOverlay.is("Off");
        boolean ghost = foodPreview.is("Bar") || foodPreview.is("Both");
        if (!bar && !ghost) return;
        if (!(mc.getCameraEntity() instanceof PlayerEntity player)) return;
        var hunger = player.getHungerManager();
        int food = Math.max(0, Math.min(20, hunger.getFoodLevel()));
        float sat = player == mc.player ? shownSaturation() : hunger.getSaturationLevel();
        // Vanilla's food row: ten shanks right of centre, right to left, on the row above the hotbar's XP bar.
        int right = ctx.getScaledWindowWidth() / 2 + 91, top = ctx.getScaledWindowHeight() - 39;
        FoodComponent fc = ghost ? heldFood(player) : null;
        if (fc != null && player.canConsume(fc.canAlwaysEat())) {
            int newFood = Math.min(20, food + Math.max(0, fc.nutrition()));
            float newSat = Math.min(newFood, Math.min(sat, food) + Math.max(0, fc.saturation()));
            float pulse = pulse();
            drawGhostShanks(ctx, player, right, top, food, newFood, pulse);
            if (bar && newSat > sat) drawSaturation(ctx, right, top, newSat, newFood, scaleAlpha(overlayColor.get(), pulse));
        }
        if (bar) drawSaturation(ctx, right, top, sat, food, overlayColor.get());
    }

    /** 0.35 to 0.8 and back, about once a second and a half. */
    private static float pulse() {
        double t = (System.currentTimeMillis() % PULSE_MS) / (double) PULSE_MS;
        return (float) (0.575 + 0.225 * Math.sin(t * Math.PI * 2));
    }

    private static int scaleAlpha(int color, float f) {
        int a = Math.round((color >>> 24) * Math.max(0, Math.min(1, f)));
        return a << 24 | color & 0xFFFFFF;
    }

    /** Faded shanks for the food points eating would add (shank i holds points 2i+1 and 2i+2). */
    private static void drawGhostShanks(DrawContext ctx, PlayerEntity player, int right, int top, int food, int newFood, float alpha) {
        if (newFood <= food) return;
        boolean hungry = player.hasStatusEffect(StatusEffects.HUNGER);
        Identifier full = hungry ? FOOD_FULL_HUNGER : FOOD_FULL, half = hungry ? FOOD_HALF_HUNGER : FOOD_HALF;
        int color = Math.round(alpha * 255) << 24 | 0xFFFFFF;
        for (int i = 0; i < 10; i++) {
            int x = right - i * 8 - 9;
            int first = 2 * i + 1, second = 2 * i + 2;
            if (second <= newFood && second > food) ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, full, x, top, 9, 9, color);
            else if (first == newFood && first > food) ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, half, x, top, 9, 9, color);
        }
    }

    /**
     * Marks {@code sat} points (never more than {@code cap}, the food level) on the food row: Outline frames each
     * whole shank it covers and the right half of a shank it half covers (vanilla's half shank is the right half),
     * drawn as one frame so shared edges aren't blended twice; Underline draws a line under the bar.
     */
    private void drawSaturation(DrawContext ctx, int right, int top, float sat, int cap, int color) {
        float s = Math.max(0, Math.min(sat, cap));
        if (s <= 0 || (color >>> 24) == 0) return;
        if (barOverlay.is("Underline")) {
            int w = Math.min(81, Math.round(81 * s / 20f));
            if (w > 0) ctx.fill(right - w, top + 9, right, top + 10, color);
            return;
        }
        int points = Math.min(20, (int) Math.floor(s + 1e-4f));
        if (points <= 0) return;
        int whole = points / 2;
        boolean half = (points & 1) != 0;
        int left = half ? right - whole * 8 - 5 : right - whole * 8 - 1;
        ctx.fill(left, top, right, top + 1, color);
        ctx.fill(left, top + 8, right, top + 9, color);
        ctx.fill(right - 1, top + 1, right, top + 8, color);
        for (int k = 1; k <= whole; k++) {
            int x = right - k * 8 - 1;
            ctx.fill(x, top + 1, x + 1, top + 8, color);
        }
        if (half) ctx.fill(left, top + 1, left + 1, top + 8, color);
    }
}
