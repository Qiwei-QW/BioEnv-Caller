# BioEnv Caller — TBtools Ⅱ 通用生信软件调用插件

在 TBtools Ⅱ 里调用 WSL 中 **conda 虚拟环境**里安装的命令行工具。

**通用性**：插件包内不含任何工具清单。装了什么就能用什么，加新工具不需要改代码。

---

## 核心设计

**约束**：工具来源只有 conda 环境；参数信息只来自工具自己的 help 输出。

这意味着：

- 代码里**没有一份"支持的软件"名单**
- 任何 conda 环境里的工具，不管认不认识，都会自动出现并获得参数界面
- 新增工具、工具升级参数，都**不需要改动或重新编译插件**

完整设计原则见 [DESIGN.md](DESIGN.md)。

### 为什么只认 conda

用户用 conda 就是为了隔离和整洁。扫描系统 PATH 会把 Windows 侧 `.exe`、
系统工具、脚本残片一起卷进来，破坏这份整洁。所以**只扫 conda 是有意为之**。

---

## 架构

```
Plugin.PluginInject             契约层（实现 TBtools 的 Plugin.PluginInterface）
        │
        ▼
bioenv.ui.MainPanel             界面：命令列表 / schema 表单 / 命令预览 / 日志
        │
        ├─► EnvScanner          发现 conda 与全部环境（唯一来源）
        ├─► EnvRunner           在环境内执行探测命令
        ├─► ToolRegistry        汇总所有环境提供的命令
        ├─► NoiseFilter         过滤依赖与系统工具
        ├─► SchemaBuilder       探测 help 并递归子命令
        ├─► HelpParser          help 文本 → 参数 schema（核心）
        ├─► CommandBuilder      schema + 输入 → 命令行
        └─► Runner              流式执行、可中断
```

**分层的关键**：`HelpParser` 完全不知道工具叫什么、来自哪里。
给它任意 help 文本，它返回参数 schema。

---

## 它如何理解一个从没见过的工具

1. **发现**：扫 conda 环境的 `bin/`，过滤掉依赖（MPI launcher、libtool wrapper、
   ncurses 工具等）和系统工具（Kerberos、Perl 工具链、ICU 工具等）
2. **分组**：按提供它的 conda 环境归入树形目录，一个环境一个父节点
3. **探测**：依次尝试 `--help`、`-h`、`-help`、`help`、裸执行，
   **取解析出选项最多的那个**（不同工具对不同 flag 的响应差别很大）
4. **解析**：识别三种主流 help 格式——
   - 缩进对齐式：`    -in <inputfile>     Input file...`
   - 分节式：`GENERAL OPTIONS:` 下的 `-s FILE   alignment file(s)`
   - 内联式：`-c class: Sets the query class`
5. **推断类型**：五层策略，见下
6. **递归**：如果 help 列出的子命令比选项还多，说明是多级工具
   （`bedtools`、`samtools` 这类），自动下钻一级取子命令的参数

### 类型推断：五层策略

类型信息在 help 里**不存在**——CLI 没有类型系统，help 是给人读的散文。
所以推断按可靠性从高到低依次尝试，先成功者胜出：

| 层 | 信号 | 例 | 置信度 |
|---|---|---|---|
| 1 | **占位符形态** | `input_file`、`int_value`、`float_value`、`#`、`N`、`<...>` | 高 |
| 2 | **描述首词名词** | `Path to...`、`Number of...`、`Output directory` | 中 |
| 3 | **枚举候选** | `{a,b,c}`、`a\|b\|c`、`one of:` | 高 |
| 4 | **描述关键词** | 整句搜索，最弱 | 低 |
| 5 | **用户修正记忆** | 你改一次，永久生效 | — |

界面如实呈现置信度：**无标记**=高置信，**`?`**=中，**`??`**=纯猜测，
**`✓`**=你设定的。改过的类型存到
`~/.TBtools/BioEnvCaller/type-corrections.properties`，重启后仍生效。

### 宁可留空，不可填错

默认值只接受**强证据**：明确的 `default: X`、`(default X)`，或方括号内是数字。

像 argparse 风格的 `[specified]`、`[estimated]` 这种"当前值/说明"一律**不抓**——
空字段用户会去填，填了垃圾值用户会直接点 Run，后者危险得多。

### 解析不了也能用

help 格式太怪时退化为纯命令行模式，工具照常可运行（DESIGN.md P6）。

---

## 目录结构

```
BioEnvCaller\
├─ build.ps1                    编译 + 测试 + 打包（唯一入口）
├─ BioEnvCaller.plugin          产物，可解压安装到 TBtools
├─ aidev-project\               TBtools AI Dev 工程根
│   ├─ project.json             插件注册信息（panelPackage=bioenv.ui）
│   ├─ MenuConfig.ini           菜单挂载点（AI+）
│   └─ src\bioenv\              插件源码
│       ├─ core\                发现 / 解析 / 组装命令（无 UI 依赖）
│       ├─ ui\                  Swing 界面
│       ├─ i18n\                中英文案表
│       └─ test\                自带测试（纯 main()，不依赖 JUnit）
├─ vmadapt\src\vmadapt\         SSH 传输模块（独立可编译，产物并入同一 JAR）
├─ docs\                        使用帮助 / VMware 连接指南 / 公众号推文
└─ _archive\ui-redesign\        UI 改造过程的截图与探针，仅供追溯
```

