package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.HudEditorScreen;
import net.evadeclient.hud.Hud;
import net.evadeclient.module.Category;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.scoreboard.number.StyledNumberFormat;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The sidebar scoreboard, drawn by Evade: hide or recolour the score numbers, trim it to fewer lines, drop the title
 * or the server address line, space the lines out, give it Evade's panel look, or move, resize or hide it.
 *
 * <p>While this module is on, {@code InGameHudMixin} cancels vanilla's sidebar and calls {@link #drawSidebar} in its
 * place (same layer, same objective, so it still shows with F3 open and hides with F1). With the default settings it
 * looks exactly like vanilla's, apart from the numbers. At its default spot it sits where vanilla puts it (right edge,
 * about the middle, following the number of lines) and scales around that anchor. It is drawn in GUI pixels, like
 * vanilla's, whatever the HUD scale. The HUD editor shows the real sidebar, or a sample while there is none.
 */
public class Scoreboard extends HudElementModule {
    /** Default y: vanilla's own spot. Saved like any other position, but dragging never produces it. */
    public static final int VANILLA_SPOT = Integer.MIN_VALUE;
    /** How long a drawn sidebar counts as on screen (a few frames even at a low frame rate). */
    private static final long LIVE_NS = 500_000_000L;
    /** How long the HUD editor's laid-out sidebar is reused (it is asked for its size several times a frame). */
    private static final long PREVIEW_NS = 50_000_000L;
    /** Vanilla never shows more than 15 lines. */
    private static final int VANILLA_MAX_LINES = 15;
    /** Panel insets with Vanilla Look off, in GUI pixels. */
    private static final int PAD_X = 4, PAD_Y = 3;

    /** Vanilla's sidebar order: highest score first, then by name. */
    private static final Comparator<ScoreboardEntry> ORDER = Comparator.comparing(ScoreboardEntry::value).reversed()
            .thenComparing(ScoreboardEntry::owner, String.CASE_INSENSITIVE_ORDER);
    /** A line that is only a server address ("play.example.net", "mc.example.co.uk"); the last label has a letter. */
    private static final Pattern SERVER_ADDRESS =
            Pattern.compile("(?i)^\\s*(www\\.|play\\.|mc\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z][a-z0-9-]*\\s*$");

    public final ModeSetting numbers = add(new ModeSetting("Numbers", "Hidden", "Hidden", "Auto", "Shown")
            .describe("The score numbers on the right. Auto hides them when they only count the lines down "
                    + "(15, 14, 13...). Text a server puts there instead always stays."));
    public final BooleanSetting recolorNumbers = add(new BooleanSetting("Recolor Numbers", false)
            .describe("Draw the score numbers in Number Color instead of red. Numbers a server colours itself keep theirs."));
    public final ColorSetting numberColor = add(new ColorSetting("Number Color", 0xFFFF5555)
            .describe("The colour Recolor Numbers uses."));
    public final BooleanSetting hideServerIp = add(new BooleanSetting("Hide Server IP Line", false)
            .describe("Drop the last line when it is just the server's address (play.example.net)."));
    public final BooleanSetting showTitle = add(new BooleanSetting("Show Title", true)
            .describe("Show the sidebar's title above the lines."));
    public final NumberSetting maxLines = add(new NumberSetting("Max Lines", 15, 1, 15, 1)
            .describe("Show at most this many lines, from the top."));
    public final NumberSetting lineHeight = add(new NumberSetting("Line Height", 9, 8, 12, 1)
            .describe("Space per line in pixels. Vanilla uses 9."));
    public final BooleanSetting vanillaLook = add(new BooleanSetting("Vanilla Look", true)
            .describe("Vanilla's flat dark strips. Off: Evade's panel, in the chosen Background Style."));
    public final BooleanSetting textShadow = add(new BooleanSetting("Text Shadow", false)
            .describe("Draw the sidebar's text with a shadow."));
    public final BooleanSetting hide = add(new BooleanSetting("Hide Scoreboard", false)
            .describe("Don't draw the sidebar at all."));

    /** One sidebar line: the name (team prefix and suffix included) and its score, or none. */
    private record Row(Text name, Text score, int scoreWidth) {}

