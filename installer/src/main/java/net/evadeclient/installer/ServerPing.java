package net.evadeclient.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Hashtable;
import java.util.Locale;

/**
 * The Minecraft server list ping, the same request the game's Multiplayer screen makes: a handshake (next state
 * "status") and a status request over TCP, answered with a JSON document (MOTD, players, version and the server's
 * own 64×64 icon as a base64 PNG). Everything is bounded by a 3 s timeout. Addresses without a port try the
 * {@code _minecraft._tcp} SRV record first, as the game does.
 */
final class ServerPing {
    static final int TIMEOUT_MS = 3000;

    private ServerPing() {}

    /** What a server said about itself. {@code favicon} is the raw PNG (null if it has none). */
    record Status(String motd, int online, int max, String version, byte[] favicon, long pingMs) {}

    static Status ping(String address) throws IOException {
        String[] hp = hostPort(address);
        String host = hp[0];
        int port = Integer.parseInt(hp[1]);
        if (hp[2] == null) {
            String[] srv = srv(host);
            if (srv != null) { host = srv[0]; port = Integer.parseInt(srv[1]); }
        }
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            // Handshake: id 0, protocol -1 ("just asking"), the address as typed, port, next state 1 (status).
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            varInt(hs, 0x00);
            varInt(hs, -1);
            string(hs, hp[0]);
            hs.write((port >> 8) & 0xFF);
            hs.write(port & 0xFF);
            varInt(hs, 1);
            packet(out, hs.toByteArray());
            packet(out, new byte[]{0x00}); // status request
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            int len = readVarInt(in);
            if (len <= 0 || len > 1 << 21) throw new IOException("Bad status reply");
            int id = readVarInt(in);
            if (id != 0x00) throw new IOException("Bad status reply");
            int jlen = readVarInt(in);
            if (jlen < 0 || jlen > len) throw new IOException("Bad status reply");
            byte[] json = new byte[jlen];
            in.readFully(json);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return parse(new String(json, StandardCharsets.UTF_8), ms);
        }
    }

    static Status parse(String json, long ms) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        int online = 0, max = 0;
        if (o.has("players") && o.get("players").isJsonObject()) {
            JsonObject p = o.getAsJsonObject("players");
            try { online = p.get("online").getAsInt(); } catch (RuntimeException ignored) { }
            try { max = p.get("max").getAsInt(); } catch (RuntimeException ignored) { }
        }
        String version = "";
        try { version = o.getAsJsonObject("version").get("name").getAsString(); } catch (RuntimeException ignored) { }
        byte[] icon = null;
        String fav = InstallerCore.str(o, "favicon");
        if (fav != null && fav.startsWith("data:image/png;base64,")) {
            try { icon = Base64.getMimeDecoder().decode(fav.substring(22)); } catch (IllegalArgumentException ignored) { }
        }
        return new Status(motd(o.get("description")).strip(), online, max, stripCodes(version), icon, ms);
    }

    /** The MOTD as plain text: a string, or a chat component (text + extra), with § codes removed. */
    static String motd(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonPrimitive()) return stripCodes(e.getAsString());
        StringBuilder sb = new StringBuilder();
        if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) sb.append(motd(x));
        else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("text")) sb.append(stripCodes(o.get("text").isJsonPrimitive() ? o.get("text").getAsString() : ""));
            if (o.has("extra")) sb.append(motd(o.get("extra")));
        }
        return sb.toString();
    }

    static String stripCodes(String s) { return s == null ? "" : s.replaceAll("§.", ""); }

    /** {host, port, explicit port or null}. Accepts "host", "host:port" and "[v6]:port". */
    static String[] hostPort(String address) {
        String a = address.strip();
        String host = a, port = null;
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            host = end > 0 ? a.substring(1, end) : a;
            if (end > 0 && a.length() > end + 2 && a.charAt(end + 1) == ':') port = a.substring(end + 2);
        } else if (a.indexOf(':') > 0 && a.indexOf(':') == a.lastIndexOf(':')) {
            host = a.substring(0, a.indexOf(':'));
            port = a.substring(a.indexOf(':') + 1);
        }
        int p = 25565;
        if (port != null) {
            try { p = Integer.parseInt(port.strip()); } catch (NumberFormatException e) { port = null; }
            if (p < 1 || p > 65535) { p = 25565; port = null; }
        }
        return new String[]{host.toLowerCase(Locale.ROOT), String.valueOf(p), port};
    }

    /** The {@code _minecraft._tcp.<host>} SRV target {host, port}, or null (no record, or no DNS provider). */
    private static String[] srv(String host) {
        if (host.matches("[0-9.]+") || host.contains(":")) return null;
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "1500");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
            try {
                javax.naming.directory.Attribute a = ctx.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"}).get("SRV");
                if (a == null || a.size() == 0) return null;
                String[] parts = a.get(0).toString().split(" ");
                if (parts.length < 4) return null;
                String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                return new String[]{target, parts[2]};
            } finally {
                ctx.close();
            }
        } catch (Throwable e) {
            return null; // no record, or the runtime has no DNS provider: use the address as typed
        }
    }

    private static void packet(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        varInt(b, body.length);
        b.write(body);
        out.write(b.toByteArray());
    }

    private static void string(ByteArrayOutputStream b, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        varInt(b, bytes.length);
        b.write(bytes);
    }

    static void varInt(ByteArrayOutputStream b, int v) {
        while (true) {
            if ((v & ~0x7F) == 0) { b.write(v); return; }
            b.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }

    static int readVarInt(InputStream in) throws IOException {
        int v = 0, shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("Connection closed");
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 35) throw new IOException("Bad VarInt");
        }
    }
}
