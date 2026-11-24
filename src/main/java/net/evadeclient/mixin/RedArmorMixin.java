package net.evadeclient.mixin;

import net.evadeclient.module.visuals.OldAnimations;
import net.evadeclient.util.Guard;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 1.7 Animations "Red Armour": worn armour gets the same red hurt overlay as the body under it while the wearer is
 * hurt (vanilla leaves armour untinted). It reads only the hurt flash vanilla already shows on the body, so nothing
 * new is revealed; Hit Color and Damage Tint recolour it with the body.
 */
@Mixin(EquipmentRenderer.class)
public abstract class RedArmorMixin {
    @ModifyArg(method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/command/RenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"),
            index = 5)
    private int evade$redArmor(Model<?> model, Object state, MatrixStack matrices, RenderLayer layer, int light, int overlay,
                               int tint, Sprite sprite, int outline, ModelCommandRenderer.CrumblingOverlayCommand crumbling) {
        return Guard.getInt(OldAnimations.class, "red armor", () -> {
            if (!(state instanceof LivingEntityRenderState s) || !s.hurt) return overlay;
            if (OldAnimations.with(o -> o.redArmor) == null) return overlay;
            return OverlayTexture.packUv(OverlayTexture.getU(0f), OverlayTexture.getV(true));
        }, overlay);
    }
}
