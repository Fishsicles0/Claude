package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.Optional;

/**
 * Player nametags: show your own in third person, and change their size, shadow and background.
 *
 * <p>Only how labels already on screen look: the game decides which players get a nametag, from how far away and
 * whether it shows through walls, exactly as before. Your own tag follows the same rules the game applies to
 * everyone else's (hidden while invisible, with the HUD hidden, or when your team hides nametags).
 */
public class Nametags extends Module {
    public final BooleanSetting showOwn = add(new BooleanSetting("Show Own", true)
            .describe("Show your own nametag in third person view."));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.5, 1.5, 0.05)
            .describe("Size of player nametags."));
    public final BooleanSetting shadow = add(new BooleanSetting("Text Shadow", false)
            .describe("Draw player nametags with a text shadow."));
    public final BooleanSetting customBackground = add(new BooleanSetting("Custom Background", false)
            .describe("Use Background Opacity below instead of the game's Text Background Opacity setting."));
    public final NumberSetting backgroundOpacity = add(new NumberSetting("Background Opacity", 25, 0, 100, 5)
            .describe("Player nametag background in percent, used with Custom Background."));

    /** True while a player's nametag is being queued (set by PlayerEntityRendererMixin), so only those are styled. */
    public static boolean playerLabel;

    private static Nametags instance;

    public Nametags() {
        super("Nametags", "Show your own nametag in third person and restyle player nametags.", Category.VISUALS);
    }

    public static Nametags active() {
        Nametags n = instance;
        if (n == null) {
            if (EvadeClient.modules == null) return null;
            n = instance = EvadeClient.modules.get(Nametags.class);
            if (n == null) return null;
        }
        return n.isEnabled() ? n : null;
    }

    /**
     * Whether {@code entity} is you and your nametag should show: third person, HUD shown (F1 hides it), not
     * invisible, nothing riding you (as the game hides tags of entities with passengers), and your team doesn't
     * hide nametags from its own members or from everyone.
     */
    public static boolean showOwnTag(Entity entity) {
        Nametags n = active();
        if (n == null || !n.showOwn.get()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entity == null || entity != mc.player) return false;
        if (mc.options.getPerspective().isFirstPerson() || !MinecraftClient.isHudEnabled()) return false;
        if (entity.isInvisible() || entity.hasPassengers()) return false;
        AbstractTeam team = entity.getScoreboardTeam();
        if (team != null) {
            AbstractTeam.VisibilityRule rule = team.getNameTagVisibilityRule();
            if (rule == AbstractTeam.VisibilityRule.NEVER || rule == AbstractTeam.VisibilityRule.HIDE_FOR_OWN_TEAM) return false;
        }
        return true;
    }

    /** The scale for player nametags right now (1 when off). */
    public static float labelScale() {
        Nametags n = active();
        return n == null ? 1f : n.scale.getFloat();
    }

    /** The label to queue for a player: with a text shadow when Text Shadow is on, otherwise unchanged. */
    public static Text styleLabel(Text label) {
        Nametags n = active();
        return n != null && n.shadow.get() && label != null ? withShadow(label) : label;
    }

    /**
     * Nametag backgrounds take their opacity from the game's Text Background Opacity setting; with Custom
     * Background on, player nametags use this module's instead. Other labels (mobs, armor stands) are unchanged.
     */
    public static float backgroundOpacity(float vanilla) {
        if (!playerLabel) return vanilla;
        Nametags n = active();
        return n != null && n.customBackground.get() ? n.backgroundOpacity.getFloat() / 100f : vanilla;
    }

    /**
     * {@code label} with every part given the shadow the game draws under normal text: the part's own colour at a
     * quarter brightness. Labels are drawn without a shadow, but a shadow colour in the style is always drawn.
     * Parts that already carry a shadow colour keep it.
     */
    public static Text withShadow(Text label) {
        MutableText out = Text.empty();
        label.visit((style, part) -> {
            if (!part.isEmpty()) {
                Style s = style.getShadowColor() != null ? style : style.withShadowColor(shadowOf(style.getColor()));
                out.append(Text.literal(part).setStyle(s));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private static int shadowOf(TextColor color) {
        int rgb = color == null ? 0xFFFFFF : color.getRgb();
        return 0xFF000000 | ((rgb >> 2) & 0x3F3F3F);
    }
}
