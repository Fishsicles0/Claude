package net.evadeclient.gui.menu;

import com.mojang.authlib.GameProfile;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.social.MojangNames;
import net.evadeclient.social.SocialClient;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Skin heads for the menu (friends, party, the account chip): the face plus hat layer, scaled with nearest sampling,
 * on a small rounded tile with an optional presence ring. The default skin for the UUID shows at once; the player's
 * own replaces it once the session service has returned their profile (once per player per session, off thread,
 * never offline or in tests).
 */
public final class Heads {
    private Heads() {}

    private static final Map<UUID, Supplier<SkinTextures>> SKINS = new HashMap<>();
    private static final Map<UUID, Boolean> FETCHED = new HashMap<>();

    /** The head of {@code id} at (x, y), {@code size} px; {@code ring} (0 = none) a presence ring colour. */
    public static void draw(DrawContext ctx, UUID id, String name, int x, int y, int size, int ring) {
        Paint.rect(ctx, x - 1, y - 1, x + size + 1, y + size + 1, Tokens.R_XS + 1, Tokens.N250);
        if (id != null) {
            try {
                PlayerSkinDrawer.draw(ctx, skin(id, name == null ? "" : name).get(), x, y, size, Theme.fa(0xFFFFFFFF));
            } catch (RuntimeException e) {
                // a skin that can't load yet: the tile stays empty
            }
        }
        if (ring != 0) {
            Paint.ring(ctx, x - 2, y - 2, x + size + 2, y + size + 2, Tokens.R_XS + 2, ring, 1.5f);
            Paint.circle(ctx, x + size, y + size, 7, Tokens.N100);
            Paint.circle(ctx, x + size, y + size, 5, ring);
        }
    }

    private static Supplier<SkinTextures> skin(UUID id, String name) {
        Supplier<SkinTextures> s = SKINS.get(id);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (s == null) {
            s = mc.getSkinProvider().supplySkinTextures(new GameProfile(id, name.isEmpty() ? "Player" : name), false);
            SKINS.put(id, s);
        }
        if (!FETCHED.containsKey(id) && !SocialClient.isLoopback() && !MojangNames.offline) {
            FETCHED.put(id, true);
            CompletableFuture.supplyAsync(() -> mc.getApiServices().sessionService().fetchProfile(id, false), Util.getDownloadWorkerExecutor())
                    .whenComplete((r, err) -> {
                        if (err != null || r == null) return;
                        mc.execute(() -> SKINS.put(id, mc.getSkinProvider().supplySkinTextures(r.profile(), false)));
                    })
                    .exceptionally(e -> {
                        EvadeClient.LOG.debug("Menu: no profile for {}", id);
                        return null;
                    });
        }
        return s;
    }
}
