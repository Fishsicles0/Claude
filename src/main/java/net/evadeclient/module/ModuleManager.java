package net.evadeclient.module;

import net.evadeclient.module.client.ClickGuiModule;
import net.evadeclient.module.client.HudModule;
import net.evadeclient.module.combat.AutoClicker;
import net.evadeclient.module.combat.KillAura;
import net.evadeclient.module.movement.Flight;
import net.evadeclient.module.movement.NoFall;
import net.evadeclient.module.movement.Sprint;
import net.evadeclient.module.movement.Step;
import net.evadeclient.module.player.AutoRespawn;
import net.evadeclient.module.player.FastPlace;
import net.evadeclient.module.render.ESP;
import net.evadeclient.module.render.Fullbright;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ModuleManager {
    private final List<Module> modules = new ArrayList<>();
    private final Set<Integer> heldKeys = new HashSet<>();

    public ModuleManager() {
        // Combat
        register(new KillAura());
        register(new AutoClicker());
        // Movement
        register(new Sprint());
        register(new Flight());
        register(new NoFall());
        register(new Step());
        // Render
        register(new Fullbright());
        register(new ESP());
        // Player
        register(new FastPlace());
        register(new AutoRespawn());
        // Client
        register(new ClickGuiModule());
        register(new HudModule());
    }

    private void register(Module module) { modules.add(module); }

    public List<Module> all() { return modules; }

    public List<Module> byCategory(Category category) {
        return modules.stream().filter(m -> m.getCategory() == category).toList();
    }

    public Module get(String name) {
        for (Module m : modules) if (m.getName().equalsIgnoreCase(name)) return m;
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(Class<T> type) {
        for (Module m : modules) if (type.isInstance(m)) return (T) m;
        return null;
    }

    public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        pollKeybinds(mc);
        if (mc.player == null || mc.world == null) return;
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onTick();
            } catch (Exception e) {
                net.evadeclient.EvadeClient.LOG.error("Module {} crashed on tick, disabling", m.getName(), e);
                m.setEnabled(false);
            }
        }
    }

    /** Edge-triggered key polling so binds work without registering vanilla KeyBindings. */
    private void pollKeybinds(MinecraftClient mc) {
        if (mc.currentScreen != null) {
            heldKeys.clear();
            return;
        }
        long window = mc.getWindow().getHandle();
        Set<Integer> pressedNow = new HashSet<>();
        for (Module m : modules) {
            int key = m.getKey();
            if (key != GLFW.GLFW_KEY_UNKNOWN && InputUtil.isKeyPressed(window, key)) pressedNow.add(key);
        }
        for (Module m : modules) {
            int key = m.getKey();
            if (pressedNow.contains(key) && !heldKeys.contains(key)) m.toggle();
        }
        heldKeys.clear();
        heldKeys.addAll(pressedNow);
    }
}
