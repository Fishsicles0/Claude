package net.evadeclient.gui.title;

import com.mojang.authlib.GameProfile;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.Motion;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.social.MojangNames;
import net.evadeclient.social.SocialClient;
import net.evadeclient.social.SocialState;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The account card's friends row on the main menu: "N friends online" and the faces of those online (as many as fit,
 * then "+N"). It reads Friends' snapshot each frame, so it follows friends coming and going. Clicking it (or Enter)
 * opens the Friends page of the Evade menu. Shown only while Friends is on and you have friends.
 */
public class FriendsStrip extends PressableWidget implements TitleTip {
    static final int HEAD = 12;
    private static final Map<UUID, Supplier<SkinTextures>> SKINS = new HashMap<>();
    private static final Map<UUID, Boolean> FETCHED = new HashMap<>();

    private final Runnable open;
    private final Intro intro;
    private final double delayMs;
    private final Motion.Spring hover = new Motion.Spring(0f);
    private long lastFrame = System.nanoTime();
    private String tip;
    private float plane;

    /** The tooltip (drawn by the screen; narrated as a hint). */
    public FriendsStrip tip(String t) { this.tip = t; return this; }

    @Override public String tip() { return tip; }

    /** Moves with parallax plane {@code depth} (see {@link TitleScene#planeX}). */
    public FriendsStrip plane(float depth) { this.plane = depth; return this; }

    public FriendsStrip(int x, int y, int w, int h, Runnable open, Intro intro, double delayMs) {
        super(x, y, w, h, Text.literal("Friends"));
        this.open = open;
        this.intro = intro;
        this.delayMs = delayMs;
    }

    /** Whether there is anything to show: Friends on, the list loaded and at least one friend. */
    public static boolean available() {
        Friends f = Friends.get();
        if (f == null || !f.isEnabled()) return false;
        SocialState s = SocialClient.state();
        return s != null && s.loaded() && !s.friends().isEmpty();
    }

    /** The friends online now, in the Friends page's order. */
    static List<SocialState.Person> online() {
        SocialState s = SocialClient.state();
        List<SocialState.Person> out = new ArrayList<>();
        if (s == null) return out;
        for (SocialState.Person p : s.friends()) if (p.online()) out.add(p);
        out.sort(SocialState.ORDER);
        return out;
    }

    /** How many friends are online now (0 when Friends is off or not loaded). */
    public static int onlineCount() {
        return available() ? online().size() : 0;
    }

    /** "3 friends online", "1 friend online", "No friends online". */
    static String summary(int n) {
        return n == 0 ? "No friends online" : n + (n == 1 ? " friend online" : " friends online");
    }

    @Override
    public void onPress(AbstractInput input) {
        open.run();
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
        List<SocialState.Person> on = online();
        StringBuilder sb = new StringBuilder(summary(on.size()));
        for (int i = 0; i < Math.min(5, on.size()); i++) sb.append(i == 0 ? ": " : ", ").append(on.get(i).name());
        builder.put(NarrationPart.HINT, Text.literal(sb.toString()));
    }

    @Override
    protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;
        float in = intro == null ? 1f : intro.p(delayMs, 380);
        if (in <= 0.001f) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean keyboardFocus = isFocused() && mc.getNavigationType().isKeyboard();
        boolean hot = isHovered() || keyboardFocus;
        float h = Math.max(0f, Math.min(1f, Motion.spring(hover, hot ? 1f : 0f, Motion.SNAPPY, dt)));

