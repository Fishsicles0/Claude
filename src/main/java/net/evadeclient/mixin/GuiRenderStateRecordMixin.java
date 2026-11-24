package net.evadeclient.mixin;

import net.evadeclient.util.HudCache;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.ItemGuiElementRenderState;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.gui.render.state.TextGuiElementRenderState;
import net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD cache: while a widget is being recorded, every element it adds to the frame's GUI state is noted, so later
 * frames can add the same elements again without running the widget's drawing code. One static check otherwise.
 */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateRecordMixin {
    @Inject(method = "addItem", at = @At("HEAD"))
    private void evade$recordItem(ItemGuiElementRenderState state, CallbackInfo ci) {
        if (HudCache.recording()) HudCache.record(HudCache.ITEM, state);
    }

    @Inject(method = "addText", at = @At("HEAD"))
    private void evade$recordText(TextGuiElementRenderState state, CallbackInfo ci) {
        if (HudCache.recording()) HudCache.record(HudCache.TEXT, state);
    }

    @Inject(method = "addSpecialElement", at = @At("HEAD"))
    private void evade$recordSpecial(SpecialGuiElementRenderState state, CallbackInfo ci) {
        if (HudCache.recording()) HudCache.record(HudCache.SPECIAL, state);
    }

    @Inject(method = "addSimpleElement", at = @At("HEAD"))
    private void evade$recordSimple(SimpleGuiElementRenderState state, CallbackInfo ci) {
        if (HudCache.recording()) HudCache.record(HudCache.SIMPLE, state);
    }

    /** A new root layer or a blur can't be replayed as a flat list: that recording is thrown away. */
    @Inject(method = {"createNewRootLayer", "applyBlur"}, at = @At("HEAD"))
    private void evade$unreplayable(CallbackInfo ci) {
        if (HudCache.recording()) HudCache.record(HudCache.UNREPLAYABLE, null);
    }
}
