package net.evadeclient.module.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Hits landed in a row without being hit back.
 *
 * <p>By default (Count: Registered Hits) a hit only counts once the server has confirmed it: it sends a damage
 * event for every hit that really landed, naming who caused it, and none for swings it rejected, hits a shield
 * blocked or hits swallowed by the target's damage cooldown. So spam clicks and misses never inflate the count.
 * Count: Attacks keeps the older behaviour of counting your swings whose target visibly takes the hit.
 */
public class ComboHud extends TextHudModule {
    /** Ticks to wait for the target's hurt animation after a swing (Count: Attacks). */
    private static final int CONFIRM_TICKS = 10;
    /** The count shown in the HUD editor while there is no combo; Format's placeholder digit. */
    private static final int SAMPLE = 5;
    private static final String PLACEHOLDER = "5";
    private static final int SAMPLE_BEST = 9;

    private final ModeSetting count = add(new ModeSetting("Count", "Registered Hits", "Registered Hits", "Attacks")
            .describe("Registered Hits counts only hits the server confirmed. Attacks counts your swings that visibly hurt the target."));
    private final BooleanSetting countProjectiles = add(new BooleanSetting("Count Projectiles", false)
            .describe("Arrows, tridents and other shots you land also add to the combo (Registered Hits only)."));
    private final BooleanSetting playersOnly = add(new BooleanSetting("Players Only", false)
            .describe("Only hits on other players count; hits on mobs are ignored."));
    private final NumberSetting resetAfter = add(new NumberSetting("Reset After", 2.5, 1, 10, 0.5)
            .describe("Seconds without a landed hit before the combo ends."));
    private final ModeSetting resetWhenHurt = add(new ModeSetting("Reset When Hurt", "Any Damage", "Any Damage", "Entity Damage", "Never")
            .describe("What ends the combo when you take damage: any damage, only damage from a mob or player, or nothing."));
    private final BooleanSetting resetOnSwitch = add(new BooleanSetting("Reset On Target Switch", false)
            .describe("Hitting a different target starts a new combo."));
    private final ModeSetting format = add(new ModeSetting("Format", "5 Combo", "5 Combo", "Combo: 5", "x5", "5 Hits")
            .describe("How the count is written; 5 stands for the count."));
    private final ModeSetting whenIdle = add(new ModeSetting("When Idle", "No combo", "No combo", "Zero", "Hide")
            .describe("What shows while there is no combo: the words \"No combo\", the format with 0, or nothing at all."));
    private final BooleanSetting showBest = add(new BooleanSetting("Show Best", false)
            .describe("A second line with your best combo this session. It starts over when you change worlds."));
    private final NumberSetting highlightAt = add(new NumberSetting("Highlight At", 5, 2, 20, 1)
            .describe("The combo turns Highlight Color once it reaches this many hits."));
    private final ColorSetting highlightColor = add(new ColorSetting("Highlight Color", 0xFFFFD21F)
            .describe("Colour of the combo from Highlight At hits on."));
    private final BooleanSetting milestoneSound = add(new BooleanSetting("Milestone Sound", false)
            .describe("A note plays at every multiple of Highlight At, a little higher each time."));
    private final BooleanSetting timerBar = add(new BooleanSetting("Timer Bar", false)
            .describe("A thin bar along the bottom that runs down until the combo expires."));

    private int combo, best;
    /** When the last hit counted (wall clock), the entity it landed on, and the client tick it was counted in. */
    private long lastHit;
    private int lastTargetId = -1;
    private long lastCountTick = -1;
    /** Count: Attacks. The swing waiting to be confirmed: target entity id, its hurt time when swung at, last tick to confirm. */
    private int pendingId = -1, pendingHurt;
    private long pendingUntil;
    private WeakReference<ClientWorld> world = new WeakReference<>(null);

    public ComboHud() { super("Combo Counter", "Hits in a row without being hit.", 4, 136); }

    @Override
    protected void onEnable() {
        combo = 0;
        best = 0;
        lastTargetId = -1;
        lastCountTick = -1;
        pendingId = -1;
        world = new WeakReference<>(mc.world);
    }

    private long expireMs() { return Math.round(resetAfter.get() * 1000); }

    /** The combo ends: it still counts towards the session best. */
    private void endCombo() {
        best = Math.max(best, combo);
        combo = 0;
        lastTargetId = -1;
    }

