# Switchboard

Switchboard is a Java 21 relay that connects a Telegram supergroup to local AI coding agents (such as Claude, Codex, and Gemini) through a local T3 Code server. Each configured agent runs as its own Telegram bot in the group, allowing you to direct individual agents, broadcast to `@everyone`, or let agents collaborate and hand work off to one another in threads backed by T3 Code.

## Features

- **Per-agent Telegram bots**: Address agents by `@DisplayName`, `@bot_username`, `@everyone`, or by replying to an agent's message.
- **Manual and autonomous modes**: Keep turns strictly user-driven (`manual`) or allow agents to trigger other agents by mentioning them (`autonomous`), guarded by configurable circuit-breaker depth, turn, and cooldown limits.
- **Interactive approvals and questions**: T3 runtime permission requests and multi-choice/free-text agent questions are posted to the group with inline buttons.
- **Per-agent Git worktrees**: When the active T3 project is a Git repository, each agent works in its own worktree off the project branch.
- **Live usage and quota tracking**: Polls T3 Code usage meters and records quota/rate-limit reset times from failed turns with automatic reset reminders.

### Group Commands

| Command | Access | Description |
|---|---|---|
| `/agents` | Anyone in group | Show current mode, active project, T3 credential expiry, and each agent's status, provider instance, and model |
| `/usage [agent]` | Anyone in group | Show live usage meters from T3 Code, observed quota limits, and reset times |
| `/help` (or `/start`) | Anyone in group | List addressing syntax and available commands |
| `/mode [manual\|autonomous]` | Admin only | View or switch whether agents can hand work off to each other |
| `/discuss <turns> <task>` | Admin only | Run a bounded multi-agent conversation for up to `<turns>` turns, even in manual mode |
| `/model [agent] [model\|default]` | Admin only | View or switch an agent's model (or reset to the configured default) |
| `/project [name\|default]` | Admin only | View or switch the active T3 project that agents work in |
| `/stopall` | Admin only | Interrupt all running turns and drop queued hand-offs |
| `/reauth` | Admin only | Exchange a fresh `t3.pairingToken` from `config.json` without restarting |

## Requirements

- **Linux or macOS.** Developed and used on Linux. The full test suite also runs on macOS (Apple Silicon) in CI, but it hasn't been tried against a live T3 Code server on a Mac. Native Windows isn't supported: the credential store creates its file with POSIX permissions, which fails on NTFS. On Windows, run both T3 Code and Switchboard inside WSL2 so they share the same `~/.t3` directory.
- **JDK 21+**
- **A running T3 Code server** (v0.1.2, orchestration protocol v2)
- **One Telegram bot per agent** created via `@BotFather`, added to a Telegram **supergroup** with either privacy mode disabled (`/setprivacy` → `Disable`) or admin rights in the group so each bot receives group messages

## Setup

1. Copy the example configuration to the global Switchboard config path and restrict its permissions:

   ```sh
   mkdir -p ~/.switchboard/configs
   chmod 700 ~/.switchboard
   cp config.example.json ~/.switchboard/configs/config.json
   chmod 600 ~/.switchboard/configs/config.json
   ```

2. Generate a one-time pairing token from your T3 Code installation:

   ```sh
   ~/.t3/bin/t3 pair --label switchboard --scope orchestration:read --scope orchestration:operate
   ```

3. Fill in `~/.switchboard/configs/config.json` with your Telegram supergroup ID (`telegram.groupId`, negative `-100...` ID), your Telegram user ID (`telegram.adminUserId`), the T3 pairing token (`t3.pairingToken`), your T3 project title or ID (`t3.project`), and each agent's BotFather token, T3 `instanceId`, and `model`.

4. Build the installable distribution:

   ```sh
   ./gradlew installDist
   ```

5. Probe the T3 server (discovers the server, exchanges the pairing token for a bearer credential in `~/.switchboard/state/t3-credential.json`, and lists projects/providers):

   ```sh
   build/install/switchboard/bin/switchboard probe
   ```

6. Start the relay:

   ```sh
   build/install/switchboard/bin/switchboard run
   ```

   An explicit config file path can also be passed as the second argument (`switchboard probe /path/to/config.json` or `switchboard run /path/to/config.json`).

## Security

- **Protect configuration and state files**: `config.json` contains your Telegram bot tokens and T3 pairing token, and `~/.switchboard/state/t3-credential.json` stores the exchanged 30-day T3 bearer token. Keep `~/.switchboard` mode `700` and `config.json` mode `600` (`chmod 600 ~/.switchboard/configs/config.json`). Once `probe` or `run` has exchanged `t3.pairingToken`, the single-use pairing token is consumed and can be removed from `config.json`.
- **Admin-gated execution**: Only messages from `telegram.adminUserId` in the configured `telegram.groupId` can start agent turns, approve runtime requests, answer agent questions, or invoke admin commands (`/mode`, `/discuss`, `/model`, `/project`, `/stopall`, `/reauth`).
- **Host execution permissions**: Agents execute on the host running T3 Code with the permissions granted by `t3.runtimeMode` (`auto-accept-edits` by default; valid modes are `approval-required`, `auto-accept-edits`, `auto`, and `full-access`). Choose a mode appropriate for your host environment.

## Documentation

- [Global Switchboard Home (`~/.switchboard`)](docs/global-home.md)
- [T3 Code Integration & Systemd Service Notes](docs/t3-protocol.md)
- [Usage-Limit Observations & Alerts](docs/usage.md)

## License

[`AGPL-3.0-only`](LICENSE)
