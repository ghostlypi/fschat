package dev.fschat.daemon.sync;

import dev.fschat.daemon.Ids;
import dev.fschat.daemon.command.CommandParser;
import dev.fschat.daemon.command.CommandParser.Command;
import dev.fschat.daemon.contacts.Contact;
import dev.fschat.daemon.directory.Directory;
import dev.fschat.daemon.file.FileStore;
import dev.fschat.daemon.remote.WsClient;
import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.EventType;
import dev.fschat.protocol.model.OpType;
import dev.fschat.protocol.model.UserRef;
import dev.fschat.protocol.wire.WsMessage;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The daemon's brain: turns the server event stream into on-disk {@code .chat}
 * files and live transcript updates for Vim, turns local user actions and
 * {@code /commands} into server ops, and owns contact/block/rename behavior via
 * the {@link Directory}. All state mutation is serialized on this object.
 */
public final class SyncEngine implements WsClient.Listener {

    /** Result of {@link #submit}: a normal post, or a command with optional feedback. */
    public record SubmitResult(boolean ok, String message) {
    }

    /** Result of an awaited {@link #createAsync}: server-confirmed success (with the file path) or failure. */
    public record CreateOutcome(boolean ok, String message, String path) {
    }

    private static final Logger LOG = System.getLogger(SyncEngine.class.getName());
    private static final long CREATE_TIMEOUT_SECONDS = 10;

    private final Path root;
    private final Directory directory;
    private volatile UpdateSink sink;
    private final Map<String, ChannelView> views = new ConcurrentHashMap<>();
    /** In-flight create requests awaiting server confirmation, keyed by clientReqId. */
    private final Map<String, CompletableFuture<CreateOutcome>> pendingCreates = new ConcurrentHashMap<>();

    private WsClient ws;
    private volatile String meId;
    private volatile String meUsername;
    private FileStore fileStore;

    public SyncEngine(Path root, Directory directory) {
        this(root, directory, UpdateSink.NONE);
    }

    public SyncEngine(Path root, Directory directory, UpdateSink sink) {
        this.root = root;
        this.directory = directory;
        this.sink = sink;
    }

    public void bind(WsClient ws) {
        this.ws = ws;
    }

    public void setSink(UpdateSink sink) {
        this.sink = sink;
    }

    public String meId() {
        return meId;
    }

    public String meUsername() {
        return meUsername;
    }

    public String meHandle() {
        return (meUsername == null || meId == null) ? null : meUsername + ":" + meId;
    }

    // ----- inbound from server ---------------------------------------------

    @Override
    public synchronized void onAuthOk(WsMessage.AuthOk msg) {
        this.meId = msg.userId();
        this.meUsername = msg.username();
        this.fileStore = new FileStore(root, meId);
        this.directory.setMeHandle(meHandle());
        for (ChannelMeta meta : msg.channels()) {
            ChannelView view = ensureView(meta);
            ws.subscribe(view.channelId(), view.syncedSeq());
            writeFile(view);
        }
    }

    @Override
    public synchronized void onChannelCreated(WsMessage.ChannelCreated msg) {
        ChannelView view = ensureView(msg.channel());
        ws.subscribe(view.channelId(), view.syncedSeq());
        Path path = writeFile(view);
        if (msg.clientReqId() != null) {
            CompletableFuture<CreateOutcome> pending = pendingCreates.remove(msg.clientReqId());
            if (pending != null) {
                pending.complete(new CreateOutcome(true, "created", path == null ? null : path.toString()));
            }
        }
    }

    @Override
    public synchronized void onEvent(WsMessage.Event e) {
        ChannelView view = views.get(e.channelId());
        if (view == null) {
            LOG.log(Level.WARNING, "event for unknown channel " + e.channelId());
            return;
        }
        Path oldPath = (fileStore != null) ? fileStore.pathFor(view.meta()) : null;
        List<ViewUpdate> updates = view.applyEvent(e);
        if (updates.isEmpty() && e.type() != EventType.RENAME) {
            return; // duplicate, gap, or hidden author — nothing to surface
        }
        Path newPath = writeFile(view);

        if (oldPath != null && newPath != null && !oldPath.equals(newPath)) {
            // The channel was renamed and its file moved: drop the old file, reload the buffer.
            deleteQuietly(oldPath);
            sink.deliver(new ViewUpdate.Renamed(e.channelId(), newPath.toString(), view.renderTranscript()));
        } else if (e.type() == EventType.RENAME) {
            sink.deliver(new ViewUpdate.Reset(e.channelId(), view.renderTranscript()));
        } else {
            for (ViewUpdate u : updates) {
                sink.deliver(u);
            }
        }
    }

