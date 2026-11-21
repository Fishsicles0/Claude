package net.evadeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A short free-text value (e.g. a nickname), set from a chat command and saved with the config. */
public class StringSetting extends Setting<String> {
    private final String hint;

    public StringSetting(String name, String value, String hint) {
        super(name, value);
        this.hint = hint;
    }

    /** How to change it, shown next to the value in the menu. */
    public String hint() { return hint; }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }
    @Override public void fromJson(JsonElement json) { value = json.getAsString(); }
    @Override public String display() { return value; }
}
