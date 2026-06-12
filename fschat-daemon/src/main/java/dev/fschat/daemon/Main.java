package dev.fschat.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fschat.daemon.config.DaemonConfig;
import dev.fschat.daemon.local.LocalControlClient;
import dev.fschat.daemon.remote.AuthClient;
import dev.fschat.daemon.remote.ClientTls;
import dev.fschat.daemon.service.ServiceInstaller;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Daemon entry point and CLI.
 *
 * <pre>
 *   fschat-daemon register alice            # create an account, save token
 *   fschat-daemon login alice               # log in, save token
 *   fschat-daemon start                     # run the long-lived daemon
 *   fschat-daemon dm bob                     # ask the running daemon to open a DM
 *   fschat-daemon group-new "trip" bob carol
 *   fschat-daemon group-add ch_123 dave
 * </pre>
 *
 * Defaults are plaintext localhost dev ports; pass {@code --tls --truststore ...}
 * for wss/https.
 */
@Command(name = "fschat-daemon", mixinStandardHelpOptions = true,
        description = "fschat daemon and CLI.",
        subcommands = {Main.Register.class, Main.Login.class, Main.Start.class,
                Main.Service.class, Main.Create.class, Main.Dm.class, Main.GroupNew.class,
                Main.GroupAdd.class, Main.GroupRemove.class})
public final class Main implements Runnable {

    /** Connection options to the server. */
    static final class ServerOpts {
        // The public service is baked in as the default so testers just `register`.
        // Each option still falls back to an env var (set FSCHAT_HOST=127.0.0.1 etc. for
        // local dev against your own server). TLS is ON by default; FSCHAT_TLS=false
        // forces plaintext for local dev.
        @Option(names = "--host", defaultValue = "${env:FSCHAT_HOST:-fschat.ghostlypi.com}",
                description = "Server host (env: FSCHAT_HOST; default: ${DEFAULT-VALUE}).")
        String host;
        @Option(names = "--auth-port", defaultValue = "${env:FSCHAT_AUTH_PORT:-7443}",
                description = "HTTPS auth port (env: FSCHAT_AUTH_PORT; default: ${DEFAULT-VALUE}).")
        int authPort;
        @Option(names = "--ws-port", defaultValue = "${env:FSCHAT_WS_PORT:-7444}",
                description = "WSS stream port (env: FSCHAT_WS_PORT; default: ${DEFAULT-VALUE}).")
        int wsPort;
        @Option(names = "--tls", description = "Force https/wss (on by default; FSCHAT_TLS=false for plaintext).")
        boolean tlsFlag;
        @Option(names = "--truststore", description = "PKCS12 truststore for the server cert (env: FSCHAT_TRUSTSTORE).")
        Path truststoreOpt;
        @Option(names = "--truststore-pass", defaultValue = "${env:FSCHAT_TRUSTSTORE_PASS:-}",
                description = "Truststore password (env: FSCHAT_TRUSTSTORE_PASS).")
        String truststorePass;

        private boolean tls() {
            if (tlsFlag) {
                return true;
            }
            // Default ON for the public TLS service; FSCHAT_TLS=false (or 0/no) opts out.
            String v = System.getenv("FSCHAT_TLS");
            if (v == null || v.isBlank()) {
                return true;
            }
            return !(v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no"));
        }

        private Path truststore() {
            if (truststoreOpt != null) {
                return truststoreOpt;
            }
            String v = System.getenv("FSCHAT_TRUSTSTORE");
            return (v != null && !v.isBlank()) ? Path.of(v) : null;
        }

        String authBase() {
            return (tls() ? "https" : "http") + "://" + host + ":" + authPort;
        }

        URI wsUri() {
            return URI.create((tls() ? "wss" : "ws") + "://" + host + ":" + wsPort);
        }

        SSLContext ssl() {
            Path ts = truststore();
            return (tls() && ts != null)
                    ? ClientTls.trusting(ts, truststorePass.toCharArray())
                    : null;
        }

        /** Explicit flags reproducing these settings, so a baked service doesn't rely on env. */
        List<String> startArgs() {
            List<String> a = new ArrayList<>(List.of(
                    "--host", host,
                    "--auth-port", String.valueOf(authPort),
                    "--ws-port", String.valueOf(wsPort)));
            if (tls()) {
                a.add("--tls");
                Path ts = truststore();
                if (ts != null) {
                    a.add("--truststore");
                    a.add(ts.toAbsolutePath().toString());
                    a.add("--truststore-pass");
                    a.add(truststorePass);
                }
            }
            return a;
        }
    }

    /** Local filesystem layout options. */
    static final class PathOpts {
        @Option(names = "--config-dir", description = "Token/port/queue dir (default: ${DEFAULT-VALUE}).")
        Path configDir = Path.of(System.getProperty("user.home"), ".config", "fschat");
        @Option(names = "--root", description = "Chat files root (default: ${DEFAULT-VALUE}).")
        Path root = Path.of(System.getProperty("user.home"), "fschat");