    /** One more hit on {@code targetId}, confirmed by the server or (Count: Attacks) by the target's hurt animation. */
    private void hit(int targetId) {
        long tick = EvadeClient.ticks;
        // A sweep hurts the target and its neighbours in the same tick; only the first of them is the hit itself.
        if (tick == lastCountTick) return;
        lastCountTick = tick;
        long now = System.currentTimeMillis();
        if (combo > 0 && now - lastHit > expireMs()) endCombo(); // expired since the last tick looked
        if (combo > 0 && resetOnSwitch.get() && targetId != lastTargetId) endCombo();
        combo++;
        best = Math.max(best, combo);
        lastHit = now;
        lastTargetId = targetId;
        int every = Math.max(1, highlightAt.getInt());
        if (milestoneSound.get() && combo % every == 0) {
            float pitch = Math.min(2f, 1f + 0.1f * (combo / every));
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, pitch));
        }
    }

    /** Whether damage to you from {@code causeId} (-1: no entity, e.g. a fall) ends the combo. */
    private boolean hurtEnds(int causeId, int self) {
        return switch (resetWhenHurt.get()) {
            case "Never" -> false;
            case "Entity Damage" -> causeId != -1 && causeId != self;
            default -> true;
        };
    }

    /** Damage events, after vanilla applied them: hits you landed (Registered Hits), and hits you took. */
    @Override
    public void onServerPacket(Packet<?> packet) {
        if (!(packet instanceof EntityDamageS2CPacket p) || !isEnabled() || mc.player == null || mc.world == null) return;
        int self = mc.player.getId();
        if (p.entityId() == self) {
            if (hurtEnds(p.sourceCauseId(), self)) {
                if (combo > 0) endCombo();
                pendingId = -1;
            }
            return;
        }
        if (!count.is("Registered Hits") || p.sourceCauseId() != self) return;
        if (!countProjectiles.get() && p.sourceDirectId() != self) return;
        Entity target = mc.world.getEntityById(p.entityId());
        if (target instanceof ArmorStandEntity) return;
        if (playersOnly.get() && !(target instanceof PlayerEntity)) return;
        hit(p.entityId());
    }

    @Override
    public void onAttack(Entity target) {
        if (!count.is("Attacks")) return;
        if (!(target instanceof LivingEntity living) || target instanceof ArmorStandEntity) return;
        if (playersOnly.get() && !(target instanceof PlayerEntity)) return;
        pendingId = target.getId();
        pendingHurt = living.hurtTime;
        pendingUntil = EvadeClient.ticks + CONFIRM_TICKS;
    }

    @Override
    public void onTick() {
        if (world.get() != mc.world) {
            // A new world or server: the combo and the session best start over.
            world = new WeakReference<>(mc.world);
            combo = 0;
            best = 0;
            lastTargetId = -1;
            pendingId = -1;
        }
        if (!count.is("Attacks")) pendingId = -1;
        if (pendingId >= 0 && mc.world != null) {
            Entity e = mc.world.getEntityById(pendingId);
            if (e instanceof LivingEntity living && living.hurtTime > pendingHurt) {
                int id = pendingId;
                pendingId = -1;
                hit(id);
            } else if (e == null || EvadeClient.ticks > pendingUntil) {
                pendingId = -1;
            } else if (e instanceof LivingEntity living) {
                pendingHurt = Math.min(pendingHurt, living.hurtTime); // still fading: any restart counts
            }
        }
        if (combo > 0 && System.currentTimeMillis() - lastHit > expireMs()) endCombo();
    }

    public int combo() { return combo; }

    /** Best combo since the counter was turned on or the world changed (the running combo included). */
    public int best() { return Math.max(best, combo); }

    /** {@code n} written in the chosen Format. */
    private String text(int n) {
        String fmt = format.get();
        if (n == 1 && fmt.equals("5 Hits")) return "1 Hit";
        return fmt.replace(PLACEHOLDER, Integer.toString(n));
    }

    /** The count on the first line: the combo, or the sample count in the HUD editor while there is none. */
    private int shownCount(boolean preview) { return combo > 0 ? combo : preview ? SAMPLE : 0; }

    @Override
    protected List<String> lines(boolean preview) {
        List<String> out = new ArrayList<>(2);
        if (combo > 0 || preview) out.add(text(shownCount(preview)));
        else if (whenIdle.is("Hide")) return out;
        else out.add(whenIdle.is("Zero") ? text(0) : "No combo");
        if (showBest.get()) {
            int b = best();
            if (b > 0) out.add("Best: " + b);
            else if (preview) out.add("Best: " + SAMPLE_BEST);
        }
        return out;
    }

    /** The plain lines, with the count in Highlight Color once it reaches Highlight At. */
    @Override
    protected List<Text> styledLines(boolean preview) {
        List<String> raw = lines(preview);
        List<Text> out = new ArrayList<>(raw.size());
        boolean hot = highlighted(preview);
        for (int i = 0; i < raw.size(); i++) {
            MutableText t = Text.literal(raw.get(i));
            if (i == 0 && hot) t = t.withColor(highlightColor.get() & 0xFFFFFF);
            out.add(t);
        }
        return out;
    }

    private boolean highlighted(boolean preview) { return shownCount(preview) >= highlightAt.getInt(); }

    /** Draws the text, then the Timer Bar along the bottom edge (every frame, so it runs down smoothly). */
    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        super.render(ctx, left, top, preview);
        if (!timerBar.get() || empty) return;
        float frac;
        if (combo > 0) frac = 1f - (System.currentTimeMillis() - lastHit) / (float) Math.max(1, expireMs());
        else if (preview) frac = 0.6f;
        else return;
        int w = Math.round((width - 4) * Math.max(0f, Math.min(1f, frac)));
        if (w <= 0) return;
        int c = highlighted(preview) ? highlightColor.get() : color.get();
        ctx.fill(left + 2, top + height - 1, left + 2 + w, top + height, c);
    }

    /** Hide When Zero (a switch before When Idle existed) on becomes When Idle: Hide. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (!settings.has(whenIdle.getName()) && isTrue(settings, "Hide When Zero") && whenIdle.isDefault()) whenIdle.set("Hide");
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
