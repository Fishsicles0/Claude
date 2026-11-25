package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.config.MenuState;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run setup, in three short steps that can all be skipped: pick a play style (switches on a fitting set of mods
 * and HUD widgets), optionally tune the FPS Mode for this PC with a quick benchmark, then a few tips about the menu.
 * Shown once by {@link FirstRun}; {@code .setup} opens it again.
 */
public final class SetupWizardScreen extends EvadePanelScreen {
    private int step;
    private FirstRun.Style chosen;
    private String tuneStatus;

    private static final String[][] TIPS = {
            {"Open the menu", "Right Shift opens Evade any time (change it under Settings). Ctrl+F jumps to search."},
            {"Toggle and tune", "Click a mod to switch it on; right-click or the gear opens its settings and keybind."},
            {"Pin favourites", "Click the star next to a mod's name to keep it on the Pinned page."},
            {"Chat commands", "Type .help in chat. Tab completes commands, mod and setting names."},
            {"Move the HUD", "Edit HUD Layout in the sidebar lets you drag every widget where you want it."},
    };

    public SetupWizardScreen(Screen parent) {
        super("Welcome to Evade", parent);
    }

    /** Test hooks. */
    public int stepForTest() { return step; }
    public void chooseForTest(String style) { chosen = FirstRun.style(style); }
    public void nextForTest() { next(); }

    @Override protected int prefWidth() { return 600; }
    @Override protected int prefHeight() { return 360; }

    @Override
    protected String subtitle() {
        return switch (step) {
            case 0 -> "Step 1 of 3 · How do you play? Pick one to switch on a set of mods (change anything later)";
            case 1 -> "Step 2 of 3 · Pick the FPS Mode that suits this PC, and see what it changes";
            default -> "Step 3 of 3 · A few things worth knowing";
        };
    }

