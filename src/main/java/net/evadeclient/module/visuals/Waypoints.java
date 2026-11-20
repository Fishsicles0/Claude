package net.evadeclient.module.visuals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Label;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved locations per server and dimension, drawn as a beam with a name and distance.
 * {@code .waypoint add <name>}, {@code .waypoint del <name>}, {@code .waypoint list}. Adds a "Death" point when you die.
 */
public class Waypoints extends Module {
    public record Point(String name, String dim, int x, int y, int z) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final BooleanSetting deaths = add(new BooleanSetting("Death Points", true));
    private final BooleanSetting beams = add(new BooleanSetting("Beams", true));
    private Map<String, List<Point>> all = new LinkedHashMap<>();
    private boolean loaded, wasDead;

    public Waypoints() {
        super("Waypoints", "Saved locations with beams and distance.", Category.VISUALS);
        setEnabled(true);
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("waypoints.json"); }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(file())) all = GSON.fromJson(Files.readString(file()), new TypeToken<LinkedHashMap<String, List<Point>>>() {}.getType());
        } catch (Exception e) { all = new LinkedHashMap<>(); }
        if (all == null) all = new LinkedHashMap<>();
    }

    private void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(all));
        } catch (Exception ignored) {}
    }

    /** "server address" or "singleplayer:<world name>". */
    private String worldKey() {
        if (mc.isInSingleplayer() && mc.getServer() != null) return "singleplayer:" + mc.getServer().getSaveProperties().getLevelName();
        var info = mc.getCurrentServerEntry();
        return info == null ? "unknown" : info.address;
    }

    private String dim() { return mc.world.getRegistryKey().getValue().toString(); }

    public List<Point> here() {
        load();
        List<Point> out = new ArrayList<>();
        for (Point p : all.getOrDefault(worldKey(), List.of())) if (p.dim.equals(dim())) out.add(p);
        return out;
    }

    public void add(String name, BlockPos pos) {
        load();
        List<Point> list = all.computeIfAbsent(worldKey(), k -> new ArrayList<>());
        list.removeIf(p -> p.name.equalsIgnoreCase(name) && p.dim.equals(dim()));
        list.add(new Point(name, dim(), pos.getX(), pos.getY(), pos.getZ()));
        save();
    }

    public boolean remove(String name) {
        load();
        List<Point> list = all.get(worldKey());
        boolean r = list != null && list.removeIf(p -> p.name.equalsIgnoreCase(name) && p.dim.equals(dim()));
        if (r) save();
        return r;
    }

    public void command(String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        switch (sub) {
            case "add", "set" -> {
                String name = a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : "Waypoint " + (here().size() + 1);
                add(name, mc.player.getBlockPos());
                CommandManager.print("Waypoint §d" + name + "§7 saved at " + mc.player.getBlockPos().toShortString());
            }
            case "del", "remove" -> {
                String name = a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : "";
                CommandManager.print(remove(name) ? "Removed §d" + name : "§cNo waypoint called " + name);
            }
            case "clear" -> { load(); all.remove(worldKey()); save(); CommandManager.print("Waypoints cleared for this server."); }
            default -> {
                List<Point> pts = here();
                if (pts.isEmpty()) CommandManager.print("No waypoints here. §d.waypoint add <name>");
                for (Point p : pts) CommandManager.print("§d" + p.name + "§7 " + p.x + ", " + p.y + ", " + p.z
                        + " (" + Math.round(Math.sqrt(mc.player.getBlockPos().getSquaredDistance(new BlockPos(p.x, p.y, p.z)))) + "m)");
            }
        }
    }

    @Override
    public void onTick() {
        boolean dead = mc.player.isDead() || mc.player.getHealth() <= 0;
        if (dead && !wasDead && deaths.get()) add("Death", mc.player.getBlockPos());
        wasDead = dead;
    }

    @Override
    public void onRender(RenderContext ctx) {
        for (Point p : here()) {
            Box b = new Box(p.x, p.y, p.z, p.x + 1, p.y + 1, p.z + 1);
            int c = p.name.equals("Death") ? 0xFFFF7A85 : 0xFFE11D2E;
            ctx.boxOutline(b, c, 2f);
            if (beams.get()) ctx.line(new Vec3d(p.x + 0.5, p.y + 1, p.z + 0.5), new Vec3d(p.x + 0.5, p.y + 256, p.z + 0.5), (c & 0xFFFFFF) | 0x90000000, 2f);
        }
    }

    @Override
    public void onHud(DrawContext ctx, float td) {
        Vec3d me = mc.player.getEntityPos();
        for (Point p : here()) {
            Vec3d at = new Vec3d(p.x + 0.5, p.y + 1.5, p.z + 0.5);
            int dist = (int) Math.round(at.distanceTo(me));
            // Far points are drawn at a fixed distance along the line of sight so the label stays readable.
            Vec3d draw = dist > 64 ? mc.gameRenderer.getCamera().getCameraPos().add(at.subtract(mc.gameRenderer.getCamera().getCameraPos()).normalize().multiply(64)) : at;
            Label.draw(ctx, draw, p.name + " §7" + dist + "m", p.name.equals("Death") ? 0xFFFF7A85 : 0xFFE11D2E);
        }
    }
}
