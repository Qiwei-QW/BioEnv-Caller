# 虚拟机适配（VM Adapt）模块说明

本目录是一个**独立的 TBtools 插件模块**，用于把 TBtools 里要跑的 Linux 生物信息学
工具，送到一台**已存在的 Linux 虚拟机**（VMware 里的 Ubuntu/Debian 等）上执行。

它只做一件事：**把 Windows 上的 TBtools 和虚拟机里的 Linux 环境接起来**。

---

## 1. 这个目录是什么

```
虚拟机适配\
├─ build.ps1              编译 + 自检脚本（唯一入口）
├─ src\vmadapt\core\      传输层与命令组装（无 UI 依赖）
├─ src\vmadapt\ui\        Swing 连接面板
├─ src\vmadapt\test\      自带的测试类（纯 main()，不依赖 JUnit）
├─ build\                 编译产物（classes\）
└─ docs\                  本目录，中文文档
```

设计上刻意保持三条边界：

| 边界 | 说明 |
| --- | --- |
| **零第三方依赖** | 底层直接调用 **Windows 自带的 OpenSSH**（`ssh.exe` / `scp.exe`），不引入 JSch、不引入 PuTTY，插件 JAR 体积不增加 |
| **零 UI 依赖** | `core` 包不 import 任何 Swing 类，可以脱离界面单独测试 |
| **零耦合** | 本模块**不修改、不引用** `BioEnvCaller` 目录下的任何文件 |

---

## 2. 与 BioEnvCaller 的关系

`BioEnvCaller` 是另一个插件，它通过 **WSL** 在本机跑 Linux 工具。本模块与它是
**平行、独立**的两套东西：

| 对比项 | BioEnvCaller（既有） | 虚拟机适配（本模块） |
| --- | --- | --- |
| 目标环境 | 本机 WSL 发行版 | VMware（或任何）Linux 虚拟机 |
| 连接方式 | `wsl.exe -d <发行版> -- bash -s` | `ssh.exe -p <端口> <用户>@<主机> bash -s` |
| 输入文件位置 | WSL 可直接读 `/mnt/c/...` | 远端**没有** `/mnt/c`，必须 `scp` 上传 |
| 认证方式 | 无需认证（同机） | **密钥登录**（见下） |
| 代码关系 | — | **无任何代码依赖** |

> **重要**：本模块是独立交付的。撰写本文档时**只读取**了 `BioEnvCaller` 的源码作为
> 参考，**没有修改它的任何文件**。若将来要把本模块接到 BioEnvCaller 上，做法见
> [移植到BioEnvCaller.md](移植到BioEnvCaller.md)。

---

## 3. 环境要求

### 3.1 客户端（Windows 本机）

| 项目 | 要求 | 本机实测结果 |
| --- | --- | --- |
| 操作系统 | Windows 10 1809+ / Windows 11（自带 OpenSSH 客户端） | 满足 |
| OpenSSH 客户端 | `C:\WINDOWS\System32\OpenSSH\ssh.exe` | **已实测**：`OpenSSH_for_Windows_9.5p2, LibreSSL 3.8.2` |
| `scp.exe` | 同目录 | **已实测**：存在 |
| `ssh-keygen.exe` | 同目录 | **已实测**：存在 |
| `sftp.exe` / `ssh-add.exe` | 同目录 | **已实测**：存在 |
| JDK | **必须是 JDK 11** | `D:\TBtools\jre`，`javac 11.0.6` |
| TBtools JAR | 提供 Swing 运行时参考 | `D:\TBtools\TBtools_JRE1.6.jar` |

**不需要**的东西（已确认本机没有，也不影响使用）：

- 不需要 PuTTY / `plink`：本机**未安装**，模块不使用它。
- Windows 10 1809 以下若没有 `ssh.exe`，请通过「设置 → 应用 → 可选功能 →
  添加功能 → OpenSSH 客户端」安装（**未实测**）。

### 3.2 为什么必须用 JDK 11

机器上同时存在 JDK 26，但**构建必须用 JDK 11**：