    @Override
    public void close() {
        MenuState.setSetupDone(true);
        super.close();
    }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        int x = button(ctx, "next", step == 2 ? "Finish" : "Next ›", right - 24, y, mx, my, 1, b -> next());
        if (step == 0) button(ctx, "skip", "Skip setup", x - 6, y, mx, my, 0, b -> close());
        else button(ctx, "prev", "‹ Previous", x - 6, y, mx, my, 0, b -> step--);
    }

    private void next() {
        if (step == 0 && chosen != null) FirstRun.apply(chosen);
        if (step >= 2) { close(); return; }
        step++;
        scrollTarget = 0;
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        return switch (step) {
            case 0 -> renderStyles(ctx, top, mx, my);
            case 1 -> renderTune(ctx, top, mx, my);
            default -> renderTips(ctx, top);
        };
    }

    private int renderStyles(DrawContext ctx, int top, int mx, int my) {
        int cols = cw >= 520 ? 3 : 2, gap = 8;
        int cardW = (cw - gap * (cols - 1)) / cols, cardH = 70;
        int i = 0;
        for (FirstRun.Style s : FirstRun.STYLES) {
            int x = cx + (i % cols) * (cardW + gap), y = top + (i / cols) * (cardH + gap);
            boolean sel = s == chosen;
            boolean hv = mouseIn(mx, my, x, y, x + cardW, y + cardH);
            float h = hover("style:" + s.name(), hv);
            Theme.roundBordered(ctx, x, y, x + cardW, y + cardH, sel ? Theme.tint(0.14f) : Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h),
                    sel ? Theme.RED : Theme.lerpColor(Theme.BORDER, Theme.BORDER_HOVER, h));
            text(ctx, s.name(), x + 10, y + 9, sel ? Theme.RED_BRIGHT : Theme.TEXT, Role.STRONG);
            List<String> lines = wrap(s.blurb(), Role.SMALL, cardW - 20);
            for (int l = 0; l < Math.min(4, lines.size()); l++) text(ctx, lines.get(l), x + 10, y + 24 + l * 10, Theme.TEXT_DIM, Role.SMALL);
            contentHit(x, y, x + cardW, y + cardH, b -> chosen = (chosen == s ? null : s));
            i++;
        }
        int rows = (FirstRun.STYLES.size() + cols - 1) / cols;
        int y = top + rows * (cardH + gap) + 4;
        String note = chosen == null ? "Nothing picked: Next keeps your mods as they are." : chosen.name() + " is applied when you press Next.";
        text(ctx, note, cx, y, Theme.TEXT_FAINT, Role.SMALL);
        return y - top + 20;
    }

    private int renderTune(DrawContext ctx, int top, int mx, int my) {
        Performance p = EvadeClient.modules.get(Performance.class);
        String mode = p == null ? "?" : p.isEnabled() ? p.fpsMode.get() : "Performance off";
        int y = top + 4;
        text(ctx, "Current FPS Mode: " + mode, cx, y, Theme.TEXT, Role.BODY);
        y += 18;
        for (String line : wrap("Tune for my PC measures your frame rate for 10 seconds with the built-in benchmark and picks "
                + "Max FPS, Balanced or Quality to match. Nothing is sent anywhere. In a world it starts right away; "
                + "from here it waits until you next join one.", Role.LABEL, cw)) {
            text(ctx, line, cx, y, Theme.TEXT_DIM, Role.LABEL);
            y += 12;
        }
        y += 8;
        button(ctx, "tune", FirstRun.tuning() ? "Measuring..." : "Tune for my PC", cx + 120, y, mx, my, 1, b -> {
            tuneStatus = FirstRun.startTune();
            // In a world, let the run see the game: close the setup.
            if (client != null && client.world != null && FirstRun.tuning()) close();
        });
        y += 24;
        String pick = FirstRun.lastPick();
        String status = tuneStatus != null ? tuneStatus : pick != null ? "Last tune picked " + pick + "." : MenuState.tunePending()
                ? "Waiting for your next world." : "Or skip this: Balanced is a good start.";
        text(ctx, status, cx, y, Theme.TEXT_FAINT, Role.SMALL);
        y += 22;
        return y - top + renderChanges(ctx, y, mx, my, p);
    }

    /**
     * What Performance changed in the player's own video settings, so nothing changes silently, with a button that
     * hands them all back (culling and the idle frame caps stay: they change no settings). Returns the height used.
     */
    private int renderChanges(DrawContext ctx, int top, int mx, int my, Performance p) {
        if (p == null || !p.isEnabled()) return 0;
        List<String> changes = p.videoChangesSummary();
        int y = top;
        text(ctx, changes.isEmpty() ? "Performance hasn't changed any of your video settings."
                : "Performance changed these video settings of yours:", cx, y, Theme.TEXT, Role.LABEL);
        y += 14;
        for (String c : changes) {
            text(ctx, "•  " + c, cx + 6, y, Theme.TEXT_DIM, Role.SMALL);
            y += 11;
        }
        for (String line : wrap("It also skips drawing far mobs and some particles, and lowers the frame rate while you're "
                + "tabbed out or AFK. Turn Performance off for none of it.", Role.SMALL, cw)) {
            text(ctx, line, cx, y, Theme.TEXT_FAINT, Role.SMALL);
            y += 11;
        }
        if (!changes.isEmpty()) {
            y += 6;
            button(ctx, "keepvideo", "Keep my own settings", cx + 150, y, mx, my, 0, b -> p.keepMyVideoSettings());
            y += 20;
        }
        return y - top;
    }

    /** Test hook: what the setup lists as changed by Performance. */
    public static List<String> changesForTest() {
        Performance p = EvadeClient.modules.get(Performance.class);
        return p == null ? List.of() : p.videoChangesSummary();
    }

    private int renderTips(DrawContext ctx, int top) {
        int y = top + 2;
        int n = 1;
        for (String[] tip : TIPS) {
            Theme.disc(ctx, cx + 1, y + 1, 14, Theme.alpha(Theme.RED, 0.85f));
            text(ctx, String.valueOf(n++), cx + 5, y + 4, Theme.ON_ACCENT, Role.SMALL);
            text(ctx, tip[0], cx + 24, y, Theme.TEXT, Role.STRONG);
            List<String> lines = wrap(tip[1], Role.LABEL, cw - 30);
            for (int l = 0; l < lines.size(); l++) text(ctx, lines.get(l), cx + 24, y + 13 + l * 11, Theme.TEXT_DIM, Role.LABEL);
            y += 20 + lines.size() * 11 + 6;
        }
        return y - top;
    }

    private List<String> wrap(String s, Role r, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String tryLine = line.isEmpty() ? word : line + " " + word;
            if (width(tryLine, r) > maxW && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tryLine);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) { next(); return true; }
        return super.keyPressed(input);
    }
}
