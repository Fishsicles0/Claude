package net.evadeclient.module.misc;

import net.evadeclient.util.Notifications;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;

/**
 * Where Notifier, Protection and Better Chat send what they have to say about you: a chat line with Evade's prefix,
 * a toast at the side of the screen, a title in the middle, and an optional UI sound. Only ever shown to you.
 * Safe to call from any thread (it hops onto the client thread).
 */
final class AlertOutput {
    /** The same prefix as {@code CommandManager.print}, so Evade's lines look alike (and Better Chat knows them). */
    static final String PREFIX = "§c§lEVADE §8» §r";
    /** The Output / Alert modes, in menu order. */
    static final String[] MODES = {"Chat", "Toast", "Chat + Toast", "Title"};

    private AlertOutput() {}

    static boolean toChat(String mode) { return "Chat".equalsIgnoreCase(mode) || "Chat + Toast".equalsIgnoreCase(mode); }

    static boolean toToast(String mode) { return "Toast".equalsIgnoreCase(mode) || "Chat + Toast".equalsIgnoreCase(mode); }

    static boolean toTitle(String mode) { return "Title".equalsIgnoreCase(mode); }

    /**
     * One alert through {@code mode}: a chat line "title · body", a toast, or a title with the body as subtitle.
     * {@code title} carries the colour; {@code plainTitle} is the same words for the toast.
     */
    static void send(String mode, Text title, String plainTitle, String body) {
        if (toChat(mode)) chat(Text.empty().append(title).append(Text.literal(" · " + body).formatted(Formatting.GRAY)));
        if (toToast(mode)) Notifications.push(plainTitle, body, false);
        if (toTitle(mode)) title(title, Text.literal(body).formatted(Formatting.GRAY));
    }

    /** A chat line with Evade's prefix (styled text, unlike {@code CommandManager.print}). */
    static void chat(Text line) {
        onClient(mc -> {
            if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal(PREFIX).append(line));
        });
    }

    /** A title in the middle of the screen, with the current title timings. */
    static void title(Text title, Text subtitle) {
        onClient(mc -> {
            if (mc.inGameHud == null) return;
            mc.inGameHud.setSubtitle(subtitle == null ? Text.empty() : subtitle);
            mc.inGameHud.setTitle(title);
        });
    }

    /** The sound a Sound / Mention Sound mode names, or null for "Off" (or anything unknown). */
    static SoundEvent sound(String name) {
        if (name == null) return null;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "pling" -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();
            case "bell" -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value();
            case "anvil" -> SoundEvents.BLOCK_ANVIL_LAND;
            case "xp" -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
            default -> null;
        };
    }

    /** Plays the named sound to you alone (UI category, no position); nothing for "Off". */
    static void play(String name, float pitch, float volume) {
        SoundEvent s = sound(name);
        if (s == null) return;
        float v = Math.max(0f, Math.min(1f, volume));
        onClient(mc -> mc.getSoundManager().play(PositionedSoundInstance.ui(s, pitch, v)));
    }

    private static void onClient(java.util.function.Consumer<MinecraftClient> action) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isOnThread()) action.accept(mc);
        else mc.execute(() -> action.accept(mc));
    }
}
