# 参与贡献

**中文** ｜ [English](CONTRIBUTING.en.md)

欢迎提 Issue 和 Pull Request。这个项目的目标是让不熟悉命令行的同学也能在 TBtools 里用上 conda 环境里的生信工具，任何让这条路更顺的改动都欢迎。

## 环境要求

| 项目 | 要求 |
|---|---|
| 系统 | Windows 10 / 11 x64 |
| TBtools | **2.558 以上**（AI 助手依赖内置模型桥） |
| JDK | **11** —— 用 TBtools 自带的 `jre` 即可；`build.ps1` 自动定位（`TBTOOLS_HOME` → `JAVA_HOME` → 常见安装位置），并校验 `javac` 确实是 11 |
| 运行环境 | 本机 WSL，或一台能 SSH 的 Linux 主机 |

## 构建与测试

```powershell
cd BioEnvCaller
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

`build.ps1` 是**唯一入口**，它会依次做：

1. 编译 `vmadapt\src`（SSH 传输模块）
2. 编译 `aidev-project\src`（插件本体）
3. 校验所有 class 都是 **major 55**（Java 11）
4. 跑**回归测试**（当前 83 条）和 **vmadapt 测试**（当前 16 条）
5. 打包出 `BioEnvCaller.plugin`

**提交前门禁必须全绿**：日志末尾要有

```
Regression: 78 passed, 0 failed
vmadapt:    15 passed, 0 failed
```

> `build.ps1` 文件必须保留 **UTF-8 BOM**，否则 PowerShell 5.1 会按 GBK 读中文注释导致语法错误。

## 代码结构

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

## 约定

- **测试**：自带测试是**纯 `main()`**，不引入 JUnit；新增行为请同时新增测试，并把测试类名加进 `build.ps1` 的门禁列表。
- **文案**：界面文字不允许硬编码，统一写进 `i18n\Strings.java`（中英各一条）；漏译会在界面上显示成键名，一眼可见。
- **不改宿主**：插件只通过公开接口使用 TBtools（`window.tbtools.ai`、`WebGuiJPanel`、`toolsKit.*`），**不要修改 TBtools 自身的文件**。
- **提交信息用英文**，说明"为什么"而不是"改了什么"。
- **版本**：发布后 tag **不再移动**；改动走 `v1.0.1`、`v1.0.2`……

## 报 Issue 时请带上

- 插件版本（`AI+` → BioEnv Caller → 设置页右下角，或 Release 页的 tag）
- TBtools 版本、Windows 版本
- 连接方式：WSL 发行版 / SSH 主机（虚拟机请说明 VMware 还是 VirtualBox、哪种网络模式）
- 复现步骤 + 截图（尤其**输出面板**里的报错原文）
- 如果和参数解析有关：把该工具的 `--help` 原文附上
