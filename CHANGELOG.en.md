# Changelog

[中文](CHANGELOG.md) ｜ **English**

## Unreleased

- **The build scripts no longer assume TBtools lives at `D:\TBtools`**: both `build.ps1` files (the root one and the one under `vmadapt\`) search `TBTOOLS_HOME` → `JAVA_HOME` → the usual install locations for JDK 11, and check every candidate's `javac -version` really says 11; `TBTOOLS_JAR` / `TBTOOLS_HOME` can also point at the host jar. When nothing is found, the message says what to set.
- **Docs**: README, CONTRIBUTING and now DESIGN / CHANGELOG are split into Chinese and English files with a one-line switcher at the top; README images use jsDelivr (`raw.githubusercontent.com` is often unreachable from mainland networks).
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
