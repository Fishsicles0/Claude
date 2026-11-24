package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.evadeclient.social.FriendColors;
import net.evadeclient.module.misc.EvadeIcons;
import net.evadeclient.module.visuals.TabTweaks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    /**
     * Evade Icons: prefix the tab-list name with the player's icon glyph. Because the icon is part of the name,
     * the tab list measures it itself, so wider entries never overlap the name or the ping bars.
     */
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void evade$icon(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        // Friend Colours first (only the player's own name inside the entry), then the icon in front.
        Text decorated = EvadeIcons.decorate(entry.getProfile().id(), FriendColors.tab(entry, cir.getReturnValue()));
        if (decorated != cir.getReturnValue()) cir.setReturnValue(decorated);
    }

    /**
     * Tab Tweaks "Numeric Ping": the ping as a coloured number, right-aligned where the bars would be. An unknown
     * ping (negative, e.g. just after joining) keeps vanilla's icon.
     */
    @Inject(method = "renderLatencyIcon", at = @At("HEAD"), cancellable = true)
    private void evade$numericPing(DrawContext ctx, int width, int x, int y, PlayerListEntry entry, CallbackInfo ci) {
        int ms = entry.getLatency();
        if (ms < 0 || !TabTweaks.numeric()) return;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String text = TabTweaks.pingText(ms);
        ctx.drawTextWithShadow(tr, text, x + width - 1 - tr.getWidth(text), y, TabTweaks.shownPingColor(ms));
        ci.cancel();
    }

    /**
     * Tab Tweaks "Numeric Ping": widen each column from the 13 pixels the bars take to what the widest number
     * needs. {@code 13} occurs exactly once in render (checked against 1.21.11; {@code allow = 1} fails loudly if
     * a later version adds another).
     */
    @ModifyConstant(method = "render", constant = @Constant(intValue = 13), allow = 1)
    private int evade$pingColumn(int vanilla) {
        return TabTweaks.numeric() ? Math.max(vanilla, TabTweaks.pingSpace()) : vanilla;
    }

    /**
     * Tab Tweaks "Hide Header" and "Player Count": render reads the field as null, or with "12 players" above it; the
     * server's header itself is kept.
     */
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/hud/PlayerListHud;header:Lnet/minecraft/text/Text;", opcode = Opcodes.GETFIELD))
    private Text evade$hideHeader(Text header) {
        return TabTweaks.header(header);
    }

    /** Tab Tweaks "Yourself First", "Friends First" and "Hide NPC Entries": the order (and entries) the list shows. */
    @ModifyReturnValue(method = "collectPlayerEntries", at = @At("RETURN"))
    private List<PlayerListEntry> evade$arrange(List<PlayerListEntry> entries) {
        return TabTweaks.arrange(entries);
    }

    /** Tab Tweaks "Scale": the list is drawn scaled about the top centre of the screen. */
    @Inject(method = "render", at = @At("HEAD"))
    private void evade$scaleStart(DrawContext ctx, int width, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        ctx.getMatrices().pushMatrix();
        float k = TabTweaks.listScale();
        if (k != 1f) ctx.getMatrices().scale(k, k);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void evade$scaleEnd(DrawContext ctx, int width, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        ctx.getMatrices().popMatrix();
    }

    /** Scale: the list lays itself out in a screen {@code 1 / scale} as wide, so it stays centred once scaled. */
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int evade$scaledWidth(int width) {
        float k = TabTweaks.listScale();
        return k == 1f ? width : Math.round(width / k);
    }

    /** Tab Tweaks "Hide Footer": render reads the field as null; the server's footer itself is kept. */
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/hud/PlayerListHud;footer:Lnet/minecraft/text/Text;", opcode = Opcodes.GETFIELD))
    private Text evade$hideFooter(Text footer) {
        return footer != null && TabTweaks.footerHidden() ? null : footer;
    }
}