- JDK 26 编译出的 class 文件主版本号是 68，JDK 11 运行时无法加载。
- TBtools 自带的 JRE 是 **Java 11**，加载高版本 class 会直接抛
  `UnsupportedClassVersionError`。
- 因此 `build.ps1` 会**显式选择 JDK 11**，并在编译后逐个检查 class 文件第 8 个
  字节必须等于 `55`（Java 11 的主版本号），不等于就构建失败。

### 3.3 服务端（Linux 虚拟机）

| 项目 | 要求 |
| --- | --- |
| 操作系统 | 任意带有 `bash` 的 Linux 发行版（Ubuntu / Debian / CentOS / Rocky 等） |
| SSH 服务 | 必须安装并运行 `sshd`，监听 22（或转发后的端口） |
| 认证 | **必须提前配好密钥登录**（见 3.4） |
| 计算环境 | 建议装 Miniconda，工具装在 conda 环境里 |

### 3.4 认证方式：只支持密钥登录

> **本方案不支持密码登录。**

原因必须说清楚，否则用户会卡在这里：

1. 每次连接都带上 `-o BatchMode=yes`。这个选项的含义是**禁止一切交互式提问**。
2. 加它的原因：TBtools 是 GUI 进程，它启动的 `ssh.exe` **没有可用的控制台
   （TTY）**。一旦 ssh 需要提问（要密码、要确认 host key），它不会报错，而是
   **永久挂起**，界面表现为「点了没反应」。
3. 所以宁可让它明确失败，也不让它挂起。而 `BatchMode=yes` 下 ssh 不会去读密码，
   直接以 `Permission denied (publickey)` 结束。
4. 结论：**必须先配好密钥**，让 ssh 不需要问任何问题就能登录。

密钥配置的完整步骤见 [VMware连接指南.md](VMware连接指南.md) 第 6 节。

---

## 4. 如何编译

在 PowerShell 中执行：

```powershell
cd "C:\Users\14231\Desktop\TBtools插件\虚拟机适配"
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

`build.ps1` 依次做四件事：

1. 定位 JDK 11（优先 `D:\TBtools\jre`，其次 Adoptium / Oracle 的 jdk-11）；
2. 清空并重建 `build\classes`，用 `-encoding UTF-8` 编译 `src` 下全部 `.java`；
3. **校验 class 文件版本必须为 55**，否则报错退出；
4. 依次运行 `vmadapt.test` 下的测试类，任何一个非零退出即判定构建失败。

> **构建状态（已实测）**：本文档定稿时在本机实际运行过 `build.ps1`，结果**全部通过**
> （连续 3 次运行均为绿色，无偶发失败）。JDK 11 定位正常（`javac 11.0.6`），
> 18 个源文件编译成功，class 版本校验通过，6 个测试类全部 PASS。

成功时的实际输出：

```
Using JDK: D:\TBtools\jre
javac 11.0.6
Compiling 18 source file(s)...
PASS  all class files are Java 11 (major 55)

=== vmadapt.test.ShellQuoteTest ===
PASS 414 checks

=== vmadapt.test.SshTargetTest ===
PASS 70 checks

=== vmadapt.test.ConnectionStoreTest ===
PASS 67 checks

=== vmadapt.test.SshCommandTest ===
PASS 73 checks

=== vmadapt.test.RemotePathTest ===
PASS 71 checks

=== vmadapt.test.UploadPlanTest ===
PASS 55 checks

=============================================
 All tests passed.
 Classes: C:\Users\14231\Desktop\TBtools插件\虚拟机适配\build\classes
