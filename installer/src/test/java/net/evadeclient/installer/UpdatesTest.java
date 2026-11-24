package net.evadeclient.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Update checks and pins: what counts as an update, and that Update all skips pinned projects. */
class UpdatesTest {
    private static ContentApi.Version v(String id, String project, String sha1, String url, String sha512) {
        return new ContentApi.Version(id, project, "1", "v", "release", "", url, "f.jar", sha1, sha512, 1,
                List.of(), List.of("fabric"), List.of(InstallerCore.MC_VERSION), "");
    }

    private static Updates.Known known(String project, ContentApi.Version cur, ContentApi.Version latest) {
        return new Updates.Known(Path.of("mods", project + ".jar"), Content.Type.MOD, "old-sha1", cur, latest);
    }

    @Test
    void hasUpdate() {
        var cur = v("v1", "AAAAAAAA", "old-sha1", "u", "s");
        assertTrue(known("a", cur, v("v2", "AAAAAAAA", "new", "u", "s")).hasUpdate());
        assertFalse(known("a", cur, v("v1", "AAAAAAAA", "old-sha1", "u", "s")).hasUpdate(), "same version");
        assertFalse(known("a", cur, null).hasUpdate(), "nothing newer");
        assertFalse(known("a", cur, v("v2", "AAAAAAAA", "new", null, "s")).hasUpdate(), "no download");
        assertFalse(known("a", cur, v("v2", "AAAAAAAA", "new", "u", null)).hasUpdate(), "no checksum to verify");
        assertFalse(known("a", cur, v("v2", "AAAAAAAA", "OLD-SHA1", "u", "s")).hasUpdate(), "same file, different id");
        assertTrue(known("a", null, v("v2", "AAAAAAAA", "new", "u", "s")).hasUpdate(), "unknown current version");
    }

    @Test
    void pinsAreSkippedByUpdateAll(@TempDir Path game) throws Exception {
        var a = known("a", v("a1", "AAAAAAAA", "old-sha1", "u", "s"), v("a2", "AAAAAAAA", "x", "u", "s"));
        var b = known("b", v("b1", "BBBBBBBB", "old-sha1", "u", "s"), v("b2", "BBBBBBBB", "y", "u", "s"));
        var c = known("c", v("c1", "CCCCCCCC", "old-sha1", "u", "s"), null);
        assertEquals(List.of(a, b), ContentPins.unpinnedUpdates(game, List.of(a, b, c)));
        ContentPins.setPinned(game, "BBBBBBBB", true);
        assertTrue(ContentPins.pinned(game, "BBBBBBBB"));
        assertEquals(List.of(a), ContentPins.unpinnedUpdates(game, List.of(a, b, c)));
        ContentPins.setPinned(game, "BBBBBBBB", false);
        assertFalse(ContentPins.pinned(game, "BBBBBBBB"));
        assertFalse(ContentPins.pinned(game, null));
    }
}
