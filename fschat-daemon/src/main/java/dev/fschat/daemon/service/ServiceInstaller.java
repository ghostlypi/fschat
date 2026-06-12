package dev.fschat.daemon.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * Installs an always-on user service so the daemon runs without anyone typing
 * {@code start}: it auto-starts at login, restarts on crash, and stays connected
 * in the background. Used by {@code register}/{@code login} so the flow is the same
 * on every OS — "register once, then just use it".
 *
 * <p>Two backends, chosen automatically: <b>systemd user units</b> on Linux and
 * <b>launchd LaunchAgents</b> on macOS. Everything is best-effort and never throws
 * into the CLI; if no service manager is available the caller falls back to telling
 * the user to run {@code fschat-daemon start} themselves.
 */
public final class ServiceInstaller {

    /** Default systemd unit name (Linux, default account). */
    public static final String UNIT = "fschat.service";
    /** Default launchd label (macOS, default account). */
    public static final String LABEL = "com.fschat.daemon";

    private ServiceInstaller() {
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    private static String home() {
        return System.getProperty("user.home");
    }

    /** True if we can manage an always-on user service on this host. */
    public static boolean available() {
        if (isLinux()) {
            return which("systemctl") != null;
        }
        if (isMac()) {
            return which("launchctl") != null;
        }
        return false;
    }

    /**
     * The service identifier for a config-dir: a systemd unit name on Linux
     * ({@code fschat.service} / {@code fschat-<tag>.service}) or a launchd label on
     * macOS ({@code com.fschat.daemon} / {@code com.fschat.daemon.<tag>}). A distinct
     * id per (non-default) config-dir lets several accounts each run their own
     * always-on daemon on one machine.
     */
    public static String unitFor(Path configDir) {
        String tag = accountTag(configDir);
        if (isMac()) {
            return tag.isEmpty() ? LABEL : LABEL + "." + tag;
        }
        return tag.isEmpty() ? UNIT : "fschat-" + tag + ".service";
    }

    /** "" for the default config-dir, else a sanitized account tag from the dir name. */
    private static String accountTag(Path configDir) {
        Path def = Path.of(home(), ".config", "fschat").toAbsolutePath().normalize();
        Path cfg = configDir.toAbsolutePath().normalize();
        if (cfg.equals(def)) {
            return "";
        }
        String name = cfg.getFileName().toString();
        String stripped = name.replaceFirst("^fschat[-_.]?", ""); // fschat-antighostlypi -> antighostlypi
        if (stripped.isEmpty()) {
            stripped = name;
        }
        return stripped.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    /** Is the named service installed? */
    public static boolean installed(String unit) {
        return Files.exists(isMac() ? plistPath(unit) : systemdUnitPath(unit));
    }

    /**
     * Write the service definition and start + enable it. Returns a human-readable
     * status line; never throws.
     *
     * @param unit      id from {@link #unitFor} (systemd unit name or launchd label)
     * @param execStart the full command (launcher + "start" + flags)
     */
    public static String install(String unit, List<String> execStart) {
        try {
            return isMac() ? installLaunchd(unit, execStart) : installSystemd(unit, execStart);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "could not install the service (" + e.getMessage()
                    + "); run the daemon yourself with:  fschat-daemon start &";
        }
    }

    /** Stop + remove the named service. Returns a status line; never throws. */
    public static String uninstall(String unit) {
        try {
            if (isMac()) {
                Path plist = plistPath(unit);
                run("launchctl", "unload", "-w", plist.toString());
                Files.deleteIfExists(plist);
                return "removed launchd agent '" + unit + "'";
            }
            run("systemctl", "--user", "disable", "--now", unit);
            Files.deleteIfExists(systemdUnitPath(unit));
            run("systemctl", "--user", "daemon-reload");
            return "removed systemd user service '" + unit + "'";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "could not remove the service: " + e.getMessage();
        }
    }

    // --- systemd (Linux) ---------------------------------------------------

    private static String installSystemd(String unit, List<String> execStart)
            throws IOException, InterruptedException {
        Path unitFile = systemdUnitPath(unit);
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
    }

    /** The systemd unit file body for the given command. Package-visible for testing. */
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

    private static Path systemdUnitPath(String unit) {
        return Path.of(home(), ".config", "systemd", "user", unit);
    }

    // --- launchd (macOS) ---------------------------------------------------

    private static String installLaunchd(String label, List<String> execStart)
            throws IOException, InterruptedException {
        Path plist = plistPath(label);
        Files.createDirectories(plist.getParent());
        Files.createDirectories(Path.of(home(), "Library", "Logs")); // for the log paths
        Files.writeString(plist, plistContent(label, execStart));
        // Reload cleanly: unload an old copy (ignore failure), then load + enable at login.
        run("launchctl", "unload", plist.toString());
        int rc = run("launchctl", "load", "-w", plist.toString());
        if (rc == 0) {
            return "daemon installed as launchd agent '" + label + "' (auto-starts at login, "
                    + "restarts on crash). Manage: launchctl kickstart|stop|print gui/$(id -u)/" + label;
        }
        return "wrote " + plist + ", but 'launchctl load' returned " + rc
                + " — load it once with:  launchctl load -w " + plist;
    }

    /** The launchd plist body for the given command. Package-visible for testing. */
    static String plistContent(String label, List<String> execStart) {
        StringBuilder args = new StringBuilder();
        for (String arg : execStart) {
            args.append("    <string>").append(xml(arg)).append("</string>\n");
        }
        String log = Path.of(home(), "Library", "Logs", label + ".log").toString();
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>Label</key><string>%s</string>
                  <key>ProgramArguments</key>
                  <array>
                %s  </array>
                  <key>RunAtLoad</key><true/>
                  <key>KeepAlive</key><true/>
                  <key>StandardOutPath</key><string>%s</string>
                  <key>StandardErrorPath</key><string>%s</string>
                </dict>
                </plist>
                """.formatted(xml(label), args.toString(), xml(log), xml(log));
    }

    private static Path plistPath(String label) {
        return Path.of(home(), "Library", "LaunchAgents", label + ".plist");
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // --- shared ------------------------------------------------------------

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
