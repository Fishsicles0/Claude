package net.evadeclient.module;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ArmorHud;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.client.ModuleListHud;
import net.evadeclient.module.client.WatermarkHud;
import net.evadeclient.module.hud.*;
import net.evadeclient.module.mods.*;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Notifications;
import net.evadeclient.module.client.CooldownHud;
import net.evadeclient.module.visuals.*;
import net.evadeclient.module.misc.*;
import net.evadeclient.render.RenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ModuleManager {
    /** Every module failure since launch, as "Name: exception" (tests assert this stays empty). */
    public static final Set<String> CRASHED = ConcurrentHashMap.newKeySet();

    private final List<Module> modules = new ArrayList<>();
    /** Key and mouse-button presses seen by the input mixins while no screen was open; drained every tick. */
    private final ConcurrentLinkedQueue<Integer> bindPresses = new ConcurrentLinkedQueue<>();
    /** The world the enable hooks were last applied for (null outside a world). */
    private ClientWorld lastWorld;

    public ModuleManager() {
        // Visuals & quality of life
        register(new NoFog());
        register(new NoRender());
        register(new BetterTooltips());
        register(new CameraClip());
        register(new MotionBlur());
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
        register(new TabTweaks());
        register(new Nametags());
        register(new HitColor());
        register(new OldAnimations());
        register(new ItemPhysics());
        register(new ShinyPots());
        register(new EnchantGlint());
        register(new Hitboxes());
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
        register(new SoundTweaks());
        // Chat, interface & convenience
        register(new AutoReconnect());
        register(new BetterChat());
        register(new AutoGG());
        register(new AutoTip());
        register(new Notifier());
        register(new Protection());
        register(new Clips());
        register(new net.evadeclient.module.misc.Replay());
        register(new EvadeIcons());
        register(new VoiceChat());
        register(new ScreenshotTools());
        // HUD
        register(new WatermarkHud());
        register(new ModuleListHud());
        register(new ArmorHud());
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
        register(new PackDisplayHud());
        register(new FrameTimeHud());
        register(new net.evadeclient.module.hud.ProfilerHud());
        // Client
        register(new ClientSettings());
    }

    private void register(Module module) { modules.add(module); }

    public List<Module> all() { return modules; }

    public List<Module> byCategory(Category category) {
        return modules.stream().filter(m -> m.getCategory() == category).toList();
    }

    private List<HudElementModule> hudElements;

    /** HUD elements, computed once (modules are registered at startup and never change). */
    public List<HudElementModule> hudElements() {
        if (hudElements == null)
            hudElements = modules.stream().filter(m -> m instanceof HudElementModule).map(m -> (HudElementModule) m).toList();
        return hudElements;
    }

    /**
     * A module by name, ignoring case, spaces and punctuation: "Nick Hider", "nickhider" and "NICK-HIDER" all
     * find Nick Hider.
     */
    public Module get(String name) {
        if (name == null) return null;
        for (Module m : modules) if (m.getName().equalsIgnoreCase(name)) return m;
        String n = normalize(name);
        if (n.isEmpty()) return null;
        for (Module m : modules) if (normalize(m.getName()).equals(n)) return m;
        return null;
    }

    /** Lower case with everything but letters and digits removed, for forgiving name matching. */
    public static String normalize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(Class<T> type) {
        for (Module m : modules) if (type.isInstance(m)) return (T) m;
        return null;
    }

    public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        pollKeybinds(mc);
        trackWorld(mc);
        if (mc.player == null || mc.world == null) return;
        net.evadeclient.util.Combat.tick(mc);
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onTick();
            } catch (Exception e) {
                crashed(m, e, null);
            }
        }
    }

    /**
     * Modules switched on before a world existed (config load at startup, a profile applied on the title screen)
     * get their onEnable once a world has loaded. Modules that change game options undo them when you leave,
     * so the game never saves Evade's values as your own, and apply them again on the next join.
     */
    private void trackWorld(MinecraftClient mc) {
        ClientWorld world = mc.player == null ? null : mc.world;
        if (world == lastWorld) return;
        if (world == null) {
            for (Module m : modules) {
                if (!undoesOnLeave(m)) continue;
                try { m.suspendHooks(); } catch (Exception e) { crashed(m, e, null); }
            }
        } else {
            for (Module m : modules) {
                try { m.applyPendingEnable(); } catch (Exception e) { crashed(m, e, null); }
            }
        }
        lastWorld = world;
    }

    /** Modules whose onEnable changes the game's own options (video settings). */
    private static boolean undoesOnLeave(Module m) {
        return m instanceof Performance;
    }

    public void onRender(RenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onRender(ctx);
            } catch (Exception e) {
                crashed(m, e, null);
            }
        }
    }

    public void onAttack(Entity target) {
        net.evadeclient.util.Combat.hit();
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onAttack(target);
            } catch (Exception e) {
                crashed(m, e, null);
            }
        }
    }

    /**
     * Log a module failure, record it in {@link #CRASHED} and switch the module off, so a broken module neither
     * takes the game down nor logs the same error every frame.
     *
     * @param where what was running ("HUD", ...), or null for the module's tick/render hooks
     */
    public static void crashed(Module m, Throwable e, String where) {
        String tag = where == null ? m.getName() : m.getName() + " (" + where + ")";
        CRASHED.add(tag + ": " + e);
        EvadeClient.LOG.error("Module {} crashed, disabling", tag, e);
        try {
            m.setEnabled(false);
        } catch (Throwable t) {
            EvadeClient.LOG.error("Module {} also failed while turning off", m.getName(), t);
        }
        Notifications.push(m.getName(), "Turned off after an error", false);
    }

    /** Called by the input mixins for a key (GLFW code) or mouse button ({@link KeySetting#mouseCode}) press. */
    public void queueBindPress(int code) {
        if (code == GLFW.GLFW_KEY_UNKNOWN || bindPresses.size() >= 64) return;
        bindPresses.add(code);
    }

    /**
     * Toggles modules and opens the menu for every bound key pressed since the last tick. Presses are queued by
     * the input mixins (only while no screen is open and F3 isn't held), so a tap shorter than a tick still counts.
     */
    private void pollKeybinds(MinecraftClient mc) {
        ClientSettings cs = get(ClientSettings.class);
        // Backstop: the menu must always be reachable, whatever a config or share code says.
        int menuKey = cs.menuKey.get();
        if (menuKey == GLFW.GLFW_KEY_UNKNOWN || !cs.menuKey.accepts(menuKey)) {
            cs.menuKey.reset();
            menuKey = cs.menuKey.get();
        }
        Integer code;
        while ((code = bindPresses.poll()) != null) {
            if (code == menuKey) {
                if (mc.currentScreen == null) mc.setScreen(new EvadeMenu());
                continue;
            }
            for (Module m : modules) {
                if (m.getKey() != code) continue;
                m.toggle();
                if (cs.toggleMessages.get()) Notifications.push(m.getName(), m.isEnabled() ? "Enabled" : "Disabled", m.isEnabled());
            }
        }
    }

    /**
     * Everything else bound to {@code code}: module toggle keys, key settings (the menu key included) and the
     * game's own controls. {@code self} (the Module, KeySetting or KeyBinding being edited) is left out.
     * Used by the menu to warn about conflicts.
     */
    public List<String> usersOf(int code, Object self) {
        List<String> out = new ArrayList<>();
        if (code == GLFW.GLFW_KEY_UNKNOWN) return out;
        for (Module m : modules) {
            if (m != self && m.getKey() == code) out.add(m.getName());
            for (Setting<?> s : m.getSettings()) {
                if (s == self || s.isHidden() || !(s instanceof KeySetting k) || k.get() != code) continue;
                boolean named = m instanceof ClientSettings
                        || normalize(k.getName()).startsWith(normalize(m.getName()));
                out.add(named ? k.getName() : m.getName() + " " + k.getName());
            }
        }
        var mc = MinecraftClient.getInstance();
        if (mc.options != null) {
            for (KeyBinding kb : mc.options.allKeys) {
                if (kb == self || kb.isUnbound()) continue;
                InputUtil.Key bound = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(kb);
                int c = switch (bound.getCategory()) {
                    case KEYSYM -> bound.getCode();
                    case MOUSE -> KeySetting.mouseCode(bound.getCode());
                    default -> GLFW.GLFW_KEY_UNKNOWN;
                };
                if (c == code) out.add(net.minecraft.text.Text.translatable(kb.getId()).getString());
            }
        }
        return out;
    }
}
