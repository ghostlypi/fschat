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
    void launcherResolvesToSomething() {
        // In a test JVM the code source isn't the installed layout, so this falls back to
        // PATH or the bare name — but it must never be null.
        assertNotNull(ServiceInstaller.launcher());
    }

    @Test
    void availableDoesNotThrow() {
        // Just exercises the OS/systemctl probe; value depends on the host.
        boolean ok = ServiceInstaller.available();
        assertEquals(ok, ServiceInstaller.available());
    }
}
