package net.evadeclient.mixin;

import net.minecraft.client.gui.CubeMapRenderer;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The Title Screen's parallax panorama: it draws the game's own cube map at its own angle and keeps the game's
 * rotation in step, so the panorama doesn't jump when another menu (Options, Singleplayer) takes over.
 */
@Mixin(RotatingCubeMapRenderer.class)
public interface RotatingCubeMapAccessor {
    @Accessor("cubeMap")
    CubeMapRenderer evade$cubeMap();

    /** The panorama's turn in degrees (vanilla calls it pitch; it is the yaw). */
    @Accessor("pitch")
    float evade$turn();

    @Accessor("pitch")
    void evade$setTurn(float turn);
}
