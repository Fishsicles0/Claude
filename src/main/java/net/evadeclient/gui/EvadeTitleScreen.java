package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.title.Intro;
import net.evadeclient.gui.title.RecentServers;
import net.evadeclient.gui.title.ServerRow;
import net.evadeclient.gui.title.TitleArt;
import net.evadeclient.gui.title.TitleButton;
import net.evadeclient.gui.title.TitleButton.Style;
import net.evadeclient.gui.title.WhatsNew;
import net.evadeclient.module.misc.Clips;
import net.evadeclient.module.misc.TitleScreenModule;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont.Role;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen;
import net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screen.option.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Evade's main menu (the Title Screen module), opened in place of the vanilla title screen.
 *
 * <p>The vanilla panorama keeps turning behind a dark vignette with a soft accent glow (or, with Panorama off, an
 * Evade backdrop). The Evade logo and the actions sit in a centred column, as in the vanilla menu. On wide screens
 * cards fill the rails beside it: the account and "What's new" on the left, "Continue playing" (recent servers with
 * favicons and live status) on the right; narrower screens drop the cards. Everything arrives in a short staggered entrance the first time
 * and fades in quickly after that.
 *
 * <p>It keeps what matters from the vanilla screen: the multiplayer-disabled checks (with their reason as a
 * tooltip), the multiplayer warning, language and accessibility buttons, the clickable copyright notice, narration
 * of every button, keyboard and controller navigation, and Escape doing nothing. Every action opens the same
 * vanilla screen the vanilla menu does. With Mod Menu installed, Mods opens its list through its public API.
 */
public class EvadeTitleScreen extends Screen {
    private static final Text COPYRIGHT = Text.translatable("title.credits");

    private final Intro intro = new Intro();
    private boolean begun, returning, refresh = true;
    private Supplier<SkinTextures> skin;

    // ---- layout (set by layout(), read by render)
    private boolean wide, compact;
    private int margin;
    private int colX, colW;
    private int logoX, logoY, markSize;
    private boolean tagline;
    /** The side rails: left (account, what's new) and right (continue playing), both cardsW wide. */
    private int leftX, rightX, cardsW;
    private int accountY = -1, serversY = -1, serversH, newsY = -1, newsH;
    private int footerCy;
    private int versionX;
    private boolean showVersion;
    private String versionText = "";
    private final List<String> newsLines = new ArrayList<>(4);
    private String greeting = "", playerName = "", accountNote = "";

    public EvadeTitleScreen() {
        super(Text.translatable("narrator.screen.title"));
    }

    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean shouldPause() { return false; }
    @Override public boolean canInterruptOtherScreen() { return true; }

    /** Title Screen turned off while this menu is open (or was its parent): back to the vanilla one. */
    @Override
    public void tick() {
        if (TitleScreenModule.active() == null && client != null) {
            client.setScreen(new net.minecraft.client.gui.screen.TitleScreen());
            return;
        }
        RecentServers.get().tick();
    }

    @Override
    public void removed() {
        RecentServers.get().cancel();
        returning = true;
        refresh = true;
    }

    // ------------------------------------------------------------------ building

