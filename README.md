# BioEnv Caller：在 TBtools 中以图形化界面调用 conda 虚拟环境中安装的生信工具

**中文** ｜ [English](README.en.md)

[![release](https://img.shields.io/github/v/release/Qiwei-QW/BioEnv-Caller)](https://github.com/Qiwei-QW/BioEnv-Caller/releases) [![license](https://img.shields.io/github/license/Qiwei-QW/BioEnv-Caller)](LICENSE) [![stars](https://img.shields.io/github/stars/Qiwei-QW/BioEnv-Caller)](https://github.com/Qiwei-QW/BioEnv-Caller/stargazers)

> **下载**：[Releases](https://github.com/Qiwei-QW/BioEnv-Caller/releases/latest) ｜ **许可**：[MIT](LICENSE) ｜ **开发**：[构建入口 build.ps1](build.ps1) · [设计记录 DESIGN.md](DESIGN.md) · [版本 CHANGELOG.md](CHANGELOG.md)

你在 Linux 中使用生信工具的时候，是不是也遇到过如下烦恼，例如：

OrthoFinder 有 21 个参数，每个都得从 `[Default = xxx]` 里挑一次；help 全是英文，看完还是不确定 `-M` 和 `-A` 是不是要配套；数据在 Windows、工具在 Linux，跑完还要来回倒腾路径；很长时间不用，又要去翻帮助、甚至去翻工具仓库。

这样的烦恼，现在可以在 BioEnv Caller 里一次解决。

> **适合谁**：会用 conda 装生信工具、但不想每次去翻 help 拼命令的人；以及课题组里只有一台服务器/工作站、需要多人轮流用的场景。

---

## 一、它解决什么问题

- **记不住参数、拼不对命令** → 选一个工具，自动生成参数表单，自动拼出命令；命令框还能直接改。
- **看不懂某个参数** → 「工具助手」里放工具自己的 help 原文，还可手动记录使用心得和踩过的坑。还给你配置了 AI 助手，它自动带着当前工具、命令、参数表、上次运行的报错等信息，简单问一句，便能快速帮你解决问题。
- **Windows 上不好跑 Linux 工具** → 直接连 WSL 或 SSH 远程，在那边跑，输出回传到插件里。
- **充分利用课题组的计算资源** → 利用该插件连接服务器或工作站，多人同时调用：每个人在自己电脑上装插件、各自连同一台机器，互不占用对方的图形界面。

---

## 二、插件主界面

![主界面](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/01-overview.png)

*主界面：左边选工具，中间填参数，下面看输出*

| 区域 | 干什么 |
|---|---|
| 最左窄条 | **图标栏**：第一个图标点一下展开工具树，再点一下收起；第二个图标是 AI 助手；最底下 ⚙ 打开设置 |
| 左侧树状面板 | **工具树**：按 conda 环境分组的命令列表，顶部有检索框 |
| 右上 | **参数区**：参数表单 + 命令预览 + 运行按钮 |
| 右下 | **输出**：命令打印的内容，右上角可清空 |
| 最底 | **状态栏**：左边一句提示，右边当前连接（如 `WSL`） |

工具树和 AI 助手**共用同一列**，分隔条可以拖、宽度会记住，双击分隔条复位。

---

## 三、安装插件

1. 下载 `BioEnvCaller.plugin`
2. 依次点击：**Others → Plugin → Install Plugin**，然后将下载的插件拖进弹出的窗口中安装就好了（安好的插件放在 `AI+` 目录下）
3. **重启 TBtools**

**要求**：TBtools **2.558 以上**（AI 助手需要）、Windows 10 / 11、本机装好 **WSL2** 或有一台能 SSH 的 Linux 主机，并配置了 conda 环境（在 conda 创建的虚拟环境中安装工具）。

> 升级时重复同样的步骤（会覆盖旧版本），**装完一定要重启 TBtools** —— 插件 jar 是被加载着的。

---

## 四、两种连接方式

点 ⚙ 打开设置，第一个分页就是「**连接**」。

![设置 → 连接](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/02-settings-connection.png)

*设置 → 连接：选方式 → 选目标 → 扫描*

### 4.1 WSL（本机，最简单）

**前提**：Windows 里已经装好 WSL2，在 WSL2 中配置了 conda 环境，并在 conda 创建的虚拟环境里安装了生信工具。

1. **连接方式** 选 `WSL`
2. **WSL 发行版** 选一个（只装了一个时是灰的）
3. 点 **扫描 conda 环境**

扫完状态栏会显示 `就绪。N 个命令可用，来自 Ubuntu。`，左边树里就会展示这台机器 conda 环境里装的全部工具。

> 扫不到东西时，先在 WSL 终端里 `conda env list` 确认环境在、`which 工具名` 能找到工具，再回来重新扫描。

### 4.2 SSH 远程（虚拟机 / 服务器 / 工作站）

**前提**：在你的 Linux 虚拟机 / 服务器 / 工作站中装好 conda 环境，并在 conda 创建的虚拟环境里安装了生信工具。

**连接方式** 选 `SSH 远程` → 第一次会自动建一条配置，也可以点「＋ 新增配置」走向导：

![SSH 连接向导](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/03-ssh-wizard.png)

*六步：主机 → 端口 → 用户名 → 密钥说明 → 密码 → 配置名*

| 步 | 填什么 |
|---|---|
| ① | 主机名或 IP |
| ② | 端口（默认 22） |
| ③ | 用户名（Linux 账号名称，即你打开终端时 "XXX@ubuntu" 的这个 "XXX" 用户名） |
| ④ | 只读提示：会在本机生成 SSH 密钥对 |
| ⑤ | 输入密码（登录 Linux 系统的密码） → **开始连接** |
| ⑥ | 给这套配置起个名字，以后在 ⚙ 页的下拉框里选你连接好的配置 |

**连不上？分析情况**：拒绝连接（端口不对或服务端没开 ssh）、超时（网络/防火墙没放行）、主机解析不了（主机名写错）。

**检查**：目标机器是否开着 sshd？在目标机终端里执行：

```bash
sudo systemctl status ssh          # 不显示 "running" 就往下走
sudo systemctl enable --now ssh    # 启用 SSH
sudo apt install openssh-server    # 没装的话先装，装完再启动
```

#### 4.2.1 连的是虚拟机（VMware / VirtualBox）

虚拟机对插件来说就是一台 SSH 主机，但**网络模式**决定了宿主机能不能连、该填哪个 IP：

- **桥接**：虚拟机像一台独立机器，宿主机能直连 → 填虚拟机在**局域网里的 IP**
- **NAT（VMware）**：宿主机能直连 → 填 **NAT 网段的 IP**
- **NAT（VirtualBox）**：宿主机默认连不进去 → 给 22 端口做**端口转发**，插件里主机填 `127.0.0.1`、端口填转发端口
- **仅主机**：宿主机能直连，但虚拟机**没有外网** → 填 **host-only 网段的 IP**

不知道是哪种？在虚拟机里看有没有默认网关：

```bash
ip route | grep default     # 有输出=桥接或NAT；没输出=仅主机
ip -4 addr show | grep inet # 取非 127.0.0.1 的那行，/24 不要填
whoami                      # 用户名就用这个
```

#### 4.2.2 连的是服务器 / 工作站

它们**没有网络模式可选**，插件这边看的是同一件事：**能不能 ssh 直连**。

- **云服务器**：公网 IP + 安全组放行 22
- **局域网工作站**：内网 IP（建议静态）+ 防火墙放行

先在 Windows 终端里 `ssh 用户名@主机 -p 端口` 能跑通，插件就能连。

**当前版本不支持**：必须经跳板机多跳（可先在 Windows 上 `ssh -N -L 2222:目标:22 用户名@跳板机` 开隧道，插件填 `127.0.0.1:2222`）；登录要短信 / OTP 二次验证。

> 关于 SSH 连接模式这里可能描述得不太详细，相信用过服务器、虚拟机的读者都十分了解了 —— 大不了问 AI。

---

## 五、选工具，填参数

- 工具树按 **conda 环境**分组，顶部**检索框**按名字过滤，**双击环境名**展开/收起
- 点一个工具，右边立刻出现**带类型的表单**：文本、数字、开关、下拉枚举、文件、目录
- 初始类型是插件猜的，在**编辑面板**中一切都可以改
- 命令**实时拼接**，框里的文字可以直接改；改乱了点「重建命令行」
- 解析不出来的参数，写进「**附加参数（每行一个）**」，更推荐在「**编辑参数**」面板中手动添加
- 路径参数点「…」可以浏览 **Linux 目录树**；SSH 模式下选 Windows 目录中的文件会**自动复制进 Linux 工作目录**

参数多的工具可以点「**在新窗口中打开**」，表单会变成独立窗口：

![独立参数窗口](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/05-param-window.png)

*独立窗口：改完直接「运行此命令」*

**选文件时按参数类型区分两个来源**：

![Linux 目录 / Windows 文件](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/06-file-dialogs.png)

*选 Linux 目录（输入）/ 选 Windows 文件（输出）*

---

## 六、第一次用某个工具，再校准参数再用——很重要！！！

插件虽然能够自动解析参数，但是不太可能猜得都对。有的时候，该是下拉的给成了文本框、help 里写了的选项漏掉、甚至多解析出根本不存在的东西。

所以第一次用一个工具，先花两分钟进「**参数编辑面板**」校准 —— 改类型、改描述、补参数、删掉多余的行。校准结果会记住，以后每次打开这个工具的表单都是对的。

![编辑参数](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/07-edit-params.png)

*参数名、描述、类型都能改；行尾两个 × 是「可恢复」和「永久移除」*

编辑面板里能做这些事：

- **改名称 / 改描述 / 改类型**：点对应格子直接改
- **调顺序**：拖动行最左边的 `≡` 手柄
- **手动添加参数**：点「＋ 手动添加参数」，填参数名和说明
- **移除参数**：行尾第一个 × 是「可恢复」（只是从表单藏起来），第二个 × 是「永久移除」
- **重置此工具**：回到刚扫描出来的样子

**参数类型描述**：

| 类型 | 含义 |
|---|---|
| （不设置类型） | 不设置 |
| `CheckBox` | 复选框 |
| `Text` | 文本 |
| `number` | 数值 |
| `InFilePath` | 输入文件路径 |
| `OutFilePath` | 输出文件路径 |
| `FilePathList` | 选择多个文件作为输入，以 `,` 分隔 |
| `DirectoryPath` | 选择一个文件夹输入 |
| `Choice` | 下拉框进行选择 |

> **注**：对于位置参数，以 `"<参数名>"` 的形式添加，这样参数名不会被加到命令行中。

改完点「**确定**」才生效，点「取消」不保存；保存过的修改**会记住**（重启 TBtools 仍在）。

---

## 七、跑起来：三种方式

- **运行**：插件里跑，输出实时刷出来，结束显示退出码和用时
- **交互运行**：程序中途要你输入，输出区底部会冒出「输入：」。输入后，回车进行下一步，直到交互结束
- **在终端中运行**：开一个真终端，**关掉插件任务照跑**

> 跑长任务（几小时那种）用「在终端中运行」更稳；插件里跑的话，TBtools 一关连接就断了。运行中的任务可以点「停止」。
>
> 输出会把 ANSI 控制序列剥掉，不会满屏 `[0;32m`；失败时给一句提示：检查必需参数是否填了、路径是否存在。

---

## 八、AI 助手（测试）：它看得见你正在跑什么

AI 已经成为我们日常工作和学习中必不可少的得力助手，所以给插件也安排了一个。

别的 AI 聊天框，你得先把上下文贴进去；这里的助手**每次提问自动带上**：当前工具名与环境、当前命令、参数表、help 原文、工作目录、上次运行的退出码和输出尾部、这台机器装了哪些工具。

所以可以直接问：「`-M` 和 `-A` 必须一起用吗？」「上次这条命令为什么退出码 1？」

![AI 助手面板](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/09-ai-panel.png)

*左边工具树，右边对话；底部显示带了多少上下文、几轮、多快*

**会话相关**：面板与导出的 Markdown 保留全部对话；内置 TBmodel 的桥每次最多 20 条消息，超出的最早几轮只在**这一次请求**里省略（面板与导出都还在），并会提示省略了几轮。每一轮下面有「重试 / 编辑 / 继续 / 复制」；失败或被停止的那轮会留在对话里但不进模型历史。

**模型怎么配**：默认用 **TBmodel**（陈老师已在 TBtools v2.558 版本中开放了内置模型接口）；要用自己的模型，去 ⚙ → 模型 → ＋ 添加模型，添加即可：

![设置 → 模型](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/10-settings-models.png)

*设置 → 模型 / 添加模型*

| 字段 | 说明 |
|---|---|
| 模型名称 | 接口要的 model id，如 `deepseek-chat` |
| 显示名称 | 留空默认用模型名称 |
| 接口地址 | 如 `https://api.deepseek.com/v1` |
| API key | 去相应的平台注册申请 |
| 最大输入长度 | 单位 token，可写 `1024K`、`1M`；只在本地决定丢多少历史，不发给服务端 |
| 最大输出长度 | 原样作为 `max_tokens` 发给服务端 |

**关于 API key 的三句话**：它是**明文保存在本机**的（`%USERPROFILE%\.TBtools\BioEnvCaller\ai-models.properties`）；**不会**进日志和错误提示；但请求由网页发出，请求期间 key 在网页内存里 —— 建议给这个 key **单独设额度**。

**用量与限制**：底部那行 `上下文 12.3k / 1M · 3 轮 · 128 tok/s · 累计 650 tok` 里，带 `≈` 的是估算值、不带的是服务端真实值；自定义模型受浏览器 **CORS** 限制（本地 Ollama 需设 `OLLAMA_ORIGINS=*`）；图片附件只支持 TBmodel。

---

## 九、几个顺手的小地方

![工具助手](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/08-tool-assistant.png)

*工具助手：左边是 help 原文（一字不改），右边写你自己的备注*

- **工具助手**：help 原文 + 你的自定义备注（自定义备注只供查看，不参与解析）
- ⚙ 页里开放了**主题切换**（经典 / 浅色 / 深色）和**中英文切换**功能，按需设置即可：

![界面风格 / 语言](https://cdn.jsdelivr.net/gh/Qiwei-QW/BioEnv-Caller@main/docs/img/04-settings-look-lang.png)

*设置 → 界面风格 / 语言*

---

## 十、数据存在哪

配置都在 `%USERPROFILE%\.TBtools\BioEnvCaller\`：

| 文件 | 内容 |
|---|---|
| `ai-models.properties` | 自定义模型（含 API key，明文） |
| `connection-mode.txt` | 上次用的连接方式 |
| `last-target.properties` | 上次选的环境 / 工具 |
| `type-corrections.properties` | 你改过的参数类型 |
| `custom-options.properties` | 你手动加的参数 |
| `tool-notes.properties` | 工具助手里的备注 |
| `discovery-cache.properties` | 扫描结果缓存 |
| `language.txt` | 界面语言 |

**AI 对话记录**存在内置网页的本地存储里；**导出的 Markdown** 存在你挑的位置。

想恢复出厂：关掉 TBtools，删掉这个目录即可。

---

## 十一、已知限制

- 只面向 **Linux** 系统（WSL2 / Linux 服务器、主机等）
- **一次一个工具**，不做流程编排
- **还没做**：对话搜索、命令语法高亮；未进行服务器连接测试
- **未适配 macOS**
- AI 面板保留全部对话，内置桥一次最多带 20 条消息；自定义模型不支持图片附件，且受 CORS 限制
- 输出为纯文本（ANSI 序列被剥掉）

---

> 开发相关：构建入口 [build.ps1](build.ps1)、设计记录 [DESIGN.md](DESIGN.md)、版本历史 [CHANGELOG.md](CHANGELOG.md)、参与贡献 [CONTRIBUTING.md](CONTRIBUTING.md)。
>
> 构建脚本按 `TBTOOLS_HOME` → `JAVA_HOME` → 常见安装位置（`D:\TBtools`、`C:\Program Files\*\jdk-11*`）的顺序找 **JDK 11**；TBtools 没装在 D 盘时，先 `setx TBTOOLS_HOME "E:\TBtools"` 再构建即可。
