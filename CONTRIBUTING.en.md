# Contributing

[中文](CONTRIBUTING.md) ｜ **English**

Issues and pull requests are welcome. The goal is to let people who are not comfortable with a terminal use the tools they installed with conda from inside TBtools; anything that makes that path smoother is welcome.

## Requirements

| Item | Requirement |
|---|---|
| OS | Windows 10 / 11 x64 |
| TBtools | **2.558 or newer** (the AI assistant needs the built-in model bridge) |
| JDK | **11** — the `jre` bundled with TBtools is fine; `build.ps1` locates it automatically (`TBTOOLS_HOME` → `JAVA_HOME` → usual install locations) and checks that `javac` really is 11 |
| Runtime | local WSL, or a Linux host reachable over SSH |

## Build and test

```powershell
cd BioEnvCaller
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

`build.ps1` is the single entry point; it:

1. compiles `vmadapt\src` (the SSH transport module)
2. compiles `aidev-project\src` (the plugin itself)
3. checks every class is **major 55** (Java 11)
4. runs the **regression tests** (87 at the moment) and the **vmadapt tests** (17)
5. produces `BioEnvCaller.plugin`

**The gate has to be green before a commit**:

```
Regression: 78 passed, 0 failed
vmadapt:    15 passed, 0 failed
```

> `build.ps1` must keep its **UTF-8 BOM**; PowerShell 5.1 otherwise reads the Chinese comments as GBK and fails to parse the file.

## Layout

```
aidev-project\
  project.json / MenuConfig.ini   TBtools AI Dev project descriptor (menu entry: AI+)
  plugin\ai.html                  AI assistant page (runs inside JxBrowser)
  plugin\tbtools-ai.json          AI capability manifest (chat / vision)
  src\bioenv\
    core\   tool discovery, help parsing, type inference, command building (no UI)
    ui\     Swing interface (MainPanel / SchemaFormPanel / parameter editor…)
    i18n\   Chinese and English string tables (all UI text goes through here)
    ai\     AI panel and model settings
    test\   plain main() tests, no JUnit, all of them in the gate
    tools\  investigation main() programs, compiled but neither packaged nor gated
vmadapt\
  src\vmadapt\core\   SSH transport, keys, remote files (no UI)
  src\vmadapt\ui\     connection panel and wizard
```

## Conventions

- **Tests** are plain `main()` methods, no JUnit. Add a test with every new behaviour and register its class in the `build.ps1` gate list.
- **No hard-coded UI strings**: put them in `i18n\Strings.java`, one Chinese and one English entry. A missing translation shows up on screen as the key name.
- **Never modify TBtools itself**; the plugin only uses public interfaces (`window.tbtools.ai`, `WebGuiJPanel`, `toolsKit.*`).
- **Commit messages are in English** and explain why, not what.
- **Tags never move** after a release; changes go into `v1.0.1`, `v1.0.2`, …

## What a bug report needs

- plugin version (Release tag, or what the settings page shows)
- TBtools version and Windows version
- how you connect: WSL distro, or SSH host — for a VM say VMware or VirtualBox and which network mode
- steps to reproduce and screenshots — above all the error text from the output pane
- for parsing problems, attach the tool's `--help` output
