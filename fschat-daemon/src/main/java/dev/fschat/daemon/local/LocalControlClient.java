package dev.fschat.daemon.local;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import dev.fschat.protocol.Json;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One-shot client used by CLI subcommands ({@code dm}, {@code group}) to send a
 * single control command to the already-running daemon over its loopback port,
 * read the reply, and exit.
 */
public final class LocalControlClient {

    private LocalControlClient() {
    }

    public static JsonNode send(int port, Map<String, Object> command) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            // Safety net: the daemon replies within its own ~10s timeout; don't hang forever.
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            String frame = Json.write(new Object[]{1, command});
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
            try (JsonParser parser = Json.MAPPER.getFactory().createParser(socket.getInputStream())) {
                while (parser.nextToken() != null) {
                    JsonNode reply = Json.MAPPER.readTree(parser);
                    if (reply != null && reply.isArray() && reply.size() >= 2 && reply.get(0).asInt() != 0) {
                        return reply.get(1);
                    }
                }
            }
            throw new IllegalStateException("no reply from daemon");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not reach daemon on port " + port + ": " + e.getMessage(), e);
        }
    }
}
