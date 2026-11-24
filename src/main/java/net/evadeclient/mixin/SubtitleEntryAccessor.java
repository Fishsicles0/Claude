package net.evadeclient.mixin;

import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A vanilla subtitle line's text (the entry type is package-private), for the Subtitles HUD element's width. */
@Mixin(targets = "net.minecraft.client.gui.hud.SubtitlesHud$SubtitleEntry")
public interface SubtitleEntryAccessor {
    @Accessor("text") Text evade$text();
}
