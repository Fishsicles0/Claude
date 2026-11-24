package net.evadeclient.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** servers.dat: NBT round trip, and the launcher's servers go on top without ever removing the player's own. */
class ServersDatTest {
    private static Map<String, Object> server(String name, String ip) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("ip", ip);
        c.put("acceptTextures", new ServersDat.Tagged(1, (byte) 1));
        return c;
    }

    @Test
    void nbtRoundTrip() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("servers", new ServersDat.NbtList(10, List.of(server("Mine", "mine.example:25565"))));
        byte[] bytes = ServersDat.write(root);
        Map<String, Object> back = ServersDat.read(bytes);
        assertArrayEquals(bytes, ServersDat.write(back));
        ServersDat.NbtList l = (ServersDat.NbtList) back.get("servers");
        @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) l.items().get(0);
        assertEquals("mine.example:25565", c.get("ip"));
        assertEquals(new ServersDat.Tagged(1, (byte) 1), c.get("acceptTextures"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(Exception.class, () -> ServersDat.read(new byte[]{8, 0, 0}));
    }

    @Test
    void keyIgnoresDefaultPortAndCase() {
        assertEquals("play.example.net", ServersDat.key(" Play.Example.NET:25565 "));
        assertEquals("play.example.net:25566", ServersDat.key("play.example.net:25566"));
    }

    @Test
    void mergeKeepsPlayerEntries(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("servers.dat");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("servers", new ServersDat.NbtList(10, List.of(server("My server", "mine.example"), server("Their name", "evade.example"))));
        Files.write(file, ServersDat.write(root));

        Set<String> owned = ServersDat.merge(file, List.of(new ServersDat.Entry("Evade Hub", "evade.example:25565", null),
                new ServersDat.Entry("New", "new.example", null)), Set.of());
        // ours first; the player's own entry for the same address keeps its place at the top only once
        assertEquals(List.of("evade.example", "new.example", "mine.example"), ServersDat.addresses(file));
        assertEquals(Set.of("new.example"), owned);
        assertTrue(Files.exists(dir.resolve("servers.dat_old")));

        // next sync without "new": the launcher removes only what it added
        ServersDat.merge(file, List.of(), owned);
        assertEquals(List.of("evade.example", "mine.example"), ServersDat.addresses(file));
    }
}
