package net.evadeclient.module;

import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();
    private boolean enabled;
    private int key;
    private boolean visible = true;

    protected Module(String name, String description, Category category) {
        this(name, description, category, GLFW.GLFW_KEY_UNKNOWN);
    }

    protected Module(String name, String description, Category category, int key) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.key = key;
    }

    protected <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }

    /** Called every client tick (20/s) while enabled and in a world. */
    public void onTick() {}

    /** Called every frame while enabled and in a world, after the world is drawn. */
    public void onRender(RenderContext ctx) {}

    /** Called right before the local player attacks {@code target} (any source: vanilla, TriggerBot, ...). */
    public void onAttack(Entity target) {}

    protected void onEnable() {}
    protected void onDisable() {}

    /** Optional suffix shown in the HUD array list, e.g. a mode name. */
    public String getSuffix() { return null; }

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (mc.player == null && !alwaysRunHooks()) return;
        if (enabled) onEnable(); else onDisable();
    }

    /** Modules that don't touch the world (e.g. ClickGUI) can opt into hooks outside a world. */
    protected boolean alwaysRunHooks() { return false; }

    public boolean isEnabled() { return enabled; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    public List<Setting<?>> getSettings() { return settings; }
    public int getKey() { return key; }
    public void setKey(int key) { this.key = key; }
    public boolean isVisible() { return visible; }
    protected void setVisible(boolean visible) { this.visible = visible; }
}