`build\` 是构建产物目录，**不入版本库**（见 `.gitignore`）。

---

## 构建

```powershell
cd BioEnvCaller
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

构建脚本自动定位 **TBtools 自带的 JDK 11**（`D:\TBtools\jre`）：

> TBtools v2.542 运行在 **OpenJDK 11.0.6** 上。用本机 `javac 26` 编译会生成
> major=70 字节码，TBtools 加载时报 `UnsupportedClassVersionError`。
> 用 TBtools 自己的 javac 编译，版本天然对齐（major=55），脚本会逐个校验。

> ⚠️ **`build.ps1` 必须保存为「UTF-8 带 BOM」。**
> 脚本里有中文注释，而 Windows PowerShell 5.1 会把**无 BOM** 的 UTF-8 当 ANSI 解码，
> 中文注释随即变成乱码并吞掉紧随其后的一行 —— 曾经因此让整个测试块的退出码读取失效，
> **失败的测试被报成 PASS、构建仍然 exit 0**。现在两个 `build.ps1` 都带 BOM，
> 改脚本时请保持这一编码（VS Code 右下角选 `UTF-8 with BOM`）。

### 每次构建自动运行

| 测试 | 断言数 | 检查内容 |
|---|---|---|
| **P1 原则检查** | — | 源码可执行逻辑中无任何具体工具名 |
| **命令行形状测试** | 24 | 只输出用户表达的值；预览不含 conda 包装；按需加引号 |
| **用法行提取测试** | 23 | 参数只写在用法行时也能捕获；方括号歧义；不重复 |
| **候选值识别测试** | 33 | 13 种措辞、5 种散文反例，不依赖关键词清单 |
| **噪声过滤测试** | 147 | 147 个真实观察到的名字，工具与依赖的区分 |
| **类型推断测试** | 48 | 五种真实 help 格式下的类型判定、候选值抓取、默认值 |
| **修正记忆测试** | 30 | 持久化、作用域、规则泛化、过度泛化防护、容错 |
| **空状态测试** | 33 | 无 WSL / 无 conda / 无工具 / 畸形输入下的行为 |
| **框架自测** | — | 真实发现环境、探测 help、统计 schema 覆盖率 |
| **UI 冒烟测试** | — | 无头实例化，覆盖普通/多级/不可解析/分组 |
| **验收测试** | — | 对打包后 JAR 反射加载，校验结构与接口 |

合计 **338 条断言**。

---

## 参数可能出现在五个位置

help 并不总是把参数写在"选项明细段"。解析器覆盖以下全部位置：

| 位置 | 形态 | 例 |
|---|---|---|
| **用法行** | `tool [options] -f <dir>` | 只有 flag 与占位符，**无描述** |
| **用法行（方括号）** | `tool [-query input_file] [-out output_file]` | 每个参数各自一对括号 |
| **分节明细块** | flag 一行，描述在下一行 | `-query <File_In>` 换行 `Input file name` |
| **选项段同行** | `-in <inputfile>     Input file...` | 最常见 |
| **选项段续行** | 描述折行到后续行 | 关键信息常在续行 |

**为什么必须全都解析**：输入输出这类必需参数，很多工具**只在用法行里声明一次**，
选项段里根本没有。漏掉用法行，用户就看不到必需参数，工具等于不可用。

### 方括号的两种含义

| 形态 | 含义 | 处理 |
|---|---|---|
| `[options]`、`[args]` | "这里还有别的参数" | 跳过 |
| `[-query input_file]` | 一个可选参数 | 提取 |

### 用法行参数标注"无描述"

用法行只给 flag 和占位符，没有说明文字。这类参数照常展示（否则看不到必需参数），
但悬浮提示会说明**"该选项只在用法行出现，工具未提供说明"**——让信息缺失读起来是
help 的事实，而不是插件解析失败。

---

## 命令行生成规则

命令里**只出现你真正填写或选择过的参数**：

| 情形 | 是否进命令 |
|---|---|
| 你在文本框里输入了内容 | ✅ |
| 你勾选了开关 | ✅ |
| 你从下拉框改选了非默认项 | ✅ |
| 文本框保持空白 | ❌ |
| 开关未勾选 | ❌ |
| 下拉框停在默认项、你没动 | ❌ |

help 里读到的默认值**只以淡灰色提示显示**，不进入命令。

**预览区只显示你的命令**，不含 conda 包装：

```
orthofinder -f pep -M msa -T fasttree -t 12
```

点 Run 后，**日志区**会回显完整命令（含 `conda run -p ... --no-capture-output`），
因为排错时需要能逐字复现。

