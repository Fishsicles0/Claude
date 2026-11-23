package net.evadeclient.clips.core;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * System-wide hotkeys, so clips can be saved while a fullscreen game has focus. Hotkeys are written like
 * "F8", "Alt+F10" or "Ctrl+Shift+S". A hotkey also works while other modifiers are held (Ctrl to crouch, Shift
 * to sprint); when several bindings match, the one naming the most modifiers wins, so F8 and Ctrl+F8 can do
 * different things. Holding a key down runs its action once.
 */
public final class Hotkeys implements NativeKeyListener {
    private static final Map<String, Integer> CODES = new LinkedHashMap<>();
    private static final Map<Integer, String> NAMES = new LinkedHashMap<>();

    static {
        for (Field f : NativeKeyEvent.class.getFields()) {
            if (!f.getName().startsWith("VC_") || !Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
            try {
                String name = pretty(f.getName().substring(3));
                int code = f.getInt(null);
                CODES.putIfAbsent(name.toUpperCase(Locale.ROOT), code);
                NAMES.putIfAbsent(code, name);
            } catch (IllegalAccessException ignored) {}
        }
    }

    private static String pretty(String raw) {
        if (raw.length() <= 3) return raw;
        String s = raw.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    private record Binding(int code, int mods, Runnable action) {}

    private final List<Binding> bindings = new ArrayList<>();
    /**
     * Keys that are down, with when they last reported a press. A press within a second of the last one for the
     * same key is the keyboard's auto-repeat (a missed release can't block a key for longer than that).
     */
    private final Map<Integer, Long> held = new ConcurrentHashMap<>();
    private static final long REPEAT_MS = 1000;
    private boolean registered;
    private String error;
    /** While set, the next key press is reported here instead of triggering hotkeys (for the settings "press a key" box). */
    private volatile Consumer<String> capture;

    public synchronized boolean start() {
        if (registered) return true;
        try {
            Logger l = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            l.setLevel(Level.OFF);
            l.setUseParentHandlers(false);
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            registered = true;
        } catch (Throwable t) {
            error = t.getMessage();
            System.err.println("[clips] global hotkeys unavailable: " + t.getMessage());
        }
        return registered;
    }

    public String error() { return error; }
    public boolean active() { return registered; }

    public synchronized void stop() {
        if (!registered) return;
        try {
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.unregisterNativeHook();
        } catch (Throwable ignored) {}
        registered = false;
    }

    public synchronized void clear() { bindings.clear(); }

    /** Binds a hotkey text to an action. Returns false if the text is not a valid key. */
    public synchronized boolean bind(String text, Runnable action) {
        int[] parsed = parse(text);
        if (parsed == null) return false;
        bindings.add(new Binding(parsed[0], parsed[1], action));
        return true;
    }

    /**
     * Reports the next key press (as text {@link #parse} accepts, or null for Esc) instead of running hotkeys, for
     * the settings "press a key" box. {@code null} cancels a pending capture.
     */
    public void captureNext(Consumer<String> c) { capture = c; }

    /** True if {@code text} names a key the global hook can use. */
    public static boolean valid(String text) { return parse(text) != null; }

    /** {key code, modifier mask} for hotkey text such as "Ctrl+F8", or null if it isn't a key the hook knows. */
    public static int[] parse(String text) {
        if (text == null || text.isBlank()) return null;
        int mods = 0;
        Integer code = null;
        for (String part : text.split("\\+")) {
            String p = part.trim().toUpperCase(Locale.ROOT);
            switch (p) {
                case "CTRL", "CONTROL" -> mods |= NativeInputEvent.CTRL_MASK;
                case "ALT" -> mods |= NativeInputEvent.ALT_MASK;
                case "SHIFT" -> mods |= NativeInputEvent.SHIFT_MASK;
                case "WIN", "META", "CMD" -> mods |= NativeInputEvent.META_MASK;
                default -> {
                    String k = p.replace(" ", "");
                    code = CODES.get(k);
                    // describe() names keys without a constant "Key<code>".
                    if (code == null && k.matches("KEY\\d{1,6}")) code = Integer.parseInt(k.substring(3));
                }
            }
        }
        return code == null ? null : new int[]{code, mods};
    }

    private static int normMods(int m) {
        int out = 0;
        if ((m & NativeInputEvent.CTRL_MASK) != 0) out |= NativeInputEvent.CTRL_MASK;
        if ((m & NativeInputEvent.ALT_MASK) != 0) out |= NativeInputEvent.ALT_MASK;
        if ((m & NativeInputEvent.SHIFT_MASK) != 0) out |= NativeInputEvent.SHIFT_MASK;
        if ((m & NativeInputEvent.META_MASK) != 0) out |= NativeInputEvent.META_MASK;
        return out;
    }

    static boolean isModifier(int code) {
        return code == NativeKeyEvent.VC_CONTROL || code == NativeKeyEvent.VC_ALT || code == NativeKeyEvent.VC_SHIFT
                || code == NativeKeyEvent.VC_META;
    }

    public static String describe(int code, int mods) {
        StringBuilder b = new StringBuilder();
        int m = normMods(mods);
        if ((m & NativeInputEvent.CTRL_MASK) != 0) b.append("Ctrl+");
        if ((m & NativeInputEvent.ALT_MASK) != 0) b.append("Alt+");
        if ((m & NativeInputEvent.SHIFT_MASK) != 0) b.append("Shift+");
        if ((m & NativeInputEvent.META_MASK) != 0) b.append("Win+");
        b.append(NAMES.getOrDefault(code, "Key" + code));
        return b.toString();
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int code = e.getKeyCode();
        if (isModifier(code)) return;
        long now = System.currentTimeMillis();
        Long last = held.put(code, now);
        if (last != null && now - last < REPEAT_MS) return; // auto-repeat while the key is held down
        Consumer<String> c = capture;
        if (c != null) {
            capture = null;
            c.accept(code == NativeKeyEvent.VC_ESCAPE ? null : describe(code, e.getModifiers()));
            return;
        }
        int mods = normMods(e.getModifiers());
        Binding best = null;
        synchronized (this) {
            for (Binding b : bindings)
                if (b.code == code && (mods & b.mods) == b.mods && (best == null || Integer.bitCount(b.mods) > Integer.bitCount(best.mods))) best = b;
        }
        if (best != null) {
            Thread t = new Thread(best.action, "hotkey");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) { held.remove(e.getKeyCode()); }
}
