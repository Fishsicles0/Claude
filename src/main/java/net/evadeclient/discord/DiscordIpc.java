package net.evadeclient.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Minimal client for the Discord desktop app's local RPC socket, enough to set Rich Presence.
 * Windows uses the named pipe {@code \\.\pipe\discord-ipc-N}; macOS and Linux use a Unix socket
 * {@code discord-ipc-N} in the runtime/temp directory (including Flatpak and Snap installs).
 * Frames are: opcode (int32 LE), length (int32 LE), UTF-8 JSON.
 */
public final class DiscordIpc implements Closeable {
    private static final int OP_HANDSHAKE = 0, OP_FRAME = 1, OP_CLOSE = 2, OP_PING = 3, OP_PONG = 4;

    private interface Pipe extends Closeable {
        void write(byte[] data) throws IOException;
        void readFully(byte[] data) throws IOException;
    }

    /** Discord answered but refused the request (bad activity fields). */
    public static final class Rejected extends IOException {
        Rejected(String message) { super(message); }
    }

    private final Pipe pipe;

    private DiscordIpc(Pipe pipe) { this.pipe = pipe; }

    /** Connects and completes the handshake, or throws if Discord isn't running. */
    public static DiscordIpc connect(String clientId) throws IOException {
        IOException last = new IOException("Discord isn't running");
        for (int i = 0; i < 10; i++) {
            for (Pipe pipe : candidates(i)) {
                DiscordIpc ipc = new DiscordIpc(pipe);
                try {
                    JsonObject hello = new JsonObject();
                    hello.addProperty("v", 1);
                    hello.addProperty("client_id", clientId);
                    ipc.send(OP_HANDSHAKE, hello);
                    JsonObject ready = ipc.receive();
                    if (!"READY".equals(str(ready, "evt"))) throw new IOException("Unexpected handshake reply: " + ready);
                    return ipc;
                } catch (IOException e) {
                    last = e;
                    ipc.close();
                }
            }
        }
        throw last;
    }

    private static List<Pipe> candidates(int n) {
        List<Pipe> out = new ArrayList<>();
        String name = "discord-ipc-" + n;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                RandomAccessFile f = new RandomAccessFile("\\\\.\\pipe\\" + name, "rw");
                out.add(new Pipe() {
                    public void write(byte[] d) throws IOException { f.write(d); }
                    public void readFully(byte[] d) throws IOException { f.readFully(d); }
                    public void close() throws IOException { f.close(); }
                });
            } catch (IOException ignored) { }
            return out;
        }
        List<String> bases = new ArrayList<>();
        for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) bases.add(v);
        }
        bases.add("/tmp");
        for (String base : bases) {
            for (String sub : new String[]{"", "app/com.discordapp.Discord/", ".flatpak/com.discordapp.Discord/xdg-run/", "snap.discord/"}) {
                Path p = Path.of(base, sub + name);
                if (!Files.exists(p)) continue;
                try {
                    SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                    ch.connect(UnixDomainSocketAddress.of(p));
                    out.add(new Pipe() {
                        public void write(byte[] d) throws IOException {
                            ByteBuffer b = ByteBuffer.wrap(d);
                            while (b.hasRemaining()) ch.write(b);
                        }
                        public void readFully(byte[] d) throws IOException {
                            ByteBuffer b = ByteBuffer.wrap(d);
                            while (b.hasRemaining()) if (ch.read(b) < 0) throw new IOException("Discord closed the connection");
                        }
                        public void close() throws IOException { ch.close(); }
                    });
                } catch (IOException ignored) { }
            }
        }
        return out;
    }

    /** Sets (or, with {@code activity == null}, clears) this app's Rich Presence. */
    public synchronized void setActivity(JsonObject activity) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) args.add("activity", activity);
        JsonObject frame = new JsonObject();
        frame.addProperty("cmd", "SET_ACTIVITY");
        frame.add("args", args);
        frame.addProperty("nonce", UUID.randomUUID().toString());
        send(OP_FRAME, frame);
        JsonObject reply = receive();
        if ("ERROR".equals(str(reply, "evt"))) throw new Rejected(String.valueOf(reply.get("data")));
    }

    private void send(int op, JsonObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(body.length).put(body);
        pipe.write(b.array());
    }

    private JsonObject receive() throws IOException {
        while (true) {
            byte[] header = new byte[8];
            pipe.readFully(header);
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int op = h.getInt(), len = h.getInt();
            if (len < 0 || len > 1 << 20) throw new IOException("Bad frame length " + len);
            byte[] body = new byte[len];
            pipe.readFully(body);
            String text = new String(body, StandardCharsets.UTF_8);
            if (op == OP_PING) { send(OP_PONG, JsonParser.parseString(text).getAsJsonObject()); continue; }
            if (op == OP_CLOSE) throw new IOException("Discord closed the connection: " + text);
            return JsonParser.parseString(text).getAsJsonObject();
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    @Override
    public void close() {
        try { pipe.close(); } catch (IOException ignored) { }
    }
}
