package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.gui.TextHotkeysScreen;
import net.evadeclient.module.misc.TextHotkeys;
import net.evadeclient.module.misc.TextHotkeys.Hotkey;
import net.evadeclient.util.Keybinds;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Text Hotkeys (one-click macros): one press sends exactly one message, holding never repeats, the Cooldown holds
 * across keys, Ctrl + K and K are different hotkeys, the per-hotkey server filter, nothing sends while a screen is
 * open, the editor (.hotkeys) edits and saves, presets add with one click, old nine-slot configs migrate, and the
 * menu search finds it as "macro" / "auto text". Messages go to the test's own singleplayer server.
 */
public final class TextHotkeysTest {
    private TextHotkeysTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private static TextHotkeys th() { return EvadeClient.modules.get(TextHotkeys.class); }

    /** Two keys nothing else uses (so pressing them only does what this test expects). */
    private static int[] freeKeys(ClientGameTestContext ctx) {
        return ctx.computeOnClient(c -> {
            int[] candidates = {GLFW.GLFW_KEY_KP_8, GLFW.GLFW_KEY_KP_9, GLFW.GLFW_KEY_KP_6, GLFW.GLFW_KEY_KP_5,
                    GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_N};
            List<Integer> free = new ArrayList<>();
            for (int k : candidates) if (Keybinds.conflictNames(k, 0, null).isEmpty() && free.size() < 2) free.add(k);
            return new int[]{free.get(0), free.get(1)};
        });
    }

    private static int sent(ClientGameTestContext ctx) { return ctx.computeOnClient(c -> th().sentCountForTest); }

    private static void tap(ClientGameTestContext ctx, int key) {
        ctx.getInput().holdKey(key);
        ctx.waitTicks(2);
        ctx.getInput().releaseKey(key);
        ctx.waitTicks(2);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.setScreen(() -> null);
        ctx.waitTicks(2);
        int[] keys = freeKeys(ctx);
        int a = keys[0], b = keys[1];
        // Keep whatever the player had; start from an empty list with the module on.
        List<Hotkey> saved = ctx.computeOnClient(c -> {
            TextHotkeys t = th();
            List<Hotkey> before = new ArrayList<>(t.hotkeys());
            t.edit().clear();
            t.serverForTest = null;
            t.resetGapForTest();
            t.setEnabled(true);
            Hotkey h1 = t.addHotkey("First", "hotkey test one");
            h1.key = a;
            Hotkey h2 = t.addHotkey("", "hotkey test two");
            h2.key = b;
            return before;
        });
        ctx.waitTicks(3);

        // 1. One press, one message; holding does not repeat.
        int s0 = sent(ctx);
        ctx.getInput().holdKey(a);
        ctx.waitTicks(2);
        int s1 = sent(ctx);
        ctx.waitTicks(50); // held for 2.5 s, longer than the gap
        int s2 = sent(ctx);
        ctx.getInput().releaseKey(a);
        ctx.waitTicks(2);
        String last = ctx.computeOnClient(c -> th().lastSentForTest);
        check(s1 == s0 + 1 && "hotkey test one".equals(last), "Text Hotkeys: one press sends its message once (" + s0 + " -> " + s1 + ", " + last + ")");
        check(s2 == s1, "Text Hotkeys: holding the key doesn't send it again (" + s2 + ")");

        // 2. The Cooldown (0.5 s by default, 0.25 to 3 s) holds across different keys, at the configured value.
        String cd = ctx.computeOnClient(c -> {
            var st = th().cooldownSetting();
            String r = st.get() + "|" + th().gapMs();
            st.set(1.0);
            th().resetGapForTest();
            return r + "|" + th().gapMs();
        });
        check(cd.equals("0.5|500|1000"), "Text Hotkeys: Cooldown defaults to 0.5 s and follows the setting (" + cd + ")");
        int g0 = sent(ctx);
        long t0 = System.currentTimeMillis();
        tap(ctx, a);
        tap(ctx, b);
        long within = System.currentTimeMillis() - t0;
        int g1 = sent(ctx);
        while (System.currentTimeMillis() - t0 < 1150) ctx.waitTick();
        tap(ctx, b);
        int g2 = sent(ctx);
        String lastB = ctx.computeOnClient(c -> th().lastSentForTest);
        check(within >= 1000 || g1 == g0 + 1, "Text Hotkeys: a second key within the 1 s cooldown is held back (" + (g1 - g0) + " sent in " + within + " ms)");
        check(g2 == g1 + 1 && "hotkey test two".equals(lastB), "Text Hotkeys: once the cooldown has passed the next press sends (" + lastB + ")");
        ctx.runOnClient(c -> th().cooldownSetting().reset());

        // 3. Combos: Ctrl + A and plain A are different hotkeys; a combo press sends only the combo's text.
        ctx.runOnClient(c -> {
            Hotkey combo = th().addHotkey("Combo", "hotkey test combo");
            combo.key = a;
            combo.mods = Keybinds.CTRL;
            th().resetGapForTest();
        });
        ctx.waitTicks(2);
        int c0 = sent(ctx);
        ctx.getInput().holdKey(GLFW.GLFW_KEY_LEFT_CONTROL);
        ctx.waitTicks(2);
        tap(ctx, a);
        ctx.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_CONTROL);
        ctx.waitTicks(2);
        int c1 = sent(ctx);
        String comboSent = ctx.computeOnClient(c -> th().lastSentForTest);
        ctx.runOnClient(c -> th().resetGapForTest());
        tap(ctx, a);
        String plainSent = ctx.computeOnClient(c -> th().lastSentForTest);
        check(c1 == c0 + 1 && "hotkey test combo".equals(comboSent), "Text Hotkeys: Ctrl + key sends the combo's text only (" + comboSent + ", " + (c1 - c0) + " sent)");
        check("hotkey test one".equals(plainSent), "Text Hotkeys: the plain key still sends its own text (" + plainSent + ")");
        String conflict = ctx.computeOnClient(c -> {
            Hotkey dup = th().addHotkey("Dup", "dup");
            dup.key = a;
            List<String> names = th().conflicts(dup);
            th().edit().remove(dup);
            return String.join(", ", names);
        });
        check(conflict.contains("First") && !conflict.contains("Combo"), "Text Hotkeys: a key shared with another hotkey is flagged, a combo isn't (" + conflict + ")");

