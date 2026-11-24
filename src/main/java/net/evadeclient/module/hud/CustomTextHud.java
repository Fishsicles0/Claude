package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Placeholders;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Your own line(s) of text on the HUD, with live values from Evade's placeholders ({@link Placeholders}):
 * {@code {fps}}, {@code {x} {y} {z}}, {@code {time:HH:mm}}, {@code {count:totem_of_undying}}, colour tags like
 * {@code <red>} or {@code <#FF8800>}, and simple conditions such as {@code {if ping>150}High ping{end}}.
 * Three of them (Custom Text 1-3), each its own movable widget.
 */
public class CustomTextHud extends TextHudModule {
    /** How many Custom Text widgets there are. */
    public static final int COUNT = 3;
    private static final String[] DEFAULTS = {
            "{fps} FPS <gray>·</gray> {ping} ms",
            "<gray>XYZ</gray> {x} {y} {z} <gray>{facing}</gray>",
            "Totems: <gold>{count:totem_of_undying}</gold>{if ping>150} <red>High ping</red>{end}",
    };

    public final int index;
    public final StringSetting text;

    public CustomTextHud(int index) {
        super("Custom Text " + index, "Your own text with live values like {fps}, {x} {y} {z}, {time:HH:mm} and colours.",
                4, 270 + (index - 1) * 16);
        this.index = index;
        this.text = add(new StringSetting("Text", DEFAULTS[Math.floorMod(index - 1, DEFAULTS.length)], "placeholders")
                .maxLength(200)
                .describe("Values: {fps} {ping} {x} {y} {z} {biome} {dimension} {facing} {time:HH:mm} {day} {server} "
                        + "{cps} {speed} {memory} {count:item} {mod:Name}. Colours: <red>...</red>, <#RRGGBB>. "
                        + "Conditions: {if ping>150}...{end}. \\n starts a new line."));
    }

    private String template() {
        String t = text.get();
        return t == null ? "" : t.strip();
    }

    @Override
    protected List<Text> styledLines(boolean preview) {
        String t = template();
        if (t.isEmpty()) {
            List<Text> out = new ArrayList<>(1);
            if (preview) out.add(Text.literal(getName()));
            return out;
        }
        List<Text> out = new ArrayList<>(Placeholders.lines(t));
        // Blank lines (e.g. a condition that is off) take no room; all blank hides the widget.
        out.removeIf(l -> l.getString().isBlank());
        if (out.isEmpty() && preview) out.add(Text.literal(getName()));
        return out;
    }

    @Override
    protected List<String> lines(boolean preview) {
        List<String> out = new ArrayList<>();
        for (Text t : styledLines(preview)) out.add(t.getString());
        return out;
    }
}
