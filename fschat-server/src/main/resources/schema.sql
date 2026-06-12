-- fschat server schema. Bootstrapped on startup with CREATE TABLE IF NOT EXISTS,
-- so it is safe to run repeatedly. The `events` table is INSERT-ONLY: EDIT and
-- DELETE are themselves new rows, never updates/deletes of prior rows, which
-- preserves full history and makes resume-from-seq sync trivial.

-- Usernames are NOT unique; identity is the handle name:hexid where hexid is
-- the (unique) user id. Multiple users may share a display name.
CREATE TABLE IF NOT EXISTS users (
  id         TEXT PRIMARY KEY,
  username   TEXT NOT NULL,
  pwd_hash   TEXT NOT NULL,
  created_ts TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_users_username ON users (username);

CREATE TABLE IF NOT EXISTS channels (
  id                  TEXT PRIMARY KEY,
  type                TEXT NOT NULL,            -- 'DM' | 'GROUP' (+ 'COMMUNITY_CHANNEL' later)
  name                TEXT,                     -- group name; NULL for DM
  parent_community_id TEXT,                     -- NULL today; reserved for communities
  created_ts          TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS members (
  channel_id TEXT NOT NULL,
  user_id    TEXT NOT NULL,
  role       TEXT NOT NULL DEFAULT 'MEMBER',    -- future: OWNER/ADMIN/MEMBER
  PRIMARY KEY (channel_id, user_id)
);

CREATE TABLE IF NOT EXISTS events (
  channel_id   TEXT NOT NULL,
  seq          INTEGER NOT NULL,                -- per-channel monotonic
  type         TEXT NOT NULL,                   -- POST|EDIT|DELETE|MEMBER_ADD|MEMBER_LEAVE
  message_id   TEXT NOT NULL,
  author_id    TEXT NOT NULL,
  content      TEXT,                            -- NULL for DELETE
  ts           TEXT NOT NULL,
  client_op_id TEXT NOT NULL,
  PRIMARY KEY (channel_id, seq),                -- also serves resume range scans
  UNIQUE (author_id, client_op_id)              -- idempotent writes across reconnect
);

-- Ownership / edit-history lookups by message.
CREATE INDEX IF NOT EXISTS ix_events_message ON events (channel_id, message_id);

-- Directional blocks: user_id has blocked blocked_id (they stop receiving them).
CREATE TABLE IF NOT EXISTS blocks (
  user_id    TEXT NOT NULL,
  blocked_id TEXT NOT NULL,
  created_ts TEXT NOT NULL,
  PRIMARY KEY (user_id, blocked_id)
);