        // 4. Server filter: only on servers whose address contains one of the parts.
        String filter = ctx.computeOnClient(c -> {
            TextHotkeys t = th();
            Hotkey hub = t.addHotkey("Hub", "/hotkeytesthub");
            hub.key = b;
            hub.servers = "example.net | mc.other.org";
            t.edit().remove(1); // the unfiltered hotkey on the same key
            StringBuilder out = new StringBuilder();
            for (String server : new String[]{null, "play.example.net", "elsewhere.com", "MC.OTHER.ORG"}) {
                t.serverForTest = server;
                t.resetGapForTest();
                int before = t.sentCountForTest;
                t.pressForTest(b, 0);
                out.append(t.sentCountForTest > before ? "Y" : "n");
            }
            t.serverForTest = null;
            return out.toString();
        });
        check(filter.equals("nYnY"), "Text Hotkeys: 'Only on servers' works where the address matches, nowhere else, not in singleplayer (" + filter + ")");

        // 5. Never while a screen is open (the editor itself here).
        ctx.runOnClient(c -> { th().resetGapForTest(); th().openEditor(null); });
        ctx.waitTicks(3);
        int o0 = sent(ctx);
        tap(ctx, a);
        int o1 = sent(ctx);
        check(o1 == o0, "Text Hotkeys: nothing is sent while a screen is open");

        // 6. The editor: .hotkeys opens it; type text, capture a Shift combo, add presets; it saves.
        ctx.setScreen(() -> null);
        ctx.waitTicks(2);
        ctx.runOnClient(c -> EvadeClient.commands.handleChat(".hotkeys"));
        ctx.waitTicks(4);
        boolean opened = ctx.computeOnClient(c -> c.currentScreen instanceof TextHotkeysScreen);
        check(opened, ".hotkeys opens the Text Hotkeys editor");
        ctx.runOnClient(c -> ((TextHotkeysScreen) c.currentScreen).addNewForTest());
        ctx.waitTicks(2);
        ctx.getInput().typeChars("typed in editor");
        ctx.waitTicks(2);
        ctx.runOnClient(c -> {
            var s = (TextHotkeysScreen) c.currentScreen;
            s.captureKeyForTest(th().hotkeys().size() - 1);
        });
        ctx.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        ctx.waitTicks(1);
        ctx.getInput().pressKey(b);
        ctx.waitTicks(1);
        ctx.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        ctx.waitTicks(2);
        String edited = ctx.computeOnClient(c -> {
            Hotkey h = th().hotkeys().get(th().hotkeys().size() - 1);
            return h.text + "|" + h.keyText() + "|" + ((TextHotkeysScreen) c.currentScreen).capturingForTest();
        });
        String expectKey = ctx.computeOnClient(c -> Keybinds.comboName(b, Keybinds.SHIFT));
        check(edited.equals("typed in editor|" + expectKey + "|false"), "Editor: typing sets the text and the key box captures a Shift combo (" + edited + ")");
        // A 300-character paste stops at 256.
        int longLen = ctx.computeOnClient(c -> {
            var h = th().hotkeys().get(th().hotkeys().size() - 1);
            h.text = TextHotkeys.clean("x".repeat(300), TextHotkeys.MAX_CHARS);
            int n = h.text.length();
            h.text = "typed in editor";
            return n;
        });
        check(longLen == TextHotkeys.MAX_CHARS, "Editor: text is capped at 256 characters (" + longLen + ")");

