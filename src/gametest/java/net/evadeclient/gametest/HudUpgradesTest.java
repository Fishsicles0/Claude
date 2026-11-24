package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.ColorPicker;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.gui.HudEditorScreen;
import net.evadeclient.hud.HudLayout;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.hud.ActionBarHud;
import net.evadeclient.module.hud.CpsHud;
import net.evadeclient.module.hud.CustomTextHud;
import net.evadeclient.module.hud.FpsHud;
import net.evadeclient.module.hud.PingHud;
import net.evadeclient.module.hud.PotionHud;
import net.evadeclient.module.hud.TitlesHud;
import net.evadeclient.module.mods.ToggleSprint;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.ColorHistory;
import net.evadeclient.util.Placeholders;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The HUD upgrades: the placeholder engine and Custom Text, label/value colours, Potion Effects options, the HUD
 * editor's undo/redo, multi-select, align/distribute, lock, hide and nudge, HUD layouts and HUD codes, the colour
 * picker in each menu style, and Titles/Action Bar placement.
 */
public final class HudUpgradesTest {
    private HudUpgradesTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    @SuppressWarnings("unchecked")
    private static <S extends Setting<?>> S setting(net.evadeclient.module.Module m, String name) {
        for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) return (S) s;
        throw new AssertionError("No setting " + name + " on " + m.getName());
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String layoutBefore = ctx.computeOnClient(c -> HudLayout.snapshot());
        try {
            placeholders(ctx, sp);
            customText(ctx);
            labelValue(ctx);
            potions(ctx, sp);
            editor(ctx);
            layouts(ctx);
            colorPicker(ctx);
            titles(ctx, sp);
        } finally {
            ctx.runOnClient(c -> {
                c.setScreen(null);
                HudLayout.restore(layoutBefore);
                EvadeClient.config.save();
            });
            sp.getServer().runCommand("effect clear @a");
            sp.getServer().runCommand("clear @a");
            ctx.waitTicks(2);
        }
    }

    // ---- placeholders --------------------------------------------------------------------------------

    private static void placeholders(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        Map<String, String> fake = Map.of("fps", "60", "ping", "200", "mod:Zoom", "Off", "x", "12");
        java.util.function.Function<String, String> values = fake::get;
        check(Placeholders.plain("{fps} FPS", values).equals("60 FPS"), "Placeholders: a value is filled in");
        check(Placeholders.plain("{nope} {fps}", values).equals("{nope} 60"), "Placeholders: an unknown name is left as typed");
        check(Placeholders.plain("{if ping>150}High{else}OK{end}", values).equals("High"), "Placeholders: {if ping>150} holds at 200 ms");
        check(Placeholders.plain("{if ping<150}Low{else}High{end} ping", values).equals("High ping"), "Placeholders: {else} branch");
        check(Placeholders.plain("{if mod:Zoom}on{end}{if !mod:Zoom}off{end}", values).equals("off"), "Placeholders: truthy and negated conditions");
        check(Placeholders.plain("{if fps>=60}{if ping>100}A{end}B{end}C", values).equals("ABC"), "Placeholders: nested conditions");
        check(Placeholders.plain("a\\nb \\{fps}", values).equals("a\nb {fps}"), "Placeholders: \\n breaks a line and \\{ escapes");
        var runs = Placeholders.parse("<red>R</red>-<#00FF00>G</>", values).get(0);
        check(runs.size() == 3 && runs.get(0).color() == 0xFF5555 && runs.get(1).color() == null && runs.get(2).color() == 0x00FF00,
                "Placeholders: <red>, </red>, <#RRGGBB> and </> colour tags (" + runs + ")");
        check(Placeholders.plain("<bogus>x", values).equals("<bogus>x"), "Placeholders: an unknown tag stays as text");

        sp.getServer().runCommand("give @a totem_of_undying 3");
        ctx.waitTicks(4);
        String[] live = ctx.computeOnClient(c -> new String[]{
                Placeholders.plain("{x}|{y}|{z}"),
                (int) Math.floor(c.player.getX()) + "|" + (int) Math.floor(c.player.getY()) + "|" + (int) Math.floor(c.player.getZ()),
                Placeholders.plain("{dimension}"), Placeholders.plain("{count:totem_of_undying}"),
                Placeholders.plain("{mod:FPS}"), Placeholders.plain("{time:yyyy}"), Placeholders.plain("{facing}"),
                Placeholders.plain("{server}")});
        check(live[0].equals(live[1]), "Placeholders: {x} {y} {z} are your block position (" + live[0] + ")");
        check(live[2].equals("Overworld"), "Placeholders: {dimension} (" + live[2] + ")");
        check(live[3].equals("3"), "Placeholders: {count:totem_of_undying} counts your own totems (" + live[3] + ")");
        check(live[4].equals("On") || live[4].equals("Off"), "Placeholders: {mod:FPS} (" + live[4] + ")");
        check(live[5].matches("\\d{4}"), "Placeholders: {time:yyyy} formats the clock (" + live[5] + ")");
        check(List.of("North", "South", "East", "West").contains(live[6]), "Placeholders: {facing} (" + live[6] + ")");
        check(live[7].equals("Singleplayer"), "Placeholders: {server} in singleplayer (" + live[7] + ")");

        sp.getServer().runCommand("gamerule reduced_debug_info true");
        ctx.waitTicks(5);
        String[] hidden = ctx.computeOnClient(c -> new String[]{String.valueOf(c.player.hasReducedDebugInfo()),
                Placeholders.plain("{x} {y} {z} {biome}")});
        sp.getServer().runCommand("gamerule reduced_debug_info false");
        ctx.waitTicks(5);
        if (hidden[0].equals("true"))
            check(hidden[1].equals("? ? ? ?"), "Placeholders: coordinates and biome follow the server's reduced debug info (" + hidden[1] + ")");
        else EvadeClient.LOG.warn("[gametest] reduced debug info didn't apply; skipped the hidden-coordinates check");
    }

    private static void customText(ClientGameTestContext ctx) {
        Object[] r = ctx.computeOnClient(c -> {
            CustomTextHud ct = EvadeClient.modules.get(CustomTextHud.class);
            String old = ct.text.get();
            ct.text.set("<red>A</red> {if 1>0}yes{end}\\n{if 0>1}gone{end}");
            List<String> plain = ct.textForTest();
            ct.text.set(old);
            boolean three = EvadeClient.modules.get("Custom Text 3") instanceof CustomTextHud;
            return new Object[]{plain, three, ct.text.getMaxLength()};
        });
        check(r[0].equals(List.of("A yes")), "Custom Text: colours, a condition, and a blank second line dropped (" + r[0] + ")");
        check((Boolean) r[1], "Custom Text: three widgets (Custom Text 1-3)");
        check((Integer) r[2] >= 200, "Custom Text: room for long templates (" + r[2] + ")");
        int wm = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.client.WatermarkHud.class).text.getMaxLength());
        check(wm >= 64, "Watermark Text takes placeholder templates (" + wm + " chars)");
        // Show Custom Text 1 for a frame of real HUD.
        ctx.runOnClient(c -> EvadeClient.modules.get(CustomTextHud.class).setEnabled(true));
        ctx.waitTicks(3);
        ctx.takeScreenshot("hud-custom-text");
        ctx.runOnClient(c -> EvadeClient.modules.get(CustomTextHud.class).setEnabled(false));
    }

    private static void labelValue(ClientGameTestContext ctx) {
        check(TextHudModule.labelEnd("FPS: 60") == 5 && TextHudModule.labelEnd("12:30") == -1 && TextHudModule.labelEnd("60 FPS") == -1,
                "Label/value split finds \"Label: \" and leaves times and plain values alone");
        Boolean ok = ctx.computeOnClient(c -> {
            FpsHud fps = EvadeClient.modules.get(FpsHud.class);
            ModeSetting fmt = setting(fps, "Format");
            String was = fmt.get();
            fmt.set("FPS: 144");
            fps.splitColors.set(true);
            List<String> lines = fps.textForTest();
            fps.splitColors.set(false);
            fmt.set(was);
            return lines.size() == 1 && lines.get(0).startsWith("FPS: ") && fps.valueColor.chromaSetting() == null
                    && fps.color.chromaSetting() == fps.chroma;
        });
        check(ok, "Label/Value Colors keeps the text, and Text Color is linked to its Chroma toggle");
    }

    // ---- potion effects --------------------------------------------------------------------------------

    private static void potions(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("effect clear @a");
        sp.getServer().runCommand("effect give @a speed 30 2");
        sp.getServer().runCommand("effect give @a poison 5 0");
        sp.getServer().runCommand("effect give @a night_vision infinite 0");
        ctx.waitTicks(4);
        List<List<String>> got = ctx.computeOnClient(c -> {
            PotionHud p = EvadeClient.modules.get(PotionHud.class);
            ModeSetting sort = setting(p, "Sort"), level = setting(p, "Level Style");
            BooleanSetting hideInf = setting(p, "Hide Permanent");
            NumberSetting max = setting(p, "Max Shown");
            List<String> longest = p.rowsForTest();
            sort.set("Shortest First");
            level.set("Number");
            List<String> shortest = p.rowsForTest();
            hideInf.set(true);
            List<String> noInf = p.rowsForTest();
            max.set(1.0);
            List<String> one = p.rowsForTest();
            sort.reset(); level.reset(); hideInf.reset(); max.reset();
            return List.of(longest, shortest, noInf, one);
        });
        check(got.get(0).size() == 3 && got.get(0).get(0).startsWith("Night Vision") && got.get(0).get(0).endsWith("**:**"),
                "Potion Effects: permanent effects first, shown as **:** (" + got.get(0) + ")");
        check(got.get(0).get(1).startsWith("Speed III"), "Potion Effects: Roman levels (" + got.get(0) + ")");
        check(got.get(1).get(0).startsWith("Poison") && got.get(1).stream().anyMatch(s -> s.startsWith("Speed 3")),
                "Potion Effects: Shortest First and number levels (" + got.get(1) + ")");
        check(got.get(2).size() == 2 && got.get(2).stream().noneMatch(s -> s.startsWith("Night Vision")),
                "Potion Effects: Hide Permanent (" + got.get(2) + ")");
        check(got.get(3).size() == 1, "Potion Effects: Max Shown (" + got.get(3) + ")");
        ctx.runOnClient(c -> {
            PotionHud p = EvadeClient.modules.get(PotionHud.class);
            ((ModeSetting) setting(p, "Layout")).set("Horizontal");
            ((BooleanSetting) setting(p, "Compact")).set(true);
            ((ModeSetting) setting(p, "Name Color")).set("Helpful/Harmful");
        });
        ctx.waitTicks(3);
        ctx.takeScreenshot("hud-potions-compact-row");
        ctx.runOnClient(c -> {
            PotionHud p = EvadeClient.modules.get(PotionHud.class);
            setting(p, "Layout").reset();
            setting(p, "Compact").reset();
            setting(p, "Name Color").reset();
        });
        sp.getServer().runCommand("effect clear @a");
    }

    // ---- HUD editor ------------------------------------------------------------------------------------

    /** Window pixel at the middle of a widget (for the mouse). */
    private static double[] centre(ClientGameTestContext ctx, Class<? extends HudElementModule> type) {
        return ctx.computeOnClient(c -> {
            var el = EvadeClient.modules.get(type);
            float k = net.evadeclient.hud.Hud.scale();
            double f = c.getWindow().getScaleFactor();
            int w = Math.round(c.getWindow().getScaledWidth() / k), h = Math.round(c.getWindow().getScaledHeight() / k);
            return new double[]{(el.resolveX(w) + el.scaledWidth() / 2.0) * k * f, (el.resolveY(h) + el.scaledHeight() / 2.0) * k * f};
        });
    }

    private static int[] pos(ClientGameTestContext ctx, Class<? extends HudElementModule> type) {
        return ctx.computeOnClient(c -> {
            var el = EvadeClient.modules.get(type);
            float k = net.evadeclient.hud.Hud.scale();
            int w = Math.round(c.getWindow().getScaledWidth() / k), h = Math.round(c.getWindow().getScaledHeight() / k);
            return new int[]{el.resolveX(w), el.resolveY(h), el.isEnabled() ? 1 : 0, el.locked.get() ? 1 : 0};
        });
    }

    /**
     * Window pixel of a spot no widget covers, in the upper part of the screen: searching rightwards from HUD (x, y),
     * or anywhere in the upper third when x is -1.
     */
    private static double[] emptyPoint(ClientGameTestContext ctx, int fromX, int y) {
        return ctx.computeOnClient(c -> {
            float k = net.evadeclient.hud.Hud.scale();
            double f = c.getWindow().getScaleFactor();
            int w = Math.round(c.getWindow().getScaledWidth() / k), h = Math.round(c.getWindow().getScaledHeight() / k);
            int y0 = y >= 0 ? y : h / 4, y1 = y >= 0 ? y : h / 3;
            for (int yy = y0; yy <= y1; yy += 4) {
                for (int xx = Math.max(0, fromX >= 0 ? fromX : w / 4); xx < w - 4; xx += 4) {
                    boolean hit = false;
                    for (HudElementModule el : EvadeClient.modules.hudElements()) {
                        if (!el.isEnabled()) continue;
                        int l = el.resolveX(w), t = el.resolveY(h);
                        if (xx >= l - 1 && xx <= l + el.scaledWidth() + 1 && yy >= t - 1 && yy <= t + el.scaledHeight() + 1) { hit = true; break; }
                    }
                    if (!hit) return new double[]{xx * k * f, yy * k * f};
                }
            }
            throw new AssertionError("No empty spot on the HUD");
        });
    }

    private static void key(ClientGameTestContext ctx, int mod, int key) {
        var in = ctx.getInput();
        if (mod != 0) in.holdKey(mod);
        in.pressKey(key);
        if (mod != 0) in.releaseKey(mod);
        ctx.waitTicks(2);
    }

    private static void editor(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            // FPS, CPS and Ping in a loose column at the left, unlocked.
            int[][] at = {{4, 4}, {200, 8}, {230, 70}};
            Class<?>[] types = {FpsHud.class, CpsHud.class, PingHud.class};
            for (int i = 0; i < 3; i++) {
                @SuppressWarnings("unchecked")
                HudElementModule el = EvadeClient.modules.get((Class<HudElementModule>) types[i]);
                el.setEnabled(true);
                el.locked.set(false);
                el.x = at[i][0];
                el.y = at[i][1];
            }
        });
        ctx.setScreen(() -> new HudEditorScreen(null));
        ctx.waitTicks(4);
        var in = ctx.getInput();

        // Click FPS, then Shift+click CPS: both picked.
        double[] f = centre(ctx, FpsHud.class), cp = centre(ctx, CpsHud.class);
        in.setCursorPos(f[0], f[1]);
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
        in.holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        in.setCursorPos(cp[0], cp[1]);
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        in.releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        ctx.waitTicks(2);
        String sel = ctx.computeOnClient(c -> ((HudEditorScreen) c.currentScreen).selection().stream()
                .map(HudElementModule::getName).toList().toString());
        check(sel.equals("[FPS, CPS]"), "HUD editor: click then Shift+click picks two widgets (" + sel + " at "
                + java.util.Arrays.toString(f) + ", " + java.util.Arrays.toString(cp) + ")");
        ctx.takeScreenshot("hud-editor-multiselect");

        // Arrow nudges the whole selection: 1, then 10 with Shift.
        int[] f0 = pos(ctx, FpsHud.class), c0 = pos(ctx, CpsHud.class);
        int d0 = ctx.computeOnClient(c -> ((HudEditorScreen) c.currentScreen).undoDepth());
        in.setCursorPos(5, 5);
        key(ctx, 0, GLFW.GLFW_KEY_RIGHT);
        key(ctx, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_DOWN);
        int[] f1 = pos(ctx, FpsHud.class), c1 = pos(ctx, CpsHud.class);
        check(f1[0] == f0[0] + 1 && f1[1] == f0[1] + 10 && c1[0] == c0[0] + 1 && c1[1] == c0[1] + 10,
                "HUD editor: arrows nudge the selection by 1, Shift by 10 (FPS " + java.util.Arrays.toString(f0) + " -> "
                        + java.util.Arrays.toString(f1) + ", CPS " + java.util.Arrays.toString(c0) + " -> " + java.util.Arrays.toString(c1) + ")");

        // Undo puts both back (quick nudges in a row may be one step); redo moves them again.
        int steps = ctx.computeOnClient(c -> ((HudEditorScreen) c.currentScreen).undoDepth()) - d0;
        check(steps >= 1, "HUD editor: nudges are recorded for undo (" + steps + " steps)");
        for (int i = 0; i < steps; i++) key(ctx, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_Z);
        int[] fu = pos(ctx, FpsHud.class), cu = pos(ctx, CpsHud.class);
        check(fu[0] == f0[0] && fu[1] == f0[1] && cu[0] == c0[0] && cu[1] == c0[1],
                "HUD editor: Ctrl+Z undoes the nudges (" + fu[0] + "," + fu[1] + ")");
        for (int i = 0; i < steps; i++) {
            if (i == 0) key(ctx, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_Y);
            else { // Ctrl+Shift+Z redoes too
                in.holdKey(GLFW.GLFW_KEY_LEFT_CONTROL);
                key(ctx, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_Z);
                in.releaseKey(GLFW.GLFW_KEY_LEFT_CONTROL);
            }
        }
        int[] fr = pos(ctx, FpsHud.class);
        check(fr[0] == f1[0] && fr[1] == f1[1], "HUD editor: Ctrl+Y (and Ctrl+Shift+Z) redo them");
        for (int i = 0; i < steps; i++) key(ctx, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_Z);

        // Align Left, then distribute three down.
        ctx.runOnClient(c -> ((HudEditorScreen) c.currentScreen).align("Left"));
        int[] fa = pos(ctx, FpsHud.class), ca = pos(ctx, CpsHud.class);
        check(fa[0] == ca[0] && fa[0] == Math.min(f0[0], c0[0]), "HUD editor: Align Left lines the picked widgets up");
        ctx.runOnClient(c -> {
            var s = (HudEditorScreen) c.currentScreen;
            s.selection().add(EvadeClient.modules.get(PingHud.class));
            s.distribute(false);
        });
        int[] a = pos(ctx, FpsHud.class), b = pos(ctx, CpsHud.class), p = pos(ctx, PingHud.class);
        int[] heights = ctx.computeOnClient(c -> new int[]{EvadeClient.modules.get(FpsHud.class).scaledHeight(),
                EvadeClient.modules.get(CpsHud.class).scaledHeight()});
        int gap1 = b[1] - (a[1] + heights[0]), gap2 = p[1] - (b[1] + heights[1]);
        check(Math.abs(gap1 - gap2) <= 1, "HUD editor: Space Down spaces three widgets evenly (" + gap1 + ", " + gap2 + ")");

        // Lock: L, then a nudge leaves it; unlock again.
        ctx.runOnClient(c -> { var s = (HudEditorScreen) c.currentScreen; s.selection().clear(); s.selection().add(EvadeClient.modules.get(FpsHud.class)); });
        key(ctx, 0, GLFW.GLFW_KEY_L);
        int[] l0 = pos(ctx, FpsHud.class);
        key(ctx, 0, GLFW.GLFW_KEY_RIGHT);
        int[] l1 = pos(ctx, FpsHud.class);
        check(l0[3] == 1 && l1[0] == l0[0], "HUD editor: L locks a widget and a locked widget doesn't move");
        key(ctx, 0, GLFW.GLFW_KEY_L);
        check(pos(ctx, FpsHud.class)[3] == 0, "HUD editor: L again unlocks it");

        // Delete hides it; undo brings it back. Toggle Sprint (not a HUD widget) is never hidden.
        key(ctx, 0, GLFW.GLFW_KEY_DELETE);
        boolean off = pos(ctx, FpsHud.class)[2] == 0;
        key(ctx, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_Z);
        boolean back = pos(ctx, FpsHud.class)[2] == 1;
        check(off && back, "HUD editor: Delete hides the widget and Ctrl+Z brings it back");
        boolean ts = ctx.computeOnClient(c -> HudLayout.ownsVisibility(EvadeClient.modules.get(ToggleSprint.class)));
        check(!ts, "HUD editor: mods with only a movable label are never hidden by the editor");

        // Right-click on empty space opens the Add widget list; picking one turns it on there.
        double[] empty = emptyPoint(ctx, -1, -1);
        in.setCursorPos(empty[0], empty[1]);
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(2);
        ctx.takeScreenshot("hud-editor-add-widget");
        String added = ctx.computeOnClient(c -> {
            var s = (HudEditorScreen) c.currentScreen;
            var ct = EvadeClient.modules.get(CustomTextHud.class);
            boolean wasOn = ct.isEnabled();
            s.addWidget(ct, 200, 120);
            boolean on = ct.isEnabled() && s.selection().contains(ct);
            s.undo();
            return on + "," + (ct.isEnabled() == wasOn);
        });
        check(added.equals("true,true"), "HUD editor: Add widget turns one on and selects it; undo removes it (" + added + ")");
        in.pressKey(GLFW.GLFW_KEY_ESCAPE); // closes the drop-down
        ctx.waitTicks(1);
        boolean stillEditor = ctx.computeOnClient(c -> c.currentScreen instanceof HudEditorScreen);
        check(stillEditor, "HUD editor: Escape closes the drop-down first");

        // Box select: drag across empty space over FPS and CPS.
        ctx.runOnClient(c -> ((HudEditorScreen) c.currentScreen).selection().clear());
        int[] ff = pos(ctx, FpsHud.class);
        double kf = ctx.computeOnClient(c -> net.evadeclient.hud.Hud.scale() * c.getWindow().getScaleFactor());
        double[] cc = centre(ctx, CpsHud.class);
        int right = ctx.computeOnClient(c -> Math.max(
                EvadeClient.modules.get(FpsHud.class).scaledWidth(), EvadeClient.modules.get(CpsHud.class).scaledWidth()));
        double[] start = emptyPoint(ctx, ff[0] + right + 3, ff[1]);
        double sx = start[0], sy = start[1];
        in.setCursorPos(sx, sy);
        in.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        for (int i = 1; i <= 6; i++) {
            in.setCursorPos(sx + (1 * kf - sx) * i / 6, sy + (cc[1] - sy) * i / 6);
            ctx.waitTicks(1);
        }
        in.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(2);
        boolean boxed = ctx.computeOnClient(c -> ((HudEditorScreen) c.currentScreen).selection().containsAll(
                List.of(EvadeClient.modules.get(FpsHud.class), EvadeClient.modules.get(CpsHud.class))));
        check(boxed, "HUD editor: dragging a box on empty space picks the widgets it touches");
        ctx.setScreen(() -> null);
    }

    // ---- layouts and codes -----------------------------------------------------------------------------

    private static void layouts(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            var ts = EvadeClient.modules.get(ToggleSprint.class);
            boolean tsOn = ts.isEnabled();
            String before = HudLayout.snapshot();
            HudLayout.applyPreset("Minimal");
            boolean fps = EvadeClient.modules.get(FpsHud.class).isEnabled();
            boolean cps = EvadeClient.modules.get(CpsHud.class).isEnabled();
            boolean coords = EvadeClient.modules.get(net.evadeclient.module.hud.CoordsHud.class).isEnabled();
            boolean tsSame = ts.isEnabled() == tsOn;
            boolean all = HudLayout.PRESETS.stream().allMatch(HudLayout::applyPreset);
            HudLayout.restore(before);
            return fps + "," + !cps + "," + coords + "," + tsSame + "," + all + "," + before.equals(HudLayout.snapshot());
        });
        check(r.equals("true,true,true,true,true,true"), "HUD layouts: Minimal shows its widgets, hides the rest, leaves other mods alone; every preset applies (" + r + ")");

        String code = ctx.computeOnClient(c -> HudLayout.shareCode());
        check(code.startsWith(HudLayout.CODE_PREFIX), "HUD code: starts with " + HudLayout.CODE_PREFIX);
        String rt = ctx.computeOnClient(c -> {
            var fps = EvadeClient.modules.get(FpsHud.class);
            int x = fps.x;
            double s = fps.scale.get();
            fps.x = x + 37;
            fps.scale.set(1.5);
            var res = HudLayout.importCode("here you go: " + code.substring(0, 20) + "\n" + code.substring(20) + " enjoy");
            boolean back = fps.x == x && Math.abs(fps.scale.get() - s) < 1e-6;
            String snap = HudLayout.snapshot();
            var bad = HudLayout.importCode("EVHUD1:notarealcode");
            var cut = HudLayout.importCode(code.substring(0, code.length() / 2));
            var none = HudLayout.importCode("hello");
            boolean unchanged = snap.equals(HudLayout.snapshot());
            return res.ok() + "," + back + "," + !bad.ok() + "," + !cut.ok() + "," + !none.ok() + "," + unchanged;
        });
        check(rt.equals("true,true,true,true,true,true"),
                "HUD code: round-trips positions and scale (even split over lines), and bad or cut codes change nothing (" + rt + ")");
    }

    // ---- colour picker ---------------------------------------------------------------------------------

    private static void colorPicker(ClientGameTestContext ctx) {
        Path tmp;
        try {
            tmp = Files.createTempFile("evade-recent", ".json");
            Files.deleteIfExists(tmp);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        check(ColorPicker.parseHex("#f00") == 0xFFFF0000 && ColorPicker.parseHex("80112233") == 0x80112233
                && ColorPicker.parseHex("xyz") == null, "Colour picker: #RGB, AARRGGBB and invalid hex");
        ctx.runOnClient(c -> { ColorHistory.FILE_OVERRIDE = tmp; ColorHistory.clear(); });
        var cs = ctx.computeOnClient(c -> EvadeClient.modules.get(ClientSettings.class));
        String styleWas = ctx.computeOnClient(c -> cs.guiStyle.get());
        int colorWas = ctx.computeOnClient(c -> EvadeClient.modules.get(FpsHud.class).color.get());
        try {
            for (String style : new String[]{"List", "Cards", "Compact"}) {
                ctx.runOnClient(c -> cs.guiStyle.set(style));
                EvadeMenu menu = ctx.computeOnClient(c -> {
                    EvadeMenu m = EvadeMenu.open(EvadeClient.modules.get(FpsHud.class));
                    c.setScreen(m);
                    return m;
                });
                ctx.waitTicks(8);
                ctx.runOnClient(c -> {
                    var col = EvadeClient.modules.get(FpsHud.class).color;
                    menu.picker.open(col, c.getWindow().getScaledWidth() * 0.75, c.getWindow().getScaledHeight() * 0.5);
                });
                ctx.waitTicks(3);
                ctx.takeScreenshot("color-picker-" + style.toLowerCase(java.util.Locale.ROOT));
                // Click the square's top-left corner: white.
                double[] at = ctx.computeOnClient(c -> {
                    double[] p = menu.picker.svPointForTest(0f, 1f);
                    double f = c.getWindow().getScaleFactor();
                    return new double[]{p[0] * f + 1, p[1] * f + 1};
                });
                ctx.getInput().setCursorPos(at[0], at[1]);
                ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                ctx.waitTicks(2);
                int v = ctx.computeOnClient(c -> EvadeClient.modules.get(FpsHud.class).color.get());
                check((v >> 16 & 0xFF) >= 0xF0 && (v >> 8 & 0xFF) >= 0xF0 && (v & 0xFF) >= 0xF0, "Colour picker (" + style + " style): a click in the square picks that colour (#"
                        + Integer.toHexString(v) + ")");
                ctx.runOnClient(c -> menu.picker.setHsvForTest(0f, 1f, 1f, 1f));
                int red = ctx.computeOnClient(c -> EvadeClient.modules.get(FpsHud.class).color.get());
                check(red == 0xFFFF0000, "Colour picker (" + style + " style): hue/saturation/value give the colour (#" + Integer.toHexString(red) + ")");
                ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                ctx.waitTicks(2);
                boolean closedOnly = ctx.computeOnClient(c -> !menu.picker.isOpen() && c.currentScreen == menu);
                check(closedOnly, "Colour picker (" + style + " style): Escape closes the picker, not the menu");
            }
            boolean remembered = ctx.computeOnClient(c -> {
                ColorHistory.reload(); // read back from the file
                return ColorHistory.get().contains(0xFFFF0000);
            });
            check(remembered, "Colour picker: picked colours are remembered as recent colours across restarts");
            boolean chroma = ctx.computeOnClient(c -> {
                var fps = EvadeClient.modules.get(FpsHud.class);
                return fps.color.chromaSetting() == fps.chroma;
            });
            check(chroma, "Colour picker: offers the Chroma toggle for text colours");
        } finally {
            ctx.runOnClient(c -> {
                cs.guiStyle.set(styleWas);
                EvadeClient.modules.get(FpsHud.class).color.set(colorWas);
                ColorHistory.FILE_OVERRIDE = null;
                ColorHistory.reload();
                c.setScreen(null);
            });
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
        }
    }

    // ---- titles and action bar -------------------------------------------------------------------------

    private static void titles(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(c -> {
            var t = EvadeClient.modules.get(TitlesHud.class);
            t.lastTransform = null;
            t.setEnabled(false);
            c.inGameHud.setTitle(Text.literal("Hello"));
        });
        ctx.waitTicks(3);
        boolean untouched = ctx.computeOnClient(c -> EvadeClient.modules.get(TitlesHud.class).lastTransform == null);
        check(untouched, "Titles: off by default leaves vanilla's placement alone");
        ctx.runOnClient(c -> {
            var t = EvadeClient.modules.get(TitlesHud.class);
            t.setEnabled(true);
            t.x = 10;
            t.y = 10;
            c.inGameHud.setTitle(Text.literal("Hello"));
            c.inGameHud.setSubtitle(Text.literal("moved"));
            var ab = EvadeClient.modules.get(ActionBarHud.class);
            ab.setEnabled(true);
            ab.lastTransform = null;
            c.inGameHud.setOverlayMessage(Text.literal("Action bar here"), false);
        });
        ctx.waitTicks(4);
        ctx.takeScreenshot("hud-titles-moved");
        float[][] tr = ctx.computeOnClient(c -> new float[][]{EvadeClient.modules.get(TitlesHud.class).lastTransform,
                EvadeClient.modules.get(ActionBarHud.class).lastTransform, {net.evadeclient.hud.Hud.scale()}});
        check(tr[0] != null && tr[1] != null, "Titles and Action Bar: vanilla's text is drawn at the element when on");
        // The title box's top-left (vanilla's sh/2 - 48 line) lands on the element's top-left: 10 HUD units in.
        float k = tr[2][0];
        int sh = ctx.computeOnClient(c -> c.getWindow().getScaledHeight());
        int sw = ctx.computeOnClient(c -> c.getWindow().getScaledWidth());
        int[] box = ctx.computeOnClient(c -> new int[]{EvadeClient.modules.get(TitlesHud.class).scaledWidth()});
        float vx = sw / 2 - box[0] * k / 2f, vy = sh / 2 - 48;
        float gotX = tr[0][0] + vx * tr[0][2], gotY = tr[0][1] + vy * tr[0][2];
        check(Math.abs(gotX - 10 * k) < 2 * k + 1 && Math.abs(gotY - 10 * k) < 1, "Titles: the title lands at the element's spot ("
                + gotX + ", " + gotY + ")");
        ctx.runOnClient(c -> {
            EvadeClient.modules.get(TitlesHud.class).setEnabled(false);
            EvadeClient.modules.get(ActionBarHud.class).setEnabled(false);
            EvadeClient.modules.get(TitlesHud.class).resetPosition();
            c.inGameHud.clearTitle();
        });
        ctx.waitTicks(1);
    }
}