命令同时**按需加引号**：含空格的值才加（`-o '/mnt/c/My Data/out.fa'`），
`pep`、`msa`、`12` 这类裸输出。

---

## 参数类型判定

类型信息在 help 里**不存在**——CLI 没有类型系统。所以按可靠性分层判定：

| 层 | 信号 | 例 | 置信度 |
|---|---|---|---|
| 1 | **占位符形态** | `<int>`、`input_file`、`#`、`N` | 高 |
| 2 | **描述首词名词** | `Path to...`、`Number of...` | 中 |
| 3 | **候选值** | `{a,b}`、`a\|b`、`one of:`、**逐行列举** | 高 |
| 4 | **描述关键词** | 整句搜索，最弱 | 低 |
| 5 | **学到的规则** | 你改一次，泛化到同类参数 | 高 |

### 界面表现由置信度决定

| 置信度 | 表现 |
|---|---|
| **高**（help 明确写了类型） | 直接用正确控件，**不显示类型选择器** |
| 中 / 低 | 控件 + `?` / `??` 标记 + 类型选择器 |
| 你设定过 | `✓`，不再显示选择器 |

**高置信时隐藏类型选择器**是刻意的：`<int>` 是工具作者的定义，不是猜测，
没有需要用户仲裁的地方。

### 修正会泛化为规则

你教系统一次，它学的是**模式**而非个例：

| 你改了 | 系统学到 |
|---|---|
| `<int>` 是数字 | 所有 `<int>` 都是数字 |
| `--infile` 是输入 | 含 `infile` 占位符的都是输入 |

所以下次遇到同样的写法**直接判对**，且置信度为"高"。

规则只在**有真实信号**时才学（尖括号占位符、或 `-o`/`--threads` 这类
跨工具通用的命名）。对 `-x` 这种任意 flag 不泛化，否则会把一个工具的含义
扩散到无关工具上。

存放位置：`~/.TBtools/BioEnvCaller/type-corrections.properties`

---

## 安装

把 `BioEnvCaller.plugin` 解压到 TBtools 插件根目录：

```
D:\TBtoolsDir\.Plugin\BioEnv Caller\
├── AiDevBuild.txt
├── BioEnvCaller.jar
├── MenuConfig.ini          ← 内容 AI+
├── project.json
└── aidev-project\          ← 源码，供 TBtools AI Dev 二次编辑
```

重启 TBtools，在 `AI+` 菜单中打开。

> 若 `AI+` 菜单未出现：用 TBtools 的 **AI Dev 面板**打开一次
> `D:\TBtoolsDir\.Plugin\BioEnv Caller`，让 TBtools 完成 runtime 注册。
> 从 AiDev 日志看，注册动作由面板触发（`runtime.register`），手工拷贝不触发。

---

## 使用

1. 点 **Detect conda environments**
2. 左侧是**按环境分组的树**：每个 conda 环境一个父节点，下面是它提供的命令
3. 用 **Filter** 框按命令名过滤
4. 点一个命令 → 自动读它的 help 生成参数表单
5. 填参数，**Command preview** 实时显示真实命令（长路径只显示最后一段，
   例如 `-o Results_Sep07_2 -f pep`；执行时自动用回完整路径）
6. **Run** 执行，输出实时滚动，**Stop** 中断

多级命令（如 `bedtools`）会在表单上方出现 **Subcommand** 下拉框。

每行参数右侧的下拉框可**改控件类型**，改完会记住。旁边的标记表示推断置信度。

想确认表单是怎么来的，点 **How was this form built?** 看原始 help 文本。

---

## 已知限制

| 限制 | 说明 |
|---|---|
| **前台执行** | TBtools 关闭则任务终止 |
| **控制文件型工具** | 例如 PAML 系列从 `.ctl` 文件读参数，命令行不接受参数，因此解析不出 help，退化为纯命令行模式 |
| **help 格式极端特殊的工具** | 少数工具解析失败，同样退化为纯命令行模式 |
| **类型推断不保证准确** | CLI 没有类型系统，推断必然有猜测成分。界面用 `?`/`??` 标出不确定的行，且都可一键改 |
| **部分 conda 包名非生信** | 例如 `attr`、`dav1d` 确实是 conda 声明的包，但属于系统工具；已按命名结构过滤，若仍有漏网可用 Filter 框排除 |

---

## 实测数据（参考机器，30 个 conda 环境）

| 指标 | 值 |
|---|---|
| 扫描可执行文件 | 2721 |
| 保留为可用命令 | 209 |
| 过滤为依赖/系统工具 | 2349 |
| **schema 覆盖率** | **约 60–78%** |
| 多级命令识别 | 正确（如 `bedtools` → 44 个子命令） |
| 全部测试断言 | 236 条，全通过 |

覆盖率指"成功解析出参数表的比例"。剩下约 1/4 大多是控制文件型工具
（命令行本就不接受参数），它们仍可通过纯命令行模式使用。
