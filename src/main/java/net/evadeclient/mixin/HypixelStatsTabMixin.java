package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.module.misc.HypixelStats;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hypixel Stats: appends the player's stat to the name the tab list draws. Wraps the single
 * {@code getPlayerName} call in {@code render} (checked against 1.21.11) rather than the method itself, so it
 * stacks with the Evade Icons hook on {@code getPlayerName} and the tab list measures the longer name itself.
 * Draws nothing over names above heads; only the tab list changes.
 */
@Mixin(PlayerListHud.class)
public abstract class HypixelStatsTabMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/PlayerListHud;getPlayerName(Lnet/minecraft/client/network/PlayerListEntry;)Lnet/minecraft/text/Text;"))
    private Text evade$hypixelStats(PlayerListHud self, PlayerListEntry entry, Operation<Text> original) {
        return HypixelStats.decorate(entry, original.call(self, entry));
    }
}
