package net.evadeclient.mixin;

import net.evadeclient.module.misc.PackOrganizer;
import net.minecraft.client.gui.screen.pack.PackListWidget;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.pack.ResourcePackOrganizer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.stream.Stream;

/**
 * Pack Organizer: the Available list of the resource pack screen lists favourites first (or only favourites). The
 * Selected list (load order) and data pack screens are passed through unchanged; with the module off nothing changes.
 */
@Mixin(PackListWidget.class)
public abstract class PackListWidgetMixin {
    @Shadow @Final PackScreen screen;

    @ModifyVariable(method = "set", at = @At("HEAD"), argsOnly = true)
    private Stream<ResourcePackOrganizer.Pack> evade$arrange(Stream<ResourcePackOrganizer.Pack> packs) {
        return PackOrganizer.arrange(screen, packs);
    }
}
