package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;

import java.util.EnumSet;

/**
 * Alerts about your own player: your totem pops, low health, hunger or air, where you died and a full inventory.
 * Only ever looks at you: other players' pops, health and whereabouts are none of its business.
 */
public class Notifier extends Module {
    private final BooleanSetting pops = add(new BooleanSetting("Totem Pops", true)
            .describe("Alerts you each time your own totem pops, with how many you have popped since you last died."));
    private final BooleanSetting lowHealth = add(new BooleanSetting("Low Health", false)
            .describe("Alerts you once when your health drops to Health Threshold."));
    private final NumberSetting healthThreshold = add(new NumberSetting("Health Threshold", 6, 1, 19, 1)
            .describe("In health points: 2 is one heart, so 6 is three hearts."));
    private final BooleanSetting lowHunger = add(new BooleanSetting("Low Hunger", true)
            .describe("Alerts you once when your food bar drops to Hunger Threshold."));
    private final NumberSetting hungerThreshold = add(new NumberSetting("Hunger Threshold", 6, 1, 19, 1)
            .describe("In food points: 2 is one drumstick. At 6 or less you can no longer sprint."));
    private final BooleanSetting lowAir = add(new BooleanSetting("Low Air", false)
            .describe("Alerts you under water once 30% of your air is left."));
    private final BooleanSetting deathCoords = add(new BooleanSetting("Death Coords", true)
            .describe("Says in chat where you died; click the coordinates to copy them. Not on servers that hide "
                    + "coordinates."));
    private final BooleanSetting inventoryFull = add(new BooleanSetting("Inventory Full", false)
            .describe("Alerts you once when your inventory has no free slot left."));
    private final ModeSetting output = add(new ModeSetting("Output", "Chat + Toast", AlertOutput.MODES)
            .describe("Where alerts show: a chat line, a toast at the side of the screen, both, or a title in the middle."));
    private final ModeSetting sound = add(new ModeSetting("Sound", "Off", "Off", "Pling", "Bell", "Anvil")
            .describe("A sound with each alert."));
    private final ColorSetting accent = add(new ColorSetting("Accent Color", 0xFFE11D2E)
            .describe("Colour of an alert's title, and of the coordinates in the death message."));

    private enum Alert { HEALTH, HUNGER, AIR, INVENTORY }

    /** Alerts already given; each one re-arms once you have recovered (see {@link #latch}). */
    private final EnumSet<Alert> fired = EnumSet.noneOf(Alert.class);
    /** Your pops since you last died or joined a world. */
    private int ownPops;
    /** The connection seen last; a new one (another server or world) starts afresh. */
    private Object session;
    /** The player entity seen last tick (a new one after respawning or changing dimension) and whether it was dead. */
    private ClientPlayerEntity player;
    private boolean wasDead;
    /** Where the server last said you died, to catch a death that skipped the death screen (instant respawn). */
    private GlobalPos knownDeath;

    public Notifier() {
        super("Notifier", "Alerts about your own player: totem pops, low health, hunger or air, your death spot and a "
                + "full inventory.", Category.MISC);
    }

    /** Called from the network mixin when the local player plays the totem-pop status (35). */
    public static void onPop(Entity e) {
        if (EvadeClient.modules == null || e == null || e != EvadeClient.mc().player) return;
        Notifier n = EvadeClient.modules.get(Notifier.class);
        if (n == null || !n.isEnabled()) return;
        n.syncSession();
        int c = ++n.ownPops;
        if (!n.pops.get()) return;
        n.alert("Totem popped", c == 1 ? "You popped a totem" : "You popped " + c + " totems this life");
    }

    /** How many totems you have popped since your last death or joining the world (for the tests). */
    public int ownPops() { return ownPops; }

    /** Same as {@link #ownPops()} (kept for the tests written against it). */
    public int pops() { return ownPops; }

    @Override
    protected void onDisable() {
        ownPops = 0;
        fired.clear();
        player = null;
        session = null;
        knownDeath = null;
    }