=============================================
```

> 单独编译一个文件时也必须带 JDK 11 与 UTF-8，例如（把 `<文件>` 换成真实路径）：
>
> ```powershell
> & "D:\TBtools\jre\bin\javac.exe" -encoding UTF-8 -d .\build\classes (Get-ChildItem .\src -Recurse -Filter *.java | ForEach-Object FullName)
> ```

---

## 5. 如何跑测试

测试是**普通 Java 类里带 `main()`**，不需要 JUnit。最省事的方式是直接跑 `build.ps1`
（它会依次运行全部测试类），也可以单独跑某一个：

```powershell
cd "C:\Users\14231\Desktop\TBtools插件\虚拟机适配"
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=true'
& "D:\TBtools\jre\bin\java.exe" -cp .\build\classes vmadapt.test.SshCommandTest
Remove-Item Env:\JAVA_TOOL_OPTIONS
```

> 把 `vmadapt.test.SshCommandTest` 换成 `src\vmadapt\test\` 下任意一个测试类的
> 全限定名即可。测试类清单以 `build.ps1` 里那份列表为准。

`-Djava.awt.headless=true` 是必需的：UI 相关测试类会触碰 Swing，在没有显示器的
环境里不加这个参数会抛 `HeadlessException`。`build.ps1` 会自动设置并在结束时清除它。

**测试覆盖的是纯逻辑**，不连真实虚拟机。`build.ps1` 会自动运行的测试类共 **6 个**：

| 测试类 | 覆盖内容 | 本机实测 |
| --- | --- | --- |
| `ShellQuoteTest` | shell 引号与转义、单引号注入防线、ANSI 清理、拆行 | ✅ PASS 414 checks |
| `SshTargetTest` | 连接参数构造 / `validate()` / 私钥路径归一化 / 端口边界 | ✅ PASS 70 checks |
| `ConnectionStoreTest` | 配置保存读取往返（Windows 反斜杠路径、中文）、损坏文件备份 | ✅ PASS 67 checks |
| `SshCommandTest` | `ssh.exe` / `scp.exe` 参数组装、脚本不进 argv、端口大小写 | ✅ PASS 73 checks |
| `RemotePathTest` | 远端路径形态判断与校验、scp 目标串拼装 | ✅ PASS 71 checks |
| `UploadPlanTest` | 目录递归展开、去重、跳过判定、进度行解析 | ✅ PASS 55 checks |

### 5.1 可选的联网测试（不随构建自动运行）

还有一个 **`vmadapt.test.SshLiveTest`**，**不在** `build.ps1` 的自动列表里，
必须**手动设置环境变量**才会真的连虚拟机：

```powershell
cd "C:\Users\14231\Desktop\TBtools插件\虚拟机适配"
$env:VMADAPT_LIVE_HOST  = '127.0.0.1'      # NAT 转发场景填 127.0.0.1
$env:VMADAPT_LIVE_PORT  = '2222'
$env:VMADAPT_LIVE_USER  = 'bio'
$env:VMADAPT_LIVE_KEY   = "$env:USERPROFILE\.ssh\id_ed25519"
& "D:\TBtools\jre\bin\java.exe" -cp .\build\classes vmadapt.test.SshLiveTest
```

不设这三个变量时，它会打印 `SKIP` 并以退出码 0 结束——**绝不让构建或无虚拟机的
环境变红**。连接成功时会输出远端 `uname -a`、`whoami` 与环境探测结果。

> **未实测（重要）**：本机**没有安装 VMware，也没有可连的 Linux 虚拟机**，因此
> 联网路径**全部未实测**，包括：
>
> - 真实 SSH 握手、主机密钥写入 `known_hosts`、密钥认证；
> - `scp` 上传/下载的**真实字节流**；
> - `createWorkDir()` 在远端真实返回的绝对路径、`remoteSize()` 的真实返回值
>   （这两个方法依赖 transport，离线时只能验证"不确定时倾向于上传"的一侧行为）；
> - 远端 `pkill` 实际是否杀掉了进程。
>
> 上面 6 个测试类验证的是**纯逻辑与参数组装**，不代表端到端已经在真机上跑通。

---

## 6. 如何启动界面验证

模块依赖 TBtools 的运行环境（`D:\TBtools\TBtools_JRE1.6.jar`）提供 Swing 与插件
框架。独立启动的入口类是 **`vmadapt.ui.MainFrame`**（它内部承载
`vmadapt.ui.ConnectionPanel`）：

```powershell
cd "C:\Users\14231\Desktop\TBtools插件\虚拟机适配"
& "D:\TBtools\jre\bin\java.exe" -cp ".\build\classes;D:\TBtools\TBtools_JRE1.6.jar" vmadapt.ui.MainFrame
```

启动后应能看到连接面板，包含这些输入项：

| 输入项 | 说明 | 示例 |
| --- | --- | --- |
| 主机 | NAT 模式填 `127.0.0.1`；桥接/仅主机填客户机 IP | `127.0.0.1` |
| 端口 | SSH 端口（NAT 转发时填转发出来的端口） | `2222` |
| 用户名 | 客户机里的 Linux 用户名 | `bio` |
| 私钥 | Windows 上私钥文件的路径 | `C:\Users\14231\.ssh\id_ed25519` |
| 标签 | 给这台机器起的名字，用于区分多台 | `ubuntu-vm` |

点「测试连接」应当返回远端的 `uname -a` 与 `whoami` 结果。

> 加 `-Djava.awt.headless=true` 会让 `MainFrame` 直接抛 `HeadlessException`
> ——启动界面时**不要**加这个参数（它是给自动化测试用的）。

> **未实测**：界面在上述命令下的实际渲染、按钮文案与「测试连接」的真实回显，
> 未在真实虚拟机上跑通（本机无 VMware、无可连虚拟机）；本文档描述的是面板应有的
> 功能范围。

---

## 7. 目录与命名约定

远端所有工作都在用户 HOME 下的一个目录里进行：

```
~/vmadapt_work/<标签>_<yyyyMMdd_HHmmss>/
```

- 放在 HOME 下，避免污染 `/tmp` 或系统目录；
- 带标签便于区分不同任务，带秒级时间戳保证多次运行不重名；
- 使用**绝对路径**记录，因为后续的 `scp`、后台任务、结果回传都是独立进程，
  工作目录（cwd）不会继承。

本机输入文件在 SSH 场景下**必须上传**：远端是独立的 Linux 系统，`/mnt/c` 只有在
WSL 里才存在。上传目标就是上面那个工作目录。

---

## 8. 本机实测状态一览

写文档时在本机实际执行的检查：

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| OpenSSH 版本 | `& "C:\WINDOWS\System32\OpenSSH\ssh.exe" -V` | `OpenSSH_for_Windows_9.5p2, LibreSSL 3.8.2` |
| scp 是否存在 | `Test-Path "C:\WINDOWS\System32\OpenSSH\scp.exe"` | `True` |
| ssh-keygen 是否存在 | `Test-Path "C:\WINDOWS\System32\OpenSSH\ssh-keygen.exe"` | `True` |
| plink 是否安装 | `Get-Command plink` | **未安装**（无输出） |
| VMware 服务 | `Get-Service VM*` | **没有 VMware 服务**，只有 Hyper-V 的 `vmcompute`、`vmic*` |
| JDK 11 | `& "D:\TBtools\jre\bin\javac.exe" -version` | `javac 11.0.6` |
| TBtools JAR | `Test-Path "D:\TBtools\TBtools_JRE1.6.jar"` | `True` |

### 明确标注「未实测」的内容

1. **VMware 的安装与配置**：本机**未安装 VMware Workstation**（`Get-Service VM*`
   只看到 Hyper-V 服务，也没有 VMware 虚拟网卡）。因此 [VMware连接指南.md](VMware连接指南.md)
   中所有 VMware 界面操作、NAT 端口转发步骤均为**按官方通用流程撰写，未在本机实测**。
2. **与真实虚拟机的 SSH 连接**：未连接过任何真实 Linux 虚拟机；真实握手、
   密钥认证、`scp` 上传均未验证。
3. **界面**：连接面板的真实渲染与交互未验证。
4. **Windows 10 1809 以下补装 OpenSSH**：未实测。

---

## 9. 相关文档

| 文档 | 读者 | 内容 |
| --- | --- | --- |
| 本文件（README.md） | 所有人 | 模块定位、环境要求、编译与测试 |
| [VMware连接指南.md](VMware连接指南.md) | 使用者 | 网络模式、端口转发、密钥配置、报错排查 |
| [移植到BioEnvCaller.md](移植到BioEnvCaller.md) | 开发者 | 如何把本模块接入 BioEnvCaller |
