# 参与贡献 · Contributing

欢迎提 Issue 和 Pull Request。这个项目的目标是让不熟悉命令行的同学也能在 TBtools 里用上 conda 环境里的生信工具，任何让这条路更顺的改动都欢迎。

*Issues and pull requests are welcome. The goal is to let people who are not comfortable with a terminal use the tools they installed with conda from inside TBtools; anything that makes that path smoother is welcome.*

## 环境要求 · Requirements

| 项目 / Item | 要求 / Requirement |
|---|---|
| 系统 / OS | Windows 10 / 11 x64 |
| TBtools | **2.558 以上**（AI 助手依赖内置模型桥）<br>*2.558 or newer; the AI assistant needs the built-in model bridge* |
| JDK | **11**，直接用 TBtools 自带的 `D:\TBtools\jre`（`build.ps1` 会自动定位）<br>*JDK 11, use the `jre` that ships with TBtools; `build.ps1` finds it* |
| 运行环境 / Runtime | 本机 WSL，或一台能 SSH 的 Linux 主机<br>*local WSL, or a Linux host reachable over SSH* |

## 构建与测试 · Build and test

```powershell
cd BioEnvCaller
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

`build.ps1` 是**唯一入口**，它会依次做：
*`build.ps1` is the single entry point; it:*

1. 编译 `vmadapt\src`（SSH 传输模块）*compiles `vmadapt\src`*
2. 编译 `aidev-project\src`（插件本体）*compiles the plugin sources*
3. 校验所有 class 都是 **major 55**（Java 11）*checks every class is Java 11*
4. 跑**回归测试**（当前 78 条）和 **vmadapt 测试**（当前 15 条）*runs 78 regression and 15 vmadapt tests*
5. 打包出 `BioEnvCaller.plugin` *produces the plugin package*

**提交前门禁必须全绿** / *the gate has to be green before a commit*：

```
Regression: 78 passed, 0 failed
vmadapt:    15 passed, 0 failed
```

> `build.ps1` 文件必须保留 **UTF-8 BOM**，否则 PowerShell 5.1 会按 GBK 读中文注释导致语法错误。
> *`build.ps1` must keep its UTF-8 BOM; PowerShell 5.1 otherwise reads the Chinese comments as GBK and fails.*

## 代码结构 · Layout

```
aidev-project\
  project.json / MenuConfig.ini   TBtools AI Dev 工程描述（菜单挂在 AI+）
  plugin\ai.html                  AI 助手页面（JxBrowser 里运行）
  plugin\tbtools-ai.json          AI 能力声明（chat / vision）
  src\bioenv\
    core\   工具发现、help 解析、参数推断、命令组装（无 UI 依赖）
    ui\     Swing 界面（MainPanel / SchemaFormPanel / 参数编辑器…）
    i18n\   中英文案表（所有界面文字都走这里）
    ai\     AI 面板与模型设置
    test\   自带测试，纯 main()，不依赖 JUnit，全部纳入门禁
    tools\  调查/调试用的 main()，编译但不打包、不入门禁
vmadapt\
  src\vmadapt\core\   SSH 传输、密钥、远端文件（无 UI 依赖）
  src\vmadapt\ui\     连接面板与向导
```

*`core` has no UI dependencies, `i18n` holds every user-facing string, `test` is pure `main()` with no JUnit and all of it is in the gate, and `tools` is compiled but neither packaged nor gated.*

## 约定 · Conventions

- **测试**：自带测试是**纯 `main()`**，不引入 JUnit；新增行为请同时新增测试，并把测试类名加进 `build.ps1` 的门禁列表。
  *Tests are plain `main()` methods, no JUnit. Add a test with every new behaviour and register its class in the `build.ps1` gate list.*
- **文案**：界面文字不允许硬编码，统一写进 `i18n\Strings.java`（中英各一条）；漏译会在界面上显示成键名，一眼可见。
  *No hard-coded UI strings: put them in `i18n\Strings.java`, one Chinese and one English entry. A missing translation shows up on screen as the key name.*
- **不改宿主**：插件只通过公开接口使用 TBtools（`window.tbtools.ai`、`WebGuiJPanel`、`toolsKit.*`），**不要修改 TBtools 自身的文件**。
  *Never modify TBtools itself; the plugin only uses public interfaces.*
- **提交信息用英文**，说明"为什么"而不是"改了什么"。
  *Commit messages are in English and explain why, not what.*
- **版本**：发布后 tag **不再移动**；改动走 `v1.0.1`、`v1.0.2`……
  *Tags never move after a release; changes go into `v1.0.1`, `v1.0.2`, …*

## 报 Issue 时请带上 · What a bug report needs

- 插件版本（Release tag，或设置页显示的版本）*plugin version*
- TBtools 版本、Windows 版本 *TBtools and Windows versions*
- 连接方式：WSL 发行版 / SSH 主机（虚拟机请说明 VMware 还是 VirtualBox、哪种网络模式）
  *how you connect: WSL distro, or SSH host — for a VM say VMware or VirtualBox and which network mode*
- 复现步骤 + 截图（尤其**输出面板**里的报错原文）
  *steps to reproduce and screenshots — above all the error text from the output pane*
- 如果和参数解析有关：把该工具的 `--help` 原文附上
  *for parsing problems, attach the tool's `--help` output*