    @Override
    protected void init() {
        TitleScreenModule mod = TitleScreenModule.active();
        boolean anims = TitleArt.anims();
        // The first entrance starts on the first frame after the loading screen has gone (see render).
        if (begun && returning) intro.restart(anims);
        returning = false;

        RecentServers recent = RecentServers.get();
        if (refresh) {
            recent.refresh(mod);
            refresh = false;
        }
        recent.pingStale((mod == null || mod.serverStatus.get()) && client.isMultiplayerEnabled());
        if (skin == null) skin = client.getSkinProvider().supplySkinTextures(client.getGameProfile(), false);

        Text mpDisabled = multiplayerDisabledText();
        String[] last = mod != null && mod.lastServerButton.get() ? mod.lastServerEntry() : null;
        boolean mods = FabricLoader.getInstance().isModLoaded("modmenu");

        layout(mod, last != null, mods);

        // ---- the action column
        int y = logoY + markSize + (tagline ? 12 : 0) + (compact ? 12 : 22);
        int rowH = compact ? 20 : 22, primH = compact ? 22 : 26, gap = compact ? 4 : 5;
        if (height < 236) { rowH = 18; primH = 20; gap = 3; }
        float sx = 0f, sy = 8f;
        double d = 60;
        int x = colX;

        add(new TitleButton(x, y, colW, primH, Text.translatable("menu.singleplayer"), Style.PRIMARY, "profiles",
                () -> client.setScreen(new SelectWorldScreen(this))).intro(intro, d, sx, sy));
        y += primH + gap; d += 25;

        TitleButton mp = add(new TitleButton(x, y, colW, primH, Text.translatable("menu.multiplayer"), Style.PRIMARY, "users",
                this::openMultiplayer).intro(intro, d, sx, sy));
        if (mpDisabled != null) {
            mp.active = false;
            mp.setTooltip(Tooltip.of(mpDisabled));
        }
        y += primH + gap; d += 25;

        if (last != null) {
            String name = last[0], address = last[1];
            RecentServers.Entry e = recent.entry(address);
            TitleButton rejoin = add(new TitleButton(x, y, colW, rowH, Text.literal("Rejoin " + name), Style.SECONDARY, "reset",
                    () -> join(name, address)).prefix("Rejoin", name).intro(intro, d, sx, sy));
            if (e != null) rejoin.favicon(e::favicon).trailing(e::shortStatus, e::dot);
            rejoin.setTooltip(Tooltip.of(Text.literal("Join " + name + " (" + address + ") again")));
            if (mpDisabled != null) rejoin.active = false;
            y += rowH + gap; d += 25;
        }
        y += compact ? 4 : 6;

        int half = (colW - gap) / 2, x2 = x + colW - half;
        add(new TitleButton(x, y, half, rowH, Text.literal("Evade Menu"), Style.SECONDARY, "evade",
                () -> client.setScreen(EvadeMenu.open(null, this))).centered(true).intro(intro, d, sx, sy))
                .setTooltip(Tooltip.of(Text.literal("Mods, HUD, profiles and settings of Evade")));
        add(new TitleButton(x2, y, half, rowH, Text.literal("Evade Clips"), Style.SECONDARY, "clips",
                this::openClips).centered(true).intro(intro, d + 20, sx, sy))
                .setTooltip(Tooltip.of(Text.literal("Open Evade Clips, or your clips folder")));
        y += rowH + gap; d += 25;

        Runnable options = () -> client.setScreen(new OptionsScreen(this, client.options));
        Runnable quit = () -> client.scheduleStop();
        if (mods) {
            add(new TitleButton(x, y, half, rowH, Text.literal("Mods"), Style.SECONDARY, "mods", this::openModMenu)
                    .centered(true).intro(intro, d, sx, sy));
            add(new TitleButton(x2, y, half, rowH, Text.translatable("menu.options"), Style.SECONDARY, "settings", options)
                    .centered(true).intro(intro, d + 20, sx, sy));
            y += rowH + gap; d += 25;
            add(new TitleButton(x, y, colW, rowH, Text.translatable("menu.quit"), Style.GHOST, "power", quit)
                    .centered(true).danger().intro(intro, d, sx, sy));
        } else {
            add(new TitleButton(x, y, half, rowH, Text.translatable("menu.options"), Style.SECONDARY, "settings", options)
                    .centered(true).intro(intro, d, sx, sy));
            add(new TitleButton(x2, y, half, rowH, Text.translatable("menu.quit"), Style.SECONDARY, "power", quit)
                    .centered(true).danger().intro(intro, d + 20, sx, sy));
        }

        // ---- side cards
        if (wide) addCardWidgets(recent, mpDisabled);

        // ---- footer: language, accessibility, version, copyright
        int fb = 16, fy = footerCy - fb / 2, fx = wide ? margin : 8;
        add(new TitleButton(fx, fy, fb, fb, Text.translatable("options.language"), Style.ICON, "server",
                () -> client.setScreen(new LanguageOptionsScreen(this, client.options, client.getLanguageManager())))
                .intro(intro, 260, 0, 4)).setTooltip(Tooltip.of(Text.translatable("options.language")));
        add(new TitleButton(fx + fb + 4, fy, fb, fb, Text.translatable("options.accessibility"), Style.ICON, "access",
                () -> client.setScreen(new AccessibilityOptionsScreen(this, client.options)))
                .intro(intro, 280, 0, 4)).setTooltip(Tooltip.of(Text.translatable("options.accessibility")));
        versionX = fx + 2 * fb + 4 + 8;

        // The required notice, clickable to the credits like vanilla's, in the menu's own type.
        int cw = TitleArt.width(COPYRIGHT.getString(), Role.SMALL);
        int rx = wide ? width - margin : width - 8;
        add(new TitleButton(rx - cw, footerCy - 5, cw, 10, COPYRIGHT, Style.LINK, null,
                () -> client.setScreen(new CreditsAndAttributionScreen(this))).intro(intro, 260, 0, 4));

        versionText = "Minecraft " + SharedConstants.getGameVersion().name() + "  ·  " + EvadeClient.NAME + " " + EvadeClient.VERSION;
        showVersion = (mod == null || mod.showVersion.get())
                && versionX + TitleArt.width(versionText, Role.SMALL) + 10 < rx - cw;
    }

