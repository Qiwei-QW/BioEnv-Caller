# vmadapt — the SSH transport module

[中文](README.md) ｜ **English**

The smallest transport layer that connects TBtools on Windows to a **Linux host (virtual
machine / cloud server / workstation)**. It does one thing: **run commands on that machine and
bring the output back**. Tool discovery, parameter parsing and the UI do not live here.

```
vmadapt\src\vmadapt\
├─ core\                   transport (no UI dependency)
│   ├─ SshTarget           one host: alias / host / port / user
│   ├─ SshCommand          builds the ssh command line (quoting, BatchMode, timeout)
│   ├─ SshTransport        runs it, returns stdout / stderr / exit code
│   ├─ SshAskpass          askpass helper for the password path
│   ├─ KeyInstaller        generates a key / appends the public key to authorized_keys
│   ├─ RemoteFiles         lists remote directories, reads and writes remote files
│   ├─ RemoteSetup         first-contact probing on the remote side
│   ├─ Reachability        tells «refused / timed out / cannot resolve» apart
│   └─ ConnectionStore     saved profiles (JSON)
├─ ui\                    Swing connection panel and wizard
└─ test\                  built-in tests (plain main(), no JUnit)
```

## Relationship to the host plugin

`BioEnv Caller` compiles this module into **the same JAR** and wires it through
`bioenv.core.SshTransportAdapter`:

- the plugin side depends on a deliberately narrow interface (run a command, list a directory,
  move a file)
- this module knows nothing about «tools», «parameters» or «command preview»
- so it can also be reused on its own by another plugin

## Building

On its own (from the repository root):

```powershell
# build.ps1 also compiles this module and packages the result into the main JAR
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

The output lands in `vmadapt\build\classes`; the tests are run by `build.ps1` together with the
rest (15 of them, including the logic that skips the live-SSH cases when no server is
configured).

## The three design boundaries

1. **`ssh` only**: no protocol of its own. If you can `ssh` to it from a terminal, it works here.
2. **No PTY**: interactive programs belong to «run in a terminal»; this module only runs
   non-interactive commands (`BatchMode=yes`, every prompt disabled).
3. **A failure must say which kind it is**: refused, timed out, cannot resolve — three kinds,
   three different messages, so the user can fix it from the message alone.
