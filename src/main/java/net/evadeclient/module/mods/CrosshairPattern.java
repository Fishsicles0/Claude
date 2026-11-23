package net.evadeclient.module.mods;

import net.evadeclient.command.CommandManager;
import net.evadeclient.setting.StringSetting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The Custom crosshair: a 15 x 15 grid of pixels, centred on the middle of the screen at one GUI pixel per cell
 * (the vanilla crosshair's size).
 *
 * <p>Stored and shared as base64url (no padding) of 29 bytes: the cells row by row from the top left, eight per
 * byte, most significant bit first, with the last byte's 7 spare bits zero. A share code is {@link #PREFIX} followed
 * by that text; anything that doesn't decode to exactly 29 bytes is refused.
 */
public final class CrosshairPattern {
    public static final int SIZE = 15, CENTER = SIZE / 2, CELLS = SIZE * SIZE, BYTES = (CELLS + 7) / 8;
    /** Starts every share code; the digit is the format version. */
    public static final String PREFIX = "EVXH1:";

    private final boolean[] cells = new boolean[CELLS];

    public CrosshairPattern() {}

    public boolean get(int x, int y) {
        return inside(x, y) && cells[y * SIZE + x];
    }

    public void set(int x, int y, boolean on) {
        if (inside(x, y)) cells[y * SIZE + x] = on;
    }

    public static boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < SIZE && y < SIZE;
    }

    /** How many cells are on. */
    public int count() {
        int n = 0;
        for (boolean c : cells) if (c) n++;
        return n;
    }

    public boolean isEmpty() { return count() == 0; }

    public void clear() { Arrays.fill(cells, false); }

    public void invert() {
        for (int i = 0; i < CELLS; i++) cells[i] = !cells[i];
    }

    /** Copy every cell from {@code other}. */
    public void load(CrosshairPattern other) {
        System.arraycopy(other.cells, 0, cells, 0, CELLS);
    }

    public CrosshairPattern copy() {
        CrosshairPattern p = new CrosshairPattern();
        p.load(this);
        return p;
    }

    @Override public boolean equals(Object o) { return o instanceof CrosshairPattern p && Arrays.equals(cells, p.cells); }
    @Override public int hashCode() { return Arrays.hashCode(cells); }

    // ------------------------------------------------------------------ codes

    /** The 39-character base64url form (what the Pattern setting saves). */
    public String encode() {
        byte[] b = new byte[BYTES];
        for (int i = 0; i < CELLS; i++) if (cells[i]) b[i >> 3] |= (byte) (0x80 >>> (i & 7));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** The share code: {@link #PREFIX} + {@link #encode()}. */
    public String shareCode() { return PREFIX + encode(); }

    /**
     * Decode a bare code (base64url, padding optional; standard base64's '+' and '/' are accepted too).
     *
     * @return the pattern, or null unless the text is exactly 29 bytes of base64
     */
    public static CrosshairPattern decode(String code) {
        if (code == null) return null;
        String s = code.strip().replace('+', '-').replace('/', '_');
        if (s.isEmpty() || s.length() > 44) return null;
        byte[] b;
        try {
            b = Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (b.length != BYTES) return null;
        CrosshairPattern p = new CrosshairPattern();
        for (int i = 0; i < CELLS; i++) p.cells[i] = (b[i >> 3] & (0x80 >>> (i & 7))) != 0;
        return p;
    }

    /**
     * Read a pasted share code: whitespace anywhere is ignored, and the code may sit inside other text (a chat
     * message, a forum post). A bare code without the prefix is accepted too.
     *
     * @return the pattern, or null if the text holds no valid crosshair code
     */
    public static CrosshairPattern parse(String text) {
        if (text == null) return null;
        int at = -1;
        for (int i = 0; i + PREFIX.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, PREFIX, 0, PREFIX.length())) { at = i; break; }
        }
        if (at < 0) {
            StringBuilder b = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) b.append(text.charAt(i));
            return decode(b.toString());
        }
        // After the prefix: the code's 39 characters, even if a line break or a space split it, and nothing more
        // (so text right after it doesn't stick to it).
        StringBuilder b = new StringBuilder(CODE_LENGTH);
        for (int i = at + PREFIX.length(); i < text.length() && b.length() < CODE_LENGTH; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (!isCodeChar(c)) break;
            b.append(c);
        }
        return b.length() == CODE_LENGTH ? decode(b.toString()) : null;
    }

    /** Characters in a code without padding: 29 bytes of base64. */
    private static final int CODE_LENGTH = (BYTES * 4 + 2) / 3;

    private static boolean isCodeChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '+' || c == '/';
    }

    // ------------------------------------------------------------------ drawing helpers

    /**
     * The on cells of a {@code w x h} mask as few rectangles as a simple sweep finds: each row's runs, merged
     * downwards while the run below spans exactly the same columns. Packed as x1, y1, x2, y2 (exclusive) in mask
     * coordinates, ready for {@code DrawContext.fill}.
     */
    public static int[] rects(boolean[] mask, int w, int h) {
        List<int[]> done = new ArrayList<>();
        List<int[]> open = new ArrayList<>(), next = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            next.clear();
            int x = 0;
            while (x < w) {
                if (!mask[y * w + x]) { x++; continue; }
                int x1 = x;
                while (x < w && mask[y * w + x]) x++;
                int[] r = null;
                for (int[] o : open) if (o[0] == x1 && o[2] == x) { r = o; break; }
                if (r == null) { r = new int[]{x1, y, x, y + 1}; done.add(r); }
                else r[3] = y + 1;
                next.add(r);
            }
            List<int[]> t = open; open = next; next = t;
        }
        int[] out = new int[done.size() * 4];
        for (int i = 0; i < done.size(); i++) System.arraycopy(done.get(i), 0, out, i * 4, 4);
        return out;
    }

    /** The cells as a {@link #SIZE} x {@link #SIZE} mask (a copy). */
    public boolean[] mask() { return cells.clone(); }

    // ------------------------------------------------------------------ presets

    /** A named starting point in the editor. */
    public record Preset(String name, CrosshairPattern pattern) {}

    public static final List<Preset> PRESETS;

    static {
        List<Preset> p = new ArrayList<>();
        int c = CENTER;
        // Plus: one solid 11 px cross.
        CrosshairPattern plus = new CrosshairPattern();
        for (int i = 2; i <= 12; i++) { plus.set(i, c, true); plus.set(c, i, true); }
        p.add(new Preset("Plus", plus));
        // Gap Cross: four 4 px arms, two pixels clear of the middle.
        CrosshairPattern gap = new CrosshairPattern();
        for (int i = 1; i <= 4; i++) {
            gap.set(c - 2 - i, c, true); gap.set(c + 2 + i, c, true);
            gap.set(c, c - 2 - i, true); gap.set(c, c + 2 + i, true);
        }
        p.add(new Preset("Gap Cross", gap));
        // Cross + Dot: the gap cross with the middle pixel.
        CrosshairPattern crossDot = gap.copy();
        crossDot.set(c, c, true);
        p.add(new Preset("Cross + Dot", crossDot));
        // Dot: 3 x 3.
        CrosshairPattern dot = new CrosshairPattern();
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) dot.set(c + x, c + y, true);
        p.add(new Preset("Dot", dot));
        // Circle: a radius-5 ring around the middle pixel.
        CrosshairPattern circle = new CrosshairPattern();
        for (int y = -c; y <= c; y++) {
            for (int x = -c; x <= c; x++) {
                double d = Math.sqrt(x * x + y * y);
                if (d >= 4.5 && d < 5.5) circle.set(c + x, c + y, true);
            }
        }
        circle.set(c, c, true);
        p.add(new Preset("Circle", circle));
        // X: diagonal arms with a gap.
        CrosshairPattern ex = new CrosshairPattern();
        for (int k = 2; k <= 5; k++) {
            ex.set(c - k, c - k, true); ex.set(c + k, c - k, true);
            ex.set(c - k, c + k, true); ex.set(c + k, c + k, true);
        }
        p.add(new Preset("X", ex));
        // Chevron: a caret opening downwards from just under the middle.
        CrosshairPattern chevron = new CrosshairPattern();
        chevron.set(c, c, true);
        for (int k = 1; k <= 4; k++) { chevron.set(c - k, c + k, true); chevron.set(c + k, c + k, true); }
        p.add(new Preset("Chevron", chevron));
        // Square: a hollow 9 px box with the middle pixel.
        CrosshairPattern square = new CrosshairPattern();
        for (int i = -4; i <= 4; i++) {
            square.set(c + i, c - 4, true); square.set(c + i, c + 4, true);
            square.set(c - 4, c + i, true); square.set(c + 4, c + i, true);
        }
        square.set(c, c, true);
        p.add(new Preset("Square", square));
        PRESETS = List.copyOf(p);
    }

    /** The preset named {@code name}, or the first one. */
    public static CrosshairPattern preset(String name) {
        for (Preset p : PRESETS) if (p.name().equals(name)) return p.pattern().copy();
        return PRESETS.get(0).pattern().copy();
    }

    // ------------------------------------------------------------------ setting

    /**
     * The Pattern setting: saves the bare code, accepts a bare or share code (from chat, a config or a profile
     * code), and shows a short summary in the menu instead of the code itself. Clicking it opens the editor.
     */
    public static final class PatternSetting extends StringSetting {
        private String decodedFor;
        private CrosshairPattern decoded;

        public PatternSetting(String name, CrosshairPattern def, String hint) {
            super(name, def.encode(), hint);
            maxLength(64);
        }

        /** Refuses anything that isn't a crosshair code (a broken saved value then loads as the default). */
        @Override
        public void set(String v) {
            CrosshairPattern p = parse(v);
            if (p == null) {
                throw new CommandManager.CommandError("Not a crosshair code. Copy one in the Crosshair Editor (it starts with " + PREFIX + ").");
            }
            super.set(p.encode());
        }

        /** The saved code (base64url). */
        public String code() { return value; }

        /** The pattern (decoded once per change; do not modify it, {@link CrosshairPattern#copy() copy} it). */
        public CrosshairPattern pattern() {
            String v = value;
            if (decoded == null || !v.equals(decodedFor)) {
                CrosshairPattern p = decode(v);
                decoded = p != null ? p : new CrosshairPattern();
                decodedFor = v;
            }
            return decoded;
        }

        /** What the menu row shows: how many pixels are drawn. The saved value is {@link #code()}. */
        @Override
        public String get() {
            int n = pattern().count();
            return n == 0 ? "Empty" : n + (n == 1 ? " pixel" : " pixels");
        }

        @Override public String display() { return get(); }
    }
}