    private <T extends net.minecraft.client.gui.widget.ClickableWidget> T add(T w) {
        return addDrawableChild(w);
    }

    /** Works out where everything goes for this window size. */
    private void layout(TitleScreenModule mod, boolean rejoin, boolean mods) {
        int W = width, H = height;
        compact = H < 330;
        margin = Math.round(Math.max(14, Math.min(72, W * 0.04f)));
        int cardW = Math.round(Math.max(160, Math.min(236, W * 0.22f)));
        int bw = Math.round(Math.max(196, Math.min(236, W * 0.24f)));
        boolean panels = mod == null || mod.sidePanels.get();
        // The column stays centred; the rails need a card's width plus a clear gap on each side of it.
        int railRoom = (W - bw) / 2 - margin - 16;
        wide = panels && H >= 250 && railRoom >= cardW;

        footerCy = H - 13;
        int top = 12, bottom = H - 28;
        markSize = Math.round(Math.max(26, Math.min(60, H * 0.12f)));
        tagline = !compact;
        int rowH = compact ? 20 : 22, primH = compact ? 22 : 26, gap = compact ? 4 : 5;
        if (H < 236) { rowH = 18; primH = 20; gap = 3; markSize = 22; }
        int buttons = 2 * primH + gap + (rejoin ? gap + rowH : 0) + (compact ? 4 : 6) + gap
                + 2 * rowH + gap + (mods ? gap + rowH : 0);
        int block = markSize + (tagline ? 12 : 0) + (compact ? 12 : 22) + buttons;

        colW = wide ? bw : Math.min(220, W - 32);
        colX = (W - colW) / 2;
        int blockTop = top + Math.max(0, Math.round((bottom - top - block) * 0.46f));
        logoY = blockTop;
        logoX = (W - TitleArt.lockupWidth(markSize)) / 2;

        // Side rails, centred on the column's middle: account and what's new on the left, continue playing on
        // the right. A rail drops its lower card when the screen is too short for it.
        accountY = serversY = newsY = -1;
        newsLines.clear();
        if (!wide) return;
        cardsW = Math.min(cardW, railRoom);
        leftX = margin;
        rightX = W - margin - cardsW;
        int cardGap = 8;
        int accountH = 48;
        int n = RecentServers.get().entries().size();
        serversH = 28 + (n == 0 ? 34 : n * 28) + 4;
        wrap(WhatsNew.HEADLINE, cardsW - 24, Role.BODY, newsLines, 3);
        newsH = 28 + newsLines.size() * 11 + 22;
        int avail = bottom - top, mid = blockTop + block / 2;
        int left = accountH + cardGap + newsH;
        boolean news = left <= avail;
        if (!news) left = accountH;
        int ly = Math.max(top, Math.min(bottom - left, mid - left / 2));
        accountY = ly;
        if (news) newsY = ly + accountH + cardGap;
        serversY = Math.max(top, Math.min(bottom - serversH, mid - serversH / 2));

        // The account card's text.
        int hour = LocalTime.now().getHour();
        greeting = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
        playerName = TitleArt.ellipsize(client.getSession().getUsername(), cardsW - 60, Role.STRONG);
        accountNote = client.getSession().getXuid().isPresent() ? "Microsoft account" : "Offline profile";
    }

