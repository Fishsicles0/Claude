package net.evadeclient.installer;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The launcher's half of Safe Start writes exactly the file the mod reads. */
class SafeStartTest {
    @Test
    void requestContract(@TempDir Path game) throws Exception {
        long now = 1_800_000_000_000L;
        SafeStart.request(game, now);
        Path f = game.resolve(".evade").resolve("safe-start.json");
        assertTrue(Files.isRegularFile(f));
        JsonObject o = InstallerCore.readJsonObject(f);
        assertEquals(now, o.get("requested").getAsLong());
        assertEquals("launcher", o.get("by").getAsString());
        assertTrue(SafeStart.pending(game, now + 1000));
        assertFalse(SafeStart.pending(game, now + SafeStart.TTL_MS), "stale after ten minutes");
        assertFalse(SafeStart.pending(game, now - 1), "not from the future");
        SafeStart.cancel(game);
        assertFalse(Files.exists(f));
        assertFalse(SafeStart.pending(game, now));
    }
}
