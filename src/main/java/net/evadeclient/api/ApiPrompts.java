package net.evadeclient.api;

import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.social.SocialJoin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Server prompts: {@code prompt.input} (a text box: "Name your island") and {@code prompt.transfer} (a server asks to
 * send you to another address; you see where and choose). Each is shown only when no other screen is open, can always
 * be cancelled, and the answer goes back on {@code evade:reply} (client to server, raw UTF-8 JSON like the hello):
 * {@code {"type":"prompt.input","id":"...","value":"..."}} or {@code {"type":"prompt.input","id":"...","cancelled":true}},
 * {@code {"type":"prompt.transfer","id":"...","accepted":true|false}}. A transfer connects only after you click Join.
 */
public final class ApiPrompts {
    public static final Identifier REPLY_CHANNEL = Identifier.of("evade", "reply");
    static final int MAX_QUEUE = 3, MAX_PER_MINUTE = 6, MAX_INPUT = 256;

    /** C2S {@code evade:reply}: raw UTF-8 JSON, no length prefix. */
    public record ReplyPayload(byte[] body) implements CustomPayload {
        public static final Id<ReplyPayload> ID = new Id<>(REPLY_CHANNEL);
        public static final PacketCodec<PacketByteBuf, ReplyPayload> CODEC = PacketCodec.of(
                (p, buf) -> buf.writeBytes(p.body()),
                buf -> { byte[] b = new byte[Math.min(buf.readableBytes(), 65536)]; buf.readBytes(b); buf.skipBytes(buf.readableBytes()); return new ReplyPayload(b); });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** A prompt waiting to be shown: input (title, text, placeholder, max) or transfer (address, name, reason). */
    public record Prompt(String type, String id, String title, String text, String placeholder, int max, String address) {
        public boolean transfer() { return type.equals("prompt.transfer"); }
    }

    private static final Deque<Prompt> queue = new ArrayDeque<>();
    private static final Deque<Long> recent = new ArrayDeque<>();
    private static Prompt showing;
    private static String lastReply;

    private ApiPrompts() {}

    static void register() {
        PayloadTypeRegistry.playC2S().register(ReplyPayload.ID, ReplyPayload.CODEC);
    }

    static boolean handle(String type, JsonObject o) {
        String id = ApiJson.id(o);
        if (id == null) return false;
        long now = System.currentTimeMillis();
        while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) recent.pollFirst();
        Prompt p;
        if (type.equals("prompt.input")) {
            int max = (int) Math.max(1, Math.min(MAX_INPUT, ApiJson.num(o, "max", 64)));
            p = new Prompt(type, id, ApiJson.str(o, "title", "", ApiJson.MAX_NAME), ApiJson.str(o, "text", "", ApiJson.MAX_TEXT),
                    ApiJson.str(o, "placeholder", "", ApiJson.MAX_NAME), max, null);
        } else {
            String address = ApiJson.str(o, "address", "", 260).toLowerCase(Locale.ROOT);
            if (address.endsWith(":25565")) address = address.substring(0, address.length() - 6);
            if (!SocialJoin.validAddress(address)) {
                reply(type, id, "accepted", false);
                return false;
            }
            p = new Prompt(type, id, ApiJson.str(o, "name", "", ApiJson.MAX_NAME), ApiJson.str(o, "reason", "", ApiJson.MAX_TEXT), "", 0, address);
        }
        queue.removeIf(q -> q.id().equals(id) && q.type().equals(type));
        if (queue.size() >= MAX_QUEUE || recent.size() >= MAX_PER_MINUTE) {
            refuse(p);
            return false;
        }
        recent.addLast(now);
        queue.addLast(p);
        return true;
    }

    /** Shows the next prompt once no screen is open (client tick). */
    static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (showing != null || queue.isEmpty() || mc.currentScreen != null || mc.player == null) return;
        showing = queue.pollFirst();
        mc.setScreen(new net.evadeclient.gui.ServerPromptScreen(showing));
    }

    /** The prompt screen's answer: text for an input (null = cancelled), or yes/no for a transfer. Render thread. */
    public static void answered(Prompt p, String value, boolean accepted) {
        if (showing == p) showing = null;
        if (p.transfer()) {
            reply(p.type(), p.id(), "accepted", accepted);
            if (accepted) SocialJoin.connect(p.address());
        } else if (value == null) reply(p.type(), p.id(), "cancelled", true);
        else {
            JsonObject r = new JsonObject();
            r.addProperty("type", p.type());
            r.addProperty("id", p.id());
            r.addProperty("value", value.length() > p.max() ? value.substring(0, p.max()) : value);
            send(r);
        }
    }

    private static void refuse(Prompt p) {
        if (p.transfer()) reply(p.type(), p.id(), "accepted", false); else reply(p.type(), p.id(), "cancelled", true);
    }

    private static void reply(String type, String id, String key, boolean value) {
        JsonObject r = new JsonObject();
        r.addProperty("type", type);
        r.addProperty("id", id);
        r.addProperty(key, value);
        send(r);
    }

    private static void send(JsonObject r) {
        lastReply = r.toString();
        try {
            if (ClientPlayNetworking.canSend(ReplyPayload.ID)) ClientPlayNetworking.send(new ReplyPayload(lastReply.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Server API: couldn't send a reply", e);
        }
    }

    static void clear() {
        queue.clear();
        recent.clear();
        showing = null;
    }

    /** Test hook: the last reply built (sent or not), or null. */
    public static String lastReplyForTest() { return lastReply; }

    /** Test hook: prompts waiting. */
    public static int queuedForTest() { return queue.size(); }
}
