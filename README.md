# CrackedCode

Kotlin(JVM) coding agent with a reusable core, a local tool runtime, and an interactive CLI.

## Modules

- `agent-core`: domain model, orchestration loop, approvals, and session persistence.
- `agent-provider-openai`: OpenAI-compatible streaming provider adapter.
- `agent-tools-local`: local filesystem, search, shell, and patch tools.
- `agent-cli`: interactive terminal application.

## Quickstart

1. `./gradlew test`
2. Set `OPENAI_API_KEY`
3. Optionally set `OPENAI_BASE_URL` and `CRACKEDCODE_MODEL`
4. `./gradlew installCcode`
5. `scripts/install-cli.sh`
6. `ccode`

The installer places the launcher in `~/bin/ccode` by default and keeps the
versioned distribution under `~/.local/share/ccode/`.

## CLI

- `ccode` starts the REPL in the current directory.
- `ccode --workspace /path/to/repo` starts the REPL for an explicit workspace.
- `ccode resume <session-id>` resumes a session and enters the REPL.
- `ccode status [session-id]` prints recent sessions or one specific session.
- `ccode tools` lists the available tools.

## Config

- Global fallback config: `~/.config/ccode/config.properties`
- Workspace override config: `.crackedcode-agent/config.properties`
- Environment variables win over both config files: `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `CRACKEDCODE_MODEL`