        DaemonConfig config() {
            return new DaemonConfig(configDir, root);
        }

        /** Explicit flags reproducing these paths, for a baked service command. */
        List<String> startArgs() {
            return List.of(
                    "--config-dir", configDir.toAbsolutePath().toString(),
                    "--root", root.toAbsolutePath().toString());
        }
    }

    @Command(name = "register", description = "Create an account and save its token.")
    static final class Register implements Callable<Integer> {
        @Mixin ServerOpts server;
        @Mixin PathOpts paths;
        @Parameters(index = "0", description = "Username.") String username;
        @Option(names = "--password", description = "Password (prompted if omitted).") String password;
        @Option(names = {"-i", "--invite"}, description = "Invite code (required; or set FSCHAT_INVITE).")
        String invite;
        @Option(names = "--no-service", description = "Don't install the always-on systemd user service.")
        boolean noService;

        @Override
        public Integer call() {
            String code = (invite != null && !invite.isBlank()) ? invite : System.getenv("FSCHAT_INVITE");
            if (code == null || code.isBlank()) {
                System.err.println("error: registration requires an invite code "
                        + "(pass --invite <code> or set FSCHAT_INVITE)");
                return 1;
            }
            String pw = password != null ? password : prompt("password for " + username + ": ");
            AuthClient.AuthInfo info;
            try {
                info = new AuthClient(server.authBase(), server.ssl()).register(username, pw, code);
            } catch (AuthClient.AuthFailed e) {
                System.err.println("error: " + e.getMessage());
                return 1;
            }
            paths.config().writeToken(info.token());
            System.out.println("registered as " + info.handle() + "; token saved to " + paths.config().tokenFile());
            System.out.println("others can reach you with:  " + info.handle());
            setUpService(server, paths, noService);
            return 0;
        }
    }

    @Command(name = "login", description = "Log in and save the token.")
    static final class Login implements Callable<Integer> {
        @Mixin ServerOpts server;
        @Mixin PathOpts paths;
        @Parameters(index = "0", description = "Username, or name:hexid if the name is shared.") String username;
        @Option(names = "--password", description = "Password (prompted if omitted).") String password;
        @Option(names = "--no-service", description = "Don't install the always-on systemd user service.")
        boolean noService;

        @Override
        public Integer call() {
            String pw = password != null ? password : prompt("password for " + username + ": ");
            AuthClient.AuthInfo info;
            try {
                info = new AuthClient(server.authBase(), server.ssl()).login(username, pw);
            } catch (AuthClient.AuthFailed e) {
                System.err.println("error: " + e.getMessage());
                return 1;
            }
            paths.config().writeToken(info.token());
            System.out.println("logged in as " + info.handle());
            setUpService(server, paths, noService);
            return 0;
        }
    }

    @Command(name = "start", description = "Run the long-lived daemon.")
    static final class Start implements Callable<Integer> {
        @Mixin ServerOpts server;
        @Mixin PathOpts paths;

        @Override
        public Integer call() throws Exception {
            DaemonConfig cfg = paths.config();
            try (DaemonApp app = new DaemonApp(cfg, server.wsUri(), server.ssl())) {
                app.start();
                System.out.printf("fschat-daemon started: server=%s localPort=%d chatRoot=%s%n",
                        server.wsUri(), app.localPort(), cfg.root());
                CountDownLatch latch = new CountDownLatch(1);
                Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
                latch.await();
            }
            return 0;
        }
    }

    @Command(name = "service", description = "Manage the always-on systemd user service (install|uninstall|status).")
    static final class Service implements Callable<Integer> {
        @Mixin ServerOpts server;
        @Mixin PathOpts paths;
        @Parameters(index = "0", description = "install | uninstall | status") String action;

        @Override
        public Integer call() {
            String unit = ServiceInstaller.unitFor(paths.configDir);
            switch (action == null ? "" : action.toLowerCase()) {
                case "install" -> {
                    if (!ServiceInstaller.available()) {
                        System.err.println("error: systemd --user is not available on this host");
                        return 1;
                    }
                    System.out.println(ServiceInstaller.install(unit, serviceExec(server, paths)));
                    return 0;
                }
                case "uninstall" -> {
                    System.out.println(ServiceInstaller.uninstall(unit));
                    return 0;
                }
                case "status" -> {
                    System.out.println(ServiceInstaller.installed(unit)
                            ? unit + " installed — manage with: systemctl --user status " + unit
                            : unit + " not installed");
                    return 0;
                }
                default -> {
                    System.err.println("error: action must be one of: install, uninstall, status");
                    return 1;
                }
            }
        }
    }

    @Command(name = "create", description = "Start a chat: a DM with one user, or a group with several.")
    static final class Create implements Callable<Integer> {
        @Mixin PathOpts paths;
        @Parameters(index = "0..*", description = "Users (username, name:hexid, or a nickname).")
        List<String> users;
        @Option(names = "--name", description = "Group name (optional; defaults to the members' names).")
        String name;

