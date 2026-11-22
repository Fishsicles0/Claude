package net.evadeclient.module;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ArmorHud;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.hud.*;
import net.evadeclient.module.mods.*;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.util.Notifications;
import net.evadeclient.module.client.CooldownHud;
import net.evadeclient.module.client.HudModule;
import net.evadeclient.module.client.TargetHud;
import net.evadeclient.module.player.AntiAFK;
import net.evadeclient.module.visuals.*;
import net.evadeclient.module.misc.*;
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
        // Visuals & quality of life
        register(new NoFog());
        register(new NoRender());
        register(new BetterTooltips());
        register(new CameraClip());
        register(new Breadcrumbs());
        register(new Waypoints());
        register(new Fullbright());
        register(new DamageTint());
        register(new ClearWater());
        register(new Particles());
        register(new PackTweaks());
        register(new InventoryBlur());
        register(new BossBar());
        register(new Scoreboard());
        register(new TntTimer());
        register(new ChunkBorders());
        register(new NickHider());
        register(new HitColor());
        register(new OldAnimations());
        register(new ItemPhysics());
        register(new ShinyPots());
        register(new EnchantGlint());
        // Controls & camera
        register(new ToggleSprint());
        register(new Zoom());
        register(new Freelook());
        register(new Crosshair());
        register(new NoHurtCam());
        register(new BlockOutline());
        register(new TimeChanger());
        register(new WeatherChanger());
        register(new FovChanger());
        register(new Performance());
        // Chat, interface & convenience
        register(new AntiAFK());
        register(new AutoReconnect());
        register(new BetterChat());
        register(new AutoGG());
        register(new AutoTip());
        register(new Notifier());
        register(new Protection());
        register(new Clips());
        register(new VoiceChat());
        // HUD
        register(new HudModule());
        register(new ArmorHud());
        register(new TargetHud());
        register(new CooldownHud());
        register(new KeystrokesHud());
        register(new CpsHud());
        register(new FpsHud());
        register(new PingHud());
        register(new CoordsHud());
        register(new PotionHud());
        register(new CompassHud());
        register(new ClockHud());
        register(new ServerHud());
        register(new ReachHud());
        register(new ComboHud());
        register(new SpeedHud());
        register(new MemoryHud());
        register(new InventoryHud());
        register(new StopwatchHud());
        register(new SaturationHud());
        register(new ItemCounterHud());
        register(new PvpInfoHud());
        register(new PackDisplayHud());
        // Client
        register(new ClientSettings());
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

    /** @return true if any module wants the packet cancelled. */
    public boolean onSendPacket(net.minecraft.network.packet.Packet<?> packet) {
        boolean cancel = false;
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                if (m.onSendPacket(packet)) {
                    cancel = true;
                    EvadeClient.LOG.debug("{} held back {}", m.getName(), packet.getClass().getSimpleName());
                }
            } catch (Exception e) {
                crashed(m, e);
            }
        }
        return cancel;
    }

    private static void crashed(Module m, Exception e) {
        EvadeClient.LOG.error("Module {} crashed, disabling", m.getName(), e);
        m.setEnabled(false);
    }

    private boolean menuKeyHeld;

    /** Edge-triggered key polling so binds work without registering vanilla KeyBindings. */
    private void pollKeybinds(MinecraftClient mc) {
        if (mc.currentScreen != null) {
            heldKeys.clear();
            menuKeyHeld = true; // don't reopen the menu on the same press that closed it
            return;
        }
        ClientSettings cs = get(ClientSettings.class);
        int menuKey = cs.menuKey.get();
        boolean menuDown = menuKey != GLFW.GLFW_KEY_UNKNOWN && InputUtil.isKeyPressed(mc.getWindow(), menuKey);
        if (menuDown && !menuKeyHeld) {
            menuKeyHeld = true;
            mc.setScreen(new EvadeMenu());
            return;
        }
        menuKeyHeld = menuDown;
        Set<Integer> pressedNow = new HashSet<>();
        for (Module m : modules) {
            int key = m.getKey();
            if (key != GLFW.GLFW_KEY_UNKNOWN && InputUtil.isKeyPressed(mc.getWindow(), key)) pressedNow.add(key);
        }
        for (Module m : modules) {
            int key = m.getKey();
            if (pressedNow.contains(key) && !heldKeys.contains(key)) {
                m.toggle();
                if (cs.toggleMessages.get()) Notifications.push(m.getName(), m.isEnabled() ? "Enabled" : "Disabled", m.isEnabled());
            }
        }
        heldKeys.clear();
        heldKeys.addAll(pressedNow);
    }
}
