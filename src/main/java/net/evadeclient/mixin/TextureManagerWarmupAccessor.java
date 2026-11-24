package net.evadeclient.mixin;

import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Texture warm-up: whether a texture is loaded already, without loading it. */
@Mixin(TextureManager.class)
public interface TextureManagerWarmupAccessor {
    @Accessor("textures")
    Map<Identifier, AbstractTexture> evade$textures();
}
