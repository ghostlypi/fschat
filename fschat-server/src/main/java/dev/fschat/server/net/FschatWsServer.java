package dev.fschat.server.net;

import dev.fschat.protocol.Json;
import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.wire.WsMessage;
import dev.fschat.server.auth.AuthException;
import dev.fschat.server.auth.AuthService;
import dev.fschat.server.auth.Principal;
import dev.fschat.server.channel.ChannelException;
import dev.fschat.server.channel.ChannelService;
import dev.fschat.server.log.EventLog;
import dev.fschat.server.log.EventLog.AppendOutcome;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;

import javax.net.ssl.SSLContext;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The wss:// endpoint. One connection per daemon, full-duplex. Enforces the
 * protocol invariants:
 * <ul>
 *   <li>the first frame must authenticate; ops before {@code auth_ok} are refused;</li>
 *   <li>every write is authorized and re-validated server-side by
 *       {@link ChannelService} (author derived from the connection, never trusted);</li>
 *   <li>committed events are fanned out to all subscribed members.</li>
 * </ul>
 */
public final class FschatWsServer extends WebSocketServer {

    private static final Logger LOG = System.getLogger(FschatWsServer.class.getName());

    private final AuthService auth;
    private final ChannelService channels;
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final CountDownLatch started = new CountDownLatch(1);

    public FschatWsServer(InetSocketAddress addr, SSLContext sslContext,
                          AuthService auth, ChannelService channels) {
        super(addr);
        this.auth = auth;
        this.channels = channels;
        setReuseAddr(true);
        if (sslContext != null) {
            setWebSocketFactory(new DefaultSSLWebSocketServerFactory(sslContext));
        }
    }

    /** Block until the listening socket is bound (so {@link #getPort()} is valid). */
    public boolean awaitStart(long timeout, TimeUnit unit) throws InterruptedException {
        return started.await(timeout, unit);
    }

