package dev.fschat.daemon.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceInstallerTest {

    @Test
    void unitContentHasRestartAndQuotedExec() {
        String unit = ServiceInstaller.unitContent(List.of(
                "/home/pi/.fschat/fschat-daemon/bin/fschat-daemon", "start",
                "--host", "192.168.1.9", "--tls",
                "--truststore", "/home/pi/my certs/ts.p12"));

        assertTrue(unit.contains("Restart=always"), unit);
        assertTrue(unit.contains("WantedBy=default.target"), unit);
        // The path with a space must be quoted; flags without spaces must not be.
        assertTrue(unit.contains("\"/home/pi/my certs/ts.p12\""), unit);
        assertTrue(unit.contains("ExecStart=/home/pi/.fschat/fschat-daemon/bin/fschat-daemon start "
                + "--host 192.168.1.9 --tls"), unit);
    }

    @Test
    void plistHasProgramArgsAndKeepAlive() {
        String plist = ServiceInstaller.plistContent("com.fschat.daemon", List.of(
                "/home/pi/.fschat/fschat-daemon/bin/fschat-daemon", "start",
                "--host", "fschat.ghostlypi.com", "--tls"));

        assertTrue(plist.contains("<key>Label</key><string>com.fschat.daemon</string>"), plist);
        assertTrue(plist.contains("<key>RunAtLoad</key><true/>"), plist);
        assertTrue(plist.contains("<key>KeepAlive</key><true/>"), plist);
        // each command token is its own <string> in ProgramArguments
        assertTrue(plist.contains("<string>start</string>"), plist);
        assertTrue(plist.contains("<string>fschat.ghostlypi.com</string>"), plist);
    }

    @Test
    void launcherResolvesToSomething() {
        // In a test JVM the code source isn't the installed layout, so this falls back to
        // PATH or the bare name — but it must never be null.
        assertNotNull(ServiceInstaller.launcher());
    }

    @Test
    void unitNameIsPerConfigDir() {
        String home = System.getProperty("user.home");
        // Default config-dir keeps the plain unit name.
        assertEquals("fschat.service", ServiceInstaller.unitFor(java.nio.file.Path.of(home, ".config", "fschat")));
        // A custom dir gets its own unit (no "fschat-fschat-" doubling).
        assertEquals("fschat-antighostlypi.service",
                ServiceInstaller.unitFor(java.nio.file.Path.of(home, ".config", "fschat-antighostlypi")));
        assertEquals("fschat-foo.service",
                ServiceInstaller.unitFor(java.nio.file.Path.of(home, ".config", "foo")));
    }

    @Test
    void availableDoesNotThrow() {
        // Just exercises the OS/systemctl probe; value depends on the host.
        boolean ok = ServiceInstaller.available();
        assertEquals(ok, ServiceInstaller.available());
    }
}