    /**
     * A sidebar laid out for drawing, in GUI pixels before Scale: its size, where the title, the names and the scores
     * go, and how far below the middle of the screen vanilla would put its bottom edge ({@code below}).
     */
    private record Layout(Text title, int titleX, int titleY, Row[] rows, int rowsTop, int rowStep, int rowOffset,
                          int nameX, int scoreRight, int bodyTop, int dividerY, boolean vanilla, int w, int h, int below,
                          boolean numbersShown) {}

    /** The sidebar {@link #drawSidebar} is drawing right now (render() is called from inside it), else null. */
    private Layout drawing;
    /** The HUD editor's sidebar and when it was laid out (System.nanoTime). */
    private Layout preview;
    private long previewAt;
    /** The last laid-out box (GUI pixels, before Scale), for the default spot. Render thread only. */
    private int boxW = 60, boxH = 55, boxBelow = 15;
    /** When the sidebar was last drawn in game (System.nanoTime), 0 for never. */
    private long seenAt;
    /** Lines and whether numbers were drawn, in the sidebar last drawn in game (for tests). */
    private int lastLines;
    private boolean lastNumbers;
    /** Recolor Numbers' format, rebuilt when Number Color changes. */
    private StyledNumberFormat recolorFormat;
    private int recolorRgb = -1;

    private static final Text SAMPLE_TITLE = Text.literal("Your Server").formatted(Formatting.YELLOW, Formatting.BOLD);
    /** The HUD editor's sample, scored 5 to 1 like a server's sidebar (so Auto hides its numbers). */
    private static final List<ScoreboardEntry> SAMPLE = List.of(
            sample(5, Text.literal("Players: ").append(Text.literal("12").formatted(Formatting.GREEN))),
            sample(4, Text.literal("Mode: ").append(Text.literal("Survival").formatted(Formatting.GREEN))),
            sample(3, Text.literal("")),
            sample(2, Text.literal("Coins: ").append(Text.literal("340").formatted(Formatting.GOLD))),
            sample(1, Text.literal("evadeclient.com").formatted(Formatting.YELLOW)));

    private static ScoreboardEntry sample(int value, Text display) {
        return new ScoreboardEntry("evade.sample." + value, value, display, null);
    }

    public Scoreboard() {
        super("Scoreboard", "Hide or recolour the numbers, trim, restyle, move, resize or hide the sidebar.", Category.VISUALS,
                -1, VANILLA_SPOT);
        setVisible(true); // a tweak to vanilla's sidebar: listed in the module list like the other visual mods
        background.describe("Draw the sidebar's background.");
        backgroundStyle.describe("The panel's style with Vanilla Look off.");
        opacity.describe("How solid the sidebar's background is.");
        smoothFont.describe("With Vanilla Look off, draw the sidebar in Evade's smooth font.");
        // This module's own settings first, then the placement and visibility ones every HUD element has.
        List<Setting<?>> settings = getSettings();
        List<Setting<?>> own = List.of(numbers, recolorNumbers, numberColor, hideServerIp, showTitle, maxLines, lineHeight,
                vanillaLook, textShadow, hide);
        settings.removeAll(own);
        settings.addAll(0, own);
    }

    public static Scoreboard active() {
        if (EvadeClient.modules == null) return null;
        Scoreboard s = EvadeClient.modules.get(Scoreboard.class);
        return s != null && s.isEnabled() ? s : null;
    }

    /** "Hide Numbers" (on/off) became Numbers: a config that had it off keeps its numbers. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        JsonElement old = settings.get("Hide Numbers");
        if (old == null || settings.has(numbers.getName())) return;
        try {
            numbers.set(old.getAsBoolean() ? "Hidden" : "Shown");
        } catch (RuntimeException ignored) {
            // unreadable: keep the default
        }
    }

    // ------------------------------------------------------------------ in game (InGameHudMixin)

    /**
     * Draws the sidebar for {@code objective} at this element's spot, in place of vanilla's. Hide Scoreboard, Hide In
     * Menus and Only In Combat apply; in the HUD editor the editor draws it (with drag handles) instead.
     */
    public void drawSidebar(DrawContext ctx, ScoreboardObjective objective) {
        if (hide.get() || mc.currentScreen instanceof HudEditorScreen || !shouldShow()) return;
        Layout l = layout(objective);
        if (l == null) return; // no title and no lines: nothing to draw
        applySize(l);
        seenAt = System.nanoTime();
        lastLines = l.rows().length;
        lastNumbers = l.numbersShown();
        float k = Hud.scale();
        int sw = Math.round(ctx.getScaledWindowWidth() / k), sh = Math.round(ctx.getScaledWindowHeight() / k);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.scale(k, k); // HUD units, like Evade's other elements (positions are saved in them)
        drawing = l;
        try {
            renderScaled(ctx, sw, sh, false);
        } finally {
            drawing = null;
            m.popMatrix();
        }
    }

