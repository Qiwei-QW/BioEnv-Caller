# BioEnv Caller

**Call the bioinformatics tools installed in your conda environments from TBtools, through a GUI**

[中文](README.md) ｜ **English**

[![release](https://img.shields.io/github/v/release/Qiwei-QW/BioEnv-Caller)](https://github.com/Qiwei-QW/BioEnv-Caller/releases) [![license](https://img.shields.io/github/license/Qiwei-QW/BioEnv-Caller)](LICENSE) [![stars](https://img.shields.io/github/stars/Qiwei-QW/BioEnv-Caller)](https://github.com/Qiwei-QW/BioEnv-Caller/stargazers)

> **Download**: [Releases](https://github.com/Qiwei-QW/BioEnv-Caller/releases/latest) ｜ **License**: [MIT](LICENSE) ｜ **Build**: [build.ps1](build.ps1) ｜ **Design**: [DESIGN.md](DESIGN.md) ｜ **Changelog**: [CHANGELOG.md](CHANGELOG.md) ｜ **Contributing**: [CONTRIBUTING.en.md](CONTRIBUTING.en.md)

If you run bioinformatics tools on Linux, you have probably met the same wall: OrthoFinder takes 21 parameters and every one of them has to be picked out of a `[Default = xxx]` line; the help text is English and still leaves you unsure whether `-M` and `-A` have to go together; the data lives on Windows while the tools live on Linux; and after a few months away you have to read the help again. BioEnv Caller is built for exactly that.

> **Who it is for**: people who install tools with conda but would rather not re-read the help every time, and groups that share one server or workstation between several members.

---

## 1. What it solves

- **Flags you cannot remember, commands you cannot get right** → pick a tool, get a generated parameter form and a generated command; the command line itself stays editable.
- **Options you do not understand** → the *Tool assistant* shows the tool's own help verbatim and keeps your own notes next to it. An AI assistant is also wired in: it automatically carries the current tool, command, parameter table and the last run's error, so one short question usually settles it.
- **Linux tools that are awkward to run on Windows** → connect to WSL or to a remote host over SSH and run there; the output comes back to the panel.
- **Shared computation resources** → several people can each run their own TBtools against the same server or workstation without fighting over one GUI session.

---

## 2. The main window

![Main window](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/01-overview.png)

*Pick a tool on the left, fill the form in the middle, read the output below*

| Area | What it does |
|---|---|
| icon rail (far left) | first icon toggles the tool tree, second toggles the AI panel, bottom ⚙ opens settings |
| left column | **tool tree**: commands grouped by conda environment, with a filter box on top |
| top right | **parameter area**: parameter form, command preview, run buttons |
| bottom right | **output**: whatever the command prints; clear button at its top right |
| bottom bar | **status bar**: a hint on the left, the current connection on the right (e.g. `WSL`) |

The tool tree and the AI panel share one column: drag the divider to resize (the width is remembered), double-click it to reset.

---

## 3. Installing

1. Download `BioEnvCaller.plugin`
2. In TBtools click **Others → Plugin → Install Plugin** and drag the file into the dialog (the plugin ends up under `AI+`)
3. **Restart TBtools**

**Requirements**: TBtools **2.558 or newer** (needed by the AI assistant), Windows 10/11, either **WSL2** on the same machine or a Linux host reachable over SSH, with conda set up and your tools installed inside conda environments.

> Upgrading is the same procedure (it overwrites); always restart afterwards — the plugin jar is loaded into the running process.

---

## 4. Two ways to connect

Click ⚙ to open settings; the first page is **Connection**.

![Settings → Connection](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/02-settings-connection.png)

*Pick a mode, pick a target, scan*

### 4.1 WSL (local, simplest)

Prerequisite: WSL2 is installed on Windows, conda is configured inside it, and your tools are installed in conda environments.

1. Set **Connection** to `WSL`
2. Pick a **WSL distro** (greyed out when only one is installed)
3. Click **Scan conda environments**

When the scan finishes the status bar shows `Ready. N commands available, from Ubuntu.` and the tree lists every tool installed in this machine's conda environments.

> If the scan comes back empty, check in a WSL terminal that `conda env list` shows the environment and `which <tool>` finds the binary, then scan again.

### 4.2 SSH (VM / server / workstation)

Prerequisite: conda is set up on that Linux VM / server / workstation, with your tools installed inside conda environments.

Set **Connection** to `SSH`, then either pick the profile created automatically the first time or click **+ New profile** to walk through the wizard:

![SSH wizard](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/03-ssh-wizard.png)

*Six steps: host → port → user → key note → password → profile name*

| Step | What to enter |
|---|---|
| ① | host name or IP |
| ② | port (default 22) |
| ③ | Linux user name — the `XXX` in the `XXX@ubuntu` prompt |
| ④ | read-only note: an SSH key pair is generated locally |
| ⑤ | password → **Connect** |
| ⑥ | name the profile; it is selected from the dropdown in ⚙ later |

**Cannot connect?** The dialog tells you which of the three it is: refused (wrong port or no sshd), timed out (network or firewall), host not resolved (typo in the host name).

**Check whether the target runs sshd**, in a terminal on the target:

```bash
sudo systemctl status ssh          # if not "running", continue
sudo systemctl enable --now ssh    # enable it
sudo apt install openssh-server    # install if missing
```

#### 4.2.1 Connecting to a VM (VMware / VirtualBox)

To the plugin a VM is just another SSH host, but the **network mode** decides whether the host can reach it and which address to enter:

- **Bridged**: the VM looks like a separate machine, reachable directly → enter its **LAN address**
- **NAT (VMware)**: reachable directly → enter the address on the **NAT subnet**
- **NAT (VirtualBox)**: not reachable by default → add a **port forward** for port 22 and enter `127.0.0.1` plus the forwarded port
- **Host-only**: reachable, but the VM has **no internet** → enter the address on the **host-only subnet**

Not sure which one? Check for a default gateway inside the VM:

```bash
ip route | grep default     # output: bridged or NAT; empty: host-only
ip -4 addr show | grep inet # take the non-loopback line, drop the /24
whoami                      # this is the user name
```

#### 4.2.2 Connecting to a server / workstation

There is no network mode to choose; the only question is whether **ssh can reach it**.

- **Cloud server**: public IP plus a security-group rule allowing port 22
- **Workstation on a LAN**: an internal address (preferably static) plus a firewall rule

If `ssh user@host -p port` works in a Windows terminal, the plugin can connect.

**Not supported yet**: multi-hop through a jump host (workaround: `ssh -N -L 2222:target:22 user@bastion` on Windows, then point the plugin at `127.0.0.1:2222`), and logins that require an SMS / OTP second factor.

---

## 5. Pick a tool, fill in the parameters

- The tree groups commands by **conda environment**; the **filter box** on top matches names; **double-click an environment** to expand or collapse it
- Pick a tool and a **typed form** appears: text, number, switch, dropdown, file, directory
- The initial types are guessed — anything can be corrected in the **parameter editor**
- The command is **built live** and the text itself is editable; if it gets messy, click **Rebuild command**
- Parameters the parser could not work out go into **Extra arguments (one per line)**; adding them in the **parameter editor** is usually better
- Path parameters have a `…` button that browses the **Linux directory tree**; in SSH mode a file picked from Windows is **copied into the Linux working directory** automatically

Tools with many parameters can be opened in their own window:

![Standalone parameter window](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/05-param-window.png)

*Edit, then click Run this command*

Two file pickers, chosen by the parameter type:

![Linux directory / Windows file](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/06-file-dialogs.png)

*Pick a Linux directory (input) / pick a Windows file (output)*

---

## 6. Calibrate the parameter table before the first run

Automatic parsing is a good guess, not a guarantee: a dropdown becomes a text box, an option written in the help is missed, or an option that does not exist appears. So the first time you use a tool, spend two minutes in the **parameter editor** — fix types and descriptions, add what is missing, remove what is not there. The corrections are remembered, so the form is right from then on.

![Parameter editor](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/07-edit-params.png)

*Name, description and type are editable; the two × at the end of a row mean hide (recoverable) and remove permanently*

What the editor can do:

- **edit name, description and type** by clicking the cell
- **drag the `≡` handle** to reorder
- **add a parameter by hand** with **+ Add parameter**
- **hide** a parameter with the first × (recoverable) or **remove it permanently** with the second
- **reset the tool** back to the freshly scanned table

**Parameter types**:

| Type | Meaning |
|---|---|
| (unset) | no type |
| `CheckBox` | check box |
| `Text` | free text |
| `number` | number |
| `InFilePath` | input file path |
| `OutFilePath` | output file path |
| `FilePathList` | several input files, comma separated |
| `DirectoryPath` | a directory |
| `Choice` | dropdown |

> **Note**: positional parameters are added as `"<name>"`, so the name itself is not written to the command line.

Nothing changes until you click **OK**; **Cancel** discards. Saved edits survive a restart.

---

## 7. Run it: three ways

- **Run**: runs inside the panel, streams the output, then prints the exit code and elapsed time
- **Interactive run**: when the program asks for input, an `Input:` line appears under the output; type, press Enter, repeat until it finishes
- **Run in terminal**: opens a real terminal — **closing the panel does not kill the job**

> For jobs that take hours, use **Run in terminal**: inside the panel the job dies with TBtools. A running job can be stopped with **Stop**.
>
> ANSI escape sequences are stripped, so the output does not fill up with `[0;32m`; on failure a hint reminds you to check required parameters and paths.

---

## 8. AI assistant (beta): it sees what you are running

You have to paste context into other chat boxes; this assistant **carries it for you**: the current tool and environment, the command, the parameter table, the help text, the working directory, the last run's exit code and output tail, and the list of tools installed on this machine.

So you can just ask: “Do `-M` and `-A` have to be used together?” or “Why did that command exit with 1?”

![AI panel](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/09-ai-panel.png)

*Tool tree on the left, conversation on the right; the bottom line shows how much context was sent, how many turns, how fast*

**Sessions**: only the last **24 messages** are kept (export a conversation as Markdown if it matters); every answer has **Retry / Edit / Continue / Copy**; a failed turn stays visible but is kept out of the model history.

**Configuring a model**: **TBmodel** works out of the box (TBtools 2.558 exposes the built-in model interface). To use your own model, go to ⚙ → Models → **+ Add model**:

![Settings → Models](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/10-settings-models.png)

*Settings → Models / Add model*

| Field | Meaning |
|---|---|
| Model name | the model id the endpoint expects, e.g. `deepseek-chat` |
| Display name | blank means the model name |
| Endpoint | e.g. `https://api.deepseek.com/v1` |
| API key | obtain from the provider |
| Max input | tokens, `1024K` or `1M`; only decides locally how much history is dropped |
| Max output | sent verbatim as `max_tokens` |

**About the API key**: it is stored **in plain text on this machine** (`%USERPROFILE%\.TBtools\BioEnvCaller\ai-models.properties`), it never appears in logs or error messages, and because requests are issued from the embedded page it lives in page memory while a request runs — so give that key **its own quota**.

**Usage and limits**: in the footer line `context 12.3k / 1M · 3 turns · 128 tok/s · total 650 tok`, a number with `≈` is estimated, one without is reported by the server. Custom models are subject to browser **CORS** (local Ollama needs `OLLAMA_ORIGINS=*`), and image attachments only work with TBmodel.

---

## 9. Small conveniences

![Tool assistant](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/08-tool-assistant.png)

*The help text verbatim on the left, your own notes on the right*

- **Tool assistant**: the tool's help plus your notes; the notes are for reading only and never affect parsing
- ⚙ also holds **theme** (classic / light / dark) and **language** (Chinese / English) switches:

![Theme / language](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/04-settings-look-lang.png)

*Settings → Theme / Language*

---

## 10. Where the data lives

Everything is kept in `%USERPROFILE%\.TBtools\BioEnvCaller\`:

| File | Contents |
|---|---|
| `ai-models.properties` | custom models, API keys in plain text |
| `connection-mode.txt` | last connection mode |
| `last-target.properties` | last environment and tool |
| `type-corrections.properties` | parameter types you corrected |
| `custom-options.properties` | parameters you added by hand |
| `tool-notes.properties` | notes from the tool assistant |
| `discovery-cache.properties` | scan cache |
| `language.txt` | interface language |

Conversations live in the embedded page's local storage; exported Markdown goes wherever you save it.

Factory reset: close TBtools and delete that folder.

---

## 11. Known limitations

- Linux only (WSL2, Linux servers, workstations)
- one tool at a time; no workflow engine
- not done yet: conversation search, command syntax highlighting; server connections are not covered by the test suite
- macOS is not supported
- the AI keeps the last 24 messages; custom models take no images and are subject to CORS
- the output pane is plain text (ANSI stripped)

---

## Development

There is one entry point:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

It compiles `vmadapt` and the plugin sources, checks every class is Java 11, runs **78 regression tests plus 15 vmadapt tests**, and produces `BioEnvCaller.plugin`. The gate has to be green before a commit. Design notes live in [DESIGN.md](DESIGN.md); contribution rules in [CONTRIBUTING.en.md](CONTRIBUTING.en.md).

> The script looks for **JDK 11** in `TBTOOLS_HOME`, then `JAVA_HOME`, then the usual install locations (`D:\TBtools`, `C:\Program Files\*\jdk-11*`). If TBtools lives somewhere else, run `setx TBTOOLS_HOME "E:\TBtools"` first.

---

> Standalone illustrated manual with the figures embedded for offline reading (Chinese): [help.md](help.md)
