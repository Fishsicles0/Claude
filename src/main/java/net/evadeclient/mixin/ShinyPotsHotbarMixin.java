package net.evadeclient.mixin;

import net.evadeclient.module.visuals.ShinyPots;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ShinyPots "Slot Tint" with "Tint Hotbar": the potion colour behind hotbar (and off-hand) potions. */
@Mixin(InGameHud.class)
public abstract class ShinyPotsHotbarMixin {
    @Inject(method = "renderHotbarItem", at = @At("HEAD"))
    private void evade$shinyPotsTint(DrawContext ctx, int x, int y, RenderTickCounter tickCounter, PlayerEntity player,
                                     ItemStack stack, int seed, CallbackInfo ci) {
        ShinyPots.drawHotbarTint(ctx, stack, x, y);
    }
}
