package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Timestamps on chat lines, and repeated messages stacked into one line with a (x3) counter. */
public class BetterChat extends Module {
    private final BooleanSetting timestamps = add(new BooleanSetting("Timestamps", true));
    private final BooleanSetting antiSpam = add(new BooleanSetting("Stack Repeats", true));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private String lastText;
    private int repeats;

    public BetterChat() {
        super("BetterChat", "Chat timestamps and stacked repeat messages.", Category.MISC);
    }

    public static BetterChat active() {
        if (EvadeClient.modules == null) return null;
        BetterChat b = EvadeClient.modules.get(BetterChat.class);
        return b != null && b.isEnabled() ? b : null;
    }

    /** @return true when the previous chat line should be removed because this one repeats it. */
    public boolean repeatsLast(Text message) {
        String s = message.getString();
        if (antiSpam.get() && s.equals(lastText)) { repeats++; return true; }
        lastText = s;
        repeats = 1;
        return false;
    }

    public Text decorate(Text message) {
        MutableText out = Text.empty();
        if (timestamps.get()) out.append(Text.literal("[" + LocalTime.now().format(TIME) + "] ").formatted(Formatting.DARK_GRAY));
        out.append(message);
        if (antiSpam.get() && repeats > 1) out.append(Text.literal(" (x" + repeats + ")").formatted(Formatting.GRAY));
        return out;
    }
}
