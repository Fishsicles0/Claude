package net.evadeclient.mixin;

import net.evadeclient.util.EntityVisualsDuck;
import net.minecraft.client.render.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Two fields on every entity render state for Hit Color (hurt flash fade) and Item Physics (a dropped item's motion).
 * Render states are rebuilt every frame from their entity, so this is the only way to carry per-entity values from
 * updateRenderState to the draw without allocating anything per frame.
 */
@Mixin(EntityRenderState.class)
public abstract class EntityVisualsRenderStateMixin implements EntityVisualsDuck {
    @Unique private float evade$hurtFade = 1f;
    @Unique private Object evade$itemMotion;

    @Override public float evade$hurtFade() { return evade$hurtFade; }
    @Override public void evade$setHurtFade(float fade) { evade$hurtFade = fade; }
    @Override public Object evade$itemMotion() { return evade$itemMotion; }
    @Override public void evade$setItemMotion(Object motion) { evade$itemMotion = motion; }
}
