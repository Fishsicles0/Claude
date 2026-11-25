package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.cosmetics.CosmeticAssets;
import net.evadeclient.cosmetics.CosmeticItem;
import net.evadeclient.cosmetics.CosmeticsCatalog;
import net.evadeclient.cosmetics.Rarity;
import net.evadeclient.cosmetics.Slot;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The starter cosmetics' texture density, regular and special alike: every bundled cape at least 16 texels per model
 * unit, every other modelled item at least 8; and the regular items' thumbnails at the special items' size (at least
 * 168 px a frame), so the Wardrobe grid and the shop cards aren't upscaled. Runs after CosmeticsTest (which loads the
 * bundled set).
 */
public final class CosmeticsQualityTest {
    private CosmeticsQualityTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(c -> {
            CosmeticsCatalog.loadBundled();
            CosmeticAssets.preloadBundled();
        });
        ctx.waitTicks(20);
        String density = ctx.computeOnClient(c -> {
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (CosmeticItem i : CosmeticsCatalog.all()) {
                if (!i.bundled) continue;
                var m = CosmeticAssets.model(i);
                if (m == null) continue;                    // particle effects and emotes have no model
                if (m.texture() == null) { b.append(i.id).append("=notexture "); continue; }
                int perUnit = m.texture().frameW / m.texW;
                if (perUnit < (i.slot == Slot.CAPE ? 16 : 8)) b.append(i.id).append('@').append(perUnit).append(' ');
                n++;
            }
            return n + " " + b;
        });
        check(density.matches("\\d+ ") && Integer.parseInt(density.trim()) >= 40,
                "Cosmetics quality: every bundled cape is 16+ texels per unit, every other modelled item 8+ (" + density.trim() + ")");
        String thumbs = ctx.computeOnClient(c -> {
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (CosmeticItem i : CosmeticsCatalog.all()) {
                if (!i.bundled || i.rarity != Rarity.REGULAR || i.slot == Slot.PET || i.slot == Slot.AURA) continue;
                int w = pngWidth(i.id);
                if (w < 168) b.append(i.id).append('=').append(w).append(' ');
                n++;
            }
            return n + " " + b;
        });
        check(thumbs.matches("\\d+ ") && Integer.parseInt(thumbs.trim()) >= 40,
                "Cosmetics quality: regular items' thumbnails are 168+ px, like the special items' (" + thumbs.trim() + ")");
    }

    /** The width of a bundled item's thumb.png, from its PNG header (0 if missing). */
    private static int pngWidth(String id) {
        try (var in = CosmeticsQualityTest.class.getResourceAsStream("/assets/evade/cosmetics/" + id + "/thumb.png")) {
            if (in == null) return 0;
            byte[] h = in.readNBytes(24);
            if (h.length < 24) return 0;
            return ((h[16] & 0xFF) << 24) | ((h[17] & 0xFF) << 16) | ((h[18] & 0xFF) << 8) | (h[19] & 0xFF);
        } catch (java.io.IOException e) {
            return 0;
        }
    }
}
