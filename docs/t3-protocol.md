# T3 Code integration notes (Phase 1)

Target: **T3 Code (Beta) 0.1.2**, orchestration protocol **v2**. Source of truth is
`github.com/pingdotgg/t3code` at tag `v0.1.2`. Paths below are relative to that repo.

Legend: **[live]** confirmed against the running server · **[src]** read from source, not yet
exercised · **[assumed]** not yet confirmed by either.

## Discovery

- [live] The server writes `~/.t3/userdata/server-runtime.json` (`origin`, `pid`, `serverVersion`).
  Read that file instead of hardcoding a port: Beta defaults to 3773, though the port can vary
  (for example, 3774 when running alongside an older Alpha 0.0.45 install). Check `pid`, because a
  crashed server can leave a stale file behind.
- [live] `GET /.well-known/t3/environment` is public and returns `serverVersion` and
  `orchestrationProtocolVersion`.
- [live] Unknown routes return the web app's `index.html` with **HTTP 200**, so a response is
  only valid if it is 200 *and* JSON.

## Auth

- [live] Policy is `desktop-managed-local`. Session methods: cookie, bearer, DPoP. Nothing is
  anonymous except the descriptor and `/api/auth/session`.
- [src] Headless clients pair with `~/.t3/bin/t3 pair`, which is the v0.1.2 CLI (avoid older
  0.0.45 `t3` binaries that may be on `PATH`, such as `~/.local/bin/t3`). `t3 pair` writes a
  `one-time-token` grant directly into the running server's store (`apps/server/src/cli/pair.ts`).
  The advertised `bootstrapMethods` value is descriptive and is not enforced on exchange.
- [live] Exchange: `POST /oauth/token`, form-encoded:
  `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`,
  `subject_token=<pairing credential>`,
  `subject_token_type=urn:t3:params:oauth:token-type:environment-bootstrap`,
  `requested_token_type=urn:ietf:params:oauth:token-type:access_token`, optional `scope`
  (narrowing only) and `client_label`. Returns a Bearer token valid for **30 days**
  (`expires_in` 2591999). The relay stores it in `t3-credential.json` (mode 600) inside the state
  directory (`~/.switchboard/state/` by default).
- [live] **Java gotcha:** `HttpClient` defaults to HTTP/2 and sends an h2c upgrade on plain
  `http://`. T3 rejects that on POSTs with an **empty 400**, while GETs still work. Pin
  `HttpClient.Version.HTTP_1_1`.
- The relay requests only `orchestration:read orchestration:operate`. `t3 pair`'s default set
  also includes `access:write`, `relay:write`, `providers:manage` and others, so pass `--scope`
  explicitly.
- [live] WebSocket: `POST /api/auth/websocket-ticket` (bearer) returns a `ticket`. Connect to
  `ws://<origin>/ws?wsTicket=<ticket>&orchestrationProtocol=2`. The server rejects the upgrade
  if `orchestrationProtocol` doesn't match.

## HTTP (reads only in v2)

- [live] `GET /api/projects`: `{projects, updatedAt}`.
- [live] `GET /api/orchestration/shell`: `{schemaVersion, snapshotSequence, threads, archivedThreads, projects}`. Requires header `x-t3-orchestration-protocol: 2`.
- [src] `GET /api/orchestration/threads/:id` (also `/transcript`, `/bounded`, `/history`): same header.
- [live] **No HTTP dispatch in v2.** `/api/orchestration/dispatch` (0.0.45) and
  `/api/orchestration/snapshot` are gone; the latter now falls through to `index.html`.

## WebSocket RPC (writes)

- [src] Effect RPC with JSON serialization (`RpcSerialization.layerJson`, `apps/server/src/ws.ts`).
  Method names are in `ORCHESTRATION_V2_WS_METHODS` (`packages/contracts/src/orchestrationV2.ts`).
- [live] Request frame: `{"_tag":"Request","id":"1","tag":"<method>","payload":{…},"headers":[]}`.
  Reply: `{"_tag":"Exit","requestId":"1","exit":{"_tag":"Success","value":…}}`. Verified with
  `orchestration.getArchivedShellSnapshot`.
- [assumed] Streaming RPCs (`subscribeThread`) reply with `Chunk` frames and need client `Ack`s.
  Not exercised yet.
- Relevant methods and commands:

| Relay need | v2 mechanism |
|---|---|
| Create an agent thread | RPC `orchestration.launchThread` (`OrchestrationV2ThreadLaunchInput`: projectId, modelSelection, runtimeMode, interactionMode, workspaceStrategy, optional initialMessage) |
| Send a turn | `dispatchCommand` → `message.dispatch` with `dispatchMode` `start_immediately` / `queue_after_active` / `steer_active` |
| Stream progress | RPC `orchestration.subscribeThread` (stream; `afterSequence` for resume) |
| `/stopall` | `run.interrupt {threadId, runId}`; `thread.stop` for a hard stop |
| `/model` | `thread.model-selection.set {threadId, modelSelection}` |

## Providers

From `~/.t3/userdata/settings.json` and `model-manifest.json`:

| Bot | `instanceId` | Example models |
|---|---|---|
| Claude | `claudeAgent` | `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-fable-5-1` |
| Codex | `codex_<instance-id>` (or `codex` when using the default instance) | `gpt-6.1-sol`, `gpt-6-astra`, `gpt-6-luna` |
| Gemini | `antigravity` | `gemini-3.8-flash-high`, `gemini-pro-agent` (no "Argon" model is listed) |

`ModelSelection` on the wire is `{instanceId, model, options?}`. Codex and the Antigravity ACP
bridge are T3-managed (`~/.t3/tools/…`), so neither binary needs to be on `PATH`.

## MCP

- T3 gives every thread its own thread-scoped MCP connection (`apps/server/src/mcp/`). Each
  harness otherwise uses its native MCP config: Claude uses `~/.claude`, and Codex uses its home.
- **Antigravity runs in an isolated profile.** MCP servers, hooks and rules in `~/.gemini` are
  intentionally not loaded (`docs/internals/providers.md`). Any MCP server attached only to a
  local Antigravity IDE session will not be visible to a T3-driven Gemini thread without
  configuring it on that T3 instance.

## Running as a service

Build the distribution from your checkout directory (`./gradlew -q installDist`) and install a
systemd user unit at `~/.config/systemd/user/switchboard.service`:

```ini
[Unit]
Description=Switchboard Telegram-to-T3 relay
After=network-online.target

[Service]
Type=simple
WorkingDirectory=%h/path/to/switchboard
ExecStart=%h/path/to/switchboard/build/install/switchboard/bin/switchboard run
Restart=on-failure
RestartSec=10
TimeoutStopSec=90

[Install]
WantedBy=default.target
```

By default, configuration and state live under `~/.switchboard` (`~/.switchboard/configs/config.json`
and `~/.switchboard/state/`; see [docs/global-home.md](global-home.md)), falling back to
`WorkingDirectory` only if `./config.json` exists without a global config. After code changes, run
`./gradlew -q installDist` from your checkout directory and restart the service.

```sh
systemctl --user daemon-reload
systemctl --user enable --now switchboard.service
systemctl --user start switchboard.service
systemctl --user stop switchboard.service
systemctl --user status switchboard.service
journalctl --user -u switchboard.service -f
```

The service restarts on failure after 10 seconds. Stopping sends SIGTERM and allows
up to 90 seconds for a clean shutdown.
