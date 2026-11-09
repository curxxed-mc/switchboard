# Usage-limit observations

`/usage` reports all agents; `/usage claude`, `/usage codex`, and `/usage gemini`
filter the report. `/help` lists the command.

## Live meters from T3 Code

T3 Code already measures subscription usage for some providers and returns it
from `server.getConfig` as `providers[].usageLimits`: windows such as `five_hour`
or `seven_day`, each with a label, `usedPercent` and `resetsAt`. `/usage` reads
that on every call and shows each agent's windows by provider instance, e.g.
`Session: 97% used · resets Sun 17:30 UTC (in 2h 4m)`, plus how old T3's reading
is. It works with the relay's existing `orchestration:*` credential.

Coverage depends on T3: Claude reports its session and weekly windows.
Antigravity reports its shared Claude & GPT windows. ChatGPT-backed Codex is
"unsupported", so `/usage` shows T3's explanation and the ChatGPT usage link.
If T3 can't be reached, `/usage` says so and falls back to the observations below.

Every 5 minutes the relay polls the same meters. When a window reaches 100% it
posts one alert through that agent's bot ("Claude used 100% of its Session usage
limit; resets …") and records the limit with T3's reset time, so the reset
reminder below fires. Each window and reset time alerts once.

A CLIProxyAPI hub (T3 Settings → Providers → Usage providers) adds pooled
accounts to T3's `usageLimitSources`. Switchboard doesn't read those yet.

## Observed limits from failed turns

Without a live meter, five-hour and weekly percentages are **unknown**. No recorded limit
does not imply available quota. Switchboard recognizes explicit quota/usage/rate
limit errors from failed T3 turns, reports the limit in the usual failure notice,
and saves the observed window and reset time in SQLite. Generic failures,
Telegram throttling, and ordinary assistant replies do not establish quota state.

Reset times are accepted from explicit `resetsAt`, `resetAt`, `resets_at`, or
`reset_at` fields (ISO timestamps with an offset, Unix seconds, or Unix
milliseconds), or a `retryAfterSeconds`/`retry_after` duration in seconds. These
fields may also be under `failure.details`. An ISO timestamp explicitly following
"reset", "try again", or "available again" in the provider error is supported.
Explicit durations such as "try again in 5 hours 15 minutes" or "resets in 2m"
are measured from when the relay observes the failure (so delayed events may
make those reminders late). A retry duration does not identify a quota window.
Ambiguous local times such as "8pm" are not guessed. Missing or unparseable reset
times are reported as **unknown** and never scheduled.

The relay checks every 30 seconds for reported reset times that have passed,
and posts a reminder through the affected agent's bot. This is a **reported-time
reminder**, not proof of recovered quota; another limit may still apply. The
reminder state survives restarts and is saved after successful Telegram delivery.
A crash between delivery and saving may duplicate a reminder. A new limit error
replaces that window's observation and arms a new reminder. A successful turn
clears previous limit observations for that agent/provider/model combination.

Observations are scoped by agent, provider instance, model, and window. Changing
models hides unrelated observations and suppresses their reminders until that
model is selected again. No automatic model switching, quota probing, or retries
are performed. Raw provider error payloads and credentials are not persisted.

Rebuild with `./gradlew installDist` and restart the relay to enable the feature.
The existing database is migrated automatically; back it up before deployment.
