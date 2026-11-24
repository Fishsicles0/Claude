package net.evadeclient.mixin;

import net.evadeclient.module.client.CooldownHud;
import net.evadeclient.util.Guard;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cooldowns "Hotbar Timer": the time left written over hotbar (and off-hand) items on cooldown. Draws only. */
@Mixin(InGameHud.class)
public abstract class CooldownHotbarMixin {
    @Inject(method = "renderHotbarItem", at = @At("TAIL"))
    private void evade$cooldownHotbarTimer(DrawContext ctx, int x, int y, RenderTickCounter tickCounter, PlayerEntity player,
                                           ItemStack stack, int seed, CallbackInfo ci) {
        Guard.run(CooldownHud.class, "hotbar timer", () -> {
            if (stack.isEmpty()) return;
            CooldownHud.drawHotbarTimer(ctx, player, stack, x, y);
        });
    }
}