        float base = Theme.fade;
        var mm = ctx.getMatrices();
        mm.pushMatrix();
        try {
            Theme.fade = base * in;
            if (plane != 0) mm.translate(Theme.snap(ctx, TitleScene.planeX(plane)), Theme.snap(ctx, TitleScene.planeY(plane)));
            int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
            if (h > 0.01f) {
                if (net.evadeclient.render.ui.UiShape.available())
                    net.evadeclient.render.ui.UiShape.shape(x1, y1, x2, y2).radius(net.evadeclient.util.Tokens.R_SM)
                            .fill(Theme.alpha(net.evadeclient.util.Tokens.N250, 0.75f * h)).rim(0.08f * h).draw(ctx);
                else Theme.round(ctx, x1, y1, x2, y2, Theme.alpha(0xFF1B1B22, 0.9f * h), 4);
            }
            if (keyboardFocus) TitleButton.focusRing(ctx, x1, y1, x2, y2, net.evadeclient.util.Tokens.R_SM, false);

            List<SocialState.Person> on = online();
            int cy = (y1 + y2) / 2;
            int tx = x1 + 6;
            TitleButton.dot(ctx, tx + 2, cy, 4, on.isEmpty() ? net.evadeclient.util.Tokens.N600 : net.evadeclient.util.Tokens.OK);
            tx += 8;
            String label = summary(on.size());
            TitleArt.textMid(ctx, label, tx, cy, on.isEmpty() ? net.evadeclient.util.Tokens.N700 : net.evadeclient.util.Tokens.N800, Role.SMALL);

            // Faces from the right edge, overlapping a little; "+N" when they don't all fit.
            int right = x2 - 6 - (h > 0.01f ? Math.round(10 * h) : 0);
            int left = tx + TitleArt.width(label, Role.SMALL) + 6;
            int step = HEAD - 3;
            int fit = Math.max(0, (right - left - HEAD) / step + 1);
            int shown = Math.min(on.size(), fit);
            String more = null;
            if (shown < on.size() && shown > 0) {
                more = "+" + (on.size() - shown + 1);
                shown--;
            }
            int x = right - HEAD;
            if (more != null) {
                int mw = TitleArt.width(more, Role.SMALL);
                TitleArt.textMid(ctx, more, right - mw, cy, net.evadeclient.util.Tokens.N700, Role.SMALL);
                x = right - mw - 4 - HEAD;
            }
            for (int i = 0; i < shown; i++) {
                SocialState.Person p = on.get(i);
                int fx = x - i * step, fy = cy - HEAD / 2;
                Theme.round(ctx, fx - 1, fy - 1, fx + HEAD + 1, fy + HEAD + 1, 0xFF0A0A0D, 3);
                PlayerSkinDrawer.draw(ctx, skin(p.uuid(), p.name()).get(), fx, fy, HEAD, Theme.fa(0xFFFFFFFF));
            }
            if (h > 0.01f) {
                float b = Theme.fade;
                Theme.fade = b * h;
                TitleArt.icon(ctx, "chevron_r", x2 - 12, cy - 4, 8, 0xFFFFFFFF);
                Theme.fade = b;
            }
        } finally {
            Theme.fade = base;
            mm.popMatrix();
        }
    }

    /**
     * A friend's skin: the default skin for their UUID straight away, then their own once the game's session service
     * has returned their profile (fetched once per friend per session, off the render thread; never in offline or
     * test modes).
     */
    static Supplier<SkinTextures> skin(UUID id, String name) {
        Supplier<SkinTextures> s = SKINS.get(id);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (s == null) {
            s = mc.getSkinProvider().supplySkinTextures(new GameProfile(id, name), false);
            SKINS.put(id, s);
        }
        if (!FETCHED.containsKey(id) && !SocialClient.isLoopback() && !MojangNames.offline) {
            FETCHED.put(id, true);
            CompletableFuture.supplyAsync(() -> mc.getApiServices().sessionService().fetchProfile(id, false), Util.getDownloadWorkerExecutor())
                    .whenComplete((r, err) -> {
                        if (err != null || r == null) return;
                        mc.execute(() -> SKINS.put(id, mc.getSkinProvider().supplySkinTextures(r.profile(), false)));
                    })
                    .exceptionally(e -> {
                        EvadeClient.LOG.debug("Title Screen: no profile for friend {}", id);
                        return null;
                    });
        }
        return s;
    }
}
