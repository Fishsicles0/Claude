package net.evadeclient.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;

import java.io.Closeable;
import java.io.EOFException;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * A minimal Discord Rich Presence client over Discord's local IPC, written from the protocol itself (no library):
 * a named pipe {@code \\.\pipe\discord-ipc-N} on Windows, a Unix socket {@code discord-ipc-N} in
 * {@code $XDG_RUNTIME_DIR}, {@code $TMPDIR}, {@code /tmp} (and the Flatpak/Snap subfolders) elsewhere.
 *
 * <p>Each frame is a little-endian {@code int32 opcode}, a little-endian {@code int32 length} and that many bytes of
 * UTF-8 JSON. Opcode 0 is the handshake ({@code {"v":1,"client_id":...}}, answered with a READY dispatch), 1 a
 * command ({@code SET_ACTIVITY}, answered by Discord), 2 close. Every command is answered, so one worker thread writes a
 * frame and then reads the answer: no thread ever reads while another writes, which matters for Windows pipes opened
 * synchronously.
 *
 * <p>All I/O runs on one daemon thread; {@link #update} only hands over the newest activity and never blocks. When
 * Discord isn't running or the connection drops, the worker retries with back-off (2 s doubling up to 60 s).
 */
public final class DiscordIpc {
    private static final int OP_HANDSHAKE = 0, OP_FRAME = 1, OP_CLOSE = 2;
    private static final long MIN_BACKOFF_MS = 2_000, MAX_BACKOFF_MS = 60_000;
    /** Largest frame we accept from Discord; anything bigger means the stream is out of step. */
    private static final int MAX_FRAME = 1 << 20;

    /** What the worker should show: an activity JSON, or {@link #CLEAR} for none. */
    private static final JsonObject CLEAR = new JsonObject();

    private final String clientId;
    private final AtomicReference<JsonObject> wanted = new AtomicReference<>();
    private final Object lock = new Object();
    private volatile boolean running = true;
    private volatile String status = "Connecting to Discord";
    private Thread thread;
    /** The open connection, so {@link #stop} can cut a read that Discord never answers. */
    private volatile Pipe current;

    public DiscordIpc(String clientId) {
        this.clientId = clientId;
    }

    /** Starts the worker thread (once). */
    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this::run, "Evade Discord IPC");
        thread.setDaemon(true);
        thread.start();
    }

    /** Shows {@code activity} (a Discord activity object), or none for null. Never blocks. */
    public void update(JsonObject activity) {
        wanted.set(activity == null ? CLEAR : activity);
        synchronized (lock) { lock.notifyAll(); }
    }

    /** Clears the status, closes the connection and ends the worker (asynchronously; never blocks). */
    public void stop() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
        Thread t = thread;
        if (t == null) return;
        // The worker clears the status and closes by itself; if Discord stops answering, cut it off after 3 s.
        Thread reaper = new Thread(() -> {
            try { t.join(3_000); } catch (InterruptedException ignored) { }
            Pipe p = current;
            if (t.isAlive() && p != null) closeQuietly(p);
        }, "Evade Discord IPC stop");
        reaper.setDaemon(true);
        reaper.start();
    }

    /** A short human-readable state for the settings ("Connected", "Discord not running"...). */
    public String status() { return status; }

    // ------------------------------------------------------------------ worker

    private void run() {
        long backoff = MIN_BACKOFF_MS;
        while (running) {
            Pipe pipe = null;
            try {
                pipe = open();
                current = pipe;
                if (pipe == null) {
                    status = "Discord not running";
                } else {
                    handshake(pipe);
                    status = "Connected";
                    backoff = MIN_BACKOFF_MS;
                    serve(pipe);
                    break; // stopped
                }
            } catch (IOException | RuntimeException e) {
                status = "Disconnected (retrying)";
                EvadeClient.LOG.debug("Discord IPC: {}", e.toString());
            } finally {
                current = null;
                if (pipe != null) closeQuietly(pipe);
            }
            if (!running) break;
            sleep(backoff);
            backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
        }
        status = "Stopped";
    }

    /** Sends each new activity as it arrives; on stop, clears the status and says goodbye. */
    private void serve(Pipe pipe) throws IOException {
        JsonObject sent = null;
        // Re-send what was wanted before this connection (a reconnect after Discord restarted).
        while (running) {
            JsonObject want = wanted.get();
            if (want != null && want != sent) {
                setActivity(pipe, want == CLEAR ? null : want);
                sent = want;
                continue;
            }
            synchronized (lock) {
                if (running && wanted.get() == sent) {
                    try { lock.wait(15_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
        try {
            setActivity(pipe, null);
            pipe.write(frame(OP_CLOSE, new JsonObject()));
        } catch (IOException ignored) {
            // Leaving anyway.
        }
    }

    private void handshake(Pipe pipe) throws IOException {
        JsonObject hello = new JsonObject();
        hello.addProperty("v", 1);
        hello.addProperty("client_id", clientId);
        pipe.write(frame(OP_HANDSHAKE, hello));
        Reply r = read(pipe);
        if (r.op == OP_CLOSE) throw new IOException("Discord refused the handshake: " + r.json);
        // A READY dispatch (op 1, "evt":"READY") is the normal answer.
    }

    private void setActivity(Pipe pipe, JsonObject activity) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) args.add("activity", activity);
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());
        pipe.write(frame(OP_FRAME, cmd));
        Reply r = read(pipe);
        if (r.op == OP_CLOSE) throw new IOException("Discord closed the connection: " + r.json);
        if (r.json.contains("\"evt\":\"ERROR\"")) {
            status = "Discord error: " + errorMessage(r.json);
        } else {
            status = "Connected";
        }
    }

    private static String errorMessage(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return o.getAsJsonObject("data").get("message").getAsString();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------ frames

    record Reply(int op, String json) {}

    static byte[] frame(int op, JsonObject payload) {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(body.length).put(body);
        return b.array();
    }

    private static Reply read(Pipe pipe) throws IOException {
        byte[] head = new byte[8];
        pipe.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int op = h.getInt(), len = h.getInt();
        if (len < 0 || len > MAX_FRAME) throw new IOException("Bad frame length " + len);
        byte[] body = new byte[len];
        pipe.readFully(body);
        return new Reply(op, new String(body, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ transports

    /** One open connection to Discord: blocking reads and writes of whole buffers. */
    private interface Pipe extends Closeable {
        void write(byte[] data) throws IOException;
        void readFully(byte[] into) throws IOException;
    }

    /** The first Discord IPC endpoint that accepts a connection, or null when none does. */
    private static Pipe open() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (int i = 0; i < 10; i++) {
            if (windows) {
                try {
                    RandomAccessFile f = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
                    return new FilePipe(f);
                } catch (IOException ignored) {
                    // Not this one.
                }
            } else {
                for (Path dir : unixDirs()) {
                    Path sock = dir.resolve("discord-ipc-" + i);
                    if (!Files.exists(sock)) continue;
                    try {
                        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                        try {
                            ch.connect(UnixDomainSocketAddress.of(sock));
                            return new SocketPipe(ch);
                        } catch (IOException e) {
                            ch.close();
                        }
                    } catch (IOException | UnsupportedOperationException ignored) {
                        // Not this one.
                    }
                }
            }
        }
        return null;
    }

    /** Folders Discord may put its socket in: the runtime/temp dirs, and the Flatpak and Snap ones inside them. */
    private static List<Path> unixDirs() {
        List<Path> bases = new ArrayList<>();
        for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) bases.add(Path.of(v));
        }
        bases.add(Path.of("/tmp"));
        List<Path> out = new ArrayList<>();
        for (Path b : bases) {
            if (out.contains(b)) continue;
            out.add(b);
            out.add(b.resolve("app/com.discordapp.Discord"));
            out.add(b.resolve("snap.discord"));
        }
        return out;
    }

    private static final class FilePipe implements Pipe {
        private final RandomAccessFile f;
        FilePipe(RandomAccessFile f) { this.f = f; }
        @Override public void write(byte[] data) throws IOException { f.write(data); }
        @Override public void readFully(byte[] into) throws IOException { f.readFully(into); }
        @Override public void close() throws IOException { f.close(); }
    }

    private static final class SocketPipe implements Pipe {
        private final SocketChannel ch;
        SocketPipe(SocketChannel ch) { this.ch = ch; }

        @Override
        public void write(byte[] data) throws IOException {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) ch.write(b);
        }

        @Override
        public void readFully(byte[] into) throws IOException {
            ByteBuffer b = ByteBuffer.wrap(into);
            while (b.hasRemaining()) if (ch.read(b) < 0) throw new EOFException("Discord closed the socket");
        }

        @Override public void close() throws IOException { ch.close(); }
    }

    private static void closeQuietly(Closeable c) {
        try { c.close(); } catch (IOException ignored) { }
    }

    private void sleep(long ms) {
        synchronized (lock) {
            if (!running) return;
            try { lock.wait(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); running = false; }
        }
    }
}
