package net.evadeclient.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Instance zips: entries can't escape the instance or overwrite the launcher's own files. */
class InstanceZipTest {
    @Test
    void safeTarget(@TempDir Path root) {
        assertEquals(root.resolve("saves/World/level.dat"), InstanceZip.safeTarget(root, "saves/World/level.dat"));
        assertEquals(root.resolve("config"), InstanceZip.safeTarget(root, "config/"));
        assertEquals(root.resolve(GameState.DIR).resolve(InstanceExtras.FILE), InstanceZip.safeTarget(root, GameState.DIR + "/" + InstanceExtras.FILE));
        for (String bad : new String[]{"../x", "/abs", "C:/x", "a\\b", "a/../../b", "a/./b", "", "x\0y",
                "evade-install.json", Launcher.INSTANCE_FILE, ".evade-staging", GameState.DIR + "/session.json"}) {
            assertNull(InstanceZip.safeTarget(root, bad), bad);
        }
    }

    @Test
    void fileName(@TempDir Path dir) {
        assertEquals("My-PvP-pack-evade-instance.zip", InstanceZip.fileName(new Launcher.Instance("a", dir, "My PvP pack!")));
        assertEquals("instance-evade-instance.zip", InstanceZip.fileName(new Launcher.Instance("a", dir, "???")));
    }
}
