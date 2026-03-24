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

## Homebrew

This repository now includes a first-pass Homebrew formula at `Formula/ccode.rb`
and a release workflow at `.github/workflows/release.yml`.

The intended flow is:

1. Tag a release as `v<version>` where the tag matches `./gradlew -q printVersion`
2. Let the release workflow run tests, build `ccode-<version>.tar` and `ccode-<version>.zip`, and publish a generated `ccode.rb` asset alongside them
3. Copy the generated `ccode.rb` into your tap repository under `Formula/ccode.rb`

The formula installs the Gradle distribution into `libexec`, wraps the launcher
with Homebrew's `JAVA_HOME`, and verifies the install with `ccode version` and
`ccode tools`.

## CLI

- `ccode` starts the REPL in the current directory.
- `ccode --workspace /path/to/repo` starts the REPL for an explicit workspace.
- `ccode resume <session-id>` resumes a session and enters the REPL.
- `ccode status [session-id]` prints recent sessions or one specific session.
- `ccode tools` lists the available tools.
- `ccode version` prints the packaged version.

## Config

- Global fallback config: `~/.config/ccode/config.properties`
- Workspace override config: `.crackedcode-agent/config.properties`
- Environment variables win over both config files: `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `CRACKEDCODE_MODEL`
