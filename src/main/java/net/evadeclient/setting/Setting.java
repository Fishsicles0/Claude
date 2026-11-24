package net.evadeclient.setting;

import com.google.gson.JsonElement;

public abstract class Setting<T> {
    private final String name;
    protected T value;
    private final T defaultValue;
    private boolean hidden;
    private String description = "";

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

    /**
     * A one-line explanation of what the setting does, shown as a tooltip in the menu and matched by search.
     * Use as {@code add(new BooleanSetting("Hide In Menus", false).describe("Hidden while a menu is open."))}.
     */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S describe(String description) {
        this.description = description == null ? "" : description.strip();
        return (S) this;
    }

    /** What the setting does, or "" when it has no description (see {@link #describe}). */
    public String getDescription() { return description; }

    /** Put the setting back to the value it was created with. */
    public void reset() { set(defaultValue); }

    public boolean isDefault() { return java.util.Objects.equals(value, defaultValue); }

    /** The value the setting was created with (what {@link #reset()} goes back to). */
    public T getDefault() { return defaultValue; }

    public String getName() { return name; }
    public T get() { return value; }
    public void set(T value) { this.value = value; }

    public abstract JsonElement toJson();
    public abstract void fromJson(JsonElement json);
    public abstract String display();

    // ------------------------------------------------------------------ config helpers

    /**
     * Apply a saved value. Anything this setting can't read leaves it at its default (not at whatever it was
     * before), so switching to a profile with a broken entry never keeps the previous profile's value.
     */
    public void loadJson(JsonElement json) {
        value = defaultValue;
        if (json == null || json.isJsonNull()) return;
        try {
            fromJson(json);
        } catch (RuntimeException e) {
            value = defaultValue;
        }
    }

    /** The default value as it would be saved. Does not change the setting. */
    public JsonElement defaultJson() {
        T current = value;
        value = defaultValue;
        try {
            return toJson();
        } finally {
            value = current;
        }
    }

    /**
     * {@code json} as this setting would save it after loading it: clamped, snapped, trimmed, or the default when
     * it can't be read. Does not change the setting.
     */
    public JsonElement normalizeJson(JsonElement json) {
        T current = value;
        try {
            loadJson(json);
            return toJson();
        } catch (RuntimeException e) {
            return defaultJson();
        } finally {
            value = current;
        }
    }

    /** Whether loading {@code json} would leave this setting at its default. Does not change the setting. */
    public boolean isDefaultJson(JsonElement json) {
        T current = value;
        try {
            loadJson(json);
            return isDefault();
        } catch (RuntimeException e) {
            return false;
        } finally {
            value = current;
        }
    }
}
