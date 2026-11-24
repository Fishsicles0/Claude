package net.evadeclient.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.hud.SubtitlesHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The title, subtitle and action-bar text vanilla is showing, for the Titles and Action Bar HUD elements. */
@Mixin(InGameHud.class)
public interface InGameHudTextAccessor {
    @Accessor("title") Text evade$title();
    @Accessor("subtitle") Text evade$subtitle();
    @Accessor("titleRemainTicks") int evade$titleRemainTicks();
    @Accessor("overlayMessage") Text evade$overlayMessage();
    @Accessor("overlayRemaining") int evade$overlayRemaining();
    @Accessor("subtitlesHud") SubtitlesHud evade$subtitlesHud();
}
