package net.evadeclient.gui.title;

import net.evadeclient.gui.Motion;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.Consumer;

/**
 * One server in the main menu's "Continue playing" card: favicon, name and live status (players online, ping), with
 * a Join chip that slides in on hover or focus. Clicking (or Enter) joins it. Narration reads "Join {name}" and the
 * status.
 */
public class ServerRow extends PressableWidget implements TitleTip {
    private static final Identifier UNKNOWN = Identifier.ofVanilla("textures/misc/unknown_server.png");

    private final RecentServers.Entry entry;
    private final Consumer<RecentServers.Entry> join;
    private final Intro intro;
    private final double delayMs;
    private final Motion.Spring hover = new Motion.Spring(0f);
    private long lastFrame = System.nanoTime();
    private String tip;
    private float plane;

    /** The tooltip (drawn by the screen; narrated as a hint). */
    public ServerRow tip(String t) { this.tip = t; return this; }

    @Override public String tip() { return tip; }

    /** Moves with parallax plane {@code depth} (see {@link TitleScene#planeX}). */
    public ServerRow plane(float depth) { this.plane = depth; return this; }
    private String name;
    private int nameFor = -1;

    public ServerRow(int x, int y, int w, int h, RecentServers.Entry entry, Consumer<RecentServers.Entry> join,
                     Intro intro, double delayMs) {
        super(x, y, w, h, Text.literal("Join " + entry.name()));
        this.entry = entry;
        this.join = join;
        this.intro = intro;
        this.delayMs = delayMs;
    }

    @Override
    public void onPress(AbstractInput input) {
        join.accept(entry);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
        builder.put(NarrationPart.HINT, Text.literal(entry.status()));
    }

    @Override
    protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;
        float in = intro.p(delayMs, 380);
        if (in <= 0.001f) return;
        boolean keyboardFocus = isFocused() && MinecraftClient.getInstance().getNavigationType().isKeyboard();
        boolean hot = active && (isHovered() || keyboardFocus);
        float h = Math.max(0f, Math.min(1f, Motion.spring(hover, hot ? 1f : 0f, Motion.SNAPPY, dt)));

        float base = Theme.fade;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            Theme.fade = base * in * this.alpha;
            m.translate(Theme.snap(ctx, (intro.moves() ? 12f * (1f - in) : 0f) + TitleScene.planeX(plane)), Theme.snap(ctx, TitleScene.planeY(plane)));
            int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
            if (h > 0.01f) {
                if (UiShape.available()) {
                    UiShape.shape(x1, y1, x2, y2).radius(Tokens.R_SM).fill(Theme.alpha(Tokens.N250, 0.80f * h))
                            .border(Theme.alpha(0xFFFFFFFF, 0.07f * h), 1).rim(0.10f * h).rimAt(mouseX - x1).draw(ctx);
                    TitleButton.slant(ctx, x1 + 3, y1 + 7 + (1f - h) * 6, x1 + 5, y2 - 7 - (1f - h) * 6, Theme.alpha(Tokens.A500, h));
                } else {
                    Theme.round(ctx, x1, y1, x2, y2, Theme.alpha(0xFF1E1E26, 0.85f * h), 4);
                    Theme.hairline(ctx, x1, y1, x2, y2, Theme.alpha(Theme.RED_BRIGHT, 0.55f * h), 4);
                }
            }
            int fs = Math.min(20, getHeight() - 6);
            int fx = x1 + 8, fy = y1 + (getHeight() - fs) / 2;
            Identifier fav = entry.favicon();
            Theme.round(ctx, fx - 1, fy - 1, fx + fs + 1, fy + fs + 1, 0xFF0A0A0D, 3);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, fav != null ? fav : UNKNOWN, fx, fy, 0, 0, fs, fs, 64, 64, 64, 64,
                    Theme.fa(active ? 0xFFFFFFFF : 0x80FFFFFF));
            Theme.hairline(ctx, fx - 1, fy - 1, fx + fs + 1, fy + fs + 1, 0x1FFFFFFF, 3);

            int tx = fx + fs + 8;
            int chipW = 34;
            int room = x2 - 6 - tx - Math.round(chipW * h);
            if (room != nameFor) {
                name = TitleArt.ellipsize(entry.name(), Math.max(10, room), Role.BODY);
                nameFor = room;
            }
            int cy = (y1 + y2) / 2;
            TitleArt.textMid(ctx, name, tx, cy - 5, active ? Tokens.N950 : Tokens.N600, Role.BODY);
            TitleButton.dot(ctx, tx + 2, cy + 6, 4, active ? entry.dot() : Tokens.N600);
            TitleArt.textMid(ctx, entry.status(), tx + 8, cy + 6, Tokens.N700, Role.SMALL);

            if (h > 0.01f) {
                float b = Theme.fade;
                Theme.fade = b * h;
                int cx2 = x2 - 6 + Math.round((1f - h) * 6f), cx1 = cx2 - chipW;
                // A secondary chip (the screen's one red fill is Singleplayer): glass with a red play glyph.
                if (UiShape.available()) UiShape.shape(cx1, cy - 7, cx2, cy + 7).pill().fill(Tokens.N350)
                        .border(Theme.alpha(Tokens.A400, 0.45f), 1).rim(0.12f).draw(ctx);
                else Theme.pill(ctx, cx1, cy - 7, cx2, cy + 7, Tokens.N350);
                TitleArt.icon(ctx, "play", cx1 + 6, cy - 3, 7, Tokens.A400);
                TitleArt.textMid(ctx, "Join", cx1 + 15, cy, Tokens.N950, Role.SMALL);
                Theme.fade = b;
            }
            if (keyboardFocus) TitleButton.focusRing(ctx, x1, y1, x2, y2, Tokens.R_SM, false);
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
    }
}
