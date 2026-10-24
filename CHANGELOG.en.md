# Changelog

[中文](CHANGELOG.md) ｜ **English**

## Unreleased

- **An SSH timeout now asks the remote process tree to stop**: the transport contract gained a far-side "stop command" argument (`runBashStreaming(script, sink, killScript)`, with a default that keeps the old behaviour). `Runner` passes down the marker-based kill script it already had for Stop; when an SSH run times out, the local `ssh.exe` is terminated and the stop command goes out over a separate connection. The message distinguishes three cases honestly: no stop command available / sent (which does not mean confirmed stopped) / could not be sent, with the reason.
- **The UI is no longer frozen by blocking process work**: the Stop button's remote kill, the interactive process start and the terminal-window launch moved to background threads (new `OffEdt`), while the status bar and log still update on the event thread. A process that comes up after the user pressed Stop is rejected and destroyed instead of being adopted.
- **Two silent failures**: `EnvRunner` now quotes the conda prefix as a proper Python literal (a prefix containing a single quote no longer makes the dependency analysis return an empty set silently), keeps stderr and reports a failure once; `KeyInstaller` shell-quotes the public key (a key comment with a single quote no longer breaks the remote command) and bounds the wait on ssh, with distinct Chinese outcomes for timeout and interruption.
- **Two panel instances no longer overwrite each other's configuration**: tool notes, type corrections and custom options now merge on save — re-read the file, apply this instance's changes and deletions (tombstones), write atomically. A deletion cannot be resurrected by another instance's stale snapshot, and a failed write keeps the pending changes for the next retry.
- **The API-key file is restricted to its owner**: after a successful save of `ai-models.properties`, permissions are narrowed to POSIX `rw-------` or a Windows ACL with only the owner (verified by re-reading). Filesystems that cannot do it leave the file alone and report at most once per process; saving is never affected.
- **AI panel**: the page→Java requests are taken atomically in one `evalString` (an export/settings click can no longer be lost, and the same request cannot be handled twice); a single answer is capped at 1,000,000 rendered characters — the stream is still read to the end, but accumulation stops and a truncation note is shown, and history/export store the capped text.
- **Tests**: `RunKillTest`, `OffEdtTest`, `EnvRunnerQuoteTest`, `ConfigMergeTest` and `KeyInstallScriptTest` added and wired into the `build.ps1` gate.

## v1.0.1 — 2026-10-23

