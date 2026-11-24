package net.evadeclient.mixin;

import net.minecraft.client.gui.screen.pack.PackListWidget;
import net.minecraft.client.gui.screen.pack.PackScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Pack Organizer: the screen a pack list belongs to. */
@Mixin(PackListWidget.class)
public interface PackListWidgetAccessor {
    @Accessor("screen")
    PackScreen evade$getScreen();
}
