package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.evadeclient.module.misc.EvadeIcons;
import net.evadeclient.module.visuals.TabTweaks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    /**
     * Evade Icons: prefix the tab-list name with the player's icon glyph. Because the icon is part of the name,
     * the tab list measures it itself, so wider entries never overlap the name or the ping bars.
     */
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void evade$icon(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        Text decorated = EvadeIcons.decorate(entry.getProfile().id(), cir.getReturnValue());
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
        ctx.drawTextWithShadow(tr, text, x + width - 1 - tr.getWidth(text), y, TabTweaks.pingColor(ms));
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

    /** Tab Tweaks "Hide Header": render reads the field as null; the server's header itself is kept. */
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/hud/PlayerListHud;header:Lnet/minecraft/text/Text;", opcode = Opcodes.GETFIELD))
    private Text evade$hideHeader(Text header) {
        return header != null && TabTweaks.headerHidden() ? null : header;
    }

    /** Tab Tweaks "Hide Footer": render reads the field as null; the server's footer itself is kept. */
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/hud/PlayerListHud;footer:Lnet/minecraft/text/Text;", opcode = Opcodes.GETFIELD))
    private Text evade$hideFooter(Text footer) {
        return footer != null && TabTweaks.footerHidden() ? null : footer;
    }
}