        String presets = ctx.computeOnClient(c -> {
            var s = (TextHotkeysScreen) c.currentScreen;
            int before = th().hotkeys().size();
            s.addPresetForTest(0); // gg
            s.addPresetForTest(5); // /msg <name>
            List<Hotkey> l = th().hotkeys();
            Hotkey gg = l.get(before), msg = l.get(before + 1);
            return (l.size() - before) + "|" + gg.label + "|" + gg.text + "|" + msg.text.strip() + "|" + gg.bound();
        });
        check(presets.equals("2|GG|gg|/msg <name>|false"), "Presets: one click adds an unbound hotkey to edit (" + presets + ")");
        String nameGuard = ctx.computeOnClient(c -> {
            TextHotkeys t = th();
            Hotkey msg = t.hotkeys().get(t.hotkeys().size() - 1);
            msg.key = GLFW.GLFW_KEY_KP_DIVIDE;
            t.resetGapForTest();
            int before = t.sentCountForTest;
            t.pressForTest(GLFW.GLFW_KEY_KP_DIVIDE, 0);
            String r = t.sentCountForTest == before ? "held" : "sent";
            msg.key = GLFW.GLFW_KEY_UNKNOWN;
            String exp = t.expand("on {server} at {time}");
            return r + "|" + exp;
        });
        check(nameGuard.startsWith("held|on singleplayer at ") && nameGuard.matches(".*\\d\\d:\\d\\d$"),
                "Presets: /msg <name> isn't sent until the name is filled in; {server} and {time} fill in (" + nameGuard + ")");
        // Esc leaves the text box, closes the open hotkey, then the editor, which saves.
        for (int i = 0; i < 3; i++) {
            ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            ctx.waitTicks(2);
        }
        boolean closed = ctx.computeOnClient(c -> c.currentScreen == null);
        String file = ctx.computeOnClient(c -> {
            try {
                return Files.readString(FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("config.json"), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        });
        check(closed && file.contains("typed in editor") && file.contains("\"Message\""), "Editor: closes with Esc and the hotkeys are saved to the config");

        // 7. Old configs: the nine fixed slots become list entries.
        String migrated = ctx.computeOnClient(c -> {
            try {
                Path tmp = Files.createTempFile("evade-old-hotkeys", ".json");
                Files.writeString(tmp, "{\"Text Hotkeys\":{\"settings\":{\"Hotkey 1 Key\":" + GLFW.GLFW_KEY_KP_7
                        + ",\"Hotkey 1 Text\":\"gg\",\"Hotkey 2 Key\":-1,\"Hotkey 2 Text\":\"\",\"Hotkey 4 Key\":-1,"
                        + "\"Hotkey 4 Text\":\"/spawn\",\"Confirmation\":true}}}", StandardCharsets.UTF_8);
                EvadeClient.config.loadFrom(tmp);
                Files.deleteIfExists(tmp);
                StringBuilder out = new StringBuilder();
                for (Hotkey h : th().hotkeys()) out.append(h.text).append('@').append(h.key).append(';');
                return out.toString();
            } catch (Exception e) {
                return "error " + e;
            }
        });
        check(migrated.equals("gg@" + GLFW.GLFW_KEY_KP_7 + ";/spawn@" + GLFW.GLFW_KEY_UNKNOWN + ";"),
                "Old nine-slot configs migrate to the hotkey list (" + migrated + ")");

        // 8. The menu's search finds it as a macro / auto text / one click.
        String found = ctx.computeOnClient(c -> {
            try {
                var m = EvadeMenu.class.getDeclaredMethod("matches", net.evadeclient.module.Module.class, String.class);
                m.setAccessible(true);
                StringBuilder out = new StringBuilder();
                for (String q : new String[]{"macro", "auto text", "autotext", "one click"})
                    out.append((boolean) m.invoke(null, th(), q) ? "Y" : "n");
                return out.toString();
            } catch (ReflectiveOperationException e) {
                return "error " + e;
            }
        });
        check(found.equals("YYYY"), "Menu search finds Text Hotkeys for macro, auto text, autotext, one click (" + found + ")");

        // Put things back.
        ctx.runOnClient(c -> {
            TextHotkeys t = th();
            t.serverForTest = null;
            t.edit().clear();
            t.edit().addAll(saved);
            t.setEnabled(false);
            EvadeClient.config.save();
            c.getToastManager().clear();
        });
        ctx.setScreen(() -> null);
        ctx.waitTicks(2);
    }
}