    private void addCardWidgets(RecentServers recent, Text mpDisabled) {
        int x = rightX, w = cardsW;
        double d = 140;
        if (accountY >= 0) d += 45;
        // Continue playing: the server rows and a link to the full list.
        String all = "All servers";
        int lw = TitleArt.width(all, Role.SMALL) + 10;
        if (12 + TitleArt.capsWidth("Continue playing", Role.CAPS, 1.2f) + 10 + lw + 12 > w) {
            all = "All"; // a narrow rail: the header keeps its room
            lw = TitleArt.width(all, Role.SMALL) + 10;
        }
        TitleButton allLink = add(new TitleButton(x + w - 12 - lw, serversY + 9, lw, 10, Text.literal(all), Style.LINK, "chevron_r",
                this::openMultiplayer).intro(intro, d, 12, 0));
        if (mpDisabled != null) allLink.active = false;
        List<RecentServers.Entry> entries = recent.entries();
        int ry = serversY + 28;
        double rd = d + 20;
        for (RecentServers.Entry e : entries) {
            ServerRow row = add(new ServerRow(x + 5, ry, w - 10, 26, e, this::join, intro, rd));
            if (mpDisabled != null) {
                row.active = false;
                row.setTooltip(Tooltip.of(mpDisabled));
            }
            ry += 28;
            rd += 25;
        }
        if (entries.isEmpty()) {
            String add = "Add a server";
            int aw = TitleArt.width(add, Role.SMALL) + 10;
            TitleButton a = add(new TitleButton(x + 12, serversY + 28 + 18, aw, 10, Text.literal(add), Style.LINK, "chevron_r",
                    this::openMultiplayer).intro(intro, rd, 12, 0));
            if (mpDisabled != null) a.active = false;
        }
        d += 45;
        if (newsY >= 0) {
            String more = "Read the changelog";
            int mw = TitleArt.width(more, Role.SMALL) + 12;
            add(new TitleButton(leftX + 12, newsY + newsH - 18, mw, 10, Text.literal(more), Style.LINK, "external",
                    () -> ConfirmLinkScreen.open(this, WhatsNew.URL)).intro(intro, d + 20, -12, 0));
        }
    }

