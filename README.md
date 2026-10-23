# BioEnv Caller

**在 TBtools 中以图形化界面调用 conda 虚拟环境中安装的生信工具**
*Call the bioinformatics tools installed in your conda environments from TBtools, through a GUI*

[![release](https://img.shields.io/github/v/release/Qiwei-QW/BioEnv-Caller)](https://github.com/Qiwei-QW/BioEnv-Caller/releases) [![license](https://img.shields.io/github/license/Qiwei-QW/BioEnv-Caller)](LICENSE) [![stars](https://img.shields.io/github/stars/Qiwei-QW/BioEnv-Caller)](https://github.com/Qiwei-QW/BioEnv-Caller/stargazers)

> **下载 / Download**：[Releases](https://github.com/Qiwei-QW/BioEnv-Caller/releases/latest) ｜ **许可 / License**：[MIT](LICENSE) ｜ **构建 / Build**：[build.ps1](build.ps1) ｜ **设计 / Design**：[DESIGN.md](DESIGN.md) ｜ **版本 / Changelog**：[CHANGELOG.md](CHANGELOG.md) ｜ **贡献 / Contributing**：[CONTRIBUTING.md](CONTRIBUTING.md)

**中文** — 你在 Linux 中使用生信工具的时候，是不是也遇到过如下烦恼：OrthoFinder 有 21 个参数，每个都得从 `[Default = xxx]` 里挑一次；help 全是英文，看完还是不确定 `-M` 和 `-A` 是不是要配套；数据在 Windows、工具在 Linux，跑完还要来回倒腾路径；很长时间不用，又要去翻帮助、甚至去翻工具仓库。这样的烦恼，现在可以在 BioEnv Caller 里一次解决。

*English* — If you run bioinformatics tools on Linux, you have probably met the same wall: OrthoFinder takes 21 parameters and every one of them has to be picked out of a `[Default = xxx]` line; the help text is English and still leaves you unsure whether `-M` and `-A` have to go together; the data lives on Windows while the tools live on Linux; and after a few months away you have to read the help again. BioEnv Caller is built for exactly that.

> **适合谁 / Who it is for**：会用 conda 装生信工具、但不想每次去翻 help 拼命令的人；课题组只有一台服务器/工作站、需要多人轮流用的场景。
> *People who install tools with conda but would rather not re-read the help every time; and groups that share one server or workstation between several members.*

---

## 一、它解决什么问题 · What it solves

- **记不住参数、拼不对命令** → 选一个工具，自动生成参数表单，自动拼出命令；命令框还能直接改。
- **看不懂某个参数** → 「工具助手」里放工具自己的 help 原文，还可手动记录使用心得和踩过的坑。还给你配置了 AI 助手，它自动带着当前工具、命令、参数表、上次运行的报错等信息，简单问一句，便能快速帮你解决问题。
- **Windows 上不好跑 Linux 工具** → 直接连 WSL 或 SSH 远程，在那边跑，输出回传到插件里。
- **充分利用课题组的计算资源** → 利用该插件连接服务器或工作站，多人同时调用：每个人在自己电脑上装插件、各自连同一台机器，互不占用对方的图形界面。

*English*

- **Flags you cannot remember, commands you cannot get right** → pick a tool, get a generated parameter form and a generated command; the command line itself stays editable.
- **Options you do not understand** → the *Tool assistant* shows the tool's own help verbatim and keeps your own notes next to it. An AI assistant is also wired in: it automatically carries the current tool, command, parameter table and the last run's error, so one short question usually settles it.
- **Linux tools that are awkward to run on Windows** → connect to WSL or to a remote host over SSH and run there; the output comes back to the panel.
- **Shared computation resources** → several people can each run their own TBtools against the same server or workstation without fighting over one GUI session.

---

## 二、插件主界面 · The main window

![主界面 / Main window](docs/img/01-overview.png)

*主界面：左边选工具，中间填参数，下面看输出*
*Main window: pick a tool on the left, fill the form in the middle, read the output below*

| 区域 / Area | 干什么 / What it does |
|---|---|
| 最左窄条<br>*icon rail* | **图标栏 Icon rail**：第一个图标点一下展开工具树，再点一下收起；第二个图标是 AI 助手；最底下 ⚙ 打开设置<br>*first icon toggles the tool tree, second toggles the AI panel, bottom ⚙ opens settings* |
| 左侧树状面板<br>*left column* | **工具树 Tool tree**：按 conda 环境分组的命令列表，顶部有检索框<br>*commands grouped by conda environment, with a filter box on top* |
| 右上<br>*top right* | **参数区 Parameter area**：参数表单 + 命令预览 + 运行按钮<br>*parameter form, command preview, run buttons* |
| 右下<br>*bottom right* | **输出 Output**：命令打印的内容，右上角可清空<br>*whatever the command prints; clear button at its top right* |
| 最底<br>*bottom bar* | **状态栏 Status bar**：左边一句提示，右边当前连接（如 `WSL`）<br>*a hint on the left, the current connection on the right (e.g. `WSL`)* |

工具树和 AI 助手**共用同一列**，分隔条可以拖、宽度会记住，双击分隔条复位。
*The tool tree and the AI panel share one column: drag the divider to resize (the width is remembered), double-click it to reset.*

---

## 三、安装插件 · Installing

1. 下载 `BioEnvCaller.plugin`
2. 依次点击：**Others → Plugin → Install Plugin**，然后将下载的插件拖进弹出的窗口中安装就好了（安好的插件放在 `AI+` 目录下）
3. **重启 TBtools**

**要求**：TBtools **2.558 以上**（AI 助手需要）、Windows 10 / 11、本机装好 **WSL2** 或有一台能 SSH 的 Linux 主机，并配置了 conda 环境（在 conda 创建的虚拟环境中安装工具）。

*English*

1. Download `BioEnvCaller.plugin`
2. In TBtools click **Others → Plugin → Install Plugin** and drag the file into the dialog (the plugin ends up under `AI+`)
3. **Restart TBtools**

**Requirements**: TBtools **2.558 or newer** (needed by the AI assistant), Windows 10/11, either **WSL2** on the same machine or a Linux host reachable over SSH, with conda set up and your tools installed inside conda environments.

> 升级时重复同样的步骤（会覆盖旧版本），**装完一定要重启 TBtools** —— 插件 jar 是被加载着的。
> *Upgrading is the same procedure (it overwrites); always restart afterwards, the plugin jar is loaded into the running process.*

---

## 四、两种连接方式 · Two ways to connect

点 ⚙ 打开设置，第一个分页就是「**连接**」。
*Click ⚙ to open settings; the first page is **Connection**.*

![设置 → 连接 / Settings → Connection](docs/img/02-settings-connection.png)

*设置 → 连接：选方式 → 选目标 → 扫描*
*Settings → Connection: pick a mode, pick a target, scan*

### 4.1 WSL（本机，最简单）· WSL (local, simplest)

**中文** — 前提：Windows 里已经装好 WSL2，在 WSL2 中配置了 conda 环境，并在 conda 创建的虚拟环境里安装了生信工具。

1. **连接方式** 选 `WSL`
2. **WSL 发行版** 选一个（只装了一个时是灰的）
3. 点 **扫描 conda 环境**

扫完状态栏会显示 `就绪。N 个命令可用，来自 Ubuntu。`，左边树里就会展示这台机器 conda 环境里装的全部工具。

*English* — Prerequisite: WSL2 is installed on Windows, conda is configured inside it, and your tools are installed in conda environments.

1. Set **Connection** to `WSL`
2. Pick a **WSL distro** (greyed out when only one is installed)
3. Click **Scan conda environments**

When the scan finishes the status bar shows `Ready. N commands available, from Ubuntu.` and the tree lists every tool installed in this machine's conda environments.

> 扫不到东西时，先在 WSL 终端里 `conda env list` 确认环境在、`which 工具名` 能找到工具，再回来重新扫描。
> *If the scan comes back empty, check in a WSL terminal that `conda env list` shows the environment and `which <tool>` finds the binary, then scan again.*

### 4.2 SSH 远程（虚拟机 / 服务器 / 工作站）· SSH (VM / server / workstation)

**中文** — 前提：在你的 Linux 虚拟机 / 服务器 / 工作站中装好 conda 环境，并在 conda 创建的虚拟环境里安装了生信工具。

**连接方式** 选 `SSH 远程` → 第一次会自动建一条配置，也可以点「＋ 新增配置」走向导：

*English* — Prerequisite: conda is set up on that Linux VM / server / workstation, with your tools installed inside conda environments.

Set **Connection** to `SSH`, then either pick the profile created automatically the first time or click **+ New profile** to walk through the wizard:

![SSH 连接向导 / SSH wizard](docs/img/03-ssh-wizard.png)

*六步：主机 → 端口 → 用户名 → 密钥说明 → 密码 → 配置名*
*Six steps: host → port → user → key note → password → profile name*

| 步 / Step | 填什么 / What to enter |
|---|---|
| ① | 主机名或 IP *host name or IP* |
| ② | 端口（默认 22）*port, default 22* |
| ③ | 用户名（Linux 账号名称，即你打开终端时 "XXX@ubuntu" 的这个 "XXX" 用户名）*Linux user name — the `XXX` in the `XXX@ubuntu` prompt* |
| ④ | 只读提示：会在本机生成 SSH 密钥对 *read-only note: an SSH key pair is generated locally* |
| ⑤ | 输入密码（登录 Linux 系统的密码） → **开始连接** *password → Connect* |
| ⑥ | 给这套配置起个名字，以后在 ⚙ 页的下拉框里选你连接好的配置 *name the profile; it is selected from the dropdown in ⚙ later* |

**连不上？分析情况**：拒绝连接（端口不对或服务端没开 ssh）、超时（网络/防火墙没放行）、主机解析不了（主机名写错）。

*Cannot connect?* The dialog tells you which of the three it is: refused (wrong port or no sshd), timed out (network or firewall), host not resolved (typo in the host name).

**检查**：目标机器是否开着 sshd？在目标机终端里执行：
*Check whether the target runs sshd, in a terminal on the target:*

```bash
sudo systemctl status ssh          # 不显示 "running" 就往下走 / if not "running", continue
sudo systemctl enable --now ssh    # 启用 SSH / enable it
sudo apt install openssh-server    # 没装的话先装，装完再启动 / install if missing
```

#### 4.2.1 连的是虚拟机（VMware / VirtualBox）· Connecting to a VM

**中文** — 虚拟机对插件来说就是一台 SSH 主机，但**网络模式**决定了宿主机能不能连、该填哪个 IP：

- **桥接**：虚拟机像一台独立机器，宿主机能直连 → 填虚拟机在**局域网里的 IP**
- **NAT（VMware）**：宿主机能直连 → 填 **NAT 网段的 IP**
- **NAT（VirtualBox）**：宿主机默认连不进去 → 给 22 端口做**端口转发**，插件里主机填 `127.0.0.1`、端口填转发端口
- **仅主机**：宿主机能直连，但虚拟机**没有外网** → 填 **host-only 网段的 IP**

*English* — To the plugin a VM is just another SSH host, but the **network mode** decides whether the host can reach it and which address to enter:

- **Bridged**: the VM looks like a separate machine, reachable directly → enter its **LAN address**
- **NAT (VMware)**: reachable directly → enter the address on the **NAT subnet**
- **NAT (VirtualBox)**: not reachable by default → add a **port forward** for port 22 and enter `127.0.0.1` plus the forwarded port
- **Host-only**: reachable, but the VM has **no internet** → enter the address on the **host-only subnet**

不知道是哪种？在虚拟机里看有没有默认网关：
*Not sure which one? Check for a default gateway inside the VM:*

```bash
ip route | grep default     # 有输出=桥接或NAT；没输出=仅主机 / output: bridged or NAT; empty: host-only
ip -4 addr show | grep inet # 取非 127.0.0.1 的那行，/24 不要填 / take the non-loopback line, drop the /24
whoami                      # 用户名就用这个 / this is the user name
```

#### 4.2.2 连的是服务器 / 工作站 · Connecting to a server / workstation

**中文** — 它们**没有网络模式可选**，插件这边看的是同一件事：**能不能 ssh 直连**。

- **云服务器**：公网 IP + 安全组放行 22
- **局域网工作站**：内网 IP（建议静态）+ 防火墙放行

先在 Windows 终端里 `ssh 用户名@主机 -p 端口` 能跑通，插件就能连。

**当前版本不支持**：必须经跳板机多跳（可先在 Windows 上 `ssh -N -L 2222:目标:22 用户名@跳板机` 开隧道，插件填 `127.0.0.1:2222`）；登录要短信 / OTP 二次验证。

*English* — There is no network mode to choose; the only question is whether **ssh can reach it**.

- **Cloud server**: public IP plus a security-group rule allowing port 22
- **Workstation on a LAN**: an internal address (preferably static) plus a firewall rule

If `ssh user@host -p port` works in a Windows terminal, the plugin can connect.

**Not supported yet**: multi-hop through a jump host (workaround: `ssh -N -L 2222:target:22 user@bastion` on Windows, then point the plugin at `127.0.0.1:2222`), and logins that require an SMS / OTP second factor.

> 关于 SSH 连接模式这里可能描述得不太详细，相信用过服务器、虚拟机的读者都十分了解了 —— 大不了问 AI。
> *This section keeps the SSH part short; if you have used servers or VMs before you will not need more, and the AI assistant will answer the rest.*

---

## 五、选工具，填参数 · Pick a tool, fill in the parameters

- 工具树按 **conda 环境**分组，顶部**检索框**按名字过滤，**双击环境名**展开/收起
- 点一个工具，右边立刻出现**带类型的表单**：文本、数字、开关、下拉枚举、文件、目录
- 初始类型是插件猜的，在**编辑面板**中一切都可以改
- 命令**实时拼接**，框里的文字可以直接改；改乱了点「重建命令行」
- 解析不出来的参数，写进「**附加参数（每行一个）**」，更推荐在「**编辑参数**」面板中手动添加
- 路径参数点「…」可以浏览 **Linux 目录树**；SSH 模式下选 Windows 目录中的文件会**自动复制进 Linux 工作目录**

*English*

- The tree groups commands by **conda environment**; the **filter box** on top matches names; **double-click an environment** to expand or collapse it
- Pick a tool and a **typed form** appears: text, number, switch, dropdown, file, directory
- The initial types are guessed — anything can be corrected in the **parameter editor**
- The command is **built live** and the text itself is editable; if it gets messy, click **Rebuild command**
- Parameters the parser could not work out go into **Extra arguments (one per line)**; adding them in the **parameter editor** is usually better
- Path parameters have a `…` button that browses the **Linux directory tree**; in SSH mode a file picked from Windows is **copied into the Linux working directory** automatically

参数多的工具可以点「**在新窗口中打开**」，表单会变成独立窗口：
*Tools with many parameters can be opened in their own window:*

![独立参数窗口 / Standalone parameter window](docs/img/05-param-window.png)

*独立窗口：改完直接「运行此命令」*
*A standalone window: edit, then click Run this command*

**选文件时按参数类型区分两个来源**：
*Two file pickers, chosen by the parameter type:*

![Linux 目录 / Windows 文件](docs/img/06-file-dialogs.png)

*选 Linux 目录（输入）/ 选 Windows 文件（输出）*
*Pick a Linux directory (input) / pick a Windows file (output)*

---

## 六、第一次用某个工具，再校准参数再用——很重要！！！ · Calibrate before the first run

**中文** — 插件虽然能够自动解析参数，但是不太可能猜得都对。有的时候，该是下拉的给成了文本框、help 里写了的选项漏掉、甚至多解析出根本不存在的东西。

所以第一次用一个工具，先花两分钟进「**参数编辑面板**」校准 —— 改类型、改描述、补参数、删掉多余的行。校准结果会记住，以后每次打开这个工具的表单都是对的。

*English* — Automatic parsing is a good guess, not a guarantee: a dropdown becomes a text box, an option written in the help is missed, or an option that does not exist appears. So the first time you use a tool, spend two minutes in the **parameter editor** — fix types and descriptions, add what is missing, remove what is not there. The corrections are remembered, so the form is right from then on.

![编辑参数 / Parameter editor](docs/img/07-edit-params.png)

*参数名、描述、类型都能改；行尾两个 × 是「可恢复」和「永久移除」*
*Name, description and type are editable; the two × at the end of a row mean hide (recoverable) and remove permanently*

编辑面板里能做这些事：
*What the editor can do:*

- **改名称 / 改描述 / 改类型**：点对应格子直接改 *edit name, description and type by clicking the cell*
- **调顺序**：拖动行最左边的 `≡` 手柄 *drag the `≡` handle to reorder*
- **手动添加参数**：点「＋ 手动添加参数」，填参数名和说明 *add a parameter by hand*
- **移除参数**：行尾第一个 × 是「可恢复」（只是从表单藏起来），第二个 × 是「永久移除」 *first × hides (recoverable), second removes permanently*
- **重置此工具**：回到刚扫描出来的样子 *reset the tool back to the freshly scanned table*

**参数类型描述 / Parameter types**：

| 类型 / Type | 含义 / Meaning |
|---|---|
| （不设置类型）*unset* | 不设置 *no type* |
| `CheckBox` | 复选框 *check box* |
| `Text` | 文本 *free text* |
| `number` | 数值 *number* |
| `InFilePath` | 输入文件路径 *input file path* |
| `OutFilePath` | 输出文件路径 *output file path* |
| `FilePathList` | 选择多个文件作为输入，以 `,` 分隔 *several input files, comma separated* |
| `DirectoryPath` | 选择一个文件夹输入 *a directory* |
| `Choice` | 下拉框进行选择 *dropdown* |

> **注**：对于位置参数，以 `"<参数名>"` 的形式添加，这样参数名不会被加到命令行中。
> *Note: positional parameters are added as `"<name>"`, so the name itself is not written to the command line.*

改完点「**确定**」才生效，点「取消」不保存；保存过的修改**会记住**（重启 TBtools 仍在）。
*Nothing changes until you click **OK**; **Cancel** discards. Saved edits survive a restart.*

---

## 七、跑起来：三种方式 · Run it: three ways

- **运行**：插件里跑，输出实时刷出来，结束显示退出码和用时
- **交互运行**：程序中途要你输入，输出区底部会冒出「输入：」。输入后，回车进行下一步，直到交互结束
- **在终端中运行**：开一个真终端，**关掉插件任务照跑**

*English*

- **Run**: runs inside the panel, streams the output, then prints the exit code and elapsed time
- **Interactive run**: when the program asks for input, an `Input:` line appears under the output; type, press Enter, repeat until it finishes
- **Run in terminal**: opens a real terminal — **closing the panel does not kill the job**

> 跑长任务（几小时那种）用「在终端中运行」更稳；插件里跑的话，TBtools 一关连接就断了。运行中的任务可以点「停止」。
> *For jobs that take hours, use **Run in terminal**: inside the panel the job dies with TBtools. A running job can be stopped with **Stop**.*
>
> 输出会把 ANSI 控制序列剥掉，不会满屏 `[0;32m`；失败时给一句提示：检查必需参数是否填了、路径是否存在。
> *ANSI escape sequences are stripped, so the output does not fill up with `[0;32m`; on failure a hint reminds you to check required parameters and paths.*

---

## 八、AI 助手（测试）：它看得见你正在跑什么 · AI assistant (beta): it sees what you are running

**中文** — AI 已经成为我们日常工作和学习中必不可少的得力助手，所以给插件也安排了一个。

别的 AI 聊天框，你得先把上下文贴进去；这里的助手**每次提问自动带上**：当前工具名与环境、当前命令、参数表、help 原文、工作目录、上次运行的退出码和输出尾部、这台机器装了哪些工具。

所以可以直接问：「`-M` 和 `-A` 必须一起用吗？」「上次这条命令为什么退出码 1？」

*English* — You have to paste context into other chat boxes; this assistant **carries it for you**: the current tool and environment, the command, the parameter table, the help text, the working directory, the last run's exit code and output tail, and the list of tools installed on this machine.

So you can just ask: “Do `-M` and `-A` have to be used together?” or “Why did that command exit with 1?”

![AI 助手面板 / AI panel](docs/img/09-ai-panel.png)

*左边工具树，右边对话；底部显示带了多少上下文、几轮、多快*
*Tool tree on the left, conversation on the right; the bottom line shows how much context was sent, how many turns, how fast*

**会话相关**：只保留最近 **24 条**消息（重要对话请导出 Markdown）；每一轮下面有「重试 / 编辑 / 继续 / 复制」；失败的那轮会留在对话里但不进模型历史。

*Sessions*: only the last **24 messages** are kept (export a conversation as Markdown if it matters); every answer has **Retry / Edit / Continue / Copy**; a failed turn stays visible but is kept out of the model history.

**模型怎么配**：默认用 **TBmodel**（陈老师已在 TBtools v2.558 版本中开放了内置模型接口）；要用自己的模型，去 ⚙ → 模型 → ＋ 添加模型，添加即可：

*Configuring a model*: **TBmodel** works out of the box (TBtools 2.558 exposes the built-in model interface). To use your own model, go to ⚙ → Models → **+ Add model**:

![设置 → 模型 / Settings → Models](docs/img/10-settings-models.png)

*设置 → 模型 / 添加模型*
*Settings → Models / Add model*

| 字段 / Field | 说明 / Meaning |
|---|---|
| 模型名称 / Model name | 接口要的 model id，如 `deepseek-chat` *the model id the endpoint expects* |
| 显示名称 / Display name | 留空默认用模型名称 *blank means the model name* |
| 接口地址 / Endpoint | 如 `https://api.deepseek.com/v1` |
| API key | 去相应的平台注册申请 *obtain from the provider* |
| 最大输入长度 / Max input | 单位 token，可写 `1024K`、`1M`；只在本地决定丢多少历史，不发给服务端 *tokens; only decides locally how much history is dropped* |
| 最大输出长度 / Max output | 原样作为 `max_tokens` 发给服务端 *sent verbatim as `max_tokens`* |

**关于 API key 的三句话**：它是**明文保存在本机**的（`%USERPROFILE%\.TBtools\BioEnvCaller\ai-models.properties`）；**不会**进日志和错误提示；但请求由网页发出，请求期间 key 在网页内存里 —— 建议给这个 key **单独设额度**。

*About the API key*: it is stored **in plain text on this machine** (`%USERPROFILE%\.TBtools\BioEnvCaller\ai-models.properties`), it never appears in logs or error messages, and because requests are issued from the embedded page it lives in page memory while a request runs — so give that key **its own quota**.

**用量与限制**：底部那行 `上下文 12.3k / 1M · 3 轮 · 128 tok/s · 累计 650 tok` 里，带 `≈` 的是估算值、不带的是服务端真实值；自定义模型受浏览器 **CORS** 限制（本地 Ollama 需设 `OLLAMA_ORIGINS=*`）；图片附件只支持 TBmodel。

*Usage and limits*: in the footer line `context 12.3k / 1M · 3 turns · 128 tok/s · total 650 tok`, a number with `≈` is estimated, one without is reported by the server. Custom models are subject to browser **CORS** (local Ollama needs `OLLAMA_ORIGINS=*`), and image attachments only work with TBmodel.

---

## 九、几个顺手的小地方 · Small conveniences

![工具助手 / Tool assistant](docs/img/08-tool-assistant.png)

*工具助手：左边是 help 原文（一字不改），右边写你自己的备注*
*Tool assistant: the help text verbatim on the left, your own notes on the right*

- **工具助手**：help 原文 + 你的自定义备注（自定义备注只供查看，不参与解析）
  *the tool's help plus your notes; the notes are for reading only and never affect parsing*
- ⚙ 页里开放了**主题切换**（经典 / 浅色 / 深色）和**中英文切换**功能，按需设置即可：
  *⚙ also holds **theme** (classic / light / dark) and **language** (Chinese / English) switches:*

![界面风格 / 语言 · Theme / language](docs/img/04-settings-look-lang.png)

*设置 → 界面风格 / 语言*
*Settings → Theme / Language*

---

## 十、数据存在哪 · Where the data lives

配置都在 `%USERPROFILE%\.TBtools\BioEnvCaller\`：
*Everything is kept in `%USERPROFILE%\.TBtools\BioEnvCaller\`:*

| 文件 / File | 内容 / Contents |
|---|---|
| `ai-models.properties` | 自定义模型（含 API key，明文）*custom models, API keys in plain text* |
| `connection-mode.txt` | 上次用的连接方式 *last connection mode* |
| `last-target.properties` | 上次选的环境 / 工具 *last environment and tool* |
| `type-corrections.properties` | 你改过的参数类型 *parameter types you corrected* |
| `custom-options.properties` | 你手动加的参数 *parameters you added by hand* |
| `tool-notes.properties` | 工具助手里的备注 *notes from the tool assistant* |
| `discovery-cache.properties` | 扫描结果缓存 *scan cache* |
| `language.txt` | 界面语言 *interface language* |

**AI 对话记录**存在内置网页的本地存储里；**导出的 Markdown** 存在你挑的位置。
*Conversations live in the embedded page's local storage; exported Markdown goes wherever you save it.*

想恢复出厂：关掉 TBtools，删掉这个目录即可。
*Factory reset: close TBtools and delete that folder.*

---

## 十一、已知限制 · Known limitations

- 只面向 **Linux** 系统（WSL2 / Linux 服务器、主机等）
  *Linux only (WSL2, Linux servers, workstations)*
- **一次一个工具**，不做流程编排
  *one tool at a time; no workflow engine*
- **还没做**：对话搜索、命令语法高亮；未进行服务器连接测试
  *not done yet: conversation search, command syntax highlighting; server connections are not covered by the test suite*
- **未适配 macOS**
  *macOS is not supported*
- AI 只带最近 24 条消息；自定义模型不支持图片附件，且受 CORS 限制
  *the AI keeps the last 24 messages; custom models take no images and are subject to CORS*
- 输出为纯文本（ANSI 序列被剥掉）
  *the output pane is plain text (ANSI stripped)*

---

## 开发 · Development

**中文** — 构建与测试只用一个入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

它依次编译 `vmadapt` 与插件源码、校验所有 class 是 Java 11、跑 **78 条回归测试 + 15 条 vmadapt 测试**，最后打出 `BioEnvCaller.plugin`。门禁必须全绿才能提交。设计决策见 [DESIGN.md](DESIGN.md)，贡献约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

*English* — There is one entry point:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

It compiles `vmadapt` and the plugin sources, checks every class is Java 11, runs **78 regression tests plus 15 vmadapt tests**, and produces `BioEnvCaller.plugin`. The gate has to be green before a commit. Design notes live in [DESIGN.md](DESIGN.md); contribution rules in [CONTRIBUTING.md](CONTRIBUTING.md).

---

> 单独的图文版使用帮助（图片内嵌、可离线阅读）：[help.md](help.md)
> *Standalone illustrated manual with the figures embedded for offline reading: [help.md](help.md)*
