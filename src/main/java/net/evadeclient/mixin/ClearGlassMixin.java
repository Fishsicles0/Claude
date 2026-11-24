package net.evadeclient.mixin;

import net.evadeclient.module.visuals.PackTweaks;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

/** Pack Tweaks Clear Glass: plain glass's texture loses the streaks inside its frame as it is loaded into the atlas. */
@Mixin(SpriteContents.class)
public abstract class ClearGlassMixin {
    @Inject(method = "<init>(Lnet/minecraft/util/Identifier;Lnet/minecraft/client/texture/SpriteDimensions;Lnet/minecraft/client/texture/NativeImage;Ljava/util/Optional;Ljava/util/List;Ljava/util/Optional;)V",
            at = @At("TAIL"))
    private void evade$clearGlass(Identifier id, SpriteDimensions dimensions, NativeImage image, Optional<?> animation,
                                  List<?> metadata, Optional<?> textureMetadata, CallbackInfo ci) {
        try {
            PackTweaks.onSpriteLoaded(id, image);
        } catch (RuntimeException ignored) {
            // never break texture loading over a visual tweak
        }
    }
}