    @Override
    public synchronized void onBackfillDone(WsMessage.BackfillDone msg) {
        ChannelView view = views.get(msg.channelId());
        if (view != null) {
            writeFile(view);
        }
    }

    @Override
    public void onAck(WsMessage.Ack msg) {
    }

    @Override
    public void onError(WsMessage.Error msg) {
        LOG.log(Level.WARNING, "server error " + msg.code() + ": " + msg.msg());
        if (msg.refId() != null) {
            CompletableFuture<CreateOutcome> pending = pendingCreates.remove(msg.refId());
            if (pending != null) {
                pending.complete(new CreateOutcome(false, msg.code() + ": " + msg.msg(), null));
            }
        }
    }

    @Override
    public void onConnected() {
        LOG.log(Level.INFO, "connected to server");
    }

    @Override
    public void onDisconnected() {
        LOG.log(Level.INFO, "disconnected from server");
    }

    // ----- outbound: open / addressing --------------------------------------

    public synchronized Optional<List<String>> transcript(String channelId) {
        ChannelView view = views.get(channelId);
        return view == null ? Optional.empty() : Optional.of(view.renderTranscript());
    }

    public synchronized Optional<String> channelIdForPath(Path path) {
        if (fileStore == null) {
            return Optional.empty();
        }
        Path target = path.toAbsolutePath().normalize();
        for (ChannelView view : views.values()) {
            if (fileStore.pathFor(view.meta()).toAbsolutePath().normalize().equals(target)) {
                return Optional.of(view.channelId());
            }
        }
        return Optional.empty();
    }

    // ----- the compose-pane entry point (posts + /commands) -----------------

    /** Handle a composed line: a {@code /command}, a {@code //literal}, or a normal post. */
    public synchronized SubmitResult submit(String channelId, String content) {
        if (!CommandParser.isCommand(content)) {
            post(channelId, CommandParser.unescape(content));
            return new SubmitResult(true, null);
        }
        try {
            return dispatch(channelId, CommandParser.parse(content));
        } catch (RuntimeException e) {
            return new SubmitResult(false, e.getMessage());
        }
    }

    private SubmitResult dispatch(String channelId, Command cmd) {
        switch (cmd.name()) {
            case "block" -> {
                UserRef u = blockTarget(channelId, cmd.args());
                ws.sendControl(new WsMessage.Block(Ids.req(), u.id()));
                directory.blocks().block(u.id());
                rerenderAll();
                return new SubmitResult(true, "blocked " + labelOf(u));
            }
            case "unblock" -> {
                UserRef u = blockTarget(channelId, cmd.args());
                ws.sendControl(new WsMessage.Unblock(Ids.req(), u.id()));
                directory.blocks().unblock(u.id());
                rerenderAll();
                return new SubmitResult(true, "unblocked " + labelOf(u));
            }
            case "nick" -> {
                require(cmd.args().size() >= 2, "usage: /nick <user> <nickname>");
                UserRef u = resolveUser(cmd.args().get(0));
                String nickname = cmd.rest().substring(cmd.args().get(0).length()).strip();
                directory.contacts().setNickname(u.id(), u.username(), nickname);
                rerenderAll();
                return new SubmitResult(true, "nicknamed " + u.username() + " -> " + nickname);
            }
            case "add" -> {
                require(!cmd.args().isEmpty(), "usage: /add <user> [nickname]");
                UserRef u = resolveUser(cmd.args().get(0));
                String nickname = cmd.args().size() >= 2
                        ? cmd.rest().substring(cmd.args().get(0).length()).strip() : null;
                directory.contacts().upsert(u.id(), u.username(), nickname);
                rerenderAll();
                return new SubmitResult(true, "added " + labelOf(u));
            }
            case "unnick" -> {
                require(!cmd.args().isEmpty(), "usage: /unnick <user>");
                UserRef u = resolveUser(cmd.args().get(0));
                directory.contacts().clearNickname(u.id());
                rerenderAll();
                return new SubmitResult(true, "removed nickname for " + u.username());
            }
            case "rename" -> {
                requireGroup(channelId, "only groups can be renamed");
                require(!cmd.rest().isBlank(), "usage: /rename <name>");
                rename(channelId, cmd.rest());
                return new SubmitResult(true, "renaming to " + cmd.rest());
            }
            case "invite" -> {
                requireGroup(channelId, "only groups have members to invite");
                require(!cmd.args().isEmpty(), "usage: /invite <user>");
                addMember(channelId, cmd.args().get(0));
                return new SubmitResult(true, "invited " + cmd.args().get(0));
            }
            case "remove" -> {
                requireGroup(channelId, "only groups have members to remove");
                require(!cmd.args().isEmpty(), "usage: /remove <user>");
                removeMember(channelId, cmd.args().get(0));
                return new SubmitResult(true, "removed " + cmd.args().get(0));
            }
            case "leave" -> {
                requireGroup(channelId, "you can only leave a group");
                removeMember(channelId, meHandle());
                return new SubmitResult(true, "leaving the group");
            }
            case "help" -> {
                return new SubmitResult(true,
                        "/block /unblock [user] · /nick <user> <name> · /add <user> [name] · /unnick <user> · "
                                + "/invite <user> · /remove <user> · /leave · /rename <name> · // for a literal slash");
            }
            default -> {
                return new SubmitResult(false, "unknown command: /" + cmd.name() + " (use // to send a literal slash)");
            }
        }
    }

