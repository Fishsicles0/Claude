package net.evadeclient.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Server rules: a server can switch off any Evade mod for its players by sending a plugin message on
 * {@code evade:rules} with a UTF-8 JSON body {@code {"disable": ["Freelook", "Minimap"]}} (names as in the menu, case
 * doesn't matter; {@code "@addons"} means every addon module, {@code "@addon:<modid>"} one mod's). Those mods are turned off at once, can't be turned on again while connected, and come back as
 * they were when you leave. Sending the message again replaces the list; an empty list allows everything.
 */
public final class ServerRules {
    public static final Identifier CHANNEL = Identifier.of("evade", "rules");

    /** The raw plugin-message body: plain bytes, no length prefix, so any server plugin can send it. */
    public record RulesPayload(byte[] body) implements CustomPayload {
        public static final Id<RulesPayload> ID = new Id<>(CHANNEL);
        public static final PacketCodec<PacketByteBuf, RulesPayload> CODEC = PacketCodec.of(
                (p, buf) -> buf.writeBytes(p.body()),
                buf -> { byte[] b = new byte[Math.min(buf.readableBytes(), 65536)]; buf.readBytes(b); buf.skipBytes(buf.readableBytes()); return new RulesPayload(b); });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Lower-case names the server has disabled. */
    private static final Set<String> disabled = new HashSet<>();
    /** Modules this class switched off (to switch back on when the rule lifts or you leave). */
    private static final Set<Module> switchedOff = new LinkedHashSet<>();

    private ServerRules() {}

    public static void register() {
        try {
            PayloadTypeRegistry.playS2C().register(RulesPayload.ID, RulesPayload.CODEC);
            ClientPlayNetworking.registerGlobalReceiver(RulesPayload.ID, (payload, ctx) -> ctx.client().execute(() -> apply(payload.body())));
        } catch (Exception e) {
            EvadeClient.LOG.warn("Server rules: couldn't register {}", CHANNEL, e);
        }
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ServerRules::clear));
    }

    /** Whether the server you're on has switched {@code m} off. */
    public static boolean isDisabled(Module m) {
        if (m == null || disabled.isEmpty()) return false;
        if (disabled.contains(m.getName().toLowerCase(Locale.ROOT))) return true;
        // "@addons" switches off every module other mods added through the Evade Addon API, "@addon:<modid>" one mod's.
        var owner = net.evadeclient.addon.EvadeAddons.owner(m);
        return owner != null && (disabled.contains("@addons") || disabled.contains("@addon:" + owner.modId().toLowerCase(Locale.ROOT)));
    }

    /** Applies a rules message (render thread). Bad JSON is ignored. */
    public static void apply(byte[] body) {
        Set<String> next = new HashSet<>();
        try {
            JsonElement root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            JsonArray list = root.isJsonObject() && ((JsonObject) root).has("disable") ? ((JsonObject) root).getAsJsonArray("disable")
                    : root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
            for (JsonElement e : list) if (e.isJsonPrimitive()) next.add(e.getAsString().strip().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            EvadeClient.LOG.warn("Server rules: ignored a message that isn't valid JSON");
            return;
        }
        set(next);
    }

    /** Replaces the disabled list. */
    public static void set(Set<String> names) {
        if (EvadeClient.modules == null) return;
        disabled.clear();
        disabled.addAll(names);
        // Lifted rules: switch back on what we switched off.
        for (var it = switchedOff.iterator(); it.hasNext(); ) {
            Module m = it.next();
            if (!isDisabled(m)) { it.remove(); m.setEnabled(true); }
        }
        int count = 0;
        for (Module m : EvadeClient.modules.all()) {
            if (!isDisabled(m)) continue;
            count++;
            if (m.isEnabled()) { m.setEnabled(false); switchedOff.add(m); }
        }
        if (count > 0) Notifications.push("Server rules", count + (count == 1 ? " mod is" : " mods are") + " disabled by this server", false);
    }

    /** On leaving: everything is allowed again and mods come back as they were. */
    public static void clear() {
        disabled.clear();
        for (Module m : switchedOff) m.setEnabled(true);
        switchedOff.clear();
    }
}
