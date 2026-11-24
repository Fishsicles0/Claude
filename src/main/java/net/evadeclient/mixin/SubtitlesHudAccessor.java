package net.evadeclient.mixin;

import net.minecraft.client.gui.hud.SubtitlesHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The sound subtitles vanilla lists, for the Subtitles HUD element's size. */
@Mixin(SubtitlesHud.class)
public interface SubtitlesHudAccessor {
    /** Vanilla's SubtitleEntry objects (a package-private type; read each with {@link SubtitleEntryAccessor}). */
    @Accessor("entries") List<Object> evade$entries();
}