- **Timeouts are no longer dead code (P0)**: probing and running read stdout to EOF before `waitFor(timeout)`, so any child that kept stdout open made the timeout unreachable and the UI sat on "detecting". Each stream now gets a pump thread and the wait comes first: the WSL probe's 25 s and the SSH run's 300 s really fire, the process is killed, and the message says it timed out. The scanner scripts (`find /` for conda, `conda env list`) get `timeout 20`. On an SSH timeout the message says the local ssh client was terminated but the remote process tree may still be running — the marker is not part of the transport contract, so it is not claimed.
- **Safe config writes**: all ten stores write through "temp file + atomic replace", so a half-written file can no longer destroy the previous copy. A file that cannot be parsed is backed up to `.bak` before continuing empty, and a failed write is reported in the output pane (`ConfigWarnings`) instead of "saved" followed by silence. Counts read from a file are bounded, so one hand-edited number cannot turn opening the settings page into an out-of-memory error.
- **Bounded output**: retained output is capped at 200 000 lines / 8 000 000 characters with an honest "N lines dropped" marker, and the output pane trims to its last 300 000 characters above 400 000 with a one-line notice.
- **Interactive runs can be stopped**: the Stop button, starting a new run and disposing the panel all end the interactive process (destroy, then destroyForcibly after 800 ms), and a stopped run no longer blocks the next one.
- **AI context completed**: the command, the connection mode, the parameter table and the last run's exit code and output tail really travel with the context now. The docs promised them; the code sent only the tool, environment, help text and working directory. The preview re-pushes 1.2 s after the last edit, so "is this command right?" sees the current command.
- **AI panel**: a 60-second idle timeout; a stop is no longer stored as a finished answer; CRLF streams render live; the bridge request is trimmed to 20 messages while the panel, persistence and export keep everything; a custom endpoint is validated and normalised on save (missing scheme, a pasted `/chat/completions`, spaces).
- **Docs**: the "keeps only the last 24 messages" claim in README / help.md now matches the behaviour.
- **Tests**: `AtomicFilesTest`, `OutputCapTest`, `ScannerTimeoutTest`, `AiContextTest`, `EndpointUrlTest` and `ProcessCaptureTest` added and wired into the `build.ps1` gate.
- **The build scripts no longer assume TBtools lives at `D:\TBtools`**: both `build.ps1` files (the root one and the one under `vmadapt\`) search `TBTOOLS_HOME` → `JAVA_HOME` → the usual install locations for JDK 11, and check every candidate's `javac -version` really says 11; `TBTOOLS_JAR` / `TBTOOLS_HOME` can also point at the host jar. When nothing is found, the message says what to set.
- **Docs**: README, CONTRIBUTING and DESIGN / CHANGELOG are split into Chinese and English files with a one-line switcher at the top; README images use jsDelivr (`raw.githubusercontent.com` is often unreachable from mainland networks).
- **Test fix**: `ConnectionLayoutTest.findSource()` had a hard-coded fallback that returned `ConnectionPanel.java` whatever the caller asked for, so running the suite from `vmadapt\` fed `UiText`'s wording checks the wrong file and turned three checks red for no real reason.

## v1.0.0 — 2026-10-19

First public release.

### Connecting

- **WSL**: lists the local distributions, pick one
- **SSH remote**: a six-step wizard (host → port → user → key notice → password → profile name)
  - can generate an SSH key and install the public key on the target
  - connection failures are told apart: «connection refused / timed out / host cannot be resolved»
  - several saved profiles supported
- The connection mode, distribution and last selected tool are remembered

### Tool discovery

- Scans executable commands out of **conda environments**, grouped by environment
- Incremental: an unchanged environment is served from cache, a changed one is rescanned automatically
- A newly installed tool appears after one click on "scan conda environments"

### Parameters

- A typed form is **parsed automatically** from the tool's `--help`: text / number / switch / choice / file / directory
- **Parameter editor**: rename, edit the description, change the type, drag to reorder, add parameters by hand, remove parameters (recoverable / permanent), reset per tool
- Type corrections are remembered across restarts
- The command preview is composed live, **can be edited by hand**, and can be rebuilt in one click
- "Extra arguments (one per line)" catches whatever could not be parsed

### Running and output

- **Run** (inside the plugin) / **interactive run** (type while it runs) / **run in a terminal** (keeps going after the plugin task ends) / **stop**
- The output pane keeps plain text, strips ANSI control sequences, and ends with the exit code and elapsed time

### Files

- A built-in **Linux directory browser** (path, permissions, size, modification time)
- Optional **Windows files**: on SSH the file is copied into the Linux-side working directory and the path is translated

### Tool assistant

- "Help text": exactly what the tool printed, verbatim
- "My help": per-tool notes of your own (reference only, never parsed)

### AI assistant

- **Context is carried automatically**: current tool and environment, command, parameter table, raw help text, working directory, last run's exit code and output tail, local tool list
- Multiple sessions (named after the tool or the start of the question), in-page confirmation before deleting, **Markdown export**
- **Real token usage** (taken from the server's `usage`; when unavailable it is estimated and marked `≈`)
- Models: **TBmodel** (built into TBtools) plus any number of OpenAI-compatible custom models, switched from the input box
- Attachments: text files, images (images with TBmodel only)
- Failures are kept on screen; retry / edit / continue / copy; code blocks copy separately

### Interface

- Three themes — classic / light / dark — and a Chinese/English interface
- The tool tree and the AI panel share the left column; **the column width is draggable and remembered**, double-clicking the divider resets it
- Settings in their own window (connection / look / language / models)

### Quality

- **93 built-in tests** (78 regression + 15 vmadapt), all part of the `build.ps1` gate
- Build target **Java 11**, the deliverable is a single `.plugin`
- No third-party runtime dependencies (beyond the TBtools host itself)

### Known limitations

- **Linux** tools only (WSL / SSH); native Windows command-line tools are out of scope
- One tool at a time, no workflow orchestration
- The AI carries only the most recent **24** messages; export important conversations
- Custom models do not accept image attachments and are subject to browser CORS