    @Override
    public void onStart() {
        started.countDown();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        conn.setAttachment(new Session());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Session s = conn.getAttachment();
        if (s != null && s.isAuthenticated()) {
            registry.remove(s.userId(), conn);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.log(Level.WARNING, "websocket error", ex);
    }

    @Override
    public void onMessage(WebSocket conn, String text) {
        WsMessage msg;
        try {
            msg = Json.read(text, WsMessage.class);
        } catch (RuntimeException e) {
            sendError(conn, "BAD_REQUEST", null, "unparseable message");
            return;
        }

        Session s = conn.getAttachment();
        try {
            if (!s.isAuthenticated()) {
                if (msg instanceof WsMessage.Auth a) {
                    handleAuth(conn, s, a);
                } else {
                    sendError(conn, "UNAUTHENTICATED", null, "authenticate first");
                    conn.close();
                }
                return;
            }
            dispatchAuthenticated(conn, s, msg);
        } catch (ChannelException e) {
            sendError(conn, e.code(), refIdOf(msg), e.getMessage());
        } catch (AuthException e) {
            sendError(conn, e.code(), refIdOf(msg), e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "error handling message", e);
            sendError(conn, "INTERNAL", refIdOf(msg), "internal error");
        }
    }

    private void dispatchAuthenticated(WebSocket conn, Session s, WsMessage msg) {
        switch (msg) {
            case WsMessage.Subscribe sub -> handleSubscribe(conn, s, sub);
            case WsMessage.Op op -> handleOp(conn, s, op);
            case WsMessage.CreateChannel cc -> handleCreate(conn, s, cc);
            case WsMessage.AddMember am -> handleAddMember(conn, s, am);
            case WsMessage.RemoveMember rm -> handleRemoveMember(conn, s, rm);
            case WsMessage.Rename rn -> handleRename(conn, s, rn);
            case WsMessage.Block b -> handleBlock(conn, s, b);
            case WsMessage.Unblock u -> handleUnblock(conn, s, u);
            case WsMessage.Ping p -> send(conn, new WsMessage.Pong(p.nonce()));
            case WsMessage.Auth ignored -> { /* already authenticated; ignore */ }
            default -> sendError(conn, "BAD_REQUEST", null, "unexpected message type");
        }
    }

    // ----- handlers ---------------------------------------------------------

    private void handleAuth(WebSocket conn, Session s, WsMessage.Auth a) {
        Principal p = auth.verify(a.token()); // throws AuthException on bad/expired token
        s.authenticate(p.userId(), p.username());
        registry.add(p.userId(), conn);
        List<ChannelMeta> chans = channels.channelsForUser(p.userId());
        send(conn, new WsMessage.AuthOk(p.userId(), p.username(), chans));
    }

    private void handleSubscribe(WebSocket conn, Session s, WsMessage.Subscribe sub) {
        // Subscribe first (at-least-once): a live event appended during backfill
        // is then also fanned out; the daemon dedupes by (channelId, seq).
        s.subscribe(sub.channelId());
        List<EventLog.LogEvent> events = channels.backfill(principal(s), sub.channelId(), sub.fromSeq());
        for (EventLog.LogEvent e : events) {
            send(conn, toEvent(e));
        }
        send(conn, new WsMessage.BackfillDone(sub.channelId(), channels.headSeq(sub.channelId())));
    }

    private void handleOp(WebSocket conn, Session s, WsMessage.Op op) {
        AppendOutcome out = channels.applyOp(principal(s), op);
        send(conn, new WsMessage.Ack(op.clientOpId(), op.channelId(),
                out.event().messageId(), out.event().seq()));
        if (!out.wasDuplicate()) {
            fanout(op.channelId(), toEvent(out.event()));
        }
    }

    private void handleCreate(WebSocket conn, Session s, WsMessage.CreateChannel cc) {
        ChannelMeta meta = channels.createChannel(principal(s), cc.type(), cc.name(), cc.memberUsernames());
        send(conn, new WsMessage.ChannelCreated(cc.clientReqId(), meta));
        pushChannelToOtherMembers(meta, s.userId());
    }

    private void handleAddMember(WebSocket conn, Session s, WsMessage.AddMember am) {
        ChannelService.MemberAddResult r = channels.addMember(principal(s), am.channelId(), am.username(), am.clientReqId());
        send(conn, new WsMessage.ChannelCreated(am.clientReqId(), r.channel()));
        fanout(am.channelId(), toEvent(r.event()));
        pushChannelToOtherMembers(r.channel(), s.userId());
    }

    private void handleRemoveMember(WebSocket conn, Session s, WsMessage.RemoveMember rm) {
        ChannelService.MemberRemoveResult r =
                channels.removeMember(principal(s), rm.channelId(), rm.username(), rm.clientReqId());
        fanout(rm.channelId(), toEvent(r.event())); // remaining members
        // The removed user is no longer in memberIds, so notify their connections directly.
        for (WebSocket c : registry.forUser(r.removedUserId())) {
            Session sess = c.getAttachment();
            if (sess != null && sess.isSubscribed(rm.channelId()) && c.isOpen()) {
                send(c, toEvent(r.event()));
            }
        }
        pushChannelToOtherMembers(r.channel(), s.userId());
    }

    private void handleRename(WebSocket conn, Session s, WsMessage.Rename rn) {
        EventLog.LogEvent event = channels.rename(principal(s), rn.channelId(), rn.name(), rn.clientReqId());
        fanout(rn.channelId(), toEvent(event));
        channels.getChannel(rn.channelId())
                .ifPresent(meta -> pushChannelToOtherMembers(meta, s.userId()));
    }

    private void handleBlock(WebSocket conn, Session s, WsMessage.Block b) {
        channels.block(principal(s), b.targetUserId());
        send(conn, new WsMessage.BlockAck(b.clientReqId(), b.targetUserId(), true));
    }

    private void handleUnblock(WebSocket conn, Session s, WsMessage.Unblock u) {
        channels.unblock(principal(s), u.targetUserId());
        send(conn, new WsMessage.BlockAck(u.clientReqId(), u.targetUserId(), false));
    }

    // ----- fanout -----------------------------------------------------------

    private void fanout(String channelId, WsMessage.Event event) {
        // Blocking only affects DMs: skip a blocked author's message events to the blocker
        // in a DM, but never filter group fanout (to avoid a group, leave it).
        boolean filterable = ChannelService.isMessageEvent(event.type()) && channels.isDirectMessage(channelId);
        for (String userId : channels.memberIds(channelId)) {
            if (filterable && channels.hasBlocked(userId, event.authorId())) {
                continue;
            }
            for (WebSocket conn : registry.forUser(userId)) {
                Session sess = conn.getAttachment();
                if (sess != null && sess.isSubscribed(channelId) && conn.isOpen()) {
                    send(conn, event);
                }
            }
        }
    }

    /** Notify other members' daemons of a (new or updated) channel so they can subscribe. */
    private void pushChannelToOtherMembers(ChannelMeta meta, String excludeUserId) {
        for (var member : meta.members()) {
            if (member.id().equals(excludeUserId)) {
                continue;
            }
            for (WebSocket conn : registry.forUser(member.id())) {
                if (conn.isOpen()) {
                    send(conn, new WsMessage.ChannelCreated(null, meta));
                }
            }
        }
    }

    // ----- helpers ----------------------------------------------------------

    private static Principal principal(Session s) {
        return new Principal(s.userId(), s.username());
    }

    private static WsMessage.Event toEvent(EventLog.LogEvent e) {
        return new WsMessage.Event(e.channelId(), e.seq(), e.type(), e.messageId(),
                e.authorId(), e.authorName(), e.content(), e.ts());
    }

    private static String refIdOf(WsMessage msg) {
        return switch (msg) {
            case WsMessage.Op op -> op.clientOpId();
            case WsMessage.CreateChannel cc -> cc.clientReqId();
            case WsMessage.AddMember am -> am.clientReqId();
            case WsMessage.RemoveMember rm -> rm.clientReqId();
            case WsMessage.Rename rn -> rn.clientReqId();
            case WsMessage.Block b -> b.clientReqId();
            case WsMessage.Unblock u -> u.clientReqId();
            case null, default -> null;
        };
    }

    private void sendError(WebSocket conn, String code, String refId, String message) {
        send(conn, new WsMessage.Error(code, refId, message));
    }

    private void send(WebSocket conn, WsMessage msg) {
        if (conn.isOpen()) {
            conn.send(Json.write(msg));
        }
    }
}
