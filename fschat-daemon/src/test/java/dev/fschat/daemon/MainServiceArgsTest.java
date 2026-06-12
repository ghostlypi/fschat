package dev.fschat.daemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings baked into the systemd unit must reproduce the connection exactly. */
class MainServiceArgsTest {

    @Test
    void plaintextStartArgsOmitTls() {
        Main.ServerOpts s = new Main.ServerOpts();
        s.host = "127.0.0.1";
        s.authPort = 8443;
        s.wsPort = 8444;
        s.tlsFlag = false;

        List<String> args = s.startArgs();
        assertEquals(List.of("--host", "127.0.0.1", "--auth-port", "8443", "--ws-port", "8444"), args);
        assertFalse(args.contains("--tls"));
    }

    @Test
    void tlsStartArgsIncludeTruststore() {
        Main.ServerOpts s = new Main.ServerOpts();
        s.host = "192.168.1.9";
        s.authPort = 8443;
        s.wsPort = 8444;
        s.tlsFlag = true;
        s.truststoreOpt = Path.of("/tmp/ts.p12");
        s.truststorePass = "devpass";

        List<String> args = s.startArgs();
        assertTrue(args.contains("--tls"));
        assertTrue(args.contains("--truststore"));
        assertTrue(args.contains("/tmp/ts.p12"));
        assertTrue(args.contains("--truststore-pass"));
        assertTrue(args.contains("devpass"));
    }

    @Test
    void pathStartArgsAreAbsolute() {
        Main.PathOpts p = new Main.PathOpts();
        p.configDir = Path.of("rel/cfg");
        p.root = Path.of("rel/chats");

        List<String> args = p.startArgs();
        assertEquals("--config-dir", args.get(0));
        assertTrue(args.get(1).startsWith("/"), args.get(1));
        assertEquals("--root", args.get(2));
        assertTrue(args.get(3).startsWith("/"), args.get(3));
    }
}
