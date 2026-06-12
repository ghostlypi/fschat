# fschat — filesystem-native chat

A CLI-first chat app where **each conversation is a file**. You read and write
messages by opening a `.chat` file in **stock Vim**; a thin plugin streams the
live conversation into a read-only transcript pane and sends what you type in a
small compose pane below it. Direct messages and group chats are supported, and
the data model is built around channels so Discord-style communities can be
added later.

```
Vim + fschat.vim  ──localhost TCP (JSON channel)──▶  Java daemon  ──wss──▶  Java server
   (per buffer)       pre-computed line updates        (per user)            (SQLite event log)
```

The server is the single source of truth: an **append-only, per-channel event
log** (POST / EDIT / DELETE / MEMBER_* events, each with a monotonic seq). A
`.chat` file is just a rendering of that log. You can only add, edit, or delete
**your own** messages — enforced authoritatively on the server.

## Requirements

- JDK 21+ (built/tested on JDK 25). Gradle is provided via the wrapper.
- Vim 8.0+ with `+channel` (run `vim --version | grep +channel`).

## Install (from a release)

Two one-line installers (replace `<org>/<repo>`; they default to `ghostlypi/fschat`):

```sh
# server host:
curl -fsSL https://github.com/<org>/<repo>/releases/latest/download/install-server.sh | bash

# each client machine (prompts to add the Vim plugin to your ~/.vimrc):
curl -fsSL https://github.com/<org>/<repo>/releases/latest/download/install-daemon.sh | bash
```

Each downloads the prebuilt distribution, installs it under `~/.fschat`, and symlinks the
launcher (`fschat-server` / `fschat-daemon`) into `~/.local/bin`. The daemon bundles the Vim
plugin and offers to add it to your `~/.vimrc` (or set `FSCHAT_ADD_VIMRC=yes|no` to answer
non-interactively). Overrides: `FSCHAT_PREFIX`, `FSCHAT_BIN`, `FSCHAT_REPO`. Requires Java 21+.

### Cutting a release

`./gradlew distTar` produces `fschat-server/build/distributions/fschat-server.tar` and
`fschat-daemon/build/distributions/fschat-daemon.tar` (the daemon tar bundles `vim/`). Upload
those two tarballs plus `scripts/install-server.sh` and `scripts/install-daemon.sh` as the
release assets, and the `curl | bash` one-liners above resolve them via `releases/latest/download`.

## Build & test

```sh
./gradlew build      # compile + run all tests
./gradlew installDist # produce runnable scripts under */build/install/
```

The runnable scripts land at:

- `fschat-server/build/install/fschat-server/bin/fschat-server`
- `fschat-daemon/build/install/fschat-daemon/bin/fschat-daemon`

## Run (local dev, plaintext)

```sh
SRV=fschat-server/build/install/fschat-server/bin/fschat-server
DMN=fschat-daemon/build/install/fschat-daemon/bin/fschat-daemon

# 1. Start the server
export FSCHAT_JWT_SECRET="change-me-to-32+-random-bytes-xxxxx"
$SRV --db ./fschat.db --https-port 8443 --ws-port 8444 &

# 2. Register a user and start their daemon (per-user config + chat root)
$DMN register alice --auth-port 8443 --config-dir ~/.config/fschat-alice --root ~/fschat-alice
$DMN start --ws-port 8444 --config-dir ~/.config/fschat-alice --root ~/fschat-alice &

# 3. Open a DM and edit the file in Vim
$DMN dm bob --config-dir ~/.config/fschat-alice --root ~/fschat-alice
vim ~/fschat-alice/dms/bob.chat
```

Type in the bottom (compose) pane and press `<CR>` to send. Group chats:

```sh
$DMN group-new "weekend-trip" bob carol --config-dir ~/.config/fschat-alice --root ~/fschat-alice
$DMN group-add <channelId> dave        --config-dir ~/.config/fschat-alice --root ~/fschat-alice
```

## Run with TLS (wss/https)

