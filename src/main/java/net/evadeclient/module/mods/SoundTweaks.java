package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.Identifier;

/**
 * Per-sound volume: turn down (or off) rain, thunder, explosions, fireworks, villagers, anvils, armour equipping and
 * hit sounds, on top of Minecraft's own sound sliders. Only ever makes sounds quieter, never louder than vanilla.
 */
public class SoundTweaks extends Module {
    private final NumberSetting rain = add(percent("Rain", "Rain and rain dripping off roofs."));
    private final NumberSetting thunder = add(percent("Thunder", "Thunder and lightning strikes."));
    private final NumberSetting explosions = add(percent("Explosions", "TNT, creepers, crystals, beds and anchors."));
    private final NumberSetting fireworks = add(percent("Fireworks", "Firework launches, bangs and crackles."));
    private final NumberSetting villagers = add(percent("Villagers", "Villagers and wandering traders."));
    private final NumberSetting anvils = add(percent("Anvils", "Using, placing and landing anvils."));
    private final NumberSetting armourEquip = add(percent("Armour Equip", "Putting armour and elytra on."));
    private final NumberSetting hits = add(percent("Hit Sounds", "Player attack sounds: strong, weak, crit, sweep, knockback."));

    /** The registered instance (looked up once: the sound system asks for every sound it plays or updates). */
    private static SoundTweaks instance;

    public SoundTweaks() {
        super("Sound Tweaks", "Volume sliders for rain, thunder, explosions and other loud sounds.", Category.MODS);
        instance = this;
    }

    private static NumberSetting percent(String name, String what) {
        return new NumberSetting(name, 100, 0, 100, 5).describe("Volume in %, on top of Minecraft's sliders. " + what);
    }

    /** How loud a sound with this id plays, 0 to 1 (1 for anything not covered, or while the module is off). */
    public static float volume(Identifier sound) {
        SoundTweaks st = instance;
        if (sound == null || st == null || !st.isEnabled()) return 1f;
        NumberSetting group = st.groupOf(sound);
        return group == null ? 1f : (float) Math.max(0, Math.min(1, group.get() / 100.0));
    }

    /** The slider for a vanilla sound event, or null when no slider covers it. */
    private NumberSetting groupOf(Identifier sound) {
        if (!Identifier.DEFAULT_NAMESPACE.equals(sound.getNamespace())) return null;
        String p = sound.getPath();
        if (p.startsWith("weather.rain")) return rain;
        if (p.startsWith("entity.lightning_bolt.")) return thunder;
        if (p.equals("entity.generic.explode") || p.equals("entity.dragon_fireball.explode")) return explosions;
        if (p.startsWith("entity.firework_rocket.")) return fireworks;
        if (p.startsWith("entity.villager.") || p.startsWith("entity.wandering_trader.")) return villagers;
        if (p.startsWith("block.anvil.")) return anvils;
        if (p.startsWith("item.armor.equip_")) return armourEquip;
        if (p.startsWith("entity.player.attack.")) return hits;
        return null;
    }
}
