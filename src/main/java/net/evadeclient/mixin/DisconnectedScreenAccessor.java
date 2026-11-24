package net.evadeclient.mixin;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.network.DisconnectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** AutoReconnect: why we were disconnected (the reason shown on the screen), to skip bans, AFK kicks and the like. */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
    @Accessor("info")
    DisconnectionInfo evade$getInfo();
}
