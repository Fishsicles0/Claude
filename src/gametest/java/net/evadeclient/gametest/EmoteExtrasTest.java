package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.cosmetics.CosmeticAssets;
import net.evadeclient.cosmetics.CosmeticItem;
import net.evadeclient.cosmetics.CosmeticsCatalog;
import net.evadeclient.cosmetics.CosmeticsEngine;
import net.evadeclient.cosmetics.EmoteFx;
import net.evadeclient.cosmetics.Emotes;
import net.evadeclient.cosmetics.Wardrobe;
import net.evadeclient.module.misc.Cosmetics;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;

/**
 * The newer emote features: "root" / "offset" keyframes turn and lift the whole model (and vanilla's reset puts it back
 * the next frame), an emote's "fx" spawns particles while it plays, and an emote's prop model loads.
 */
public final class EmoteExtrasTest {
    private EmoteExtrasTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(c -> {
            Wardrobe.setTestMode(true);
            Emotes.moveCamera = false;
            CosmeticsCatalog.loadBundled();
            CosmeticAssets.preloadBundled();
            Cosmetics m = CosmeticsEngine.module();
            if (m != null) { m.setEnabled(true); m.particles.set(100.0); }
        });
        ctx.waitTicks(20);

        // whole-model turn and lift, on a fresh player model posed like the game poses yours
        String pose = ctx.computeOnClient(c -> {
            PlayerEntityModel model = new PlayerEntityModel(c.getLoadedEntityModels().getModelPart(EntityModelLayers.PLAYER), false);
            StringBuilder b = new StringBuilder();
            for (String[] want : new String[][]{{"levitate", "1.2"}, {"breakdance", "1.7"}}) {
                CosmeticItem it = CosmeticsCatalog.get(want[0]);
                if (it == null || it.emote() == null) { b.append(want[0]).append("=missing "); continue; }
                model.setAngles(new PlayerEntityRenderState());
                Emotes.apply(model, new Emotes.Playing(it, it.emote(), Emotes.clock() - Double.parseDouble(want[1])));
                var r = model.getRootPart();
                b.append(want[0]).append(String.format(" y=%.1f roll=%.0f ", r.originY, Math.toDegrees(r.roll)));
            }
            model.setAngles(new PlayerEntityRenderState());
            b.append(String.format("reset y=%.1f roll=%.0f", model.getRootPart().originY, Math.toDegrees(model.getRootPart().roll)));
            return b.toString();
        });
        var nums = java.util.regex.Pattern.compile("levitate y=(-?[\\d.]+) roll=(-?\\d+) breakdance y=(-?[\\d.]+) roll=(-?\\d+) reset y=(-?[\\d.]+) roll=(-?\\d+)").matcher(pose);
        check(nums.find() && Float.parseFloat(nums.group(1)) < -8 && Math.abs(Math.abs(Float.parseFloat(nums.group(4))) - 180) < 2
                        && Math.abs(Float.parseFloat(nums.group(5))) < 0.01 && Integer.parseInt(nums.group(6)) == 0,
                "Emotes: offset lifts the whole model, root turns it upside down, and the next frame resets it (" + pose + ")");

        // particles while an emote with fx plays
        int before = ctx.computeOnClient(c -> EmoteFx.spawnedTotal);
        boolean played = ctx.computeOnClient(c -> Emotes.play(CosmeticsCatalog.get("fire_ring")));
        ctx.waitTicks(20);
        int after = ctx.computeOnClient(c -> EmoteFx.spawnedTotal);
        ctx.runOnClient(c -> Emotes.stop());
        check(played && after > before, "Emotes: Fire Ring spawns its particles while it plays (" + (after - before) + ")");

        // the prop (Kitty Pat's kitten) loads as a model and only belongs to the emote
        boolean prop = ctx.computeOnClient(c -> {
            CosmeticItem it = CosmeticsCatalog.get("kitty_pat");
            return it != null && it.hasModel() && CosmeticAssets.model(it) != null && it.emote() != null;
        });
        check(prop, "Emotes: Kitty Pat's kitten prop loads with the emote");

        ctx.runOnClient(c -> {
            Emotes.stop();
            Emotes.moveCamera = true;
            Wardrobe.setTestMode(false);
        });
    }
}