    @Override
    public void onTick() {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        syncSession();
        boolean dead = p.isDead() || p.getHealth() <= 0;
        if (p != player) {
            // Respawned, changed dimension or just joined. A new death spot with no death seen means an instant
            // respawn skipped it; the first player of a connection only notes the spot (that death is old news).
            GlobalPos serverDeath = p.getLastDeathPos().orElse(null);
            if (player != null && !wasDead && serverDeath != null && !serverDeath.equals(knownDeath)) onDeath(serverDeath);
            knownDeath = serverDeath;
            player = p;
        } else if (dead && !wasDead) {
            onDeath(GlobalPos.create(mc.world.getRegistryKey(), p.getBlockPos()));
        }
        wasDead = dead;
        if (!dead) checkStatus(p);
    }

    /** A new connection (another server, or a world opened again) starts the count and the alerts afresh. */
    private void syncSession() {
        Object s = mc.getNetworkHandler();
        if (s != session) {
            session = s;
            ownPops = 0;
            fired.clear();
            player = null;
            knownDeath = null;
        }
    }

    private void onDeath(GlobalPos at) {
        ownPops = 0; // the count restarts with each life
        fired.clear();
        if (deathCoords.get()) deathMessage(at);
    }

    /** "You died at 120 64 -30 in minecraft:overworld", the coordinates click-to-copy. Never where coordinates are hidden. */
    private void deathMessage(GlobalPos at) {
        if (mc.player == null || mc.player.hasReducedDebugInfo()) return;
        BlockPos b = at.pos();
        String xyz = b.getX() + " " + b.getY() + " " + b.getZ();
        Style copy = Style.EMPTY.withColor(accent.get() & 0xFFFFFF)
                .withClickEvent(new ClickEvent.CopyToClipboard(xyz))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to copy")));
        AlertOutput.chat(Text.literal("You died at ").formatted(Formatting.GRAY)
                .append(Text.literal(xyz).setStyle(copy))
                .append(Text.literal(" in " + at.dimension().getValue()).formatted(Formatting.DARK_GRAY)));
    }

    /** Health, hunger, air and a full inventory: each alerts once, then waits until you have recovered. */
    private void checkStatus(ClientPlayerEntity p) {
        if (p.isCreative() || p.isSpectator()) return; // none of it matters there
        float health = p.getHealth();
        double hp = healthThreshold.get();
        latch(Alert.HEALTH, lowHealth.get(), health <= hp, health > hp + 2,
                () -> alert("Low health", hearts(health) + " left"));
        int food = p.getHungerManager().getFoodLevel();
        double fp = hungerThreshold.get();
        latch(Alert.HUNGER, lowHunger.get(), food <= fp, food > fp + 2,
                () -> alert("Low hunger", "Food " + food + "/20: time to eat"));
        int air = p.getAir(), maxAir = p.getMaxAir();
        latch(Alert.AIR, lowAir.get(), maxAir > 0 && air <= maxAir * 0.3, air >= maxAir,
                () -> alert("Low air", Math.max(0, Math.round(100f * air / maxAir)) + "% air left: surface"));
        boolean full = p.getInventory().getEmptySlot() == -1;
        latch(Alert.INVENTORY, inventoryFull.get(), full, !full,
                () -> alert("Inventory full", "No free slot left"));
    }

    /** Fires {@code alert} once when {@code low}; it re-arms once {@code recovered}, or when its setting is off. */
    private void latch(Alert alert, boolean enabled, boolean low, boolean recovered, Runnable fire) {
        if (!enabled) {
            fired.remove(alert);
        } else if (low) {
            if (fired.add(alert)) fire.run();
        } else if (recovered) {
            fired.remove(alert);
        }
    }

    /** "3 hearts", "2.5 hearts", "1 heart" for a health value in half-hearts. */
    private static String hearts(float health) {
        float h = Math.max(0, Math.round(health) / 2f);
        String n = h == Math.floor(h) ? String.valueOf((int) h) : String.valueOf(h);
        return n + (h == 1 ? " heart" : " hearts");
    }

    /** One alert through Output, in Accent Color, with the chosen Sound. */
    private void alert(String title, String body) {
        AlertOutput.send(output.get(), Text.literal(title).withColor(accent.get() & 0xFFFFFF), title, body);
        AlertOutput.play(sound.get(), 1.0f, 0.7f);
    }
}
