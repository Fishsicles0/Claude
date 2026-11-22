package net.evadeclient.setting;

import com.google.gson.JsonElement;

public abstract class Setting<T> {
    private final String name;
    protected T value;
    private final T defaultValue;

    protected Setting(String name, T value) {
        this.name = name;
        this.value = value;
        this.defaultValue = value;
    }

    /** Put the setting back to the value it was created with. */
    public void reset() { set(defaultValue); }

    public boolean isDefault() { return java.util.Objects.equals(value, defaultValue); }

    public String getName() { return name; }
    public T get() { return value; }
    public void set(T value) { this.value = value; }

    public abstract JsonElement toJson();
    public abstract void fromJson(JsonElement json);
    public abstract String display();
}
