package net.evadeclient.module;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ArmorHud;
import net.evadeclient.module.client.ClickGuiModule;
import net.evadeclient.module.client.CooldownHud;
import net.evadeclient.module.client.HudModule;
import net.evadeclient.module.client.TargetHud;
import net.evadeclient.module.combat.AimAssist;
import net.evadeclient.module.combat.AutoClicker;
import net.evadeclient.module.combat.AutoCritical;
import net.evadeclient.module.combat.AutoMend;
import net.evadeclient.module.combat.AutoPot;
import net.evadeclient.module.combat.AutoTotem;
import net.evadeclient.module.combat.Hitbox;
import net.evadeclient.module.combat.Reach;
import net.evadeclient.module.combat.TriggerBot;
import net.evadeclient.module.render.ChestESP;
import net.evadeclient.module.render.ChunkLocator;
import net.evadeclient.module.render.PlayerTracers;
import net.evadeclient.render.RenderContext;
import net.evadeclient.util.TargetTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
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
        register(new AutoPot());
        register(new AutoTotem());
        register(new AutoMend());
        register(new AimAssist());
        register(new AutoCritical());
        register(new AutoClicker());
        register(new Reach());
        register(new TriggerBot());
        register(new Hitbox());
        // Render
        register(new ChestESP());
        register(new ChunkLocator());
        register(new PlayerTracers());
        // Client
        register(new HudModule());
        register(new ArmorHud());
        register(new TargetHud());
        register(new CooldownHud());
        register(new ClickGuiModule());
    }

    private void register(Module module) { modules.add(module); }

    public List<Module> all() { return modules; }

    public List<Module> byCategory(Category category) {
        return modules.stream().filter(m -> m.getCategory() == category).toList();
    }

    public List<HudElementModule> hudElements() {
        return modules.stream().filter(m -> m instanceof HudElementModule).map(m -> (HudElementModule) m).toList();
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
        TargetTracker.tick();
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onTick();
            } catch (Exception e) {
                crashed(m, e);
            }
        }
    }

    public void onRender(RenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onRender(ctx);
            } catch (Exception e) {
                crashed(m, e);
            }
        }
    }

    public void onAttack(Entity target) {
        TargetTracker.onAttack(target);
        for (Module m : modules) {
            if (m.isEnabled()) m.onAttack(target);
        }
    }

    private static void crashed(Module m, Exception e) {
        EvadeClient.LOG.error("Module {} crashed, disabling", m.getName(), e);
        m.setEnabled(false);
    }

    /** Edge-triggered key polling so binds work without registering vanilla KeyBindings. */
    private void pollKeybinds(MinecraftClient mc) {
        if (mc.currentScreen != null) {
            heldKeys.clear();
            return;
        }
        Set<Integer> pressedNow = new HashSet<>();
        for (Module m : modules) {
            int key = m.getKey();
            if (key != GLFW.GLFW_KEY_UNKNOWN && InputUtil.isKeyPressed(mc.getWindow(), key)) pressedNow.add(key);
        }
        for (Module m : modules) {
            int key = m.getKey();
            if (pressedNow.contains(key) && !heldKeys.contains(key)) m.toggle();
        }
        heldKeys.clear();
        heldKeys.addAll(pressedNow);
    }
}
