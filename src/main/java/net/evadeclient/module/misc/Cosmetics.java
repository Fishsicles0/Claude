package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.cosmetics.CosmeticFx;
import net.evadeclient.cosmetics.CosmeticsEngine;
import net.evadeclient.cosmetics.EmoteWheelScreen;
import net.evadeclient.cosmetics.Emotes;
import net.evadeclient.cosmetics.PlayerCosmetics;
import net.evadeclient.cosmetics.Wardrobe;
import net.evadeclient.cosmetics.WardrobeScreen;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StatusSetting;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Evade cosmetics: capes, wings, hats, masks, back items, auras, trails, pets, emotes, kill and win effects and item
 * skins, picked in the Wardrobe. Only other Evade players see yours (vanilla players see nothing), nothing changes
 * your hitbox or size, and servers can switch them off with the "Cosmetics" server rule.
 */
public class Cosmetics extends Module {
    public final BooleanSetting showMine = add(new BooleanSetting("Show My Cosmetics", true)
            .describe("Show your own cosmetics in third person, in your inventory and in the Wardrobe."));
    public final BooleanSetting showOthers = add(new BooleanSetting("Show Others' Cosmetics", true)
            .describe("Show the cosmetics other Evade players near you are wearing."));
    public final BooleanSetting hideInCombat = add(new BooleanSetting("Hide In Combat", false)
            .describe("Hide every cosmetic for a few seconds after you hit something or take damage."));
    public final NumberSetting particles = add(new NumberSetting("Particle Amount", 100, 0, 100, 10)
            .describe("How many particles auras, trails and effects make, in %. 0 turns cosmetic particles off."));
    public final NumberSetting distance = add(new NumberSetting("Render Distance", 32, 8, 64, 4)
            .describe("How far away, in blocks, other players' cosmetics are still drawn."));
    public final KeySetting emoteKey = add(new KeySetting("Emote Wheel Key", GLFW.GLFW_KEY_Y)
            .describe("Opens the emote wheel. Moving, attacking or taking damage ends an emote."));
    public final StatusSetting account = add(new StatusSetting("Account", Wardrobe::status, "auto")
            .describe("Whether you are signed in to Evade cosmetics, which other Evade players need to see what you wear."));

    private boolean emoteKeyWasDown;
    private int ticks;

    public Cosmetics() {
        super("Cosmetics", "Evade capes, wings, hats, pets, emotes and effects, seen only by other Evade players.", Category.VISUALS);
        setEnabled(true);
        CosmeticsEngine.init(this);
    }

    @Override
    public void onTick() {
        ticks++;
        if (ticks % 20 == 0) {
            if (showOthers.get()) PlayerCosmetics.tick(mc, distance.get());
            Wardrobe.refresh(false);
        }
        Emotes.tick(mc);
        CosmeticFx.tick(mc, this);
        boolean down = emoteKey.isDown();
        if (down && !emoteKeyWasDown && mc.currentScreen == null) EmoteWheelScreen.open();
        emoteKeyWasDown = down;
    }

    @Override
    public void onAttack(Entity target) {
        Emotes.onAttack();
        CosmeticFx.onAttack(target);
    }

    @Override
    public void onServerPacket(Packet<?> packet) {
        if (isEnabled()) CosmeticFx.onServerPacket(packet);
    }

    @Override
    protected void onDisable() {
        Emotes.stop();
        CosmeticFx.clear();
    }

    @Override public List<String> commandAliases() { return List.of("wardrobe", "emote", "redeem"); }

    @Override
    public List<String> commandHelp() {
        return List.of("§d.wardrobe§7 - pick your cosmetics", "§d.emote <name>§7 - play an emote you own",
                "§d.redeem <code>§7 - redeem a cosmetics code");
    }

    @Override
    public void command(String[] args) {
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "wardrobe" -> mc.send(WardrobeScreen::openFromCurrent);
            case "emote" -> {
                if (args.length < 2) { mc.send(EmoteWheelScreen::open); return; }
                String q = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                var item = CosmeticsEngine.findEmote(q);
                if (item == null) throw new CommandManager.CommandError("No emote called \"" + q + "\" in your wardrobe");
                Emotes.play(item);
            }
            case "redeem" -> {
                if (args.length < 2) throw new CommandManager.CommandError("Usage: .redeem <code>");
                CosmeticsEngine.redeem(args[1]);
            }
            default -> { }
        }
    }

    /** The module, or null before startup finished. */
    public static Cosmetics get() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(Cosmetics.class);
    }

}