        @Override
        public Integer call() {
            if (users == null || users.isEmpty()) {
                System.err.println("error: at least one user is required");
                return 1;
            }
            java.util.Map<String, Object> cmd = new java.util.LinkedHashMap<>();
            cmd.put("c", "create");
            cmd.put("members", users);
            if (name != null) {
                cmd.put("name", name);
            }
            JsonNode reply;
            try {
                reply = LocalControlClient.send(paths.config().readPort(), cmd);
            } catch (RuntimeException e) {
                System.err.println("error: " + e.getMessage());
                return 1;
            }
            if (reply.path("ok").asBoolean()) {
                System.out.println(reply.path("info").asText(users.size() == 1 ? "DM created" : "group created"));
                return 0;
            }
            System.err.println("error: " + reply.path("error").asText("create failed"));
            return 1;
        }
    }

    @Command(name = "dm", description = "Open (or create) a DM with a user (username or name:hexid).")
    static final class Dm implements Callable<Integer> {
        @Mixin PathOpts paths;
        @Parameters(index = "0", description = "Peer (name:hexid recommended; a shared name is ambiguous).")
        String username;

        @Override
        public Integer call() {
            JsonNode reply = LocalControlClient.send(paths.config().readPort(),
                    Map.of("c", "dm", "username", username));
            int colon = username.indexOf(':');
            String where = (colon >= 0)
                    ? "open " + paths.root.resolve("dms").resolve(
                            username.substring(0, colon) + "-" + username.substring(colon + 1) + ".chat")
                    : "DM requested; find it under " + paths.root.resolve("dms") + " (ls for <name>-<hexid>.chat)";
            return report(reply, where);
        }
    }

    @Command(name = "group-new", description = "Create a group chat.")
    static final class GroupNew implements Callable<Integer> {
        @Mixin PathOpts paths;
        @Parameters(index = "0", description = "Group name.") String name;
        @Parameters(index = "1..*", description = "Member usernames.") List<String> members;

        @Override
        public Integer call() {
            JsonNode reply = LocalControlClient.send(paths.config().readPort(),
                    Map.of("c", "group-new", "name", name, "members", members == null ? List.of() : members));
            return report(reply, "group '" + name + "' requested");
        }
    }

    @Command(name = "group-add", description = "Add a user to a group (any member may).")
    static final class GroupAdd implements Callable<Integer> {
        @Mixin PathOpts paths;
        @Parameters(index = "0", description = "Channel id.") String channelId;
        @Parameters(index = "1", description = "User to add (username or name:hexid).") String username;

        @Override
        public Integer call() {
            JsonNode reply = LocalControlClient.send(paths.config().readPort(),
                    Map.of("c", "group-add", "channelId", channelId, "username", username));
            return report(reply, "added " + username);
        }
    }

    @Command(name = "group-remove", description = "Remove a user from a group (any member may).")
    static final class GroupRemove implements Callable<Integer> {
        @Mixin PathOpts paths;
        @Parameters(index = "0", description = "Channel id.") String channelId;
        @Parameters(index = "1", description = "User to remove (username or name:hexid).") String username;

        @Override
        public Integer call() {
            JsonNode reply = LocalControlClient.send(paths.config().readPort(),
                    Map.of("c", "group-remove", "channelId", channelId, "username", username));
            return report(reply, "removed " + username);
        }
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** ExecStart command for the systemd unit: the launcher + "start" + explicit settings. */
    private static List<String> serviceExec(ServerOpts server, PathOpts paths) {
        List<String> exec = new ArrayList<>();
        exec.add(ServiceInstaller.launcher().toString());
        exec.add("start");
        exec.addAll(server.startArgs());
        exec.addAll(paths.startArgs());
        return exec;
    }

    /** After register/login, install (or refresh) the always-on daemon service. */
    private static void setUpService(ServerOpts server, PathOpts paths, boolean noService) {
        if (noService) {
            System.out.println("(--no-service) run the daemon yourself with:  fschat-daemon start &");
            return;
        }
        if (!ServiceInstaller.available()) {
            System.out.println("note: no systemd --user here; start the daemon with:  fschat-daemon start &");
            return;
        }
        // A non-default --config-dir gets its own unit, so multiple accounts can each
        // run their own always-on daemon on one machine.
        System.out.println(ServiceInstaller.install(
                ServiceInstaller.unitFor(paths.configDir), serviceExec(server, paths)));
    }

    private static int report(JsonNode reply, String successMessage) {
        if (reply.path("ok").asBoolean()) {
            System.out.println(successMessage);
            return 0;
        }
        System.err.println("error: " + reply.path("error").asText("request failed"));
        return 1;
    }

    private static String prompt(String label) {
        if (System.console() != null) {
            return new String(System.console().readPassword(label));
        }
        try {
            System.out.print(label);
            return new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read password", e);
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
