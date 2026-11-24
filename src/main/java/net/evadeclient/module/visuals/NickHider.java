package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Hides your real username everywhere it's drawn on your screen (chat, tab list, nametags, scoreboard), can show
 * other players under aliases, and can swap your skin, your cape and other players' skins. Local only: nothing is
 * sent anywhere, and other players still see everything as it really is.
 *
 * <p>Names are swapped where text comes from, not where it is drawn: in literal text components
 * ({@code PlayerLiteralTextMixin}), plain-string arguments of translated messages, and the few places that draw
 * or measure a raw {@code String}. Wrapping, trimming and drawing therefore all see the same string, so a name can
 * never be split across lines or cut by indices that belonged to the other string. Only drawing is affected:
 * {@code Text.getString()} keeps the real text, so nothing sent back to the server (sign edits, anvil names,
 * chat) ever carries a nickname or an alias.
 *
 * <p>Skins are swapped in {@code PlayerListEntryMixin}: the tab list, the player model and its head all read the
 * skin from the player's list entry ({@code AbstractClientPlayerEntity.getSkin} delegates to it).
 */
public class NickHider extends Module {
    private static final String CUSTOM_FILE = "Custom File";

    public final StringSetting nick = add(new StringSetting("Nickname", "You", ".nick <name>").maxLength(16)
            .describe("The name shown instead of yours, up to 16 characters."));
    public final ModeSetting ownSkin = add(new ModeSetting("Own Skin", "Real", "Real", "Default", "Steve", "Alex", CUSTOM_FILE)
            .describe("Your skin on your screen: your real one, a default skin, Steve, Alex, or your own skin.png."));
    public final ModeSetting customModel = add(new ModeSetting("Custom Model", "Wide", "Wide", "Slim")
            .describe("Arm width for Own Skin: Custom File. Slim is the thinner Alex-style model."));
    public final StatusSetting skinFile = add((StatusSetting) new StatusSetting("Skin File", this::skinFileStatus,
            "click: open folder").onClick(this::openSkinFolder)
            .describe("Own Skin: Custom File reads config/evade/skin.png (64x64, or a classic 64x32). Click to open the folder."));
    public final BooleanSetting hideCape = add(new BooleanSetting("Hide Cape", false)
            .describe("Hides your cape, and the cape pattern on your elytra, on your screen."));
    public final BooleanSetting hideOthers = add(new BooleanSetting("Hide Others", false)
            .describe("Shows other players' names as aliases on your screen, so viewers can't see who you play with."));
    public final ModeSetting othersStyle = add(new ModeSetting("Others Style", "Numbered", "Generic", "Numbered", "Hashed")
            .describe("Hidden names as Player, Player 3 (in the order you met them) or Player-7F3A (the same each time)."));
    public final BooleanSetting hideOthersSkins = add(new BooleanSetting("Hide Others' Skins", false)
            .describe("Shows other players with default skins and no capes, on your screen only."));
    public final BooleanSetting ignoreCase = add(new BooleanSetting("Ignore Case", true)
            .describe("Also catches names typed with other capitals, like notch or NOTCH."));
    public final BooleanSetting wholeWord = add(new BooleanSetting("Whole Word Only", true)
            .describe("Only swaps whole names, so Max never turns Maximum into Youimum. Off also swaps inside longer words."));
    /** Per-install secret behind "Hashed" aliases, so nobody else can work out which player an alias belongs to. */
    private final StringSetting aliasSeed = add(new StringSetting("Alias Seed", "", "").hidden());

    /** Characters a Minecraft name is made of: a whole-word match must not continue into one of them. */
    private static boolean isWord(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }

    private static NickHider instance;

    // ------------------------------------------------------------------ text state

    /** What is swapped right now; rebuilt when the session name, nickname, a flag or the alias table changes. */
    private volatile Swap swap;
    /** Other players' aliases, rebuilt on the client thread when someone new shows up (see {@link #updateSeen}). */
    private volatile Aliases aliases;
    /** Results for recently swapped strings; only touched on the client thread (see {@link #replace}). */
    private final LinkedHashMap<String, String> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private static final int CACHE_SIZE = 256, CACHE_MAX_LENGTH = 2048;
    private Swap cacheFor;

    /** Everyone this connection has shown (never shrinks, so old chat lines stay hidden after someone leaves). */
    private final LinkedHashMap<UUID, Seen> seen = new LinkedHashMap<>();
    private WeakReference<ClientPlayNetworkHandler> seenFor = new WeakReference<>(null);
    private int seenListSize = -1, nextNumber = 1;
    private record Seen(String name, int number) {}

