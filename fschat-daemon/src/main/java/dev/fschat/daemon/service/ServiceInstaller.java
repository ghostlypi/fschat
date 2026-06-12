package dev.fschat.daemon.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * Installs a systemd <em>user</em> service so the daemon runs without anyone typing
 * {@code start}: it auto-starts on login/boot (with linger), restarts on crash, and
 * stays connected in the background. Used by {@code register}/{@code login} so the
 * common flow is "register once, then just use it".
 *
 * <p>Everything here is best-effort and never throws into the CLI: if systemd isn't
 * available (non-Linux, no {@code systemctl}) the caller falls back to telling the
 * user to run {@code fschat-daemon start} themselves.
 */
public final class ServiceInstaller {

    public static final String UNIT = "fschat.service";

    private ServiceInstaller() {
    }

    /** True if we can manage a systemd user service on this host. */
    public static boolean available() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") && which("systemctl") != null;
    }

    /** Is the named unit file already present? */
    public static boolean installed(String unit) {
        return Files.exists(unitPath(unit));
    }

    /**
     * Write the named unit, reload, enable lingering, and {@code enable --now}.
     * Returns a human-readable status line; never throws. A per-account unit name
     * (e.g. {@code fschat-antighostlypi.service}) lets several accounts each run
     * their own always-on daemon on one machine.
     *
     * @param unit      the systemd user unit name (e.g. {@code fschat.service})
     * @param execStart the full ExecStart command (launcher + "start" + flags)
     */
    public static String install(String unit, List<String> execStart) {
        try {
            Path unitFile = unitPath(unit);
            Files.createDirectories(unitFile.getParent());
            Files.writeString(unitFile, unitContent(execStart));
            try {
                Files.setPosixFilePermissions(unitFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignore) {
                // non-POSIX fs; the contents aren't very sensitive
            }
            run("systemctl", "--user", "daemon-reload");
            // Linger lets the service run at boot without an interactive login. Best-effort:
            // on locked-down hosts this needs `sudo loginctl enable-linger <user>` once.
            run("loginctl", "enable-linger", System.getProperty("user.name"));
            int rc = run("systemctl", "--user", "enable", "--now", unit);
            if (rc == 0) {
                return "daemon installed as systemd user service '" + unit + "' (auto-starts on "
                        + "login/boot, restarts on crash). Manage: systemctl --user status|restart|stop " + unit;
            }
            return "wrote " + unitFile + ", but 'systemctl --user enable --now' returned " + rc
                    + " — enable it once with:  systemctl --user enable --now " + unit;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "could not install the systemd service (" + e.getMessage()
                    + "); run the daemon yourself with:  fschat-daemon start &";
        }
    }

    /** Disable + remove the named unit. Returns a status line; never throws. */
    public static String uninstall(String unit) {
        try {
            run("systemctl", "--user", "disable", "--now", unit);
            Files.deleteIfExists(unitPath(unit));
            run("systemctl", "--user", "daemon-reload");
            return "removed systemd user service '" + unit + "'";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "could not remove the service: " + e.getMessage();
        }
    }

    /**
     * The systemd unit name for a given config-dir. The default config-dir uses
     * {@code fschat.service}; any other dir gets {@code fschat-<tag>.service} (tag
     * derived from the dir name), so each account has its own always-on service.
     */
    public static String unitFor(Path configDir) {
        Path def = Path.of(System.getProperty("user.home"), ".config", "fschat").toAbsolutePath().normalize();
        Path cfg = configDir.toAbsolutePath().normalize();
        if (cfg.equals(def)) {
            return UNIT;
        }
        String tag = cfg.getFileName().toString().replaceAll("[^A-Za-z0-9_.-]", "-");
        // Avoid "fschat-fschat-..." when the dir is already named like fschat-<x>.
        return (tag.startsWith("fschat") ? tag : "fschat-" + tag) + ".service";
    }

    /** The unit file body for the given ExecStart command. Package-visible for testing. */
    static String unitContent(List<String> execStart) {
        StringBuilder exec = new StringBuilder();
        for (String arg : execStart) {
            if (exec.length() > 0) {
                exec.append(' ');
            }
            exec.append(arg.contains(" ") ? '"' + arg + '"' : arg);
        }
        return """
                [Unit]
                Description=fschat daemon
                StartLimitIntervalSec=0

                [Service]
                Type=simple
                ExecStart=%s
                Restart=always
                RestartSec=3

                [Install]
                WantedBy=default.target
                """.formatted(exec);
    }

    /**
     * Resolve the installed launcher script ({@code bin/fschat-daemon}) so the service
     * doesn't depend on PATH. Falls back to PATH lookup, then the bare name.
     */
    public static Path launcher() {
        try {
            Path jar = Path.of(ServiceInstaller.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // jar lives at <home>/fschat-daemon/lib/<name>.jar -> launcher at ../bin/fschat-daemon
            Path lib = jar.getParent();
            if (lib != null && "lib".equals(lib.getFileName().toString())) {
                Path bin = lib.getParent().resolve("bin").resolve("fschat-daemon");
                if (Files.isExecutable(bin)) {
                    return bin;
                }
            }
        } catch (Exception ignore) {
            // fall through to PATH lookup
        }
        Path onPath = which("fschat-daemon");
        return onPath != null ? onPath : Path.of("fschat-daemon");
    }

    private static Path unitPath(String unit) {
        return Path.of(System.getProperty("user.home"), ".config", "systemd", "user", unit);
    }

    private static Path which(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(":")) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static int run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return p.waitFor();
    }
}
