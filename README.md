# BioEnv Caller — TBtools Ⅱ 通用生信软件调用插件

在 TBtools Ⅱ 里调用 WSL 中 **conda 虚拟环境**里安装的命令行工具。

**通用性**：插件包内不含任何工具清单。装了什么就能用什么，加新工具不需要改代码。

> **版本** v1.0.0 ｜ **许可证** [MIT](LICENSE) ｜ **下载** [Releases](../../releases) ｜ **使用说明** [help.md](help.md) ｜ **更新日志** [CHANGELOG.md](CHANGELOG.md)
>
> 只想装上用：下载 Release 里的 `BioEnvCaller.plugin`，解压到 TBtools 插件目录即可（见[安装](#安装)）。
> 想改代码：`build.ps1` 是唯一入口，详见[构建](#构建)。

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
├─ aidev-project\               TBtools AI Dev 工程根
│   ├─ project.json             插件注册信息（panelPackage=bioenv.ui）
│   ├─ MenuConfig.ini           菜单挂载点（AI+）
│   ├─ plugin\ai.html           AI 助手页面（JxBrowser 里运行）
│   ├─ plugin\tbtools-ai.json   AI 能力声明（chat / vision）
│   └─ src\bioenv\              插件源码
│       ├─ core\                发现 / 解析 / 组装命令（无 UI 依赖）
│       ├─ ui\                  Swing 界面
│       ├─ i18n\                中英文案表
│       ├─ test\                自带测试，全部纳入构建门禁（纯 main()，不依赖 JUnit）
│       └─ tools\               调查/调试用 main()，编译但不打包、不入门禁
├─ vmadapt\                     SSH 传输模块（独立可编译，产物并入同一 JAR）
├─ docs\img\                    使用帮助里的十张标注图（help.md 已内嵌一份）
├─ help.md                      完整图文使用帮助
├─ DESIGN.md                    设计决策记录（为什么这样解析、这样排版）
├─ CHANGELOG.md                 版本更新日志
└─ LICENSE                      MIT
```

`build\` 是构建产物目录，**不入版本库**（见 `.gitignore`）。

> **`test\` 与 `tools\` 的分工**：`test\` 里每个文件都是测试，全部在 `build.ps1`
> 的门禁列表里；`tools\` 里是当初用来观察 help 解析结果的调查程序（无断言），
> 编译进 `build\classes` 供手工运行，**不进 JAR、不进回归门禁**。
>
> 运行调查工具：
> ```powershell
> & 'D:\TBtools\jre\bin\java.exe' -cp 'build\classes;build\vmadapt-classes' bioenv.tools.ShowParse
> ```

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

门禁覆盖 `bioenv\test\` 下**全部 64 个测试类**，每个都打印自己的断言数。
构建输出形如：

```
PASS  bioenv.test.NoiseFilterTest  (passed: 180   failed: 0)
PASS  bioenv.test.TypeInferenceTest  (passed: 47   failed: 0)
...
Regression: 64 passed, 0 failed
```

**不在这里手抄断言总数**——手抄的表会和代码脱节（这份 README 曾经写"338 条"，
而实际数字早已不同）。以构建输出为准。

测试大致分这几类：

| 类别 | 代表 | 检查内容 |
|---|---|---|
| **原则检查** | `SelfTest` | 源码可执行逻辑中无任何具体工具名（DESIGN.md P1） |
| **命令行组装** | `CommandLineTest`、`TypedValueReachesCommandTest` | 只输出用户表达的值；预览不含 conda 包装；开关写成裸 flag |
| **help 解析** | `UsageLineTest`、`HelpFormatTest`、`ExampleSectionTest` | 五种参数位置、方括号歧义、示例段落不误抓 |
| **噪声过滤** | `NoiseFilterTest` | 真实观察到的工具名与依赖名的区分 |
| **类型推断** | `TypeInferenceTest`、`KindChooserTest` | 判定类型、抓候选值、认默认值 |
| **修正记忆** | `CorrectionStoreTest` | 持久化、作用域、规则泛化、过度泛化防护 |
| **界面** | `LayoutTest`、`MainLayoutTest`、`UiSmokeTest` | 分隔条数量与位置、组件树、无头实例化 |
| **打包验收** | 构建脚本内的门禁 | 类文件 major=55、产品类全部入 JAR、测试与 tools 不泄漏 |

两个测试在缺少所需条件时**打印 SKIP 并 exit 0**（构建汇总里会列出），
而不是失败：`OptionsWindowTest` 要显示器，`AutoCopyInputsTest` 要可用的 WSL。
它们在有条件的机器上照常做真事。

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

从 [Releases](../../releases) 下载 `BioEnvCaller.plugin`（或自己用 `build.ps1` 构建），解压到 TBtools 插件根目录：

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

## 实测数据（开发机，30 个 conda 环境）

下面每个数字都可用 `bioenv.tools` 里的探针复跑，不是估的：

| 指标 | 值 | 复跑方式 |
|---|---|---|
| conda 环境 | 30 | `RealRegistry` |
| `bin/` 条目总数 | 2721 | `RealScan` |
| 注册表扫描的候选命令 | 652 | `RealRegistry` |
| **保留为可用命令** | **389** | `RealRegistry` |
| 作为依赖被过滤 | 258 | `RealRegistry` |
| 作为依赖被过滤的包 | 61 | `RealRegistry` |
| 无安装记录、退回扫 `bin/` 的环境 | 3 | `RealRegistry` |
| 扫描耗时 | 约 27 秒 | `RealRegistry` |
| **schema 覆盖率** | **约 93%**（30 个真工具抽样，28 成功） | `RealSchema 30 <env...>` |
| 多级命令识别 | 正确（`easel` 识别出 4 个子命令） | `RealSchema` |

> "候选命令 652" 是注册表在筛掉依赖**之前**手里有多少条；它不等于 `RealScan`
> 的 1237，因为两者的起点不同（注册表走 conda 清单，`RealScan` 走 `bin/` 目录）。
> 详见下面「三个数字口径不同」。

```powershell
# 复跑（需在装了 WSL + conda 的机器上）
& 'D:\TBtools\jre\bin\java.exe' -cp 'build\classes;build\vmadapt-classes' bioenv.tools.RealRegistry
& 'D:\TBtools\jre\bin\java.exe' -cp 'build\classes;build\vmadapt-classes' bioenv.tools.RealSchema 30 mafft_env hmmer_env trimal_env
```

### 三个数字口径不同，别混起来看

`2721 → 652 → 389` 不是同一件事的三次统计，而是三道不同的关口：

| 口径 | 数 | 由谁得出 | 含义 |
|---|---|---|---|
| `bin/` 条目 | 2721 | `RealScan` | 所有环境 `bin/` 下的文件总数（**含包装脚本与依赖**） |
| 简单规则下"像工具" | 1237 | `RealScan` | 只按命名结构过滤后的数——**这是参考值，不是插件显示的** |
| 注册表实际纳入 | **389** | `RealRegistry` | `ToolRegistry` 只认**该环境 conda 清单里声明要装的包**，652 是它在筛之前手里的候选数 |

`RealScan` 与 `RealRegistry` 的差额（1237 vs 389）正是插件设计的核心：
**它不靠扫 `bin/` 猜，而是读每个环境的 `conda-meta/*.json`**，只把"你要求装进来的包"
当作工具。这是 DESIGN.md P2「只认 conda」的落地，也解释了为什么最终数字比
`bin/` 条目少一个量级。

> 想自己核对时**以 `RealRegistry` 为准**——它跑的就是面板点"Detect conda
> environments"时的那条路径。`RealScan` 只是用来看物理上有多少东西。

### 覆盖率说明

93% 指"成功解析出参数表"的比例，样本是 11 个环境里抽的 **30 个真工具**（28 成功）。
抽样的两个失败项都不是解析缺陷：`easel` 与 `esl-mixdchlet` 是多级/派发型工具，
前者正确地识别出 4 个子命令，后者只认 `-h` 不认 `--help`。

**这个数字高度依赖你装了什么**：

- `mafft` / `raxml` / `trimal` / `dos2unix` / `seqkit` / `hmmer` 这类规范 CLI → 接近 100%
- **PAML 系列 → 0%**：它从 `.ctl` 读参数，任何命令行参数都被当成文件名
  （`codeml --help` 的回应是 `error when opening file --help`）。属已知限制，走纯命令行模式仍可用
- **EMBOSS 的部分命令**：环境里是壳脚本，指向的二进制不在，本身就没有 help 可读

所以覆盖率不是插件的固定属性，而是"你的工具集里规范 CLI 占比"的函数。

---

## 这些数字是怎么来的

`build.ps1` 的门禁跑的是**喂人造数据**的单元测试（64 个类），它保证逻辑正确，
但**回答不了"在真机上能不能用"**。真机路径由四个探针覆盖，在 `bioenv\tools` 下：

| 探针 | 做什么 | 是否会写 |
|---|---|---|
| `RealScan` | 数环境、数 `bin/` 条目、按简单规则算保留/过滤 | 否 |
| `RealRegistry` | 跑 `ToolRegistry.scan()`，**即面板真正调用的那条路径** | 否 |
| `RealSchema` | 对真工具跑 help 并建 schema，统计覆盖率 | 否（但会执行工具） |
| `RealEndToEnd` | **完整链路**：扫描 → 解析 → 组装命令 → 执行 → 验证工具真的干了活 | 写 WSL 临时目录 |

### 端到端验证

`RealEndToEnd` 会真的把命令跑起来，然后检查**工具产出**，而不是检查命令行"看起来对不对"：

```
seqkit seq --line-width 20   序列被重新折成 <=20 字符的行
mafft                        两条序列对齐返回，且带对齐引入的空位
trimal -in                   读入比对并输出 FASTA
dos2unix                     CR 字符确实被去掉
unix2dos                     CR 字符确实被加回
```

开发机上 **5/5 通过**。复跑：

```powershell
& 'D:\TBtools\jre\bin\java.exe' -cp 'build\classes;build\vmadapt-classes' bioenv.tools.RealEndToEnd
```

### 跑探针前请注意

- `RealSchema` / `RealEndToEnd` 会**真的执行**工具（这正是插件点命令时的行为）。
- 控制文件型工具可能在**当前工作目录**留下文件——PAML 就会。
  **请在临时目录里跑，别在源码目录里跑。**
- `RealEndToEnd` 会在 WSL 的 home 下建一个 `bioenv-e2e-<时间戳>` 临时目录，
  **结束时自动删除**；加参数 `keep` 可以保留它看内容。

### 待观察：mafft 的 `/dev/stderr` 报错（**未能复现**）

开发过程中曾**观察到一次**：

```
mafft: line 2673: /dev/stderr: Permission denied
ERROR conda.cli.main_run:execute(148): `conda run mafft ...` failed.
```

而 mafft 脚本第 217 行确实是 `progressfile="/dev/stderr"`，所以这条报错本身是真实的。

**但此后无法复现。** 为定位触发条件，逐一试过下列构造，**全部正常**
（`Permission denied` 计数均为 0，两条序列均正确返回，退出码均为 0）：

| 构造 | 结果 |
|---|---|
| stderr 合并进 stdout（`2>&1`） | 正常 |
| stderr 独立管道 | 正常 |
| stderr 重定向到文件 | 正常 |
| stderr 丢弃（`2>/dev/null`） | 正常 |
| stderr 接到 PTY（`script -qec`） | 正常 |

同样排除的变量：路径是否规范化（`/home/demo/x.fa` 与 `.../miniconda3/../x.fa` 都正常）、
`PATH` 是否被单引号包住、`bash -lc` 与 `bash -s` 的差别。

**因此触发条件未知。** 唯一确定的是：**结果不受影响**——
那次报错的同一批运行中，stdout 上的对齐结果完整正确，
`mafft file.fa 2>/dev/null | grep -c '^>'` 返回 2。

> 若你再次遇到这条报错，请记录**完整命令行与上下文**再反馈。
> 在此之前不应据此改动代码：现象复现不了，就没有可修的对象。

---

## 许可证

[MIT](LICENSE) © 2026 Qiwei

你可以自由使用、修改、分发（包括商用），保留版权声明与许可声明即可。
本项目按「现状」提供，不附带任何担保。
