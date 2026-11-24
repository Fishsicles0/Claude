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
import net.evadeclient.module.combat.*;
import net.evadeclient.module.movement.*;
import net.evadeclient.module.player.*;
import net.evadeclient.module.visuals.*;
import net.evadeclient.module.world.*;
import net.evadeclient.module.misc.*;
import net.evadeclient.module.combat.AimAssist;
import net.evadeclient.module.combat.AutoClicker;
import net.evadeclient.module.combat.AutoCritical;
import net.evadeclient.module.combat.AutoMend;
import net.evadeclient.module.combat.AutoPot;
import net.evadeclient.module.combat.AutoTotem;
import net.evadeclient.module.combat.Hitbox;
import net.evadeclient.module.combat.Reach;
import net.evadeclient.module.combat.TriggerBot;
import net.evadeclient.module.visuals.ChestESP;
import net.evadeclient.module.visuals.ChunkLocator;
import net.evadeclient.module.visuals.PlayerTracers;
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
        register(new KillAura());
        register(new CrystalAura());
        register(new AnchorAura());
        register(new BowAimbot());
        register(new AutoArmor());
        register(new Surround());
        register(new AutoTrap());
        register(new Velocity());
        register(new AntiBot());
        register(new AutoLog());
        register(new AutoCity());
        register(new AutoAnvil());
        register(new BedAura());
        register(new AutoWeapon());
        register(new Offhand());
        register(new SelfTrap());
        register(new HoleFiller());
        register(new AutoWeb());
        register(new BowSpam());
        // Movement
        register(new Fly());
        register(new ElytraFly());
        register(new Speed());
        register(new Scaffold());
        register(new NoFall());
        register(new Step());
        register(new Spider());
        register(new LongJump());
        register(new HighJump());
        register(new Jesus());
        register(new Dolphin());
        register(new Parkour());
        register(new AntiVoid());
        register(new SafeWalk());
        register(new Blink());
        register(new net.evadeclient.module.movement.Timer());
        register(new AutoSprint());
        register(new NoSlow());
        register(new InventoryMove());
        register(new AirJump());
        register(new ReverseStep());
        register(new FastClimb());
        register(new AutoJump());
        // Visuals
        register(new ESP());
        register(new ChestESP());
        register(new OreESP());
        register(new ChunkLocator());
        register(new PlayerTracers());
        register(new Nametags());
        register(new Freecam());
        register(new NoFog());
        register(new Trajectories());
        register(new HoleESP());
        register(new Breadcrumbs());
        register(new LogoutSpots());
        register(new Waypoints());
        register(new LightOverlay());
        register(new NoRender());
        register(new BetterTooltips());
        register(new CameraClip());
        // World
        register(new Nuker());
        register(new AutoMine());
        register(new VeinMiner());
        register(new FastPlace());
        register(new FastBreak());
        register(new AutoFarm());
        register(new SpawnProofer());
        register(new Printer());
        register(new PacketMine());
        register(new Excavator());
        register(new AutoBreed());
        register(new AutoShearer());
        register(new AutoSmelter());
        register(new LiquidFiller());
        // Player
        register(new AutoEat());
        register(new AutoTool());
        register(new ChestStealer());
        register(new InventorySorter());
        register(new AutoFish());
        register(new AutoRespawn());
        register(new AntiHunger());
        register(new AntiAFK());
        register(new AutoWalk());
        register(new FakePlayer());
        register(new AutoReplenish());
        register(new FastUse());
        register(new MiddleClickExtra());
        register(new AirPlace());
        register(new AutoGap());
        register(new ChestSwap());
        register(new AutoReconnect());
        register(new BetterChat());
        register(new Notifier());
        register(new AutoGG());
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
        // Mods
        register(new ToggleSprint());
        register(new Zoom());
        register(new Freelook());
        register(new Fullbright());
        register(new Crosshair());
        register(new NoHurtCam());
        register(new BlockOutline());
        register(new TimeChanger());
        register(new WeatherChanger());
        register(new DiscordPresence());
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
