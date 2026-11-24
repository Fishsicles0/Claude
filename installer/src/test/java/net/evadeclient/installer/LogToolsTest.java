package net.evadeclient.installer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Log sharing must never leak a sign-in token, an IP address, the Windows/Linux user name or the player's name. */
class LogToolsTest {
    @Test
    void masksTokensAndSessionIds() {
        String s = LogTools.mask("Launching with --accessToken eyJhbGciOiJI.payload.sig --uuid 1234abcd", null);
        assertFalse(s.contains("eyJhbGciOiJI"), s);
        assertFalse(s.contains("1234abcd"), s);
        assertTrue(s.contains("--accessToken <hidden>"), s);
        assertEquals("Session ID is <hidden>", LogTools.mask("Session ID is token:abc123:def", null));
        assertEquals("Authorization: Bearer <hidden>", LogTools.mask("Authorization: Bearer abc.DEF-123_x", null));
        assertTrue(LogTools.mask("{\"access_token\":\"s3cr3t\"}", null).contains("<hidden>"));
        String jwt = "eyJ" + "a".repeat(10) + "." + "b".repeat(10) + "." + "c".repeat(10);
        assertEquals("tok <token>", LogTools.mask("tok " + jwt, null));
    }

    @Test
    void masksAddressesButKeepsLoopbackAndVersions() {
        assertEquals("Connecting to x.x.x.x, 25565", LogTools.mask("Connecting to 203.0.113.7, 25565", null));
        assertEquals("bound to 127.0.0.1:25565", LogTools.mask("bound to 127.0.0.1:25565", null));
        assertEquals("listening on 0.0.0.0", LogTools.mask("listening on 0.0.0.0", null));
        // a version like 1.21.11.4 is four numbers with dots but not an address past 255
        assertEquals("v 999.1.2.3", LogTools.mask("v 999.1.2.3", null));
        assertEquals("peer x:x:x:x", LogTools.mask("peer 2001:db8:85a3:0:0:8a2e:370:7334", null));
    }

    @Test
    void masksUserFoldersAndPlayerName() {
        assertEquals("C:\\Users\\<user>\\AppData", LogTools.mask("C:\\Users\\alice\\AppData", null));
        assertEquals("/home/<user>/.minecraft", LogTools.mask("/home/alice/.minecraft", null));
        assertEquals("Setting user: <player>", LogTools.mask("Setting user: Notch", null));
        assertEquals("<player> joined the game", LogTools.mask("Steve123 joined the game", "Steve123"));
        // only whole names: a longer name containing it stays
        assertEquals("Steve1234 joined", LogTools.mask("Steve1234 joined", "Steve123"));
        // names under three characters are never replaced (too many false hits)
        assertEquals("ab joined", LogTools.mask("ab joined", "ab"));
    }

    @Test
    void levelsFollowStackTraces() {
        List<LogTools.Line> lines = LogTools.parse(List.of(
                "[12:00:00] [Render thread/INFO]: hello",
                "[12:00:01] [Render thread/ERROR]: oh no",
                "java.lang.IllegalStateException: boom",
                "\tat a.b.C.d(C.java:1)",
                "[12:00:02] [Render thread/WARN]: careful",
                "[12:00:03] [main/FATAL]: dead"));
        assertEquals(List.of(LogTools.Level.INFO, LogTools.Level.ERROR, LogTools.Level.ERROR, LogTools.Level.ERROR,
                LogTools.Level.WARN, LogTools.Level.ERROR), lines.stream().map(LogTools.Line::level).toList());
        assertEquals(5, LogTools.filter(lines, 1, null).size());
        assertEquals(4, LogTools.filter(lines, 2, "").size());
        assertEquals(1, LogTools.filter(lines, 0, "CAREFUL").size());
    }
}
