package net.evadeclient.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPos")
    void evade$setPos(Vec3d pos);

    @Accessor("thirdPerson")
    void evade$setThirdPerson(boolean thirdPerson);
}
