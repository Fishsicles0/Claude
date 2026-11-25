package net.evadeclient.gui.title;

import net.evadeclient.EvadeClient;
import net.evadeclient.cosmetics.CosmeticAssets;
import net.evadeclient.cosmetics.Loadout;
import net.evadeclient.cosmetics.Wardrobe;
import net.evadeclient.module.misc.Cosmetics;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;

/**
 * The main menu's Wardrobe card: you, turning slowly on a small 3D stand in what you have equipped, beside a line
 * about it and a Wardrobe button (the button is a widget the screen adds). The figure ({@link TeaserFigure}) is drawn
 * like the Wardrobe's own preview, with the same cosmetics code used read-only, but in your own skin and with its
 * picture kept between frames.
 *
 * <p>Note for the lead: the Wardrobe's own preview ({@code WardrobePreview}) still uses the default skin for your UUID
 * outside a world; an optional skin argument there would match this card.
 */
public final class WardrobeTeaser {
    private WardrobeTeaser() {}

    private static boolean preloaded;
    /** Frames in which the 3D preview failed (it stops trying after a few, and the card shows only its text). */
    private static int failures;

    /** Whether the card can be offered: the Cosmetics mod exists (it may be off: then you're shown as you are). */
    public static boolean available() {
        return EvadeClient.modules != null && Cosmetics.get() != null && failures < 3;
    }

    /** What you wear, owned items only; nothing when Cosmetics or Show My Cosmetics is off. */
    public static Loadout loadout() {
        Cosmetics c = Cosmetics.get();
        if (c == null || !c.isEnabled() || !c.showMine.get()) return Loadout.EMPTY;
        try {
            return Wardrobe.renderLoadout();
        } catch (RuntimeException e) {
            return Loadout.EMPTY;
        }
    }

    /** The card's line: "3 cosmetics equipped", "Nothing equipped yet" or "Cosmetics are off". */
    public static String summary() {
        Cosmetics c = Cosmetics.get();
        if (c != null && !c.isEnabled()) return "Cosmetics are off";
        int n = loadout().all().size();
        return n == 0 ? "Nothing equipped yet" : n + (n == 1 ? " cosmetic equipped" : " cosmetics equipped");
    }

    /**
     * The turning figure in (x1, y1)-(x2, y2), with a soft accent floor glow under its feet. {@code turn} false holds
     * it at a three-quarter view (Reduce Motion).
     */
    public static void figure(DrawContext ctx, net.minecraft.entity.player.SkinTextures skin, int x1, int y1, int x2, int y2,
                              boolean turn, float alpha) {
        if (alpha <= 0.01f || x2 - x1 < 8 || y2 - y1 < 16) return;
        if (!preloaded) {
            preloaded = true;
            try {
                CosmeticAssets.preloadBundled();
            } catch (RuntimeException e) {
                EvadeClient.LOG.warn("Title Screen: couldn't preload cosmetics", e);
            }
        }
        int cx = (x1 + x2) / 2;
        TitleArt.glow(ctx, cx, y2 - 2, (x2 - x1) * 2, Math.max(10, (y2 - y1) / 5), Theme.alpha(Theme.RED, 0.45f * alpha));
        TitleArt.glow(ctx, cx, (y1 + y2) / 2, (x2 - x1) * 2, (y2 - y1), Theme.alpha(Theme.RED, 0.10f * alpha));
        if (alpha < 0.99f) return; // the model can't fade or slide: it appears once the card has arrived
        // Still (Light effects, Reduce Motion): one fixed moment, so the picture is rendered once and kept.
        float time = turn ? (float) (Intro.now() / 1000.0) : 2f;
        float yaw = turn ? 25f + (time * 28f) % 360f : 25f; // starts facing you, three-quarter
        try {
            TeaserFigure.draw(ctx, skin, loadout(), yaw, -6f, time, x1, y1, x2, y2);
        } catch (RuntimeException e) {
            failures++;
            EvadeClient.LOG.warn("Title Screen: Wardrobe preview failed", e);
        }
    }
}
