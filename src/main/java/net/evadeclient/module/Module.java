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
    /** True once {@link #onEnable} has run for the current enable (and {@link #onDisable} has not yet). */
    private boolean hooksActive;
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

    /** Called every frame while enabled, on the 2D HUD layer (labels that follow world positions, etc.). */
    public void onHud(net.minecraft.client.gui.DrawContext ctx, float tickDelta) {}

    /** Called right before the local player attacks {@code target}. */
    public void onAttack(Entity target) {}

    /**
     * Called on the client thread for EVERY module (enabled or not) after vanilla has processed one of the server
     * packets the network mixin forwards: world time, entity damage, health and game state changes. Keep it cheap
     * and never send anything back; it exists so modules can react to what the server just told the client.
     */
    public void onServerPacket(net.minecraft.network.packet.Packet<?> packet) {}

    /**
     * Called after this module's settings were loaded from a config or profile. {@code settings} is the raw saved
     * settings object keyed by setting name (unknown keys included), so renamed or retyped settings can be migrated.
     */
    public void onConfigLoaded(com.google.gson.JsonObject settings) {}

    /**
     * Chat command words (without the '.') this module handles, e.g. {@code List.of("coords")}. Matched ignoring
     * case; built-in commands win over module aliases.
     */
    public List<String> commandAliases() { return List.of(); }

    /**
     * Runs one of {@link #commandAliases()}: {@code args[0]} is the alias as typed, the rest are its words. Throw
     * {@link net.evadeclient.command.CommandManager.CommandError} for a mistake in what was typed (shown in red).
     */
    public void command(String[] args) {}

    /** Lines for {@code .help}, in the same style as the built-in ones (e.g. "§d.coords§7 - copy your position"). */
    public List<String> commandHelp() { return List.of(); }

    /**
     * Called when the module turns on. Outside a world (title screen, config load at startup) this waits until
     * the next world has loaded, so it always runs with a player and world present
     * (unless {@link #alwaysRunHooks} says otherwise).
     */
    protected void onEnable() {}

    /** Called when the module turns off, only if {@link #onEnable} ran for this enable. */
    protected void onDisable() {}

    /** Optional suffix shown in the HUD array list, e.g. a mode name. */
    public String getSuffix() { return null; }

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (enabled) {
            // No world yet: ModuleManager runs onEnable once one has loaded (applyPendingEnable).
            if (mc.player == null && !alwaysRunHooks()) return;
            hooksActive = true;
            onEnable();
        } else {
            if (!hooksActive) return;
            hooksActive = false;
            onDisable();
        }
    }

    /** Runs a deferred {@link #onEnable} for a module that was switched on before a world existed. */
    void applyPendingEnable() {
        if (!enabled || hooksActive) return;
        hooksActive = true;
        onEnable();
    }

    /** Undoes {@link #onEnable} while staying enabled (used when leaving a world); it runs again on the next join. */
    void suspendHooks() {
        if (!hooksActive) return;
        hooksActive = false;
        onDisable();
    }

    /** Whether {@link #onEnable} has run for the current enable (for tests). */
    public boolean hooksActive() { return hooksActive; }

    /** Modules that don't touch the world can opt into enable/disable hooks outside a world. */
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
