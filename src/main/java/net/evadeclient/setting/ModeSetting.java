package net.evadeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

public class ModeSetting extends Setting<String> {
    private final List<String> modes;

    public ModeSetting(String name, String value, String... modes) {
        super(name, value);
        this.modes = List.of(modes);
    }

    public boolean is(String mode) { return value.equalsIgnoreCase(mode); }

    public void cycle(boolean forward) {
        int i = modes.indexOf(value);
        int n = modes.size();
        value = modes.get(((forward ? i + 1 : i - 1) % n + n) % n);
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement json) {
        String s = json.getAsString();
        for (String m : modes) if (m.equalsIgnoreCase(s)) value = m;
    }

    @Override public String display() { return value; }
}