    /** Other players' real names (as received) mapped to the alias shown for them, in one style. */
    private record Aliases(Map<String, String> byName, String style) {}

    /** What the chat was last re-wrapped for (chat lines are wrapped once, then cached). */
    private String chatKey;
    private boolean chatRefreshPending;

    public NickHider() {
        super("Nick Hider", "Masks your name and skin on your screen for streaming, and can hide other players' too.",
                Category.VISUALS);
    }

    public static NickHider active() {
        NickHider n = instance;
        if (n == null) {
            if (EvadeClient.modules == null) return null;
            n = instance = EvadeClient.modules.get(NickHider.class);
            if (n == null) return null;
        }
        return n.isEnabled() ? n : null;
    }

    // ------------------------------------------------------------------ text

    /**
     * Swap your username for the nickname, and (with Hide Others) other players' names for their aliases, in a
     * piece of text that is about to be drawn or measured. Only on the client thread, so the integrated server
     * (singleplayer) never sees a nickname.
     */
    public static String replace(String text) {
        if (text == null || text.isEmpty()) return text;
        NickHider n = active();
        if (n == null) return text;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || !mc.isOnThread()) return text;
        if (n.hideOthers.get()) n.noticeNewPlayers(mc);
        Swap s = n.swap(mc);
        if (s.idle() || text.length() < s.minLength || !s.mayMatch(text)) return text;
        if (text.length() > CACHE_MAX_LENGTH) return s.apply(text);
        if (n.cacheFor != s) {
            n.cache.clear();
            n.cacheFor = s;
        }
        String out = n.cache.get(text);
        if (out == null) {
            out = s.apply(text);
            n.cache.put(text, out);
        }
        return out;
    }

    /**
     * The swap rules on their own, without the module or a world: {@code text} with {@code realName} shown as
     * {@code nickname} and every name in {@code otherAliases} shown as its alias. For previews and tests.
     */
    public static String swapText(String text, String realName, String nickname, boolean ignoreCase,
                                  boolean wholeWordOnly, Map<String, String> otherAliases) {
        if (text == null || text.isEmpty()) return text;
        Swap s = Swap.build(realName == null ? "" : realName, nickname, ignoreCase, wholeWordOnly,
                otherAliases == null ? null : new Aliases(otherAliases, ""));
        return s.idle() || text.length() < s.minLength || !s.mayMatch(text) ? text : s.apply(text);
    }

    /**
     * A plain-string argument of a translated message ({@code "%s joined"} with a string instead of a text), which
     * shows the nickname when drawn and the real text everywhere else. Built once per translation and cached by
     * the game, so it decides on every visit rather than when it is made.
     */
    public static StringVisitable translationArgument(String raw) {
        return new StringVisitable() {
            @Override
            public <T> Optional<T> visit(StringVisitable.Visitor<T> visitor) {
                return visitor.accept(raw);
            }

            @Override
            public <T> Optional<T> visit(StringVisitable.StyledVisitor<T> styledVisitor, Style style) {
                return styledVisitor.accept(style, replace(raw));
            }

            @Override
            public String toString() {
                return "nick_arg{" + raw + "}";
            }
        };
    }

    /** The swap for the current session name, nickname, flags and aliases. */
    private Swap swap(MinecraftClient mc) {
        var session = mc.getSession();
        String real = session == null || session.getUsername() == null ? "" : session.getUsername();
        String raw = nick.get();
        boolean ic = ignoreCase.get(), ww = wholeWord.get();
        Aliases a = hideOthers.get() ? aliases : null;
        Swap s = swap;
        if (s != null && s.aliases == a && s.ignoreCase == ic && s.wholeWord == ww
                && (s.real == real || s.real.equals(real)) && (s.rawNick == raw || s.rawNick.equals(raw))) return s;
        s = Swap.build(real, raw, ic, ww, a);
        swap = s;
        return s;
    }

    /**
     * The nickname as it is drawn: no formatting codes (they would recolour the rest of the line) or control
     * characters, at most 16 characters like {@code .nick}, and "You" when nothing is left.
     */
    static String clean(String nick) {
        if (nick == null) return "You";
        StringBuilder b = new StringBuilder(nick.length());
        nick.codePoints().filter(c -> c != '§' && !Character.isISOControl(c)).forEach(b::appendCodePoint);
        String s = b.toString().strip();
        if (s.length() > 16) s = s.substring(0, Character.isHighSurrogate(s.charAt(15)) ? 15 : 16);
        return s.isEmpty() ? "You" : s;
    }

    /** ASCII-only lower case, the same folding the matcher uses (names are ASCII). */
    static String lowerAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] out = s.toCharArray();
                for (int j = i; j < out.length; j++) out[j] = lower(out[j]);
                return new String(out);
            }
        }
        return s;
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    /** Case-insensitive (ASCII, like the matcher) indexOf without allocating: the cheap test before the scan. */
    static int indexOfIgnoreCase(String text, String needle) {
        int n = needle.length(), last = text.length() - n;
        if (n == 0) return 0;
        char first = lower(needle.charAt(0));
        for (int i = 0; i <= last; i++) {
            if (lower(text.charAt(i)) == first && text.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }

    /**
     * Everything one swap needs, immutable so the render thread can use it while a new one is built. Names are found
     * by one left-to-right scan: your own name by comparing at each possible start, other players' names by a hash
     * of the word (or window) at that start, so the cost doesn't grow with the number of players. The longest name
     * at a position wins, and swapped text is never scanned again (an alias or nickname can't be swapped twice).
     */
    private static final class Swap {
        private static final int[] POW31 = new int[18];

        static {
            POW31[0] = 1;
            for (int i = 1; i < POW31.length; i++) POW31[i] = POW31[i - 1] * 31;
        }

        final String real, rawNick, shown;
        final boolean ignoreCase, wholeWord, own;
        final Aliases aliases;
        /** Other names (lower case when ignoring case) and their aliases; empty when no one else is hidden. */
        final Map<String, String> others;
        final HashSet32 hashes;
        /** The distinct lengths of other names, longest first, and their range. */
        final int[] lengths;
        final int otherMin, otherMax, minLength;

        private Swap(String real, String rawNick, String shown, boolean ignoreCase, boolean wholeWord, boolean own,
                     Aliases aliases, Map<String, String> others) {
            this.real = real;
            this.rawNick = rawNick;
            this.shown = shown;
            this.ignoreCase = ignoreCase;
            this.wholeWord = wholeWord;
            this.own = own;
            this.aliases = aliases;
            this.others = others;
            this.hashes = new HashSet32(others.keySet());
            TreeSet<Integer> lens = new TreeSet<>(Collections.reverseOrder());
            for (String k : others.keySet()) lens.add(k.length());
            this.lengths = lens.stream().mapToInt(Integer::intValue).toArray();
            this.otherMin = lens.isEmpty() ? Integer.MAX_VALUE : lens.last();
            this.otherMax = lens.isEmpty() ? 0 : lens.first();
            this.minLength = Math.min(own ? real.length() : Integer.MAX_VALUE, otherMin);
        }

        static Swap build(String real, String rawNick, boolean ic, boolean ww, Aliases aliases) {
            String raw = rawNick == null ? "" : rawNick;
            Map<String, String> others = new HashMap<>();
            if (aliases != null) {
                for (Map.Entry<String, String> e : aliases.byName().entrySet()) {
                    String name = e.getKey(), alias = e.getValue();
                    if (name == null || alias == null || name.isEmpty() || name.length() >= POW31.length) continue;
                    String key = ic ? lowerAscii(name) : name;
                    if (!real.isEmpty() && (ic ? key.equalsIgnoreCase(real) : key.equals(real))) continue;
                    others.putIfAbsent(key, alias);
                }
            }
            others = others.isEmpty() ? Map.of() : others;
            boolean own = !real.isEmpty();
            String shown = clean(raw);
            // A nickname longer than your name must not contain it (it would grow if a swapped string were ever
            // swapped again): it falls back to "You", and to no swap at all when even that contains your name.
            if (own && shown.length() > real.length() && new Swap(real, raw, shown, ic, ww, true, aliases, Map.of()).ownMatchIn(shown)) {
                shown = "You";
                own = !new Swap(real, raw, shown, ic, ww, true, aliases, Map.of()).ownMatchIn(shown);
            }
            return new Swap(real, raw, shown, ic, ww, own, aliases, others);
        }

        boolean idle() {
            return !own && others.isEmpty();
        }

        private boolean ownMatchIn(String text) {
            for (int i = 0; i < text.length(); i++) if (ownAt(text, i)) return true;
            return false;
        }

        /** Whether your own name starts at {@code i} (whole word when that is on). */
        private boolean ownAt(String text, int i) {
            int n = real.length();
            return i + n <= text.length() && (!wholeWord || startOk(text, i))
                    && text.regionMatches(ignoreCase, i, real, 0, n) && (!wholeWord || endOk(text, i + n));
        }

        /**
         * A name may start at {@code i} unless it would continue a word. A formatting code right before it
         * ("§aNotch") doesn't count as part of the word.
         */
        private static boolean startOk(String text, int i) {
            return i == 0 || !isWord(text.charAt(i - 1)) || i >= 2 && text.charAt(i - 2) == '§';
        }

        private static boolean endOk(String text, int end) {
            return end >= text.length() || !isWord(text.charAt(end));
        }

        /** A character that can begin a prefixed name (".Steve" for players joining from other editions). */
        private static boolean prefix(char c) {
            return c > ' ' && c <= '~' && !isWord(c);
        }

        private static int wordEnd(String text, int i) {
            int n = text.length();
            while (i < n && isWord(text.charAt(i))) i++;
            return i;
        }

        private int hash(String text, int from, int to) {
            int h = 0;
            for (int i = from; i < to; i++) {
                char c = text.charAt(i);
                h = 31 * h + (ignoreCase ? lower(c) : c);
            }
            return h;
        }

        /** The alias for the other player named {@code text[from, to)}, or null. */
        private String lookup(String text, int from, int to, int hash) {
            if (!hashes.contains(hash)) return null;
            String key = text.substring(from, to);
            return others.get(ignoreCase ? lowerAscii(key) : key);
        }

        private boolean otherWord(String text, int from, int to) {
            int len = to - from;
            return len >= otherMin && len <= otherMax && hashes.contains(hash(text, from, to));
        }

        /** Cheap test before the scan: false means nothing in {@code text} can be swapped. */
        boolean mayMatch(String text) {
            if (own && (ignoreCase ? indexOfIgnoreCase(text, real) : text.indexOf(real)) >= 0) return true;
            if (others.isEmpty()) return false;
            if (!wholeWord) return true; // anything may hide a name: the result cache keeps this cheap
            int n = text.length();
            for (int i = 0; i < n; ) {
                if (!isWord(text.charAt(i))) {
                    i++;
                    continue;
                }
                int e = wordEnd(text, i);
                if (otherWord(text, i, e)) return true;
                if (i >= 1 && text.charAt(i - 1) == '§' && otherWord(text, i + 1, e)) return true;
                if (i >= 1 && prefix(text.charAt(i - 1)) && startOk(text, i - 1) && otherWord(text, i - 1, e)) return true;
                i = e;
            }
            return false;
        }

        /** {@code text} with every name swapped; the same instance when nothing was. */
        String apply(String text) {
            int n = text.length();
            boolean hasOthers = !others.isEmpty();
            int[] prefixHash = null;
            if (hasOthers && !wholeWord) {
                prefixHash = new int[n + 1];
                for (int i = 0; i < n; i++) {
                    char c = text.charAt(i);
                    prefixHash[i + 1] = prefixHash[i] * 31 + (ignoreCase ? lower(c) : c);
                }
            }
            StringBuilder out = null;
            int copied = 0;
            for (int i = 0; i < n; ) {
                if (wholeWord && !startOk(text, i)) {
                    i++;
                    continue;
                }
                int len = 0;
                String rep = null;
                if (own && ownAt(text, i)) {
                    len = real.length();
                    rep = shown;
                }
                if (hasOthers) {
                    if (wholeWord) {
                        char c = text.charAt(i);
                        int word = isWord(c) ? i : prefix(c) && i + 1 < n && isWord(text.charAt(i + 1)) ? i + 1 : -1;
                        if (word >= 0) {
                            int e = wordEnd(text, word), l = e - i;
                            if (l > len && l >= otherMin && l <= otherMax) {
                                String a = lookup(text, i, e, hash(text, i, e));
                                if (a != null) {
                                    len = l;
                                    rep = a;
                                }
                            }
                        }
                    } else {
                        for (int l : lengths) {
                            if (l <= len) break;
                            if (i + l > n) continue;
                            String a = lookup(text, i, i + l, prefixHash[i + l] - prefixHash[i] * POW31[l]);
                            if (a != null) {
                                len = l;
                                rep = a;
                                break;
                            }
                        }
                    }
                }
                if (rep == null) {
                    i++;
                    continue;
                }
                if (out == null) out = new StringBuilder(n + 16);
                out.append(text, copied, i).append(rep);
                i += len;
                copied = i;
            }
            return out == null ? text : out.append(text, copied, n).toString();
        }
    }

    /** An open-addressing set of string hashes: the allocation-free "could this be a name?" test. */
    private static final class HashSet32 {
        private final int[] keys;
        private final boolean[] used;
        private final int mask;

        HashSet32(Collection<String> strings) {
            int cap = Integer.highestOneBit(Math.max(8, strings.size() * 2)) << 1;
            keys = new int[cap];
            used = new boolean[cap];
            mask = cap - 1;
            for (String s : strings) add(s.hashCode());
        }

        private static int slot(int h) {
            int x = h * 0x9E3779B9;
            return x ^ (x >>> 16);
        }

        private void add(int h) {
            int i = slot(h) & mask;
            while (used[i]) {
                if (keys[i] == h) return;
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = h;
        }

        boolean contains(int h) {
            int i = slot(h) & mask;
            while (used[i]) {
                if (keys[i] == h) return true;
                i = (i + 1) & mask;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ other players

    /**
     * Called before every swap while Hide Others is on: picks up players as soon as the list changes size, so a
     * join message is already wrapped with the new player's alias. Same-size changes are caught on the next tick.
     */
    private void noticeNewPlayers(MinecraftClient mc) {
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh == null) {
            if (!seen.isEmpty() || aliases != null) forgetPlayers();
            return;
        }
        Aliases a = aliases;
        if (seenFor.get() != nh || nh.getPlayerList().size() != seenListSize || a == null || !a.style().equals(othersStyle.get())) {
            updateSeen(mc, nh, false);
        }
    }

    /**
     * Records the players this connection has shown and republishes the aliases when one was new. Your own entry,
     * names that can't be player names and names made only of digits (they would hide numbers on screen) are left
     * out. Returns whether anyone new was added.
     */
    private boolean updateSeen(MinecraftClient mc, ClientPlayNetworkHandler nh, boolean withWorld) {
        boolean added = false;
        if (seenFor.get() != nh) {
            seen.clear();
            nextNumber = 1;
            seenFor = new WeakReference<>(nh);
            aliases = null;
        }
        Collection<PlayerListEntry> list = nh.getPlayerList();
        for (PlayerListEntry e : list) {
            GameProfile p = e.getProfile();
            if (p != null && see(p.id(), p.name())) added = true;
        }
        if (withWorld && mc.world != null) {
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (see(p.getUuid(), p.getGameProfile().name())) added = true;
            }
        }
        seenListSize = list.size();
        Aliases a = aliases;
        if (added || a == null || !a.style().equals(othersStyle.get())) publishAliases();
        return added;
    }

    private boolean see(UUID id, String name) {
        if (id == null || name == null || !otherName(name) || isSelf(id)) return false;
        Seen old = seen.get(id);
        if (old != null) {
            if (old.name().equals(name)) return false;
            seen.put(id, new Seen(name, old.number()));
            return true;
        }
        seen.put(id, new Seen(name, nextNumber++));
        return true;
    }

    /** A name worth hiding: 3 to 16 name characters with at least one letter, optionally after one prefix symbol. */
    static boolean otherName(String name) {
        int n = name.length(), start = n > 0 && Swap.prefix(name.charAt(0)) ? 1 : 0;
        if (n - start < 3 || n - start > 16) return false;
        boolean letter = false;
        for (int i = start; i < n; i++) {
            char c = name.charAt(i);
            if (!isWord(c)) return false;
            if (c < '0' || c > '9') letter = true;
        }
        return letter;
    }

    private void publishAliases() {
        String style = othersStyle.get();
        Map<String, String> byName = new HashMap<>(Math.max(16, seen.size() * 2));
        if (style.equals("Hashed")) {
            long seed = seed();
            Set<Integer> used = new HashSet<>();
            for (Map.Entry<UUID, Seen> e : seen.entrySet()) {
                int code = hash16(e.getKey(), seed);
                // Two players on one code would look like one: the later one takes the next free code.
                while (!used.add(code) && used.size() < 0x10000) code = (code + 1) & 0xFFFF;
                byName.putIfAbsent(e.getValue().name(), String.format(Locale.ROOT, "Player-%04X", code));
            }
        } else {
            boolean generic = style.equals("Generic");
            for (Seen s : seen.values()) byName.putIfAbsent(s.name(), generic ? "Player" : "Player " + s.number());
        }
        aliases = new Aliases(Collections.unmodifiableMap(byName), style);
    }

    private void forgetPlayers() {
        seen.clear();
        nextNumber = 1;
        seenListSize = -1;
        seenFor = new WeakReference<>(null);
        aliases = null;
    }

    /** 16 bits of the player's UUID mixed with this install's secret seed: stable for you, meaningless to others. */
    static int hash16(UUID id, long seed) {
        long x = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 29) ^ seed;
        x = (x ^ (x >>> 33)) * 0xFF51AFD7ED558CCDL;
        x = (x ^ (x >>> 33)) * 0xC4CEB9FE1A85EC53L;
        x ^= x >>> 33;
        return (int) (x >>> 48);
    }

    private long seed() {
        String s = aliasSeed.get();
        if (s != null && s.length() == 16) {
            try {
                return Long.parseUnsignedLong(s, 16);
            } catch (NumberFormatException ignored) {
                // made up below
            }
        }
        long v = new SecureRandom().nextLong();
        aliasSeed.set(String.format(Locale.ROOT, "%016x", v));
        return v;
    }

    // ------------------------------------------------------------------ skins

    private static final SkinTextures STEVE = new SkinTextures(
            new AssetInfo.TextureAssetInfo(Identifier.ofVanilla("entity/player/wide/steve")), null, null, PlayerSkinType.WIDE, true);
    private static final SkinTextures ALEX = new SkinTextures(
            new AssetInfo.TextureAssetInfo(Identifier.ofVanilla("entity/player/slim/alex")), null, null, PlayerSkinType.SLIM, true);
    private static final Identifier CUSTOM_ID = Identifier.of("evade", "nick_hider/custom_skin");
    private static final SkinTextures CUSTOM_WIDE = new SkinTextures(
            new AssetInfo.TextureAssetInfo(CUSTOM_ID, CUSTOM_ID), null, null, PlayerSkinType.WIDE, true);
    private static final SkinTextures CUSTOM_SLIM = new SkinTextures(
            new AssetInfo.TextureAssetInfo(CUSTOM_ID, CUSTOM_ID), null, null, PlayerSkinType.SLIM, true);
    private static final long MAX_SKIN_BYTES = 8L << 20;

    /** The custom skin texture while one is registered (client thread). */
    private NativeImageBackedTexture customTexture;
    /** Modification time and size of the skin.png last loaded or tried, so it is only read again once it changes. */
    private long customStamp = Long.MIN_VALUE;
    private int customCheckIn;
    private String lastSkinProblem;
    /** The Skin File row: probed at most once a second while something shows it. */
    private String fileStatus = "";
    private long fileStatusAt;
    /** The last skin whose cape was hidden, and the result (the skin supplier hands back the same instance). */
    private SkinTextures capedSkin, capelessSkin;

    /** Whether {@code id} is you: your player in the world, or your account (they differ behind some proxies). */
    public static boolean isSelf(UUID id) {
        if (id == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return false;
        if (mc.player != null && id.equals(mc.player.getUuid())) return true;
        return mc.getSession() != null && mc.uuidEquals(id);
    }

    /** The skin to show for the player {@code id} (only ever on your screen). */
    public static SkinTextures skin(UUID id, SkinTextures original) {
        NickHider n = active();
        if (n == null || id == null || original == null) return original;
        if (isSelf(id)) return n.ownSkinFor(id, original);
        return n.hideOthersSkins.get() ? DefaultSkinHelper.getSkinTextures(id) : original;
    }

    private SkinTextures ownSkinFor(UUID id, SkinTextures original) {
        SkinTextures s = switch (ownSkin.get()) {
            case "Default" -> DefaultSkinHelper.getSkinTextures(id);
            case "Steve" -> STEVE;
            case "Alex" -> ALEX;
            // Until skin.png has loaded (or when it can't be), a default skin rather than the real one.
            case CUSTOM_FILE -> customTexture == null ? DefaultSkinHelper.getSkinTextures(id)
                    : customModel.is("Slim") ? CUSTOM_SLIM : CUSTOM_WIDE;
            default -> original;
        };
        if (s != original || !hideCape.get() || original.cape() == null && original.elytra() == null) return s;
        SkinTextures caped = capedSkin, capeless = capelessSkin;
        if (caped == original && capeless != null) return capeless;
        capeless = new SkinTextures(original.body(), null, null, original.model(), original.secure());
        capedSkin = original;
        capelessSkin = capeless;
        return capeless;
    }

    private static Path skinPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("skin.png");
    }

    /** Polls skin.png while Own Skin is Custom File (loading it when it changes) and frees it otherwise. */
    private void tickCustomSkin() {
        if (!ownSkin.is(CUSTOM_FILE)) {
            if (customTexture != null || customStamp != Long.MIN_VALUE) releaseCustomSkin();
            return;
        }
        if (--customCheckIn > 0) return;
        customCheckIn = 20;
        Path file = skinPath();
        long stamp;
        try {
            stamp = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file) : -1;
        } catch (IOException e) {
            stamp = -1;
        }
        if (stamp == customStamp) return;
        customStamp = stamp;
        fileStatusAt = 0;
        if (stamp == -1) {
            dropCustomTexture();
            skinProblem("Put your skin in config/evade as skin.png");
            return;
        }
        String problem = loadCustomSkin(file);
        if (problem != null) {
            dropCustomTexture();
            skinProblem(problem);
        } else if (lastSkinProblem != null) {
            // It was missing or broken before: say that the fix worked.
            lastSkinProblem = null;
            Notifications.push("Nick Hider", "Now using your skin.png", true);
        }
    }

    /** Reads, checks and uploads skin.png; returns what is wrong with it, or null once it is in use. */
    private String loadCustomSkin(Path file) {
        NativeImage image = null;
        try {
            if (Files.size(file) > MAX_SKIN_BYTES) return "skin.png is too large to be a skin";
            image = NativeImage.read(Files.readAllBytes(file));
            int w = image.getWidth(), h = image.getHeight();
            if (!skinSize(w, h)) return "skin.png is " + w + "x" + h + ": a skin is 64x64 or 64x32";
            image = toModernLayout(image);
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "Evade Nick Hider skin", image);
            image = null; // owned by the texture now
            mc.getTextureManager().registerTexture(CUSTOM_ID, texture); // closes the one it replaces
            customTexture = texture;
            return null;
        } catch (IOException | RuntimeException e) {
            EvadeClient.LOG.warn("Nick Hider couldn't read {}", file, e);
            return "skin.png couldn't be read as a PNG image";
        } finally {
            if (image != null) image.close();
        }
    }

    /** 64x64 and classic 64x32 skins, and their HD versions (sides a multiple of 64, up to 1024). */
    static boolean skinSize(int w, int h) {
        return w >= 64 && w <= 1024 && w % 64 == 0 && (h == w || h * 2 == w);
    }

    /**
     * What the game does to a downloaded skin: a classic 64x32 skin gets its left arm and leg mirrored into the
     * modern layout, and the base layer is made opaque (only the outer layer may be see-through). Scaled for HD.
     */
    private static NativeImage toModernLayout(NativeImage image) {
        int w = image.getWidth(), s = w / 64;
        boolean classic = image.getHeight() * 2 == w;
        if (classic) {
            NativeImage full = new NativeImage(w, w, true);
            full.copyFrom(image);
            image.close();
            image = full;
            full.fillRect(0, 32 * s, 64 * s, 32 * s, 0);
            int[][] rects = {
                    {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
                    {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
                    {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};
            for (int[] r : rects) full.copyRect(r[0] * s, r[1] * s, r[2] * s, r[3] * s, r[4] * s, r[5] * s, true, false);
        }
        opaque(image, 0, 0, 32 * s, 16 * s);
        if (classic) clearIfOpaque(image, 32 * s, 0, 64 * s, 32 * s);
        opaque(image, 0, 16 * s, 64 * s, 32 * s);
        opaque(image, 16 * s, 48 * s, 48 * s, 64 * s);
        return image;
    }

    private static void opaque(NativeImage image, int x1, int y1, int x2, int y2) {
        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) image.setColorArgb(x, y, image.getColorArgb(x, y) | 0xFF000000);
        }
    }

    /** A classic skin's hat layer drawn fully opaque was never meant as a hat: make it see-through, like the game. */
    private static void clearIfOpaque(NativeImage image, int x1, int y1, int x2, int y2) {
        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) if ((image.getColorArgb(x, y) >>> 24) < 128) return;
        }
        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) image.setColorArgb(x, y, image.getColorArgb(x, y) & 0xFFFFFF);
        }
    }

    private void skinProblem(String problem) {
        if (problem.equals(lastSkinProblem)) return;
        lastSkinProblem = problem;
        Notifications.push("Nick Hider", problem, false);
    }

    private void dropCustomTexture() {
        if (customTexture == null) return;
        customTexture = null;
        mc.getTextureManager().destroyTexture(CUSTOM_ID);
    }

    private void releaseCustomSkin() {
        dropCustomTexture();
        customStamp = Long.MIN_VALUE;
        customCheckIn = 0;
        fileStatusAt = 0;
    }

    /** The Skin File row: whether skin.png is there and usable, and whether it is on your player right now. */
    private String skinFileStatus() {
        long now = System.currentTimeMillis();
        if (now - fileStatusAt < 1000) return fileStatus;
        fileStatusAt = now;
        String status;
        Path file = skinPath();
        try {
            if (!Files.isRegularFile(file)) {
                status = "No skin.png yet";
            } else {
                int[] size = pngSize(file);
                if (size == null) status = "skin.png isn't a PNG";
                else if (!skinSize(size[0], size[1])) status = "skin.png is " + size[0] + "x" + size[1];
                else status = (customTexture != null && isEnabled() ? "In use: " : "Found: ") + size[0] + "x" + size[1];
            }
        } catch (IOException | RuntimeException e) {
            status = "skin.png can't be read";
        }
        return fileStatus = status;
    }

    /** Width and height from a PNG header, or null when the file isn't a PNG. */
    private static int[] pngSize(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(24);
            if (b.length < 24 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G'
                    || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') return null;
            return new int[]{readInt(b, 16), readInt(b, 20)};
        }
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    private void openSkinFolder() {
        Path dir = skinPath().getParent();
        try {
            Files.createDirectories(dir);
            Util.getOperatingSystem().open(dir.toFile());
        } catch (IOException | RuntimeException e) {
            EvadeClient.LOG.warn("Couldn't open {}", dir, e);
        }
        fileStatusAt = 0;
        customCheckIn = 0;
        if (!Files.isRegularFile(skinPath())) Notifications.push("Nick Hider", "Save your skin in this folder as skin.png", true);
    }

    // ------------------------------------------------------------------ config

    /** "Hide Skin" (on/off) became Own Skin: on means the default skin, as before. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (settings.has(ownSkin.getName())) return;
        JsonElement old = settings.get("Hide Skin");
        if (old == null) return;
        try {
            ownSkin.set(old.getAsBoolean() ? "Default" : "Real");
        } catch (RuntimeException ignored) {
            // unreadable: keep the default
        }
    }

    // ------------------------------------------------------------------ chat

    /**
     * Chat lines are wrapped once when they arrive and then cached, so re-wrap them whenever what is hidden
     * changes; otherwise old lines would keep a real name (or an old nickname) until the chat is resized.
     */
    private void refreshChat() {
        chatKey = currentKey();
        chatRefreshPending = false;
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().reset();
    }

    private String currentKey() {
        var session = mc.getSession();
        return (session == null ? "" : session.getUsername()) + "\n" + nick.get() + "\n" + ignoreCase.get() + wholeWord.get()
                + "\n" + (hideOthers.get() ? othersStyle.get() : "-");
    }

    @Override
    protected void onEnable() {
        refreshChat();
        customCheckIn = 0;
    }

    @Override
    protected void onDisable() {
        refreshChat();
        releaseCustomSkin();
        cache.clear();
        cacheFor = null;
        capedSkin = capelessSkin = null;
    }

    @Override
    public void onTick() {
        if (hideOthers.get()) {
            ClientPlayNetworkHandler nh = mc.getNetworkHandler();
            // Someone the size check missed (a swap in the same packet, a player only seen in the world):
            // their name may already be in wrapped chat lines.
            if (nh != null && updateSeen(mc, nh, true)) chatRefreshPending = true;
        }
        swap(mc); // build outside rendering when something changed
        if (!currentKey().equals(chatKey)) chatRefreshPending = true;
        // Re-wrapping jumps the chat back to the newest line: wait until it is closed.
        if (chatRefreshPending && !(mc.currentScreen instanceof ChatScreen)) refreshChat();
        tickCustomSkin();
    }

    /** Other players' current aliases by real name, for previews and tests (empty while Hide Others is off). */
    public Map<String, String> currentAliases() {
        Aliases a = hideOthers.get() ? aliases : null;
        return a == null ? Map.of() : a.byName();
    }

    /** Names this connection has aliased, in the order they were first seen (for tests). */
    public List<String> seenNames() {
        List<String> out = new ArrayList<>(seen.size());
        for (Seen s : seen.values()) out.add(s.name());
        return out;
    }
}
