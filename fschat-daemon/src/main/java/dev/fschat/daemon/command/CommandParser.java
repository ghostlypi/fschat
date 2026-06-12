package dev.fschat.daemon.command;

import java.util.List;

/**
 * Parses a compose-pane command line. A message beginning with a single {@code /}
 * is a command; {@code //} is the escape to post a literal leading slash.
 */
public final class CommandParser {

    /**
     * @param name command name, lower-cased (e.g. {@code block})
     * @param args whitespace-split arguments
     * @param rest everything after the command name, trimmed (for free-text args like rename)
     */
    public record Command(String name, List<String> args, String rest) {
    }

    private CommandParser() {
    }

    /** True if {@code content} is a command (single leading slash, not {@code //}). */
    public static boolean isCommand(String content) {
        return content.startsWith("/") && !content.startsWith("//");
    }

    /** Strip the escaping slash from a {@code //literal} message. */
    public static String unescape(String content) {
        return content.startsWith("//") ? content.substring(1) : content;
    }

    /** Parse a command line that {@link #isCommand} returned true for. */
    public static Command parse(String content) {
        String body = content.substring(1).strip();
        int sp = body.indexOf(' ');
        String name = (sp < 0 ? body : body.substring(0, sp)).toLowerCase();
        String rest = (sp < 0 ? "" : body.substring(sp + 1).strip());
        List<String> args = rest.isEmpty() ? List.of() : List.of(rest.split("\\s+"));
        return new Command(name, args, rest);
    }
}
