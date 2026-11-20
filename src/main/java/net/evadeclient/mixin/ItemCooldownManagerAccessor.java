package net.evadeclient.mixin;

import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ItemCooldownManager.class)
public interface ItemCooldownManagerAccessor {
    /** Values are ItemCooldownManager.Entry records (package-private), read via {@link net.evadeclient.util.Cooldowns}. */
    @Accessor("entries")
    Map<Identifier, ?> evade$getEntries();

    @Accessor("tick")
    int evade$getTick();
}
