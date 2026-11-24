package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

/**
 * Visual-only 1.7 style animations: swing while eating/drinking, the old held-item position, sword blocking (shown
 * while your shield blocks), the old rod and bow poses, red armour when hurt, the fixed hurt tilt, the instant sneak
 * camera and the old heart flash. Everything is drawn on your screen only: no packet, no attack, no change to what
 * the server sees. See MinecraftClientSwingMixin, HeldItemRendererMixin and OldVisualsMixin.
 */
public class OldAnimations extends Module {
    public final BooleanSetting swingWhileUsing = add(new BooleanSetting("Swing While Eating", true)
            .describe("Clicking while you eat, drink or block swings your hand on your screen, like 1.7. Nothing is sent to the server."));
    public final BooleanSetting oldPosition = add(new BooleanSetting("1.7 Item Position", true)
            .describe("Holds items lower in first person, like older versions."));
    public final NumberSetting lower = add(new NumberSetting("Item Lowered By", 0.08, 0, 0.3, 0.01)
            .describe("How far 1.7 Item Position lowers held items, in blocks."));
    public final BooleanSetting swordBlock = add(new BooleanSetting("1.7 Sword Block", true)
            .describe("With a sword in your main hand and a shield in your off hand, blocking shows the old sword-block pose instead of the shield. The shield still blocks as normal."));
    public final BooleanSetting oldRod = add(new BooleanSetting("1.7 Rod", false)
            .describe("Holds fishing rods (and sticks with carrots or fungus) the other way round, like 1.7."));
    public final BooleanSetting oldBow = add(new BooleanSetting("1.7 Bow", false)
            .describe("Tilts the bow toward the middle of the screen while you draw it, like 1.7."));
    public final BooleanSetting redArmor = add(new BooleanSetting("Red Armour", true)
            .describe("Armour flashes red along with the player or mob wearing it when they're hurt, like 1.7."));
    public final BooleanSetting oldHurtTilt = add(new BooleanSetting("1.7 Hurt Tilt", false)
            .describe("Your view always shakes the same way when you're hurt, like 1.7, instead of away from the hit."));
    public final BooleanSetting oldSneak = add(new BooleanSetting("1.7 Sneak Camera", false)
            .describe("Your view drops straight down when you sneak instead of gliding. Camera only: your real eye height is the server's."));
    public final BooleanSetting oldHealthFlash = add(new BooleanSetting("1.7 Health Flash", false)
            .describe("Your hearts flash only right after you take damage, with 1.7's timing, not when you heal."));

    public OldAnimations() {
        super("1.7 Animations", "Visual 1.7 animations: swing while eating, sword block, item poses, red armour.", Category.VISUALS);
    }

    /** The module when it's on and {@code option} is set, else null (for the render hooks). */
    public static OldAnimations with(java.util.function.Function<OldAnimations, BooleanSetting> option) {
        OldAnimations o = active();
        return o != null && option.apply(o).get() ? o : null;
    }

    /**
     * 1.7 Sword Block applies right now: you're using a shield in your off hand with a sword in your main hand. The
     * shield is what blocks (vanilla); this only changes which hand is drawn blocking.
     */
    public static boolean swordBlocking(net.minecraft.entity.player.PlayerEntity p) {
        if (with(o -> o.swordBlock) == null || p == null || !p.isUsingItem() || p.getActiveHand() != net.minecraft.util.Hand.OFF_HAND) return false;
        return p.getActiveItem().getItem() instanceof net.minecraft.item.ShieldItem
                && p.getMainHandStack().isIn(net.minecraft.registry.tag.ItemTags.SWORDS);
    }

    public static OldAnimations active() {
        if (EvadeClient.modules == null) return null;
        OldAnimations o = EvadeClient.modules.get(OldAnimations.class);
        return o != null && o.isEnabled() ? o : null;
    }
}
