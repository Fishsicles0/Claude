package net.evadeclient.util;

/**
 * Implemented by crit/sharpness particles (DamageParticle, via DamageParticleMixin): a colour the particle keeps for
 * its whole life. Vanilla fades these particles towards yellow/orange every tick; a kept colour is put back after
 * each tick instead.
 */
public interface ParticleColorDuck {
    /**
     * Paints the particle {@code rgb} (0xRRGGBB), keeping vanilla's small per-particle brightness variation, and
     * keeps it that colour until it dies.
     */
    void evade$keepColor(int rgb);
}