    /** Greedy word wrap of {@code s} into at most {@code max} lines of {@code w} px (the last one ellipsized). */
    private static void wrap(String s, int w, Role r, List<String> out, int max) {
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String tryLine = line.isEmpty() ? words[i] : line + " " + words[i];
            if (TitleArt.width(tryLine, r) <= w || line.isEmpty()) {
                line.setLength(0);
                line.append(tryLine);
            } else {
                if (out.size() == max - 1) {
                    StringBuilder rest = new StringBuilder(line);
                    for (int j = i; j < words.length; j++) rest.append(' ').append(words[j]);
                    out.add(TitleArt.ellipsize(rest.toString(), w, r));
                    return;
                }
                out.add(line.toString());
                line.setLength(0);
                line.append(words[i]);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
    }

    // ------------------------------------------------------------------ actions

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

    private void openMultiplayer() {
        client.setScreen(client.options.skipMultiplayerWarning ? new MultiplayerScreen(this) : new MultiplayerWarningScreen(this));
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

    private void join(RecentServers.Entry e) {
        join(e.name(), e.info.address);
    }

    /**
     * Connects to a server, with its server list entry (and resource pack choice) when it has one. Until the
     * multiplayer warning has been accepted it shows that first, as the Multiplayer button does.
     */
    private void join(String name, String address) {
        if (!client.isMultiplayerEnabled()) return;
        if (!client.options.skipMultiplayerWarning) {
            client.setScreen(new MultiplayerWarningScreen(this));
            return;
        }
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

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TitleScreenModule mod = TitleScreenModule.active();
        var cs = net.evadeclient.module.client.ClientSettings.class;
        if (EvadeClient.modules != null && EvadeClient.modules.get(cs) != null) EvadeClient.modules.get(cs).applyTheme();
        int W = width, H = height;
        boolean panorama = mod == null || mod.panorama.get();
        if (panorama) {
            renderPanoramaBackground(ctx, delta);
            if (mod != null && mod.blur.get()) {
                try {
                    ctx.applyBlur();
                } catch (IllegalStateException ignored) {
                    // Something already blurred this frame.
                }
            }
        } else {
            backdrop(ctx, W, H);
        }
        float base = Theme.fade;
        try {
            Theme.fade = 1f;
            // Darken toward the column (and the cards), keep the middle of the panorama lively.
            if (panorama) Theme.fill(ctx, 0, 0, W, H, 0x30000000);
            if (wide) {
                // Both rails sit on darker edges; the middle keeps a light veil behind the column.
                int reach = leftX + cardsW + Math.round(W * 0.10f);
                TitleArt.rampH(ctx, 0, 0, reach, H, 0xB8060608, false);
                TitleArt.rampH(ctx, W - reach, 0, W, H, 0xB8060608, true);
                Theme.fill(ctx, 0, 0, W, H, 0x30000000);
            } else {
                Theme.fill(ctx, 0, 0, W, H, 0x58000000);
            }
            TitleArt.vignette(ctx, W, H, 0xC8000000);
            ctx.fillGradient(0, Math.round(H * 0.62f), W, H, 0x00000000, 0xB0000000);
            ctx.fillGradient(0, 0, W, 36, 0x50000000, 0x00000000);

            // A soft accent glow behind the logo, breathing slowly, and one rising from the lower corner.
            double t = Intro.now() / 1000.0;
            float breathe = TitleArt.anims() ? 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2 / 7.0) : 0.5f;
            float in = intro.p(0, 900);
            int cx = logoX + TitleArt.lockupWidth(markSize) / 2;
            int cy = logoY + markSize / 2;
            TitleArt.glow(ctx, cx, cy, markSize * 9, markSize * 6, Theme.alpha(Theme.RED, (0.26f + 0.06f * breathe) * in));
            TitleArt.glow(ctx, W / 2, H + H / 6, Math.round(W * 1.3f), Math.round(H * 0.95f),
                    Theme.alpha(Theme.RED, (0.15f + 0.03f * (1f - breathe)) * in));
        } finally {
            Theme.fade = base;
        }
    }

    /** With Panorama off: Evade's own dark backdrop with a faint grid, like the launcher's. */
    private void backdrop(DrawContext ctx, int W, int H) {
        ctx.fillGradient(0, 0, W, H, 0xFF0D0D11, 0xFF060608);
        int step = 32;
        int ox = (W % step) / 2, oy = (H % step) / 2;
        for (int x = ox; x < W; x += step) ctx.fill(x, 0, x + 1, H, 0x07FFFFFF);
        for (int y = oy; y < H; y += step) ctx.fill(0, y, W, y + 1, 0x07FFFFFF);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!begun && client.getOverlay() == null) {
            intro.begin(TitleArt.anims());
            begun = true;
        }
        drawLogo(ctx);
        if (wide) drawCards(ctx);
        float c = intro.p(260, 320);
        super.render(ctx, mouseX, mouseY, delta);
        if (showVersion && c > 0.01f) {
            float base = Theme.fade;
            Theme.fade = c;
            TitleArt.text(ctx, versionText, versionX, footerCy - 3, 0xFF8C8C98, Role.SMALL);
            Theme.fade = base;
        }
    }

