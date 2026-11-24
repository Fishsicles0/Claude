package net.evadeclient.mixin;

import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.pack.ResourcePackOrganizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.Path;

/** Pack Organizer: which folder a pack screen shows (resource or data packs), and rebuilding its two lists. */
@Mixin(PackScreen.class)
public interface PackScreenAccessor {
    @Accessor("file")
    Path evade$getFile();

    @Invoker("updatePackLists")
    void evade$updatePackLists(ResourcePackOrganizer.AbstractPack focused);
}
