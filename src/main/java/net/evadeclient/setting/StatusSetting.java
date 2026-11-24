package net.evadeclient.setting;

import com.google.gson.JsonElement;

import java.util.function.Supplier;

/** A read-only line in a mod's settings that shows live status (never saved to the config). */
public class StatusSetting extends StringSetting {
    private final Supplier<String> live;

    public StatusSetting(String name, Supplier<String> live, String hint) {
        super(name, "", hint);
        this.live = live;
    }

    @Override public String get() { return live.get(); }
    @Override public void fromJson(JsonElement json) { /* live value: nothing to load */ }
}