    // ----- op / control issuing ---------------------------------------------

    public void post(String channelId, String content) {
        ws.enqueueOp(new WsMessage.Op(Ids.op(), channelId, OpType.POST, null, content));
    }

    public void edit(String channelId, String messageId, String content) {
        ws.enqueueOp(new WsMessage.Op(Ids.op(), channelId, OpType.EDIT, messageId, content));
    }

    public void delete(String channelId, String messageId) {
        ws.enqueueOp(new WsMessage.Op(Ids.op(), channelId, OpType.DELETE, messageId, null));
    }

    public void createDm(String token) {
        ws.sendControl(new WsMessage.CreateChannel(Ids.req(), ChannelType.DM, null, List.of(resolveServerToken(token))));
    }

    public void createGroup(String name, List<String> tokens) {
        List<String> resolved = tokens.stream().map(this::resolveServerToken).collect(Collectors.toList());
        ws.sendControl(new WsMessage.CreateChannel(Ids.req(), ChannelType.GROUP, name, resolved));
    }

    /**
     * {@code create <user> ...}: DM for one member, else a group auto-named from the members.
     * Returns a future that completes when the server confirms (ChannelCreated) or rejects
     * (Error), or times out after {@value #CREATE_TIMEOUT_SECONDS}s. The caller awaits it on its
     * own thread (never under this monitor).
     */
    public synchronized CompletableFuture<CreateOutcome> createAsync(List<String> tokens, String explicitName) {
        CompletableFuture<CreateOutcome> future = new CompletableFuture<>();
        if (tokens == null || tokens.isEmpty()) {
            future.complete(new CreateOutcome(false, "at least one user is required", null));
            return future;
        }
        String reqId = Ids.req();
        pendingCreates.put(reqId, future);
        // Fail loudly if the server never confirms, and always clean up the pending entry.
        future.orTimeout(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((r, ex) -> pendingCreates.remove(reqId));
        try {
            if (tokens.size() == 1) {
                ws.sendControl(new WsMessage.CreateChannel(reqId, ChannelType.DM, null,
                        List.of(resolveServerToken(tokens.get(0)))));
            } else {
                String name = (explicitName != null && !explicitName.isBlank())
                        ? explicitName
                        : tokens.stream().map(this::displayLabelForToken).collect(Collectors.joining(", "));
                List<String> resolved = tokens.stream().map(this::resolveServerToken).collect(Collectors.toList());
                ws.sendControl(new WsMessage.CreateChannel(reqId, ChannelType.GROUP, name, resolved));
            }
        } catch (RuntimeException e) {
            pendingCreates.remove(reqId);
            future.complete(new CreateOutcome(false, e.getMessage(), null));
        }
        return future;
    }

    public void addMember(String channelId, String token) {
        ws.sendControl(new WsMessage.AddMember(Ids.req(), channelId, resolveServerToken(token)));
    }

    public void removeMember(String channelId, String token) {
        ws.sendControl(new WsMessage.RemoveMember(Ids.req(), channelId, resolveServerToken(token)));
    }

    public void rename(String channelId, String name) {
        ws.sendControl(new WsMessage.Rename(Ids.req(), channelId, name));
    }

    // ----- re-render after a contact/block change ---------------------------

    private void rerenderAll() {
        directory.recompute();
        for (ChannelView v : views.values()) {
            writeFile(v);
            sink.deliver(new ViewUpdate.Reset(v.channelId(), v.renderTranscript()));
        }
    }

    // ----- resolution helpers -----------------------------------------------

    /** Resolve a token to an existing user (handle, unique contact label, or unique known member). */
    private UserRef resolveUser(String token) {
        if (token.contains(":")) {
            String id = token.substring(token.indexOf(':') + 1);
            String name = token.substring(0, token.indexOf(':'));
            String username = memberById(id).map(UserRef::username)
                    .or(() -> directory.contacts().get(id).map(Contact::username))
                    .orElse(name);
            return new UserRef(id, username);
        }
        List<Contact> labelled = directory.contactsWithLabel(token);
        if (labelled.size() == 1) {
            Contact c = labelled.get(0);
            return new UserRef(c.userId(), c.username());
        }
        if (labelled.size() > 1) {
            throw new IllegalArgumentException("ambiguous '" + token + "'; use name:hexid");
        }
        return memberByUsername(token)
                .orElseThrow(() -> new IllegalArgumentException("unknown user '" + token + "'; use name:hexid"));
    }

    /** A token to send to the server for create/add: nickname/label -> handle, else pass through. */
    private String resolveServerToken(String token) {
        if (token.contains(":")) {
            return token;
        }
        List<Contact> labelled = directory.contactsWithLabel(token);
        if (labelled.size() == 1) {
            Contact c = labelled.get(0);
            return c.username() + ":" + c.userId();
        }
        return token; // bare username — the server resolves (and rejects ambiguity)
    }

    /** Best-effort display label for a token, used to auto-name a group (never throws). */
    private String displayLabelForToken(String token) {
        try {
            return labelOf(resolveUser(token));
        } catch (RuntimeException e) {
            return token.contains(":") ? token.substring(0, token.indexOf(':')) : token;
        }
    }

    /** The /block, /unblock target: explicit arg, or the DM peer when none is given. */
    private UserRef blockTarget(String channelId, List<String> args) {
        if (!args.isEmpty()) {
            return resolveUser(args.get(0));
        }
        ChannelView v = views.get(channelId);
        if (v != null && v.type() == ChannelType.DM) {
            for (UserRef m : v.members()) {
                if (!m.id().equals(meId)) {
                    return m;
                }
            }
        }
        throw new IllegalArgumentException("specify a user to block");
    }

    private String labelOf(UserRef u) {
        return directory.contacts().get(u.id()).map(Contact::label).orElse(u.username());
    }

    private Optional<UserRef> memberById(String id) {
        for (ChannelView v : views.values()) {
            for (UserRef u : v.members()) {
                if (u.id().equals(id)) {
                    return Optional.of(u);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<UserRef> memberByUsername(String username) {
        UserRef found = null;
        for (ChannelView v : views.values()) {
            for (UserRef u : v.members()) {
                if (u.username().equals(username)) {
                    if (found != null && !found.id().equals(u.id())) {
                        return Optional.empty(); // ambiguous
                    }
                    found = u;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireGroup(String channelId, String message) {
        ChannelView v = views.get(channelId);
        require(v != null && v.type() == ChannelType.GROUP, message);
    }

    // ----- internals --------------------------------------------------------

    private ChannelView ensureView(ChannelMeta meta) {
        ChannelView view = views.get(meta.id());
        if (view == null) {
            view = new ChannelView(meta, directory);
            views.put(meta.id(), view);
        } else {
            view.updateMeta(meta);
        }
        return view;
    }

    private Path writeFile(ChannelView view) {
        if (fileStore == null) {
            return null;
        }
        Path path = fileStore.pathFor(view.meta());
        fileStore.write(path, view.renderFile());
        return path;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
