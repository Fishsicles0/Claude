package net.evadeclient.util;

/**
 * Extra per-frame data on every entity render state (EntityRenderState, via EntityVisualsRenderStateMixin), filled
 * while the state is built from its entity and read while it is drawn, when the entity itself is no longer at hand.
 */
public interface EntityVisualsDuck {
    /** Hit Color: how much of the hurt flash is left this frame, 1 (just hit) down to 0. 1 when never set. */
    float evade$hurtFade();

    void evade$setHurtFade(float fade);

    /** Item Physics: the dropped item's motion state for this frame (an ItemPhysics.Motion), or null. */
    Object evade$itemMotion();

    void evade$setItemMotion(Object motion);
}
