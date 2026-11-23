package net.evadeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A short free-text value (e.g. a nickname), set from a chat command and saved with the config. */
public class StringSetting extends Setting<String> {
    /** Longest value any text setting keeps unless it sets its own (see {@link #maxLength(int)}). */
    public static final int DEFAULT_MAX_LENGTH = 256;

    private final String hint;
    private Runnable onClick;
    private int maxLength = DEFAULT_MAX_LENGTH;

    public StringSetting(String name, String value, String hint) {
        super(name, value);
        this.hint = hint;
    }

    /** Make the row clickable in the menu (e.g. to open another screen). */
    public StringSetting onClick(Runnable action) { this.onClick = action; return this; }

    public Runnable clickAction() { return onClick; }

    /**
     * Cap the value at {@code chars} characters (e.g. 16 for a nickname). Applies to everything that sets it:
     * the menu, chat commands, configs and imported profile codes.
     */
    public StringSetting maxLength(int chars) {
        this.maxLength = Math.max(1, chars);
        if (value != null && value.length() > maxLength) value = clean(value);
        return this;
    }

    /** The longest value this setting keeps. */
    public int getMaxLength() { return maxLength; }

    /** How to change it, shown next to the value in the menu. */
    public String hint() { return hint; }

    /** Stores {@code value} without control characters (line breaks, tabs) and cut to {@link #getMaxLength()}. */
    @Override public void set(String value) { this.value = clean(value); }

    private String clean(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), maxLength));
        for (int i = 0, n = 0; i < s.length() && n < maxLength; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp)) continue;
            out.appendCodePoint(cp);
            n++;
        }
        return out.toString();
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }
    @Override public void fromJson(JsonElement json) { set(json.getAsString()); }
    @Override public String display() { return value; }
}
