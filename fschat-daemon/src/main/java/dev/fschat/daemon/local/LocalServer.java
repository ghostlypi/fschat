package dev.fschat.daemon.local;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import dev.fschat.daemon.sync.SyncEngine;
import dev.fschat.daemon.sync.UpdateSink;
import dev.fschat.daemon.sync.ViewUpdate;
import dev.fschat.protocol.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback TCP server that the {@code fschat.vim} plugin connects to using Vim's
 * JSON channel mode. Each frame is a JSON array {@code [id, body]}:
 * <ul>
 *   <li>Vim-&gt;daemon requests carry a positive id; the daemon replies with the
 *       same id and an {@code {ok:...}} body.</li>
 *   <li>Daemon-&gt;Vim pushes use id {@code 0}, which Vim dispatches to the channel
 *       callback &mdash; this is the live transcript-update mechanism.</li>
 * </ul>
 *
 * Implements {@link UpdateSink}: transcript updates from the {@link SyncEngine}
 * are routed as id-0 pushes to whichever connections are viewing that channel.
 * Binds 127.0.0.1 only.
 */
public final class LocalServer implements UpdateSink, AutoCloseable {

    private final SyncEngine engine;
    private final Path portFile;
    private final Set<Conn> connections = ConcurrentHashMap.newKeySet();

    private ServerSocket serverSocket;
    private volatile boolean running;

    public LocalServer(SyncEngine engine, Path portFile) {
        this.engine = engine;
        this.portFile = portFile;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to bind local server", e);
        }
        running = true;
        if (portFile != null) {
            try {
                Files.createDirectories(portFile.getParent());
                Files.writeString(portFile, Integer.toString(port()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to write port file " + portFile, e);
            }
        }
        Thread acceptor = new Thread(this::acceptLoop, "fschat-local-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Conn conn = new Conn(socket);
                connections.add(conn);
                Thread t = new Thread(conn::readLoop, "fschat-local-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    // transient accept error; keep serving
                }
            }
        }
    }

    @Override
    public void deliver(ViewUpdate update) {
        Map<String, Object> push = toPush(update);
        for (Conn conn : connections) {
            if (update.channelId().equals(conn.viewing)) {
                conn.sendFrame(0, push);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        for (Conn conn : connections) {
            conn.closeQuietly();
        }
    }

    private static Map<String, Object> toPush(ViewUpdate update) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channelId", update.channelId());
        if (update instanceof ViewUpdate.Append a) {
            m.put("push", "append");
            m.put("lines", a.lines());
        } else if (update instanceof ViewUpdate.Replace r) {
            m.put("push", "replace");
            m.put("fromLine", r.fromLine());
            m.put("toLine", r.toLine());
            m.put("lines", r.lines());
        } else if (update instanceof ViewUpdate.Reset r) {
            m.put("push", "reset");
            m.put("lines", r.lines());
        } else if (update instanceof ViewUpdate.Renamed r) {
            m.put("push", "renamed");
            m.put("path", r.path());
            m.put("lines", r.lines());
        }
        return m;
    }

    /** A single Vim connection. */
    private final class Conn {
        private final Socket socket;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private volatile String viewing;

        Conn(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        void readLoop() {
            // Read each [id, body] array as one whole value. (MappingIterator.readValues
            // would unwrap a top-level array into its elements, which is wrong here.)
            try (JsonParser parser = Json.MAPPER.getFactory().createParser(socket.getInputStream())) {
                while (running && parser.nextToken() != null) {
                    JsonNode frame = Json.MAPPER.readTree(parser);
                    if (frame == null || !frame.isArray() || frame.size() < 2) {
                        continue;
                    }
                    int id = frame.get(0).asInt();
                    JsonNode body = frame.get(1);
                    sendFrame(id, handle(body));
                }
            } catch (IOException | RuntimeException e) {
                // connection closed or malformed; fall through to cleanup
            } finally {
                connections.remove(this);
                closeQuietly();
            }
        }

        private Map<String, Object> handle(JsonNode body) {
            String command = body.path("c").asText("");
            try {
                return switch (command) {
                    case "open" -> handleOpen(body);
                    case "send" -> {
                        SyncEngine.SubmitResult r = engine.submit(
                                body.get("channelId").asText(), body.get("content").asText());
                        Map<String, Object> reply = r.ok() ? ok() : error(r.message());
                        if (r.ok() && r.message() != null) {
                            reply.put("info", r.message());
                        }
                        yield reply;
                    }
                    case "create" -> awaitCreate(body);
                    case "edit" -> {
                        engine.edit(body.get("channelId").asText(), body.get("messageId").asText(),
                                body.get("content").asText());
                        yield ok();
                    }
                    case "delete" -> {
                        engine.delete(body.get("channelId").asText(), body.get("messageId").asText());
                        yield ok();
                    }
                    case "dm" -> {
                        engine.createDm(body.get("username").asText());
                        yield ok();
                    }
                    case "group-new" -> {
                        engine.createGroup(body.get("name").asText(), stringList(body.get("members")));
                        yield ok();
                    }
                    case "group-add" -> {
                        engine.addMember(body.get("channelId").asText(), body.get("username").asText());
                        yield ok();
                    }
                    case "group-remove" -> {
                        engine.removeMember(body.get("channelId").asText(), body.get("username").asText());
                        yield ok();
                    }
                    default -> error("unknown command: " + command);
                };
            } catch (RuntimeException e) {
                return error(e.getMessage());
            }
        }

        /** Issue a create and block until the server confirms or rejects it (or it times out). */
        private Map<String, Object> awaitCreate(JsonNode body) {
            var future = engine.createAsync(stringList(body.get("members")),
                    body.has("name") ? body.get("name").asText() : null);
            try {
                SyncEngine.CreateOutcome outcome = future.get();
                if (outcome.ok()) {
                    Map<String, Object> reply = ok();
                    if (outcome.path() != null) {
                        reply.put("info", "created " + outcome.path());
                    }
                    return reply;
                }
                return error(outcome.message());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted while creating");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof java.util.concurrent.TimeoutException) {
                    return error("timed out waiting for server confirmation");
                }
                return error("create failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
            }
        }

        private Map<String, Object> handleOpen(JsonNode body) {
            Path path = Path.of(body.get("path").asText());
            var channelId = engine.channelIdForPath(path);
            if (channelId.isEmpty()) {
                return error("channel not ready for " + path);
            }
            String cid = channelId.get();
            this.viewing = cid;
            Map<String, Object> reply = ok();
            reply.put("channelId", cid);
            reply.put("meId", engine.meHandle());
            reply.put("lines", engine.transcript(cid).orElse(List.of()));
            return reply;
        }

        void sendFrame(int id, Object body) {
            String json = Json.write(new Object[]{id, body});
            synchronized (writeLock) {
                try {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                    out.flush();
                } catch (IOException e) {
                    closeQuietly();
                }
            }
        }

        void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message == null ? "error" : message);
        return m;
    }

    private static List<String> stringList(JsonNode array) {
        List<String> list = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> list.add(n.asText()));
        }
        return list;
    }
}
