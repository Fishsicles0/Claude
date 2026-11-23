package net.evadeclient.setting;

import com.google.gson.JsonElement;

public abstract class Setting<T> {
    private final String name;
    protected T value;
    private final T defaultValue;
    private boolean hidden;

    protected Setting(String name, T value) {
        this.name = name;
        this.value = value;
        this.defaultValue = value;
    }

    /**
     * Saved and loaded with the config like any other setting, but never shown: not in the menu, search, chat
     * {@code .set} or the website. For state a module remembers by itself (e.g. the last server joined).
     * Use as {@code add(new BooleanSetting("Last Sprint", false).hidden())}.
     */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S hidden() {
        this.hidden = true;
        return (S) this;
    }

    /** Whether this setting is persisted state that the menu and commands leave out (see {@link #hidden()}). */
    public boolean isHidden() { return hidden; }

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
