package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.title.FriendsStrip;
import net.evadeclient.gui.title.Intro;
import net.evadeclient.gui.title.RecentServers;
import net.evadeclient.gui.title.ServerRow;
import net.evadeclient.gui.title.TitleArt;
import net.evadeclient.gui.title.TitleBackground;
import net.evadeclient.gui.title.TitleButton;
import net.evadeclient.gui.title.TitleButton.Style;
import net.evadeclient.gui.title.TitleScene;
import net.evadeclient.gui.title.TitleTip;
import net.evadeclient.gui.title.WardrobeTeaser;
import net.evadeclient.gui.title.WhatsNew;
import net.evadeclient.module.misc.Clips;
import net.evadeclient.module.misc.TitleScreenModule;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen;
import net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screen.option.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Evade's main menu (the Title Screen module), opened in place of the vanilla title screen.
 *
 * <p><b>Layout in design units.</b> The menu is laid out in "design px" (the GUI size divided by a whole number
 * {@link #zoom}) and drawn under that matrix scale, so at 1080p with GUI scale 1, or at 4K, it fills the screen
 * instead of sitting in the middle as a postage stamp. Text is rasterised for the real device pixels (see
 * {@link TitleArt#zoom}), shapes are SDFs, so everything stays crisp. Mouse input is mapped the same way.
 *
 * <p><b>Depth.</b> Four planes with parallax: the panorama (it turns with the cursor), the embers, the side cards and
 * the centre stack, with the logo floating against them. Light comes from one place: the centre stage is the only
 * zone with glow (an additive, dithered red under-light and the logo's breathing halo); the world around it sinks into
 * one dithered radial falloff. The single red call to action is Singleplayer; everything else is dark glass.
 *
 * <p>The centre column: the logo, Singleplayer (primary), Multiplayer (with friends online), an optional Rejoin, a
 * row of utility tiles (Evade Menu, Clips, Mods with Mod Menu, Options) and Quit as a ghost. On wide screens glass
 * cards fill the rails: the account (with the friends strip) and "What's new" on the left; the Wardrobe (you, turning
 * on a lit floor disc) and "Continue playing" on the right. Narrower screens drop the cards and keep a Wardrobe icon
 * in the footer.
 *
 * <p><b>Motion.</b> The first showing plays a staggered entrance (a click or key skips it); after that everything
 * fades in quickly. When the loading screen hands over, its logo flies into this screen's logo slot (shared element,
 * see {@link EvadeSplash}) while the menu fades in behind it. Animations Reduced: no parallax, no slides or
 * breathing, a 120 ms fade; Off: still.
 *
 * <p>It keeps what matters from the vanilla screen: the multiplayer-disabled checks (with their reason as a
 * tooltip), the multiplayer warning, language and accessibility buttons, the clickable copyright notice, narration
 * of every button, keyboard and controller navigation (Tab, arrows, Enter), and Escape doing nothing. With Mod Menu
 * installed, Mods opens its list through its public API.
 */
public class EvadeTitleScreen extends Screen {
    private static final Text COPYRIGHT = Text.translatable("title.credits");
    /** The backdrop's motion state, kept across showings so the parallax doesn't jump. */
    private static final TitleScene SCENE = new TitleScene();
    /** Design size the zoom aims for (the layout works from about 320x200 up). */
    private static final int DESIGN_W = 960, DESIGN_H = 540;
    private static final int TEASER_H = 112, FRIENDS_H = 24, ACCOUNT_H = 50, CARD_GAP = 10;
    /** Parallax depths (design px at the screen's edge): side cards, centre stack, logo (floats against them). */
    private static final float P_CARDS = 5f, P_STACK = 1.5f, P_LOGO = -3f;

    // ---- frame cost (CPU time of this screen's own drawing, for the gametest's report)
    private static double costEmaMicros;
    /** Layers, for the gametest's per-layer cost profile: a set bit leaves that layer out. Always 0 in play. */
    public static final int L_PANORAMA = 1, L_VEILS = 2, L_EMBERS = 4, L_VIGNETTE = 8, L_GLOWS = 16, L_CARDS = 32, L_PREVIEW = 64;
    public static int skipLayersForTest;

    private static boolean layer(int bit) {
        return (skipLayersForTest & bit) == 0;
    }

    private final Intro intro = new Intro();
    private boolean begun, returning, refresh = true;
    /** The loading screen flew its logo into ours: our logo is simply there (no entrance of its own). */
    private boolean handed;
    private Supplier<SkinTextures> skin;

    // ---- design units
    /** Design px to GUI px (a whole number, 1 unless the screen is much bigger than the design). */
    private int zoom = 1;
    /** The screen in design px. */
    private int W, H;

    // ---- layout (set by layout(), read by render; design px)
    private boolean wide, compact;
    private int margin;
    private int colX, colW, colBottom;
    private int logoX, logoY, markSize;
    private boolean tagline;
    /** The side rails: left (account, what's new) and right (wardrobe, continue playing), both cardsW wide. */
    private int leftX, rightX, cardsW;
    private int accountY = -1, accountH = ACCOUNT_H, serversY = -1, serversH, newsY = -1, newsH, teaserY = -1;
    private boolean friends;
    private int figX1, figY1, figX2, figY2;
    private int footerCy;
    private int versionX;
    private boolean showVersion;
    private String versionText = "";
    private final List<String> newsLines = new ArrayList<>(4);
    private final List<String> teaserLines = new ArrayList<>(3);
    private String greeting = "", playerName = "", accountNote = "";

    // ---- tooltip
    private ClickableWidget tipFor;
    private double tipSince;

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
        TitleArt.zoom(1);
        returning = true;
        refresh = true;
    }

    // ------------------------------------------------------------------ design units

    /** The whole-number zoom from GUI px to design px: how many times the design fits, divided by the GUI scale. */
    private int computeZoom() {
        if (client == null) return 1;
        var win = client.getWindow();
        int gs = Math.max(1, win.getScaleFactor());
        double fit = Math.min(win.getFramebufferWidth() / (double) DESIGN_W, win.getFramebufferHeight() / (double) DESIGN_H);
        return Math.max(1, (int) Math.floor(Math.floor(fit) / gs));
    }

    private Click scaled(Click c) {
        return zoom == 1 ? c : new Click(c.x() / zoom, c.y() / zoom, c.buttonInfo());
    }

    // ------------------------------------------------------------------ building

    @Override
    protected void init() {
        zoom = computeZoom();
        TitleArt.zoom(zoom);
        W = width / zoom;
        H = height / zoom;
        TitleScreenModule mod = TitleScreenModule.active();
        boolean anims = Motion.level() != Motion.Level.OFF;
        // The first entrance starts on the first frame after the loading screen has gone (see render).
        if (begun && returning) {
            intro.restart(anims);
            handed = false;
        }
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

        Metrics k = metrics(last != null, mods);
        layout(mod, k);

        // ---- the action column: one primary, then glass
        int y = logoY + markSize + (tagline ? 14 : 0) + k.logoGap;
        double d = 60;
        int x = colX;
        float sy = 8f;

        add(new TitleButton(x, y, colW, k.primH, Text.translatable("menu.singleplayer"), Style.PRIMARY, "profiles",
                () -> client.setScreen(new SelectWorldScreen(this))).intro(intro, d, 0, sy).plane(P_STACK))
                .tip("Play your worlds, or create a new one");
        y += k.primH + k.gap; d += 30;

        TitleButton mp = add(new TitleButton(x, y, colW, k.primH, Text.translatable("menu.multiplayer"), Style.SECONDARY, "users",
                this::openMultiplayer).intro(intro, d, 0, sy).plane(P_STACK));
        if (mpDisabled != null) {
            mp.active = false;
            mp.tip(mpDisabled);
        } else {
            mp.tip("Your server list: join servers and play with friends");
            if (FriendsStrip.available()) mp.trailing(EvadeTitleScreen::friendsNote, () -> FriendsStrip.onlineCount() > 0 ? Tokens.OK : 0);
        }
        y += k.primH + k.gap; d += 30;

        if (last != null) {
            String name = last[0], address = last[1];
            RecentServers.Entry e = recent.entry(address);
            TitleButton rejoin = add(new TitleButton(x, y, colW, k.rowH, Text.literal("Rejoin " + name), Style.SECONDARY, "reset",
                    () -> join(name, address)).prefix("Rejoin", name).intro(intro, d, 0, sy).plane(P_STACK));
            if (e != null) rejoin.favicon(e::favicon).trailing(e::shortStatus, e::dot);
            rejoin.tip("Join " + name + " (" + address + ") again");
            if (mpDisabled != null) rejoin.active = false;
            y += k.rowH + k.gap; d += 30;
        }
        y += k.gap;

        // The utility tiles: Evade Menu, Clips, (Mods), Options.
        int n = mods ? 4 : 3;
        int tw = (colW - (n - 1) * k.gap) / n;
        int extra = colW - (tw * n + (n - 1) * k.gap);
        int tx = x;
        List<TitleButton> tiles = new ArrayList<>(4);
        tiles.add(new TitleButton(0, y, 0, k.tileH, Text.literal("Evade Menu"), Style.TILE, "evade",
                () -> client.setScreen(EvadeMenu.open(null, this))).tip("Mods, HUD, profiles and settings of Evade"));
        tiles.add(new TitleButton(0, y, 0, k.tileH, Text.literal("Evade Clips"), Style.TILE, "clips", this::openClips)
                .tip("Open Evade Clips, or your clips folder"));
        if (mods) tiles.add(new TitleButton(0, y, 0, k.tileH, Text.literal("Mods"), Style.TILE, "mods", this::openModMenu)
                .tip("Every mod installed, with its own settings"));
        tiles.add(new TitleButton(0, y, 0, k.tileH, Text.translatable("menu.options"), Style.TILE, "settings",
                () -> client.setScreen(new OptionsScreen(this, client.options))).tip("Minecraft's video, sound and control settings"));
        for (int i = 0; i < tiles.size(); i++) {
            TitleButton t = tiles.get(i);
            int w = tw + (i < extra ? 1 : 0);
            t.setX(tx);
            t.setWidth(w);
            add(t.intro(intro, d + i * 24, 0, sy).plane(P_STACK));
            tx += w + k.gap;
        }
        y += k.tileH + k.gap;
        d += 30;

        Text quitText = Text.translatable("menu.quit");
        int qw = Math.min(colW, TitleArt.width(quitText.getString(), Role.STRONG) + 14 + 7 + 2 * Math.round(k.quitH * 0.5f));
        add(new TitleButton(x + (colW - qw) / 2, y, qw, k.quitH, quitText, Style.GHOST, "power", () -> client.scheduleStop())
                .centered(true).danger().intro(intro, d + 60, 0, sy * 0.5f).plane(P_STACK));
        colBottom = y + k.quitH;

        // ---- side cards
        if (wide) addCardWidgets(recent, mpDisabled);

        // ---- footer: language, accessibility, wardrobe, version, copyright
        int fb = 18, fy = footerCy - fb / 2, fx = wide ? margin : 8;
        add(new TitleButton(fx, fy, fb, fb, Text.translatable("options.language"), Style.ICON, "server",
                () -> client.setScreen(new LanguageOptionsScreen(this, client.options, client.getLanguageManager())))
                .intro(intro, 260, 0, 4)).tip(Text.translatable("options.language"));
        fx += fb + 4;
        add(new TitleButton(fx, fy, fb, fb, Text.translatable("options.accessibility"), Style.ICON, "access",
                () -> client.setScreen(new AccessibilityOptionsScreen(this, client.options)))
                .intro(intro, 280, 0, 4)).tip(Text.translatable("options.accessibility"));
        fx += fb + 4;
        if (WardrobeTeaser.available()) {
            add(new TitleButton(fx, fy, fb, fb, Text.literal("Wardrobe"), Style.ICON, "wardrobe", this::openWardrobe)
                    .intro(intro, 300, 0, 4)).tip("Wardrobe: your capes, wings, hats, pets and emotes");
            fx += fb + 4;
        }
        versionX = fx + 6;

        // The required notice, clickable to the credits like vanilla's, in the menu's own type.
        int cw = TitleArt.width(COPYRIGHT.getString(), Role.SMALL);
        int rx = wide ? W - margin : W - 8;
        add(new TitleButton(rx - cw, footerCy - 5, cw, 10, COPYRIGHT, Style.LINK, null,
                () -> client.setScreen(new CreditsAndAttributionScreen(this))).intro(intro, 260, 0, 4))
                .tip("Credits and attribution");

        versionText = "Minecraft " + SharedConstants.getGameVersion().name() + "  ·  " + EvadeClient.NAME + " " + EvadeClient.VERSION;
        showVersion = (mod == null || mod.showVersion.get())
                && versionX + TitleArt.width(versionText, Role.SMALL) + 10 < rx - cw;
    }

    /** "3 friends online" for the Multiplayer button, or nothing when none are. */
    private static String friendsNote() {
        int n = FriendsStrip.onlineCount();
        return n == 0 ? "" : n + (n == 1 ? " friend on" : " friends on");
    }

    private <T extends ClickableWidget> T add(T w) {
        return addDrawableChild(w);
    }

    /** Control sizes for this screen height (design px). */
    private record Metrics(int primH, int rowH, int tileH, int quitH, int gap, int logoGap, int buttons) {}

    private Metrics metrics(boolean rejoin, boolean mods) {
        compact = H < 330;
        int primH, rowH, tileH, quitH, gap, logoGap;
        if (H < 236) { primH = 22; rowH = 20; tileH = 30; quitH = 16; gap = 3; logoGap = 8; }
        else if (compact) { primH = 28; rowH = 22; tileH = 36; quitH = 18; gap = 5; logoGap = 12; }
        else { primH = Tokens.H_XL; rowH = Tokens.H_LG; tileH = 46; quitH = 22; gap = 7; logoGap = 26; }
        int buttons = 2 * primH + gap + (rejoin ? gap + rowH : 0) + gap + gap + tileH + gap + quitH;
        return new Metrics(primH, rowH, tileH, quitH, gap, logoGap, buttons);
    }

    /** Works out where everything goes for this window size (design px). */
    private void layout(TitleScreenModule mod, Metrics k) {
        margin = Math.round(Math.max(14, Math.min(64, W * 0.045f)));
        int cardW = Math.round(Math.max(168, Math.min(250, W * 0.225f)));
        int bw = Math.round(Math.max(216, Math.min(292, W * 0.27f)));
        boolean panels = mod == null || mod.sidePanels.get();
        // The column stays centred; the rails need a card's width plus a clear gap on each side of it.
        int railRoom = (W - bw) / 2 - margin - 20;
        wide = panels && H >= 250 && railRoom >= cardW;

        footerCy = H - 14;
        int bottom = H - 30;
        markSize = Math.round(Math.max(26, Math.min(66, H * 0.11f)));
        if (H < 236) markSize = 22;
        tagline = !compact;
        int block = markSize + (tagline ? 14 : 0) + k.logoGap + k.buttons;
        int topBar = 24;
        int top = bottom - topBar >= block ? topBar : 8;

        colW = wide ? bw : Math.min(240, W - 32);
        colX = (W - colW) / 2;
        int blockTop = top + Math.max(0, Math.round((bottom - top - block) * 0.46f));
        logoY = blockTop;
        logoX = (W - TitleArt.lockupWidth(markSize)) / 2;

        // Side rails beside the centre stack, sharing one top line so their headers align. A rail drops a card
        // when the screen is too short for it.
        accountY = serversY = newsY = teaserY = -1;
        friends = false;
        newsLines.clear();
        teaserLines.clear();
        if (!wide) return;
        int railTop = topBar + 2;
        cardsW = Math.min(cardW, railRoom);
        leftX = margin;
        rightX = W - margin - cardsW;
        int avail = bottom - railTop, mid = blockTop + block / 2;

        friends = (mod == null || mod.friendsOnline.get()) && FriendsStrip.available();
        accountH = ACCOUNT_H + (friends ? FRIENDS_H : 0);
        wrap(WhatsNew.HEADLINE, cardsW - 28, Role.BODY, newsLines, 3);
        newsH = 32 + newsLines.size() * 12 + 24;
        int left = accountH + CARD_GAP + newsH;
        boolean news = left <= avail;
        if (!news) left = accountH;

        int nServers = RecentServers.get().entries().size();
        serversH = 32 + (nServers == 0 ? 36 : nServers * 30) + 6;
        boolean teaser = (mod == null || mod.cosmeticsPreview.get()) && WardrobeTeaser.available()
                && TEASER_H + CARD_GAP + serversH <= avail;
        int right = serversH + (teaser ? TEASER_H + CARD_GAP : 0);

        int tallest = Math.max(left, right);
        int ry0 = Math.max(railTop, Math.min(bottom - tallest, mid - tallest / 2));
        accountY = ry0;
        if (news) newsY = ry0 + accountH + CARD_GAP;
        int ry = ry0;
        if (teaser) {
            teaserY = ry;
            ry += TEASER_H + CARD_GAP;
            int figW = Math.min(64, Math.max(42, cardsW / 3));
            figX1 = rightX + 10;
            figX2 = figX1 + figW;
            figY1 = teaserY + 26;
            figY2 = teaserY + TEASER_H - 12;
            wrap(WardrobeTeaser.summary(), rightX + cardsW - 14 - (figX2 + 10), Role.BODY, teaserLines, 2);
        }
        serversY = ry;

        // The account card's text.
        int hour = LocalTime.now().getHour();
        greeting = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
        playerName = TitleArt.ellipsize(client.getSession().getUsername(), cardsW - 64, Role.STRONG);
        accountNote = client.getSession().getXuid().isPresent() ? "Microsoft account" : "Offline profile";
    }

    private void addCardWidgets(RecentServers recent, Text mpDisabled) {
        int x = rightX, w = cardsW;
        double d = 140;
        if (accountY >= 0 && friends) {
            add(new FriendsStrip(leftX + 6, accountY + ACCOUNT_H - 2, w - 12, FRIENDS_H - 4, this::openFriends, intro, d + 40).plane(P_CARDS))
                    .tip("Open Friends in the Evade menu");
        }
        if (accountY >= 0) d += 45;
        if (teaserY >= 0) {
            int bx = figX2 + 10, bw = x + w - 12 - bx, bh = 22;
            add(new TitleButton(bx, teaserY + TEASER_H - 12 - bh, bw, bh, Text.literal("Wardrobe"), Style.SECONDARY, "wardrobe",
                    this::openWardrobe).centered(true).intro(intro, d + 30, 12, 0).plane(P_CARDS))
                    .tip("Pick capes, wings, hats, pets and emotes");
            d += 45;
        }
        // Continue playing: the server rows and a link to the full list.
        String all = "All servers";
        int lw = TitleArt.width(all, Role.SMALL) + 10;
        if (14 + TitleArt.capsWidth("Continue playing", Role.CAPS, 1.0f) + 10 + lw + 14 > w) {
            all = "All"; // a narrow rail: the header keeps its room
            lw = TitleArt.width(all, Role.SMALL) + 10;
        }
        TitleButton allLink = add(new TitleButton(x + w - 14 - lw, serversY + 11, lw, 10, Text.literal(all), Style.LINK, "chevron_r",
                this::openMultiplayer).intro(intro, d, 12, 0).plane(P_CARDS));
        allLink.tip(mpDisabled != null ? mpDisabled : Text.literal("Your full server list"));
        if (mpDisabled != null) allLink.active = false;
        List<RecentServers.Entry> entries = recent.entries();
        int ry = serversY + 32;
        double rd = d + 20;
        for (RecentServers.Entry e : entries) {
            ServerRow row = add(new ServerRow(x + 6, ry, w - 12, 28, e, this::join, intro, rd).plane(P_CARDS));
            if (mpDisabled != null) {
                row.active = false;
                row.tip(mpDisabled.getString());
            } else {
                row.tip("Join " + e.name() + " (" + e.info.address + ")");
            }
            ry += 30;
            rd += 25;
        }
        if (entries.isEmpty()) {
            String add = "Add a server";
            int aw = TitleArt.width(add, Role.SMALL) + 10;
            TitleButton a = add(new TitleButton(x + 14, serversY + 32 + 20, aw, 10, Text.literal(add), Style.LINK, "chevron_r",
                    this::openMultiplayer).intro(intro, rd, 12, 0).plane(P_CARDS));
            if (mpDisabled != null) a.active = false;
        }
        d += 45;
        if (newsY >= 0) {
            String more = "Read the changelog";
            int mw = TitleArt.width(more, Role.SMALL) + 12;
            add(new TitleButton(leftX + 14, newsY + newsH - 20, mw, 10, Text.literal(more), Style.LINK, "external",
                    () -> ConfirmLinkScreen.open(this, WhatsNew.URL)).intro(intro, d + 20, -12, 0).plane(P_CARDS))
                    .tip("Everything new in " + WhatsNew.VERSION + ", on evadeclient.com");
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
        if (!line.isEmpty()) out.add(TitleArt.ellipsize(line.toString(), w, r));
    }

    // ------------------------------------------------------------------ input (mapped to design px)

    /** A click while the entrance plays finishes it (and does nothing else); otherwise as usual. */
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (intro.full() && intro.skip()) return true;
        Click c = scaled(click);
        if (teaserY >= 0 && c.button() == 0 && c.x() >= figX1 && c.x() < figX2 && c.y() >= figY1 && c.y() < figY2) {
            openWardrobe();
            return true;
        }
        return super.mouseClicked(c, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        return super.mouseReleased(scaled(click));
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        return super.mouseDragged(scaled(click), offsetX / zoom, offsetY / zoom);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        return super.mouseScrolled(mouseX / zoom, mouseY / zoom, horizontal, vertical);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX / zoom, mouseY / zoom);
    }

    /** Escape, Space or Enter while the entrance plays finish it; any other key also does, and still works. */
    @Override
    public boolean keyPressed(KeyInput input) {
        if (intro.full() && intro.skip() && (input.isEscape() || input.isEnterOrSpace())) return true;
        return super.keyPressed(input);
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

    private void openWardrobe() {
        client.setScreen(new net.evadeclient.cosmetics.WardrobeScreen(this));
    }

    /** The Evade menu on its Friends page; closing it comes back here. */
    private void openFriends() {
        EvadeMenu.openFriendsForTest(0); // jumps the menu's page (the hook is public; it isn't test-only in effect)
        client.setScreen(EvadeMenu.open(null, this));
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

    // ------------------------------------------------------------------ the loading screen's hand-over

    /** Whether Evade's loading screen is over this menu now (it may be flying its logo into ours). */
    private boolean underSplash() {
        return client != null && client.getOverlay() instanceof SplashOverlay;
    }

    /**
     * Where this menu's logo mark sits, in device pixels: {x, y, mark size, design px per unit (device)} and its
     * design mark size in [4]. False before the first layout. The loading screen flies its logo here.
     */
    public boolean logoSlot(float[] out) {
        if (client == null || markSize <= 0 || W <= 0) return false;
        float k = zoom * (float) Math.max(1, client.getWindow().getScaleFactor());
        out[0] = logoX * k;
        out[1] = logoY * k;
        out[2] = markSize * k;
        out[3] = k;
        out[4] = markSize;
        return true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long t0 = System.nanoTime();
        TitleScreenModule mod = TitleScreenModule.active();
        var cs = net.evadeclient.module.client.ClientSettings.class;
        if (EvadeClient.modules != null && EvadeClient.modules.get(cs) != null) EvadeClient.modules.get(cs).applyTheme();
        Tokens.refresh();
        TitleArt.zoom(zoom);
        int GW = width, GH = height;
        boolean motion = TitleArt.anims();
        boolean light = TitleScreenModule.lightEffects();
        // Under the loading screen the mouse reads (0, 0): hold the parallax centred so the hand-over lands true.
        boolean parallax = (mod == null || mod.parallax.get()) && !light && !underSplash();
        SCENE.update(mouseX, mouseY, GW, GH, parallax, motion);
        String bg = mod == null ? "Panorama" : mod.backgroundMode();
        boolean panorama = bg.equals("Panorama");
        if (panorama) {
            if (layer(L_PANORAMA)) SCENE.panorama(ctx, GW, GH, motion, !light && !TitleArt.gpu());
        } else {
            switch (bg) {
                case "Evade Animated" -> TitleBackground.animated(ctx, GW, GH);
                case "Solid Color" -> ctx.fill(0, 0, GW, GH, 0xFF000000 | mod.backgroundColor.get());
                case "Custom Image" -> {
                    // A picture gets the same treatment as the panorama (blur, the darker veil); none falls back to the grid.
                    panorama = TitleBackground.image(ctx, GW, GH, mod.imageFile.get());
                    if (!panorama) TitleBackground.grid(ctx, GW, GH, 0);
                }
                default -> TitleBackground.grid(ctx, GW, GH, 0);
            }
        }
        if (panorama && mod != null && mod.blur.get()) {
            try {
                ctx.applyBlur();
            } catch (IllegalStateException ignored) {
                // Something already blurred this frame.
            }
        }
        float base = Theme.fade;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            Theme.fade = 1f;
            m.scale(zoom, zoom);
            boolean shader = TitleArt.gpu() && !light;
            if (layer(L_VEILS)) veils(ctx, panorama, light, shader);
            // Embers between the veil and the vignette: they read as depth, and the edges swallow them.
            if (layer(L_EMBERS) && (mod == null || mod.embers.get())) SCENE.embers(ctx, W, H, intro.p(200, 1400), motion, light);
            if (layer(L_VIGNETTE)) vignette(ctx, light, shader);
            if (layer(L_GLOWS)) glows(ctx, light, motion);
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
        cost(t0);
    }

    /** Centre of the stage: the middle of the CTA block. */
    private float stageY() {
        return (logoY + colBottom) / 2f;
    }

    /**
     * The veil. Full (shader): one dithered radial falloff over the whole screen, clear-ish behind the centre stack
     * and deep at the edges (the side rails sit in its shade), replacing a flat veil and the vignette texture. Light,
     * or without the shader: three strips that tile the screen with no overlap (top fade, flat, bottom fade), so every
     * pixel is blended once.
     */
    private void veils(DrawContext ctx, boolean panorama, boolean light, boolean shader) {
        if (shader) {
            float c = panorama ? 0.30f : 0.10f, e = panorama ? 0.86f : 0.62f;
            int centre = Theme.alpha(Tokens.N0, c), edge = Theme.alpha(Tokens.N0, e);
            TitleArt.stage(ctx, W, H, W / 2f, stageY(), centre, edge);
            return;
        }
        float veil = panorama ? (wide ? (light ? 0.44f : 0.38f) : 0.47f) : (wide ? 0.19f : 0.35f);
        float topA = light ? 0.56f : 0.42f, botA = 0.78f;
        int t1 = Math.round(H * 0.28f), b1 = Math.round(H * 0.58f);
        int v = Theme.alpha(0xFF000000, veil);
        int top = Theme.alpha(0xFF000000, 1f - (1f - veil) * (1f - topA));
        int bot = Theme.alpha(0xFF000000, 1f - (1f - veil) * (1f - botA));
        ctx.fillGradient(0, 0, W, t1, top, v);
        ctx.fill(0, t1, W, b1, v);
        ctx.fillGradient(0, b1, W, H, v, bot);
        if (wide && !light) {
            int reach = leftX + cardsW + Math.round(W * 0.06f);
            TitleArt.rampH(ctx, 0, 0, reach, H, 0x90060608, false);
            TitleArt.rampH(ctx, W - reach, 0, W, H, 0x90060608, true);
        }
    }

    /** Full only: a cool, dark top (sky) and a deep floor under the footer, both dithered. */
    private void vignette(DrawContext ctx, boolean light, boolean shader) {
        if (light) return;
        if (shader) {
            int floorTop = Math.round(H * 0.64f);
            UiShape.shape(0, floorTop, W, H).vertical(0x00000000, Theme.alpha(Tokens.N0, 0.62f)).draw(ctx);
            UiShape.shape(0, 0, W, Math.round(H * 0.26f)).vertical(Theme.alpha(0xFF04070E, 0.55f), 0x0004070E).draw(ctx);
            return;
        }
        TitleArt.vignette(ctx, W, H, 0xB0000000);
    }

    /**
     * The only light on the stage: a warm red under-light rising from below the centre and the logo's slow breathing
     * halo, both additive. Light effects: one small halo behind the logo.
     */
    private void glows(DrawContext ctx, boolean light, boolean motion) {
        double t = Intro.now() / 1000.0;
        float breathe = motion ? 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2 / 7.0) : 0.5f;
        float in = Math.max(intro.p(0, 900), handed ? 1f : 0f);
        float lw = TitleArt.lockupWidth(markSize);
        float cx = logoX + lw / 2f + TitleScene.planeX(P_LOGO);
        float cy = logoY + markSize / 2f + TitleScene.planeY(P_LOGO);
        if (light) {
            TitleArt.glow(ctx, Math.round(cx), Math.round(cy), Math.round(lw * 1.6f), markSize * 3, Theme.alpha(Tokens.A600, 0.30f * in));
            return;
        }
        int red = Tokens.A600;
        TitleArt.softGlow(ctx, cx, cy, lw * 0.85f, markSize * 1.7f, Theme.alpha(red, (0.24f + 0.05f * breathe) * in));
        TitleArt.softGlow(ctx, W / 2f, H * 1.06f, W * 0.62f, H * 0.42f, Theme.alpha(red, (0.16f + 0.03f * (1f - breathe)) * in));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long t0 = System.nanoTime();
        TitleArt.zoom(zoom);
        boolean splash = underSplash();
        boolean flying = splash && EvadeSplash.flying();
        if (!begun && (client.getOverlay() == null || EvadeSplash.handingOver())) {
            intro.begin(Motion.level() != Motion.Level.OFF);
            begun = true;
            handed = flying || EvadeSplash.handingOver() && EvadeSplash.flies();
        }
        int mx = Math.floorDiv(mouseX, zoom), my = Math.floorDiv(mouseY, zoom);
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.scale(zoom, zoom);
            if (!flying) drawLogo(ctx);
            if (wide && layer(L_CARDS)) drawCards(ctx, mx, my);
            cost(t0);
            super.render(ctx, mx, my, delta);
            t0 = System.nanoTime();
            float c = intro.p(260, 320);
            if (showVersion && c > 0.01f) {
                float base = Theme.fade;
                Theme.fade = c;
                TitleArt.textMid(ctx, versionText, versionX, footerCy, Tokens.N600, Role.SMALL);
                Theme.fade = base;
            }
            if (!splash) drawTooltip(ctx, mx, my);
        } finally {
            m.popMatrix();
        }
        cost(t0);
    }

    /** The tooltip of the hovered widget (after 450 ms) or the keyboard-focused one (at once), above it. */
    private void drawTooltip(DrawContext ctx, int mx, int my) {
        ClickableWidget target = null;
        boolean keyboard = client.getNavigationType().isKeyboard();
        for (var child : children()) {
            if (!(child instanceof ClickableWidget w) || !(child instanceof TitleTip t) || t.tip() == null || !w.visible) continue;
            if (w.isHovered() || keyboard && w.isFocused()) { target = w; break; }
        }
        double now = Intro.now();
        if (target != tipFor) { tipFor = target; tipSince = now; }
        if (target == null || !intro.done()) return;
        double delay = keyboard && target.isFocused() ? 0 : 450;
        float a = Motion.clamp01((float) ((now - tipSince - delay) / Math.max(1, Motion.duration(Motion.DUR_QUICK))));
        if (Motion.level() == Motion.Level.OFF && now - tipSince >= delay) a = 1f;
        TitleArt.tooltip(ctx, ((TitleTip) target).tip(), target.getX(), target.getY(), target.getX() + target.getWidth(),
                target.getY() + target.getHeight(), W, H, a);
    }

    /** Adds this frame's time since {@code t0} to the running frame cost (an average over about a second). */
    private static void cost(long t0) {
        double us = (System.nanoTime() - t0) / 1000.0;
        costEmaMicros += (us - costEmaMicros) * 0.02;
    }

    /**
     * The logo's entrance: the mark slides in and settles, the wordmark is revealed left to right behind it, the
     * tagline fades up, then a light sweeps once across. A replay (coming back) is a quick fade. Handed over by the
     * loading screen, it is simply there.
     */
    private void drawLogo(DrawContext ctx) {
        float in = handed ? 1f : intro.p(0, 520);
        if (in <= 0.001f) return;
        boolean moves = intro.moves() && !handed;
        float base = Theme.fade;
        var m = ctx.getMatrices();
        int lw = TitleArt.lockupWidth(markSize);
        int letter = TitleArt.wordLetter(markSize);
        int wx = TitleArt.wordX(logoX, markSize);
        m.pushMatrix();
        try {
            // The logo floats against the cards (its own parallax plane).
            m.translate(Theme.snap(ctx, TitleScene.planeX(P_LOGO)), Theme.snap(ctx, (moves ? (1f - in) * 10f : 0f) + TitleScene.planeY(P_LOGO)));
            Theme.fade = in;
            m.pushMatrix();
            if (moves) m.translate(Theme.snap(ctx, (1f - in) * -14f), 0);
            TitleArt.mark(ctx, logoX, logoY, markSize, Theme.fa(net.evadeclient.util.Logo.markTint()));
            m.popMatrix();
            float wordIn = handed ? 1f : intro.full() ? intro.p(120, 640) : in;
            if (wordIn > 0.001f) {
                Theme.fade = Math.min(1f, wordIn * 1.4f);
                int ww = lw - (wx - logoX);
                int reveal = Math.round((ww + 8) * wordIn);
                boolean clip = wordIn < 0.999f && moves;
                if (clip) ctx.enableScissor(wx - 2, logoY - markSize, wx + reveal, logoY + markSize * 2);
                TitleArt.word(ctx, wx, logoY + (markSize - letter) / 2, letter, Theme.fa(0xFFF2F2F5));
                if (clip) ctx.disableScissor();
            }
            if (tagline) {
                String tl = "Your game, sharper";
                int tx = logoX + (lw - TitleArt.capsWidth(tl, Role.CAPS_SM, 2.2f)) / 2;
                int ty = logoY + markSize + 3;
                Theme.fade = intro.p(380, 460);
                TitleArt.caps(ctx, tl, tx, ty, Tokens.N700, Role.CAPS_SM, 2.2f);
            }
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
        if (!handed) sweep(ctx);
    }

    /**
     * A soft light sweeping once across the logo after it arrives: the logo drawn again in white through a narrow
     * scissor that moves left to right, in nested bands so its edges are soft.
     */
    private void sweep(DrawContext ctx) {
        if (!intro.full()) return;
        double t = intro.elapsed() - 760;
        if (t < 0 || t > 900) return;
        float p = Motion.cubicOut((float) (t / 900));
        int lw = TitleArt.lockupWidth(markSize);
        int band = Math.max(12, markSize);
        int cx = logoX - band + Math.round((lw + 2 * band) * p);
        float fadeOut = 1f - Math.max(0f, (float) (t - 600) / 300f);
        float base = Theme.fade;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(Theme.snap(ctx, TitleScene.planeX(P_LOGO)), Theme.snap(ctx, TitleScene.planeY(P_LOGO)));
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
            m.popMatrix();
        }
    }

    // ------------------------------------------------------------------ side cards

    private void drawCards(DrawContext ctx, int mx, int my) {
        int x = rightX, l = leftX, w = cardsW;
        double d = 140;
        if (accountY >= 0) {
            float in = card(ctx, l, accountY, w, accountH, d, mx, my);
            if (in > 0) drawAccount(ctx, l, accountY, w, in);
            d += 45;
        }
        if (teaserY >= 0) {
            float in = card(ctx, x, teaserY, w, TEASER_H, d, mx, my);
            if (in > 0) drawTeaser(ctx, x, teaserY, w, in);
            d += 45;
        }
        float in = card(ctx, x, serversY, w, serversH, d, mx, my);
        if (in > 0) {
            float base = beginCard(ctx, x, in);
            try {
                header(ctx, "Continue playing", x, serversY, w);
                if (RecentServers.get().entries().isEmpty()) {
                    TitleArt.text(ctx, "Servers you join show up here.", x + 14, serversY + 34, Tokens.N700, Role.SMALL);
                }
            } finally {
                endCard(ctx, base);
            }
        }
        d += 45;
        if (newsY >= 0) {
            float nin = card(ctx, l, newsY, w, newsH, d, mx, my);
            if (nin > 0) {
                float base = beginCard(ctx, l, nin);
                try {
                    header(ctx, "What's new", l, newsY, w);
                    // The version as a small accent badge (red400 on a deep red tint).
                    int pw = TitleArt.width(WhatsNew.VERSION, Role.SMALL) + 12;
                    int px2 = l + w - 14, px1 = px2 - pw;
                    if (TitleArt.gpu()) UiShape.shape(px1, newsY + 9, px2, newsY + 22).pill().fill(Theme.alpha(Tokens.A900, 0.55f))
                            .border(Theme.alpha(Tokens.A500, 0.35f), 1).draw(ctx);
                    else Theme.pill(ctx, px1, newsY + 9, px2, newsY + 22, Theme.alpha(Theme.RED, 0.22f));
                    TitleArt.textMid(ctx, WhatsNew.VERSION, px1 + 6, newsY + 15.5f, Tokens.A400, Role.SMALL);
                    int ly = newsY + 34;
                    for (String line : newsLines) {
                        TitleArt.text(ctx, line, l + 14, ly, Tokens.N950, Role.BODY);
                        ly += 12;
                    }
                } finally {
                    endCard(ctx, base);
                }
            }
        }
    }

    /** A card's header: tracked caps in n700 and a fading hairline under it. */
    private static void header(DrawContext ctx, String title, int x, int y, int w) {
        TitleArt.caps(ctx, title, x + 14, y + 12, Tokens.N700, Role.CAPS, 1.0f);
        if (TitleArt.gpu()) UiShape.shape(x + 10, y + 26, x + w - 10, y + 26 + 1f / Theme.deviceScale(ctx))
                .mirror(0x12FFFFFF, 0x00FFFFFF, true, 0.35f).draw(ctx);
        else Theme.fadeLine(ctx, x + 10, y + 26, x + w - 10, 0x12FFFFFF);
    }

    /**
     * Draws a card's body as it slides in: deep glass over the blurred world (spec glass.deep), Gaussian contact and
     * ambient shadows, a hairline, and a specular top rim whose brightest point follows the cursor across it.
     * Returns its intro progress.
     */
    private float card(DrawContext ctx, int x, int y, int w, int h, double delay, int mx, int my) {
        float in = intro.p(delay, 380);
        if (in <= 0.001f) return 0f;
        float base = Theme.fade;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            Theme.fade = in;
            plane(ctx);
            if (intro.moves()) m.translate(Theme.snap(ctx, slide(x) * (1f - in)), 0);
            boolean over = mx >= x && mx < x + w && my >= y && my < y + h;
            float r = Tokens.R_LG;
            int fill = Theme.alpha(Tokens.N100, Tokens.reduceTransparency ? 1f : 0.80f);
            if (TitleArt.gpu() && TitleScreenModule.lightEffects()) {
                // Light: a tight contact shadow and a flat glass fill, so only the edge bands run the shader.
                UiShape.shape(x, y, x + w, y + h).radius(r).fill(Theme.alpha(0xFF000000, 0.50f)).asShadow(2f, 2f, true).draw(ctx);
                UiShape.shape(x, y, x + w, y + h).radius(r).fill(Theme.alpha(Tokens.N100, Tokens.reduceTransparency ? 1f : 0.84f))
                        .border(Theme.alpha(0xFFFFFFFF, 0.07f), 1).rim(over ? 0.15f : 0.10f).rimAt(over ? mx - x : w * 0.3f).draw(ctx);
            } else if (TitleArt.gpu()) {
                UiShape.shape(x, y, x + w, y + h).radius(r).fill(Theme.alpha(0xFF000000, 0.38f)).asShadow(10f, 8f, true).draw(ctx);
                UiShape.shape(x, y, x + w, y + h).radius(r).fill(Theme.alpha(0xFF000000, 0.45f)).asShadow(2f, 2f, true).draw(ctx);
                UiShape.shape(x, y, x + w, y + h).radius(r).vertical(fill, Theme.alpha(Tokens.N50, Tokens.reduceTransparency ? 1f : 0.86f))
                        .border(Theme.alpha(0xFFFFFFFF, 0.07f), 1).rim(over ? 0.15f : 0.10f).rimAt(over ? mx - x : w * 0.3f)
                        .grain(Tokens.reduceTransparency ? 0f : 0.012f).draw(ctx);
            } else {
                Theme.shadow(ctx, Theme.SH_CARD, x, y + 2, x + w, y + h + 2, 0x99000000);
                Theme.round(ctx, x, y, x + w, y + h, 0xD80C0C10, 6);
                Theme.fadeLine(ctx, x + 4, y, x + w - 4, 0x22FFFFFF);
                Theme.hairline(ctx, x, y, x + w, y + h, 0x1EFFFFFF, 6);
            }
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
        return in;
    }

    /** The side cards' parallax plane. */
    private static void plane(DrawContext ctx) {
        ctx.getMatrices().translate(Theme.snap(ctx, TitleScene.planeX(P_CARDS)), Theme.snap(ctx, TitleScene.planeY(P_CARDS)));
    }

    /** Cards slide in from their own side: the left rail from the left, the right rail from the right. */
    private float slide(int x) {
        return x < W / 2 ? -12f : 12f;
    }

    /** Starts drawing a card's contents at intro progress {@code in} (fade and slide); returns the fade to restore. */
    private float beginCard(DrawContext ctx, int x, float in) {
        float base = Theme.fade;
        ctx.getMatrices().pushMatrix();
        Theme.fade = base * in;
        plane(ctx);
        if (intro.moves()) ctx.getMatrices().translate(Theme.snap(ctx, slide(x) * (1f - in)), 0);
        return base;
    }

    private void endCard(DrawContext ctx, float base) {
        Theme.fade = base;
        ctx.getMatrices().popMatrix();
    }

    private void drawAccount(DrawContext ctx, int x, int y, int w, float in) {
        float base = beginCard(ctx, x, in);
        try {
            int fs = 30, fx = x + 12, fy = y + 10;
            if (TitleArt.gpu()) {
                UiShape.shape(fx, fy, fx + fs, fy + fs).radius(Tokens.R_XS + 1).fill(Theme.alpha(0xFF000000, 0.5f)).asShadow(2f, 1.5f, true).draw(ctx);
            }
            Theme.round(ctx, fx - 1, fy - 1, fx + fs + 1, fy + fs + 1, 0xFF0A0B0E, 4);
            if (skin != null) PlayerSkinDrawer.draw(ctx, skin.get(), fx, fy, fs, Theme.fa(0xFFFFFFFF));
            Theme.hairline(ctx, fx - 1, fy - 1, fx + fs + 1, fy + fs + 1, 0x26FFFFFF, 4);
            int tx = fx + fs + 11;
            TitleArt.caps(ctx, greeting, tx, y + 11, Tokens.N700, Role.CAPS_SM, 1.4f);
            TitleArt.text(ctx, playerName, tx, y + 21, Tokens.N950, Role.STRONG);
            boolean ms = accountNote.startsWith("Microsoft");
            dot(ctx, tx + 2, y + 37, 4, ms ? Tokens.OK : Tokens.N600);
            TitleArt.textMid(ctx, accountNote, tx + 8, y + 37, Tokens.N700, Role.SMALL);
            if (friends) {
                if (TitleArt.gpu()) UiShape.shape(x + 12, y + ACCOUNT_H - 4, x + w - 12, y + ACCOUNT_H - 4 + 1f / Theme.deviceScale(ctx))
                        .mirror(0x14FFFFFF, 0x00FFFFFF, true, 0.4f).draw(ctx);
                else Theme.fill(ctx, x + 10, y + ACCOUNT_H - 4, x + w - 10, y + ACCOUNT_H - 3, 0x14FFFFFF);
            }
        } finally {
            endCard(ctx, base);
        }
    }

    private static void dot(DrawContext ctx, float cx, float cy, float d, int color) {
        if (TitleArt.gpu()) UiShape.shape(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2).radius(d / 2).fill(color).draw(ctx);
        else Theme.disc(ctx, Math.round(cx - d / 2), Math.round(cy - d / 2), Math.round(d), color);
    }

    /** The Wardrobe card: the turning figure on a lit floor disc, a line about what you wear and the Wardrobe button. */
    private void drawTeaser(DrawContext ctx, int x, int y, int w, float in) {
        float base = beginCard(ctx, x, in);
        try {
            header(ctx, "Your look", x, y, w);
            int tx = figX2 + 10, ty = y + 36;
            for (String line : teaserLines) {
                TitleArt.text(ctx, line, tx, ty, Tokens.N950, Role.BODY);
                ty += 12;
            }
            String sub = TitleArt.ellipsize("Click to try things on", x + w - 14 - tx, Role.SMALL);
            TitleArt.text(ctx, sub, tx, ty + 3, Tokens.N700, Role.SMALL);
            WardrobeTeaser.floor(ctx, figX1, figY1, figX2, figY2, in);
        } finally {
            endCard(ctx, base);
        }
        // Outside the card's slide: the 3D figure is placed in screen space and appears once the card has arrived.
        float fb = Theme.fade;
        Theme.fade = fb * in;
        try {
            if (layer(L_PREVIEW) && skin != null) {
                float px = TitleScene.planeX(P_CARDS), py = TitleScene.planeY(P_CARDS);
                int k = zoom;
                int ox = Math.round(px * k), oy = Math.round(py * k);
                var m = ctx.getMatrices();
                m.pushMatrix();
                m.scale(1f / k, 1f / k); // the figure's special element is placed in GUI px, not by the matrix
                try {
                    WardrobeTeaser.figure(ctx, skin.get(), figX1 * k + ox, figY1 * k + oy, figX2 * k + ox, figY2 * k + oy,
                            TitleArt.anims() && !TitleScreenModule.lightEffects(), in);
                } finally {
                    m.popMatrix();
                }
            }
        } finally {
            Theme.fade = fb;
        }
    }

    // ------------------------------------------------------------------ for the gametest

    /** CPU time this screen spends drawing a frame, in microseconds (a running average). */
    public static double frameCostMicros() { return costEmaMicros; }

    /** Design px to GUI px (gametest). */
    public int zoom() { return zoom; }

    /** Whether the side rails are showing (and which cards): "wide", "teaser", "friends", "news". */
    public List<String> shownParts() {
        List<String> out = new ArrayList<>();
        if (wide) out.add("wide");
        if (teaserY >= 0) out.add("teaser");
        if (friends) out.add("friends");
        if (newsY >= 0) out.add("news");
        return out;
    }

    /**
     * Every box on the screen by name ({x1, y1, x2, y2} in GUI px): the logo, the cards and each widget. Used to
     * check nothing overlaps and everything stays on screen at every size.
     */
    public Map<String, int[]> boxes() {
        Map<String, int[]> out = new LinkedHashMap<>();
        int z = zoom;
        out.put("logo", new int[]{logoX * z, logoY * z, (logoX + TitleArt.lockupWidth(markSize)) * z, (logoY + markSize + (tagline ? 14 : 0)) * z});
        if (accountY >= 0) out.put("card:account", new int[]{leftX * z, accountY * z, (leftX + cardsW) * z, (accountY + accountH) * z});
        if (newsY >= 0) out.put("card:news", new int[]{leftX * z, newsY * z, (leftX + cardsW) * z, (newsY + newsH) * z});
        if (teaserY >= 0) out.put("card:teaser", new int[]{rightX * z, teaserY * z, (rightX + cardsW) * z, (teaserY + TEASER_H) * z});
        if (serversY >= 0) out.put("card:servers", new int[]{rightX * z, serversY * z, (rightX + cardsW) * z, (serversY + serversH) * z});
        int i = 0;
        for (var child : children()) {
            if (child instanceof ClickableWidget w) {
                out.put("w" + (i++) + ":" + w.getMessage().getString(), new int[]{w.getX() * z, w.getY() * z,
                        (w.getX() + w.getWidth()) * z, (w.getY() + w.getHeight()) * z});
            }
        }
        return out;
    }
}
