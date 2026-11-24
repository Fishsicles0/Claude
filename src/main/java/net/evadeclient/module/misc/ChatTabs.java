package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.chat.ChatLines;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Hypixel;
import net.evadeclient.util.Notifications;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.screen.ChatScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tabs above the chat box: All, plus tabs that show only the lines matching a rule, each with an unread count and its
 * own sound. Ready-made tabs for Hypixel party, guild and private messages and for Evade friends' messages, and three
 * of your own built from a regular expression. A tab only changes which of the lines you already received are shown;
 * nothing is sent, and All always has everything.
 *
 * <p>Hooks: ChatHudV25Mixin (which lines are shown), ChatScreenV25Mixin (the tab bar).
 */
public class ChatTabs extends Module {
    private static final String[] PRESET_MODES = {"Off", "Silent", "Pling", "Bell", "XP"};
    private static final String[] SOUND_MODES = {"Silent", "Pling", "Bell", "XP"};
    private static final int CUSTOM = 3;

    private final ModeSetting party = add(new ModeSetting("Party Tab", "Silent", PRESET_MODES)
            .describe("A tab for Hypixel party chat and Evade party messages, and the sound a new line in it plays."));
    private final ModeSetting guild = add(new ModeSetting("Guild Tab", "Silent", PRESET_MODES)
            .describe("A tab for Hypixel guild and officer chat, and the sound a new line in it plays."));
    private final ModeSetting dms = add(new ModeSetting("DMs Tab", "Pling", PRESET_MODES)
            .describe("A tab for Hypixel private messages (From and To lines), and the sound a new one plays."));
    private final BooleanSetting hypixelOnly = add(new BooleanSetting("Hypixel Tabs Only On Hypixel", true)
            .describe("Show the Party, Guild and DMs tabs only while you're on Hypixel."));
    private final ModeSetting friends = add(new ModeSetting("Evade Friends Tab", "Silent", PRESET_MODES)
            .describe("A tab for messages from your Evade friends and party, and the sound a new one plays."));
    private final StringSetting[] names = new StringSetting[CUSTOM];
    private final StringSetting[] rules = new StringSetting[CUSTOM];
    private final ModeSetting[] sounds = new ModeSetting[CUSTOM];
    private final BooleanSetting badges = add(new BooleanSetting("Unread Badges", true)
            .describe("Show how many new lines a tab has that you haven't looked at."));
    private final NumberSetting volume = add(new NumberSetting("Sound Volume", 0.6, 0.1, 1.0, 0.1)
            .describe("How loud the tab sounds are (at most one a second per tab)."));
    private final BooleanSetting backToAll = add(new BooleanSetting("Back To All On Close", true)
            .describe("Closing chat switches back to All, so no new line is hidden from you while you play."));

    /** One tab: its label, what it matches (plain text, as received) and its sound ("Silent" for none). */
    public record Tab(String name, Pattern rule, String sound) {}

    private List<Tab> tabs = List.of();
    private String tabsKey;
    /** Bumped whenever the tab list changes, so stored tab matches are worked out again. */
    private int version;
    /** The tab being shown: -1 for All, else an index into {@link #tabs()}. */
    private int selected = -1;
    private int[] unread = new int[0];
    private long[] lastSound = new long[0];

    public ChatTabs() {
        super("Chat Tabs", "Tabs above chat for party, guild, private and friend messages, plus your own rules.", Category.MISC);
        for (int i = 0; i < CUSTOM; i++) {
            int n = i + 1;
            names[i] = add(new StringSetting("Tab " + n + " Name", "", "blank turns it off").maxLength(12)
                    .describe("The label of your own tab " + n + ". Leave it blank for no tab."));
            rules[i] = add(new StringSetting("Tab " + n + " Rule", "", "a regular expression").maxLength(200)
                    .describe("Lines matching this regular expression (any case, anywhere in the line) go in tab " + n + "."));
            sounds[i] = add(new ModeSetting("Tab " + n + " Sound", "Silent", SOUND_MODES)
                    .describe("The sound a new line in tab " + n + " plays."));
        }
    }

    public static ChatTabs active() {
        if (EvadeClient.modules == null) return null;
        ChatTabs t = EvadeClient.modules.get(ChatTabs.class);
        return t != null && t.isEnabled() ? t : null;
    }

    @Override
    protected void onDisable() {
        boolean wasFiltering = selected >= 0;
        selected = -1;
        if (wasFiltering) refreshChat();
    }

    // ------------------------------------------------------------------ the tab list