    private void drawLogo(DrawContext ctx) {
        float in = intro.p(0, 500);
        if (in <= 0.001f) return;
        float base = Theme.fade;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            Theme.fade = in;
            m.translate(0, Theme.snap(ctx, (1f - in) * 10f));
            int word = 0xFFF2F2F5;
            TitleArt.lockup(ctx, logoX, logoY, markSize, net.evadeclient.util.Logo.markTint(), word);
            if (tagline) {
                int tx = (width - TitleArt.capsWidth("Your game, sharper", Role.CAPS_SM, 1.6f)) / 2;
                int ty = logoY + markSize + 2;
                float tin = intro.p(160, 420);
                Theme.fade = tin;
                TitleArt.caps(ctx, "Your game, sharper", tx, ty, 0xFFB4B4C0, Role.CAPS_SM, 1.6f);
            }
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
        sweep(ctx);
    }

    /**
     * A soft light sweeping once across the logo after it arrives: the logo drawn again in white through a narrow
     * scissor that moves left to right, in nested bands so its edges are soft.
     */
    private void sweep(DrawContext ctx) {
        if (!intro.full()) return;
        double t = intro.elapsed() - 600;
        if (t < 0 || t > 900) return;
        float p = Motion.cubicOut((float) (t / 900));
        int lw = TitleArt.lockupWidth(markSize);
        int band = Math.max(12, markSize);
        int cx = logoX - band + Math.round((lw + 2 * band) * p);
        float fadeOut = 1f - Math.max(0f, (float) (t - 600) / 300f);
        float base = Theme.fade;
        try {
            for (int i = 0; i < 4; i++) {
                int half = band / 2 - i * band / 9;
                if (half <= 0) break;
                ctx.enableScissor(cx - half, logoY - 2, cx + half, logoY + markSize + 2);
                Theme.fade = 0.16f * fadeOut;
                TitleArt.lockup(ctx, logoX, logoY, markSize, 0xFFFFFFFF, 0xFFFFFFFF);
                ctx.disableScissor();
            }
        } finally {
            Theme.fade = base;
        }
    }

    private void drawCards(DrawContext ctx) {
        int x = rightX, l = leftX, w = cardsW;
        double d = 140;
        if (accountY >= 0) {
            float in = card(ctx, l, accountY, w, 48, d);
            if (in > 0) drawAccount(ctx, l, accountY, w, in);
            d += 45;
        }
        float in = card(ctx, x, serversY, w, serversH, d);
        if (in > 0) {
            float base = beginCard(ctx, x, in);
            try {
                TitleArt.caps(ctx, "Continue playing", x + 12, serversY + 10, Theme.TEXT_HEADER, Role.CAPS, 1.2f);
                if (RecentServers.get().entries().isEmpty()) {
                    TitleArt.text(ctx, "Servers you join show up here.", x + 12, serversY + 30, Theme.TEXT_DIM, Role.SMALL);
                }
            } finally {
                endCard(ctx, base);
            }
        }
        d += 45;
        if (newsY >= 0) {
            float nin = card(ctx, l, newsY, w, newsH, d);
            if (nin > 0) {
                float base = beginCard(ctx, l, nin);
                try {
                    TitleArt.caps(ctx, "What's new", l + 12, newsY + 10, Theme.TEXT_HEADER, Role.CAPS, 1.2f);
                    int pw = TitleArt.width(WhatsNew.VERSION, Role.SMALL) + 10;
                    int px2 = l + w - 12, px1 = px2 - pw;
                    Theme.pill(ctx, px1, newsY + 8, px2, newsY + 19, Theme.alpha(Theme.RED, 0.22f));
                    Theme.hairline(ctx, px1, newsY + 8, px2, newsY + 19, Theme.alpha(Theme.RED_BRIGHT, 0.5f), 5);
                    TitleArt.text(ctx, WhatsNew.VERSION, px1 + 5, newsY + 10, Theme.RED_BRIGHT, Role.SMALL);
                    int ly = newsY + 28;
                    for (int i = 0; i < newsLines.size(); i++) {
                        TitleArt.text(ctx, newsLines.get(i), l + 12, ly, Theme.TEXT, Role.BODY);
                        ly += 11;
                    }
                } finally {
                    endCard(ctx, base);
                }
            }
        }
    }

