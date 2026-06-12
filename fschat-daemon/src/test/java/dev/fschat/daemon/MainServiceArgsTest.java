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
    void startArgsAlwaysIncludeHostAndPorts() {
        Main.ServerOpts s = new Main.ServerOpts();
        s.host = "fschat.ghostlypi.com";
        s.authPort = 7443;
        s.wsPort = 7444;

        List<String> args = s.startArgs();
        assertEquals("--host", args.get(0));
        assertEquals("fschat.ghostlypi.com", args.get(1));
        assertTrue(args.contains("--auth-port") && args.contains("7443"));
        assertTrue(args.contains("--ws-port") && args.contains("7444"));
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