Generate a dev keystore + truststore, then pass `--tls`:

```sh
scripts/gen-dev-certs.sh ./certs          # creates dev-keystore.p12 + dev-truststore.p12

$SRV --keystore ./certs/dev-keystore.p12 --keystore-pass devpass \
     --db ./fschat.db --https-port 8443 --ws-port 8444 &

$DMN register alice --tls --truststore ./certs/dev-truststore.p12 --truststore-pass devpass \
     --config-dir ~/.config/fschat-alice --root ~/fschat-alice
$DMN start --tls --truststore ./certs/dev-truststore.p12 --truststore-pass devpass \
     --config-dir ~/.config/fschat-alice --root ~/fschat-alice &
```

## Run the server in Docker

A multi-stage `Dockerfile` builds the distribution and runs it on a JRE; the SQLite DB
lives on a `/data` volume. Works with Docker or Podman.

```sh
docker build -t fschat-server .
docker run -d --name fschat-server \
  -p 8443:8443 -p 8444:8444 \
  -e FSCHAT_JWT_SECRET="$(head -c 32 /dev/urandom | base64)" \
  -v fschat-data:/data \
  fschat-server
```

Or with Compose (set `FSCHAT_JWT_SECRET` in your shell or a `.env` file):

```sh
FSCHAT_JWT_SECRET="$(head -c 32 /dev/urandom | base64)" docker compose up --build -d
```

For TLS, mount a keystore and extend the command:

```sh
docker run -d -p 8443:8443 -p 8444:8444 \
  -e FSCHAT_JWT_SECRET=... -v fschat-data:/data -v "$PWD/certs:/certs:ro" \
  fschat-server --host 0.0.0.0 --db /data/fschat.db --https-port 8443 --ws-port 8444 \
  --keystore /certs/dev-keystore.p12 --keystore-pass devpass
```

(Keep `FSCHAT_JWT_SECRET` stable across restarts, or previously issued tokens stop verifying.)

## Vim plugin setup

Install the plugin once as a package:

```sh
mkdir -p ~/.vim/pack/fschat/start
ln -s "$PWD/vim" ~/.vim/pack/fschat/start/fschat
```

After that, just open a `.chat` file with plain `vim` — no flags needed:

```sh
cd ~/fschat-alice && vim dms/bob.chat      # or: vim ~/fschat-alice/dms/bob.chat
```

The daemon is **auto-discovered**: on start it drops a `.fschat-port` marker in its
chat root, and the plugin walks up from the file you open to find it. Because each
account's files live under its own root, the file you open selects the account — so
two accounts are just two folders, no per-window configuration.

Overrides (rarely needed): `let g:fschat_port = 8444` (explicit port) or
`let g:fschat_port_file = expand('~/.config/fschat-alice/port')` (explicit file).

In a `.chat` buffer: `<CR>` (compose pane) sends; commands typed in the compose pane
(`/block`, `/nick`, `/invite`, `/remove`, `/leave`, `/rename`, …; `//` sends a literal
slash); `:FschatEdit` / `:FschatDelete` act on the message under the cursor (your own
only); `:FschatReload` re-fetches. See `:help fschat`.

## Project layout

| Module           | What it is                                                        |
|------------------|-------------------------------------------------------------------|
| `fschat-protocol`| Wire/model records + Jackson serialization (no I/O).              |
| `fschat-server`  | SQLite event log, auth (bcrypt + JWT), HTTPS + WSS endpoints.     |
| `fschat-daemon`  | Per-user client: WS sync, `.chat` rendering, local Vim server.    |
| `vim/`           | The `fschat.vim` plugin (Vimscript).                              |

## Known limitations (MVP)

- The daemon re-subscribes from seq 0 on startup (full backfill); fine at this scale.
- The local TCP server trusts any process on `127.0.0.1`.
- Server-side errors (e.g. a rejected edit) are logged by the daemon; the Vim
  plugin pre-checks ownership but does not yet surface every server error inline.