    /** The tabs shown right now (after All), rebuilt when a setting changes. */
    public List<Tab> tabs() {
        boolean onHypixel = Hypixel.connected();
        StringBuilder key = new StringBuilder().append(party.get()).append('|').append(guild.get()).append('|')
                .append(dms.get()).append('|').append(friends.get()).append('|').append(hypixelOnly.get() && !onHypixel);
        for (int i = 0; i < CUSTOM; i++) key.append('|').append(names[i].get()).append('\u0000').append(rules[i].get())
                .append('\u0000').append(sounds[i].get());
        String k = key.toString();
        if (!k.equals(tabsKey)) {
            tabsKey = k;
            List<Tab> out = new ArrayList<>();
            boolean hypixel = !hypixelOnly.get() || onHypixel;
            if (hypixel) {
                preset(out, "Party", party, "^(?:Party >|\\[Evade] Party >)");
                preset(out, "Guild", guild, "^(?:Guild >|Officer >)");
                preset(out, "DMs", dms, "^(?:From|To) (?:\\[[^\\]]{1,20}] )?[A-Za-z0-9_]{1,16}: ");
            } else if (!party.is("Off")) {
                out.add(new Tab("Party", Pattern.compile("^\\[Evade] Party >"), party.get()));
            }
            preset(out, "Friends", friends, "^\\[Evade] ");
            for (int i = 0; i < CUSTOM; i++) {
                String name = names[i].get() == null ? "" : names[i].get().strip();
                String rule = rules[i].get() == null ? "" : rules[i].get().strip();
                if (name.isEmpty() || rule.isEmpty()) continue;
                try {
                    out.add(new Tab(name, Pattern.compile(rule, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), sounds[i].get()));
                } catch (PatternSyntaxException e) {
                    Notifications.push("Chat Tabs", "Tab " + (i + 1) + ": not a valid rule, tab left out", false);
                }
            }
            String selectedName = selected >= 0 && selected < tabs.size() ? tabs.get(selected).name() : null;
            tabs = List.copyOf(out);
            unread = new int[tabs.size()];
            lastSound = new long[tabs.size()];
            version++;
            int was = selected;
            selected = -1;
            for (int i = 0; selectedName != null && i < tabs.size(); i++) if (tabs.get(i).name().equals(selectedName)) selected = i;
            if (was >= 0 && selected < 0) refreshChat();
        }
        return tabs;
    }

    private static void preset(List<Tab> out, String name, ModeSetting mode, String regex) {
        if (mode.is("Off")) return;
        out.add(new Tab(name, Pattern.compile(regex), mode.get()));
    }

    /** Bit i set when the line belongs to tab i (worked out once per tab list). */
    private long bits(ChatLines.Info info, String plain) {
        List<Tab> t = tabs();
        if (info != null && info.tabsVersion == version) return info.tabBits;
        long b = 0;
        for (int i = 0; i < t.size() && i < 64; i++) {
            try {
                if (t.get(i).rule().matcher(plain).find()) b |= 1L << i;
            } catch (RuntimeException ignored) {
                // a pathological pattern never hides a line
            }
        }
        if (info != null) {
            info.tabBits = b;
            info.tabsVersion = version;
        }
        return b;
    }

    // ------------------------------------------------------------------ hooks

    /** From ChatLines as a new message is stored: counts it as unread in its tabs and plays their sounds. */
    public void onNewMessage(ChatLines.Info info) {
        long b = bits(info, info.plain);
        if (b == 0) return;
        boolean own = info.plain.startsWith("To ") || info.plain.startsWith("[Evade] You >") || info.plain.startsWith("[Evade] Party > You:");
        long now = System.currentTimeMillis();
        boolean viewing = mc.currentScreen instanceof ChatScreen;
        for (int i = 0; i < tabs.size() && i < 64; i++) {
            if ((b & (1L << i)) == 0) continue;
            if (!(viewing && selected == i)) unread[i]++;
            String sound = tabs.get(i).sound();
            if (!own && !"Silent".equals(sound) && now - lastSound[i] > 1000) {
                lastSound[i] = now;
                AlertOutput.play(sound, "Pling".equals(sound) ? 1.4f : 1.0f, volume.getFloat());
            }
        }
    }

    /** Whether a stored chat line is shown with the selected tab (always, on All or while the module is off). */
    public static boolean shows(ChatHudLine line) {
        ChatTabs t = active();
        if (t == null || t.selected < 0 || line == null) return true;
        if (t.selected >= t.tabs().size()) return true;
        long b = t.bits(ChatLines.info(line), ChatLines.plain(line));
        return (b & (1L << t.selected)) != 0;
    }

    /** Whether a tab other than All is selected, so chat shows only some of its lines. */
    public static boolean filtering() {
        ChatTabs t = active();
        return t != null && t.selected >= 0;
    }

    public int selected() { return selected; }

    public int unread(int tab) { return badges.get() && tab >= 0 && tab < unread.length ? unread[tab] : 0; }

    /** Shows tab {@code index} (-1 for All) and marks it read. */
    public void select(int index) {
        List<Tab> t = tabs();
        int next = index < 0 || index >= t.size() ? -1 : index;
        if (next >= 0) unread[next] = 0;
        if (next == selected) return;
        selected = next;
        refreshChat();
    }

    /** From the chat screen as it closes. */
    public void onChatClosed() {
        if (backToAll.get() && selected >= 0) select(-1);
    }

    private void refreshChat() {
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().reset();
    }

    /** Test hook: the tab names, in order. */
    public List<String> tabNamesForTest() { return tabs().stream().map(Tab::name).toList(); }
}
