package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.Clips;
import net.evadeclient.module.misc.TitleScreenModule;
import net.evadeclient.util.Logo;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.AccessibilityOnboardingButtons;
import net.minecraft.client.gui.screen.option.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen;
import net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableTextWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.widget.TextIconButtonWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.Text;

/**
 * Evade's main menu (the Title Screen module): the Evade logo over the panorama and a column of Evade-styled
 * buttons. It does everything the vanilla title screen's buttons do (and opens the same vanilla screens), plus a
 * one-click rejoin of the last server and Evade Clips. With Mod Menu installed, a Mods button opens its mod list
 * through Mod Menu's own public API, so Mod Menu keeps working.
 */
public class EvadeTitleScreen extends Screen {
    private static final Text COPYRIGHT = Text.translatable("title.credits");
    private static final int BTN_W = 200, BTN_H = 20, GAP = 4;

    public EvadeTitleScreen() {
        super(Text.translatable("narrator.screen.title"));
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    /** Title Screen turned off while this menu is open (or was its parent): back to the vanilla one. */
    @Override
    public void tick() {
        if (TitleScreenModule.active() == null && client != null) client.setScreen(new net.minecraft.client.gui.screen.TitleScreen());
    }

    @Override
    protected void init() {
        TitleScreenModule mod = TitleScreenModule.active();
        int cx = width / 2, left = cx - BTN_W / 2;
        int y = Math.max(height / 4 + 40, 70);

        addDrawableChild(new EvadeButton(left, y, BTN_W, BTN_H, Text.translatable("menu.singleplayer"),
                () -> client.setScreen(new SelectWorldScreen(this)), true));
        y += BTN_H + GAP;

        Text mpDisabled = multiplayerDisabledText();
        EvadeButton mp = addDrawableChild(new EvadeButton(left, y, BTN_W, BTN_H, Text.translatable("menu.multiplayer"),
                () -> client.setScreen(client.options.skipMultiplayerWarning ? new MultiplayerScreen(this) : new MultiplayerWarningScreen(this)), false));
        if (mpDisabled != null) {
            mp.active = false;
            mp.setTooltip(Tooltip.of(mpDisabled));
        }
        y += BTN_H + GAP;

        String[] last = mod != null && mod.lastServerButton.get() ? mod.lastServerEntry() : null;
        if (last != null) {
            String name = last[0], address = last[1];
            String prefix = "Rejoin ";
            int room = BTN_W - 12 - textRenderer.getWidth(prefix);
            String shown = textRenderer.getWidth(name) <= room ? name : textRenderer.trimToWidth(name, room - textRenderer.getWidth("...")).strip() + "...";
            EvadeButton rejoin = addDrawableChild(new EvadeButton(left, y, BTN_W, BTN_H, Text.literal(prefix + shown),
                    () -> rejoin(name, address), false));
            rejoin.setTooltip(Tooltip.of(Text.literal("Join " + name + " (" + address + ") again")));
            if (mpDisabled != null) rejoin.active = false;
            y += BTN_H + GAP;
        }

        addDrawableChild(new EvadeButton(left, y, BTN_W, BTN_H, Text.literal("Evade Clips"), this::openClips, false))
                .setTooltip(Tooltip.of(Text.literal("Open Evade Clips, or your clips folder")));
        y += BTN_H + GAP;

        if (FabricLoader.getInstance().isModLoaded("modmenu")) {
            addDrawableChild(new EvadeButton(left, y, BTN_W, BTN_H, Text.literal("Mods"), this::openModMenu, false));
            y += BTN_H + GAP;
        }

        y += 8;
        int half = (BTN_W - GAP) / 2;
        addDrawableChild(new EvadeButton(left, y, half, BTN_H, Text.translatable("menu.options"),
                () -> client.setScreen(new OptionsScreen(this, client.options)), false));
        addDrawableChild(new EvadeButton(left + half + GAP, y, half, BTN_H, Text.translatable("menu.quit"),
                () -> client.scheduleStop(), false));
        TextIconButtonWidget lang = addDrawableChild(AccessibilityOnboardingButtons.createLanguageButton(20,
                b -> client.setScreen(new LanguageOptionsScreen(this, client.options, client.getLanguageManager())), true));
        lang.setPosition(left - 24, y);
        TextIconButtonWidget access = addDrawableChild(AccessibilityOnboardingButtons.createAccessibilityButton(20,
                b -> client.setScreen(new AccessibilityOptionsScreen(this, client.options)), true));
        access.setPosition(left + BTN_W + 4, y);

        int cw = textRenderer.getWidth(COPYRIGHT);
        addDrawableChild(new PressableTextWidget(width - cw - 2, height - 10, cw, 10, COPYRIGHT,
                b -> client.setScreen(new CreditsAndAttributionScreen(this)), textRenderer));
    }

    private Text multiplayerDisabledText() {
        if (client.isMultiplayerEnabled()) return null;
        if (client.isUsernameBanned()) return Text.translatable("title.multiplayer.disabled.banned.name");
        var ban = client.getMultiplayerBanDetails();
        if (ban != null) {
            return ban.expires() != null ? Text.translatable("title.multiplayer.disabled.banned.temporary")
                    : Text.translatable("title.multiplayer.disabled.banned.permanent");
        }
        return Text.translatable("title.multiplayer.disabled");
    }

    private void openClips() {
        Clips clips = EvadeClient.modules == null ? null : EvadeClient.modules.get(Clips.class);
        if (clips == null) return;
        clips.command(new String[]{"clips"});
    }

    /** Mod Menu's mod list, through its public API ({@code ModMenuApi.createModsScreen}). */
    private void openModMenu() {
        try {
            Class<?> api = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi");
            Object screen = api.getMethod("createModsScreen", Screen.class).invoke(null, this);
            if (screen instanceof Screen s) {
                client.setScreen(s);
                return;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            EvadeClient.LOG.warn("Couldn't open Mod Menu's mod list", e);
        }
        Notifications.push("Title Screen", "Couldn't open the mod list", false);
    }

    /** Connects to the last server, with its server list entry (and resource pack choice) when it has one. */
    private void rejoin(String name, String address) {
        ServerInfo info = null;
        try {
            ServerList list = new ServerList(client);
            list.loadFile();
            info = list.get(address);
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Title Screen couldn't read the server list", e);
        }
        if (info == null) info = new ServerInfo(name, address, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(new MultiplayerScreen(this), client, ServerAddress.parse(address), info, false, null);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TitleScreenModule mod = TitleScreenModule.active();
        if (mod == null || mod.panorama.get()) {
            renderPanoramaBackground(ctx, delta);
            // A soft darkening towards the bottom so the buttons read well over any panorama.
            ctx.fillGradient(0, 0, width, height, 0x30000000, 0xA0000000);
        } else {
            ctx.fillGradient(0, 0, width, height, Theme.BG | 0xFF000000, Theme.SIDEBAR | 0xFF000000);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int mark = 36;
        int w = Logo.lockupWidth(mark);
        int logoY = Math.max(10, Math.max(height / 4 + 40, 70) - mark - 22);
        Logo.lockup(ctx, (width - w) / 2, logoY, mark);
        TitleScreenModule mod = TitleScreenModule.active();
        if (mod == null || mod.showVersion.get()) {
            String v = "Minecraft " + SharedConstants.getGameVersion().name() + "  ·  " + EvadeClient.NAME + " " + EvadeClient.VERSION;
            ctx.drawTextWithShadow(textRenderer, v, 2, height - 10, 0xFFB0B0B8);
        }
    }

    /** A rounded Evade button: dark card, accent edge on the primary one, brighter on hover. */
    static final class EvadeButton extends PressableWidget {
        private final Runnable action;
        private final boolean primary;

        EvadeButton(int x, int y, int w, int h, Text label, Runnable action, boolean primary) {
            super(x, y, w, h, label);
            this.action = action;
            this.primary = primary;
        }

        @Override
        public void onPress(AbstractInput input) { action.run(); }

        @Override
        protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
            boolean hot = active && (isHovered() || isFocused());
            int fill = !active ? 0xC0101013 : hot ? 0xE0202028 : 0xC8121216;
            int border = !active ? 0xFF1C1C22 : hot || primary ? (Theme.RED | 0xFF000000) : 0xFF2A2A31;
            int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
            Theme.roundBordered(ctx, x1, y1, x2, y2, fill, border);
            if (primary) Theme.round(ctx, x1 + 1, y1 + 3, x1 + 3, y2 - 3, Theme.RED | 0xFF000000, 1);
            int color = !active ? 0xFF6E6E7A : hot ? 0xFFFFFFFF : 0xFFE4E4EA;
            var tr = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
            Text msg = getMessage();
            int tw = tr.getWidth(msg);
            ctx.drawTextWithShadow(tr, msg, x1 + (getWidth() - tw) / 2, y1 + (getHeight() - 8) / 2, color);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }
}
