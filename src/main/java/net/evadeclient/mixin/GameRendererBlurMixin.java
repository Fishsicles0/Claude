package net.evadeclient.mixin;

import net.evadeclient.module.visuals.InventoryBlur;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Inventory Blur strength. The menu blur shader reads its radius from the per-frame global uniforms (MenuBlurRadius),
 * which vanilla fills from the Menu Background Blur option; while Inventory Blur is on and a container screen is open,
 * the module's Strength goes there instead; Menu Blur does the same for the other menus it covers. Every other
 * screen keeps the game's setting.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererBlurMixin {
    @ModifyArg(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gl/GlobalSettings;set(IIDJLnet/minecraft/client/render/RenderTickCounter;ILnet/minecraft/client/render/Camera;Z)V"),
            index = 5)
    private int evade$inventoryBlurRadius(int vanilla) {
        return net.evadeclient.module.visuals.MenuBlur.blurRadius(InventoryBlur.blurRadius(vanilla));
    }
}
