package net.evadeclient.mixin;

import net.evadeclient.module.misc.PackOrganizer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.pack.PackListWidget;
import net.minecraft.client.gui.screen.pack.ResourcePackOrganizer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pack Organizer: a gold star on favourite packs, and right-click on a pack to star or un-star it (left-click keeps
 * vanilla's behaviour). Only on the resource pack screen while the module is on.
 */
@Mixin(PackListWidget.ResourcePackEntry.class)
public abstract class PackEntryMixin {
    @Shadow @Final private ResourcePackOrganizer.Pack pack;
    @Shadow @Final private PackListWidget widget;

    private boolean evade$applies() {
        return widget instanceof PackListWidgetAccessor a && PackOrganizer.appliesTo(a.evade$getScreen());
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void evade$star(DrawContext ctx, int mouseX, int mouseY, boolean hovered, float delta, CallbackInfo ci) {
        if (!PackOrganizer.isFavourite(pack.getName()) || !evade$applies()) return;
        var self = (PackListWidget.ResourcePackEntry) (Object) this;
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawTextWithShadow(tr, "★", self.getContentRightEnd() - tr.getWidth("★") - 2, self.getContentY() + 1, 0xFFFFC83D);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void evade$favourite(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() != 1 || !evade$applies()) return;
        PackOrganizer.toggleFavourite(pack.getName());
        cir.setReturnValue(true);
    }
}
