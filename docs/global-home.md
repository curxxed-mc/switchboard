# Global Switchboard home

Switchboard shares `~/.switchboard/` across projects and agent worktrees:

- `configs/config.json`: relay configuration, including bot tokens.
- `skills/`: shared skill files.
- `mcps/`: shared MCP configuration files.
- `mds/`: shared Markdown documents.
- `state/`: default SQLite database and T3 credentials.

`run` and `probe` create missing directories without replacing existing files.
An explicit config argument still takes priority. Otherwise Switchboard uses
`~/.switchboard/configs/config.json`, falling back to `./config.json` if only the
local file exists. Copy `config.example.json` to the global config path and fill
in your values to opt in. Restrict the home directory and secret files to your
own user (`chmod 700 ~/.switchboard`, `chmod 600 ~/.switchboard/configs/config.json`).

An omitted, null, or blank `t3.stateDir` uses `~/.switchboard/state`. Explicit
paths retain their existing behavior, including paths relative to the working
directory. Existing configs, credentials, and databases are not automatically
moved; stop the relay before migrating its state, and update `t3.stateDir` when
ready. Do not start multiple relay instances sharing the same bots or state.

The skills, MCP, and Markdown directories are storage locations only: this does
not automatically install skills, register MCP servers, or inject documents into
agent prompts. Provider-specific integration remains separate.
