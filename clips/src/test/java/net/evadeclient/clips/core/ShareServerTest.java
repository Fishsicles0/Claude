package net.evadeclient.clips.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which share servers Evade Clips will talk to: https only, plain http only to this computer. */
class ShareServerTest {
    @Test
    void httpsAndLocalTestServersAreAllowed() {
        assertNull(CloudShare.serverProblem("https://clips.evadeclient.com"));
        assertNull(CloudShare.serverProblem("https://clips.example.com/"));
        assertNull(CloudShare.serverProblem("http://127.0.0.1:8787"));
        assertNull(CloudShare.serverProblem("http://localhost:8787"));
        assertNull(CloudShare.serverProblem("http://[::1]:8787"));
        assertNull(CloudShare.serverProblem(""));
    }

    @Test
    void plainHttpAndJunkAreRefused() {
        assertNotNull(CloudShare.serverProblem("http://clips.example.com"));
        assertNotNull(CloudShare.serverProblem("http://192.168.1.5:8787"));
        assertNotNull(CloudShare.serverProblem("ftp://clips.example.com"));
        assertNotNull(CloudShare.serverProblem("clips.example.com"));
        assertNotNull(CloudShare.serverProblem("http://localhost.evil.com"));
    }

    @Test
    void defaultServerIsRecognised() {
        assertTrue(CloudShare.isDefaultServer("https://clips.evadeclient.com/"));
        assertTrue(CloudShare.isDefaultServer("HTTPS://Clips.EvadeClient.com"));
        assertFalse(CloudShare.isDefaultServer("https://clips.example.com"));
    }
}