    /** Draws a card's body (shadow, glass fill, rim) as it slides in; returns its intro progress. */
    private float card(DrawContext ctx, int x, int y, int w, int h, double delay) {
        float in = intro.p(delay, 380);
        if (in <= 0.001f) return 0f;
        float base = Theme.fade;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            Theme.fade = in;
            m.translate(Theme.snap(ctx, slide(x) * (1f - in)), 0);
            Theme.shadow(ctx, Theme.SH_CARD, x, y + 2, x + w, y + h + 2, 0x99000000);
            Theme.round(ctx, x, y, x + w, y + h, 0xD80C0C10, 6);
            Theme.round(ctx, x, y, x + w, y + 22, 0x07FFFFFF, 6, Theme.TL | Theme.TR);
            Theme.fadeLine(ctx, x + 4, y, x + w - 4, 0x22FFFFFF);
            Theme.hairline(ctx, x, y, x + w, y + h, 0x1EFFFFFF, 6);
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
        return in;
    }

    /** Starts drawing a card's contents at intro progress {@code in} (fade and slide); returns the fade to restore. */
    /** Cards slide in from their own side: the left rail from the left, the right rail from the right. */
    private float slide(int x) {
        return x < width / 2 ? -12f : 12f;
    }

    private float beginCard(DrawContext ctx, int x, float in) {
        float base = Theme.fade;
        ctx.getMatrices().pushMatrix();
        Theme.fade = base * in;
        ctx.getMatrices().translate(Theme.snap(ctx, slide(x) * (1f - in)), 0);
        return base;
    }

    private void endCard(DrawContext ctx, float base) {
        Theme.fade = base;
        ctx.getMatrices().popMatrix();
    }

    private void drawAccount(DrawContext ctx, int x, int y, int w, float in) {
        float base = beginCard(ctx, x, in);
        try {
            int fs = 28, fx = x + 10, fy = y + 10;
            Theme.round(ctx, fx - 1, fy - 1, fx + fs + 1, fy + fs + 1, 0xFF0A0A0D, 4);
            if (skin != null) PlayerSkinDrawer.draw(ctx, skin.get(), fx, fy, fs, Theme.fa(0xFFFFFFFF));
            Theme.hairline(ctx, fx - 1, fy - 1, fx + fs + 1, fy + fs + 1, 0x26FFFFFF, 4);
            int tx = fx + fs + 10;
            TitleArt.caps(ctx, greeting, tx, y + 10, Theme.TEXT_HEADER, Role.CAPS_SM, 1.2f);
            TitleArt.text(ctx, playerName, tx, y + 19, Theme.TEXT, Role.STRONG);
            Theme.disc(ctx, tx, y + 33, 4, accountNote.startsWith("Microsoft") ? Theme.OK : Theme.TEXT_FAINT);
            TitleArt.text(ctx, accountNote, tx + 7, y + 31, Theme.TEXT_DIM, Role.SMALL);
        } finally {
            endCard(ctx, base);
        }
    }
}
