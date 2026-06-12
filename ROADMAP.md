# fschat roadmap

Post-MVP features, ordered roughly by how much new architecture they need. Each
item notes what already exists so the work is incremental, not a rewrite.

Status legend: `[ ]` todo · `[~]` partially in place · `[x]` done

---

## 1. Cross-device support (same account, multiple daemons)

**Where we are:** the server is already multi-connection. `ConnectionRegistry`
keys connections by `userId` in a `Set`, and `FschatWsServer.fanout` delivers
every event to *all* of a member's subscribed connections. Live posts/edits/
deletes converge on every device today. What's missing is persistence,
device-awareness, and offline delivery.

- [x] Server fanout to multiple connections per user (`ConnectionRegistry`, `FschatWsServer.fanout`)
- [x] Per-device independent subscribe + resume (`SyncEngine` tracks its own `syncedSeq`)
- [x] Convergent edits/deletes (events folded by `messageId` in seq order)
- [ ] **Persist `syncedSeq` per channel** in the daemon so a restart resumes instead of re-backfilling from 0 (write/read it in `DaemonConfig`; subscribe from the stored value in `SyncEngine.onAuthOk`)
- [x] **Verify same-account/two-daemon**: `CrossDeviceTest` connects one account from two daemons and asserts cross-device delivery + edit convergence (a real-process run is still a nice-to-have)
- [ ] **Offline catch-up correctness check** when a device is down during many events (resume gap-buffering already handles ordering; add a test)
- [ ] **Device sessions**: name connections, list active devices, "log out everywhere" (token revocation list or short-TTL tokens + refresh)
- [ ] **Push to offline devices** (notifications): out-of-band delivery when no daemon is connected — depends on a notification transport (APNs/FCM/email/webhook)
- [ ] **Read/delivery state** per user (and optionally per device): a `read_seq` per (user, channel); surfaces unread counts
- [ ] **Token security hardening** for many devices: per-device tokens, rotation, revocation (today tokens are non-revocable until expiry)

---

## 2. Communities (Discord-style servers)

**Where we are:** the data model was built channel-centric on purpose. The
`channels` table already has `type` and `parent_community_id`, `members` already
has a `role` column (defaulting `MEMBER`), and **all authorization flows through
the single `ChannelService.authorize`-style seam** (currently membership-only).
Adding communities should not require reshaping messages, events, or sync.

- [~] Channel-as-universal-unit model (`ChannelType`, `parent_community_id`, `members.role` columns exist; unused today)
- [ ] **`communities` table** (id, name, owner, created_ts) + `ChannelType.COMMUNITY_CHANNEL`
- [ ] **Roles & permissions**: a `roles`/`permissions` table; grow the authorization seam from "is member of channel" to "does this user's role in this community permit OP in this channel"
- [ ] **Community CRUD + channel management** over the wire: create community, create/rename/delete channels under it, join/leave, invite
- [ ] **Wire protocol additions**: `CreateCommunity`, `CreateCommunityChannel`, `JoinCommunity`, role-change events; a `subscribe-all` convenience for a community's channels
- [ ] **Membership/role events in the log** (reuse the `MEMBER_ADD`/`MEMBER_LEAVE` pattern; add `ROLE_CHANGE`) so they sync and render as `#sys` lines
- [ ] **Filesystem layout**: `~/fschat/communities/<community>/<channel>.chat`; teach `FileStore.pathFor` and `SyncEngine` the community dimension
- [ ] **CLI**: `community new`, `community channel-new`, `community join`, `community role`
- [ ] **Vim**: surface community/channel structure (a tree/listing buffer or `:FschatChannels`)
- [ ] **Scale**: per-community connection/subscription management when a user is in many channels

---

## 3. Video calling

**Where we are:** nothing — this is net-new and the largest departure from the
"chat is a file in Vim" model, since real-time media can't live in a text
buffer. The existing WS connection is a natural **signaling** channel; media
itself needs WebRTC and a separate client surface.

- [ ] **Decide the call client**: Vim can't render video. Options — a companion GUI/web client that reuses the daemon's auth+signaling, or launch an external tool; document the choice
- [ ] **Signaling over the existing WSS**: add `CallOffer` / `CallAnswer` / `IceCandidate` / `CallEnd` wire messages relayed by the server to channel members (server stays a dumb relay; no media touches it)
- [ ] **Call lifecycle as events**: `CALL_START` / `CALL_END` log events so a call shows up as a `#sys` line in the `.chat` transcript ("📞 call started / 12m")
- [ ] **WebRTC peer connections** (SDP + ICE); 1:1 first, then small-group SFU later
- [ ] **STUN/TURN**: bundle/configure servers for NAT traversal (TURN relay for symmetric NATs)
- [ ] **Media capture/playback** in the chosen client (camera/mic permissions, device selection)
- [ ] **Group calls**: an SFU (selective forwarding unit) once 1:1 works; ties into communities (per-channel voice)
- [ ] **Ringing/availability**: depends on the cross-device **push to offline devices** item above
- [ ] **Security**: DTLS-SRTP for media, authorize call setup through the same `ChannelService` seam as messages

---

## 4. Bots & API access

**Where we are:** a bot is really just a programmatic account, and the
foundation is already there — a bot can register a user, obtain a JWT, open the
same WSS connection, subscribe, and post/edit/delete exactly like a human
client. What's missing is making that a *supported, safe, documented* surface:
bot identities, scoped credentials, rate limits, and a stable public API.

- [~] Programmatic account path (any client can already drive the WS protocol with a token)
- [ ] **Bot accounts**: a user/account `kind` (`HUMAN` | `BOT`) so bots are first-class and distinguishable in membership and rendering (`#msg` could mark bot authors)
- [ ] **API keys / bot tokens**: long-lived, revocable, scoped credentials separate from human login JWTs (depends on the token-revocation work in §1)
- [ ] **Scoped permissions**: which channels/communities a bot may read/write, expressed through the `ChannelService` authorization seam and (later) community roles
- [ ] **Stable public API**: document and version the wire protocol + an HTTP/REST facade (send message, list channels, create channel) so bots needn't speak the raw WS framing
- [ ] **Outbound webhooks**: deliver channel events to an external URL (HMAC-signed) — reuses the event log + fanout, just to an HTTP sink instead of a socket
- [ ] **Inbound webhooks**: a posting endpoint that maps a webhook token to a channel (simple "post to channel" for CI/alerts)
- [ ] **Rate limiting & quotas** per token (protects the single-connection-per-daemon and the server from abusive bots)
- [ ] **Slash-commands / interactions framework**: register commands a bot responds to; route `/command` messages to the owning bot
- [ ] **Client SDK**: a small Java (and/or other-language) library wrapping auth + WS + the protocol records, so bot authors reuse `fschat-protocol` rather than reimplement it
- [ ] **API docs & examples**: an "echo bot" reference implementation

---

## Cross-cutting follow-ups (smaller, surface during the above)

- [ ] Surface server-side `Error` messages (e.g. a rejected edit) inline in Vim, not just daemon logs
- [ ] Local-socket auth secret so not every process on `127.0.0.1` can drive the daemon
- [ ] Token-at-rest hardening (OS keychain instead of a `0600` file)
- [ ] Message attachments / media (separate blob store; referenced by event)