    /** Whether the sidebar has been drawn in game in the last few frames. */
    public boolean sidebarShown() {
        return seenAt != 0 && System.nanoTime() - seenAt < LIVE_NS;
    }

    /** Lines in the sidebar last drawn in game (for tests). */
    public int shownLines() { return lastLines; }

    /** Whether the sidebar last drawn in game had its score numbers (for tests). */
    public boolean numbersDrawn() { return lastNumbers; }

    // ------------------------------------------------------------------ the HUD element

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        Layout l;
        if (preview) {
            l = previewLayout();
            applySize(l);
        } else if (drawing != null) {
            l = drawing;
        } else {
            // From Evade's HUD layer: the sidebar is drawn in vanilla's place, so this only says whether it is there.
            empty = !sidebarShown();
            return;
        }
        float k = Hud.scale();
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(left, top);
        m.scale(1f / k, 1f / k); // drawn in GUI pixels, the same size as vanilla's
        try {
            draw(ctx, l);
        } finally {
            m.popMatrix();
        }
    }

    @Override
    public int resolveX(int screenW) {
        if (mc.currentScreen instanceof HudEditorScreen) applySize(previewLayout());
        if (!atDefaultSpot()) return super.resolveX(screenW);
        var win = mc.getWindow();
        if (win == null) return super.resolveX(screenW);
        return clampX(Math.round(vanillaLeft(win.getScaledWidth()) / Hud.scale()), screenW);
    }

    @Override
    public int resolveY(int screenH) {
        if (mc.currentScreen instanceof HudEditorScreen) applySize(previewLayout());
        // Checked on y alone (not atDefaultSpot), so a hand-edited x with vanilla's y still works.
        if (y != VANILLA_SPOT) return super.resolveY(screenH);
        var win = mc.getWindow();
        if (win == null) return super.resolveY(screenH);
        return clampY(Math.round(vanillaTop(win.getScaledHeight()) / Hud.scale()), screenH);
    }

    /** Left edge (GUI pixels) at vanilla's spot: 1 pixel from the right edge, scaled around it, kept on screen. */
    private float vanillaLeft(int gw) {
        float s = scale.getFloat();
        return Math.max(0, gw - (boxW + 1) * s);
    }

    /** Top edge (GUI pixels) at vanilla's spot: a third of the lines below the middle, scaled around it, kept on screen. */
    private float vanillaTop(int gh) {
        float s = scale.getFloat();
        return Math.max(0, Math.min(gh - boxH * s, gh / 2 + (boxBelow - boxH) * s));
    }

    /** Remember {@code l}'s box and size the element to it (HUD units). */
    private void applySize(Layout l) {
        boxW = l.w();
        boxH = l.h();
        boxBelow = l.below();
        float k = Hud.scale();
        width = Math.max(1, Math.round(l.w() / k));
        height = Math.max(1, Math.round(l.h() / k));
    }

    /** The HUD editor's sidebar: the real one when the world has one, else the sample. Reused for a few frames. */
    private Layout previewLayout() {
        long now = System.nanoTime();
        if (preview != null && now - previewAt < PREVIEW_NS) return preview;
        ScoreboardObjective obj = currentObjective();
        Layout l = obj == null ? null : layout(obj);
        if (l == null) l = layout(SAMPLE_TITLE, SAMPLE, defaultFormat(), ScoreboardEntry::name);
        preview = l;
        previewAt = now;
        return l;
    }

    /** The objective vanilla would show in the sidebar: the player's team colour slot first, then the sidebar slot. */
    private ScoreboardObjective currentObjective() {
        if (mc.world == null || mc.player == null) return null;
        net.minecraft.scoreboard.Scoreboard board = mc.world.getScoreboard();
        Team team = board.getScoreHolderTeam(mc.player.getNameForScoreboard());
        if (team != null) {
            ScoreboardDisplaySlot slot = ScoreboardDisplaySlot.fromFormatting(team.getColor());
            ScoreboardObjective byTeam = slot == null ? null : board.getObjectiveForSlot(slot);
            if (byTeam != null) return byTeam;
        }
        return board.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
    }

    // ------------------------------------------------------------------ layout and drawing

    /** {@code objective}'s sidebar laid out with this module's settings; null when it would draw nothing. */
    private Layout layout(ScoreboardObjective objective) {
        net.minecraft.scoreboard.Scoreboard board = objective.getScoreboard();
        List<ScoreboardEntry> sorted = board.getScoreboardEntries(objective).stream()
                .filter(e -> !e.hidden())
                .sorted(ORDER)
                .limit(VANILLA_MAX_LINES)
                .toList();
        return layout(objective.getDisplayName(), sorted, objective.getNumberFormatOr(defaultFormat()),
                e -> Team.decorateName(board.getScoreHolderTeam(e.owner()), e.name()));
    }

    /**
     * Lays out a sidebar: {@code sorted} are the lines vanilla would show (at most 15, highest first), {@code format}
     * the objective's number format (a score's own format still wins). Null when there is no title and no line.
     */
    private Layout layout(Text title, List<ScoreboardEntry> sorted, NumberFormat format, Function<ScoreboardEntry, Text> nameOf) {
        TextRenderer tr = mc.textRenderer;
        if (tr == null) return null;
        boolean vanilla = vanillaLook.get();
        Style font = vanilla || !smoothFont.get() ? null : sidebarFont();

        int n = sorted.size();
        Text[] names = new Text[n];
        for (int i = 0; i < n; i++) names[i] = nameOf.apply(sorted.get(i));
        // Numbers are judged on everything vanilla would show, the address line included.
        boolean nums = numbersShown(sorted);
        if (hideServerIp.get() && n > 0 && isServerAddress(names[n - 1])) n--;
        int count = Math.min(n, maxLines.getInt());

        int colon = tr.getWidth(inFont(Text.literal(": "), font));
        int content = 0;
        Row[] rows = new Row[count];
        for (int i = 0; i < count; i++) {
            ScoreboardEntry e = sorted.get(i);
            Text name = inFont(names[i], font);
            Text score = null;
            int sw = 0;
            NumberFormat own = e.numberFormatOverride();
            // A hidden number is a styled one (vanilla's red, a recolour, a server's styled numbers). Fixed text a
            // server puts in the score column is content and stays; a blank format draws nothing either way.
            if (nums || !((own != null ? own : format) instanceof StyledNumberFormat)) {
                score = inFont(e.formatted(format), font);
                sw = tr.getWidth(score);
            }
            rows[i] = new Row(name, score, sw);
            content = Math.max(content, tr.getWidth(name) + (sw > 0 ? colon + sw : 0));
        }
        Text shownTitle = showTitle.get() ? inFont(title, font) : null;
        if (shownTitle == null && count == 0) return null;
        int titleW = shownTitle == null ? 0 : tr.getWidth(shownTitle);
        content = Math.max(content, titleW);

        int step = lineHeight.getInt();
        int rowOffset = Math.max(0, (step - 8) / 2); // centre the 8-pixel text in taller rows
        int below = count * step / 3;
        if (vanilla) {
            // Vanilla's layout: a 9-pixel title strip, then a strip with the lines 1 pixel below its top; names 2 pixels
            // in, scores flush with the right edge.
            int bodyTop = shownTitle != null ? 9 : 0;
            int rowsTop = bodyTop + 1;
            int w = content + 4;
            return new Layout(shownTitle, 2 + content / 2 - titleW / 2, 1, rows, rowsTop, step, rowOffset, 2, w,
                    bodyTop, -1, true, w, rowsTop + count * step, below, nums);
        }
        // Evade's panel: even insets all round (a glyph's advance includes 1 pixel after it, hence the -1s), and a
        // faint line under the title.
        int w = content + 2 * PAD_X - 1;
        int rowsTop = PAD_Y + (shownTitle != null ? 12 : 0);
        int h = count > 0 ? rowsTop + count * step + PAD_Y - 1 : 2 * PAD_Y + 8;
        int dividerY = shownTitle != null && count > 0 ? PAD_Y + 10 : -1;
        return new Layout(shownTitle, PAD_X + content / 2 - titleW / 2, PAD_Y, rows, rowsTop, step, rowOffset, PAD_X,
                w - PAD_X + 1, 0, dividerY, false, w, h, below, nums);
    }

    /** The sidebar at (0, 0), in GUI pixels. */
    private void draw(DrawContext ctx, Layout l) {
        TextRenderer tr = mc.textRenderer;
        if (background.get()) {
            if (l.vanilla()) {
                if (l.title() != null) ctx.fill(0, 0, l.w(), 9, backgroundColor(mc.options.getTextBackgroundColor(0.4f)));
                ctx.fill(0, l.bodyTop(), l.w(), l.h(), backgroundColor(mc.options.getTextBackgroundColor(0.3f)));
            } else {
                Theme.hudBox(ctx, 0, 0, l.w(), l.h());
            }
        }
        if (l.dividerY() >= 0)
            ctx.fill(PAD_X - 1, l.dividerY(), l.w() - PAD_X + 1, l.dividerY() + 1, Theme.alpha(0x22FFFFFF, Theme.hudOpacity));
        boolean shadow = textShadow.get();
        if (l.title() != null) ctx.drawText(tr, l.title(), l.titleX(), l.titleY(), -1, shadow);
        Row[] rows = l.rows();
        for (int i = 0; i < rows.length; i++) {
            int y = l.rowsTop() + i * l.rowStep() + l.rowOffset();
            ctx.drawText(tr, rows[i].name(), l.nameX(), y, -1, shadow);
            if (rows[i].scoreWidth() > 0) ctx.drawText(tr, rows[i].score(), l.scoreRight() - rows[i].scoreWidth(), y, -1, shadow);
        }
    }

    /**
     * Whether score numbers are drawn for {@code sorted} (highest first): Shown always, Hidden never, Auto unless
     * they only count the lines down by one (15, 14, 13...), which is how servers order their sidebar text.
     */
    private boolean numbersShown(List<ScoreboardEntry> sorted) {
        if (numbers.is("Shown")) return true;
        if (numbers.is("Hidden")) return false;
        if (sorted.size() < 2) return true; // one score says nothing about order: it is a real number
        for (int i = 0; i + 1 < sorted.size(); i++)
            if (sorted.get(i).value() - sorted.get(i + 1).value() != 1) return true;
        return false;
    }

    /** The format for scores the objective doesn't format itself: vanilla's red, or Number Color. */
    private NumberFormat defaultFormat() {
        if (!recolorNumbers.get()) return StyledNumberFormat.RED;
        int rgb = numberColor.get() & 0xFFFFFF;
        if (recolorFormat == null || rgb != recolorRgb) {
            recolorFormat = new StyledNumberFormat(Style.EMPTY.withColor(rgb));
            recolorRgb = rgb;
        }
        return recolorFormat;
    }

    /** Whether a sidebar line is only a server address (colour codes and spaces ignored). */
    private static boolean isServerAddress(Text line) {
        String s = Formatting.strip(line.getString());
        return s != null && SERVER_ADDRESS.matcher(s).matches();
    }

    /** Vanilla's background colour {@code color} faded by Background Opacity. */
    private int backgroundColor(int color) {
        float o = opacity.getFloat() / 100f;
        if (o >= 0.999f) return color;
        int a = Math.round((color >>> 24) * o);
        return a << 24 | color & 0xFFFFFF;
    }

    /**
     * The smooth font rasterised for the sidebar's size on screen: it is drawn in GUI pixels times Scale, so one unit
     * is (GUI scale x Scale) device pixels.
     */
    private Style sidebarFont() {
        var win = mc.getWindow();
        double gs = win == null ? 1 : Math.max(1, win.getScaleFactor());
        int g = (int) Math.round(gs * scale.getFloat());
        return UiFont.style(UiFont.Role.BODY, Math.max(UiFont.MIN_G, Math.min(UiFont.MAX_G, g)));
    }

    /** {@code t} in {@code font} (children that only set a colour inherit it), or as is for the pixel font. */
    private static Text inFont(Text t, Style font) {
        return font == null ? t : Text.empty().setStyle(font).append(t);
    }
}
