# BioEnv Caller · UI 可改动边界梳理

> **基线：`虚拟机适配\merged`（2026-10-03 19:10 构建）** —— 只分析这一份，不使用 `最终修改\`。
> 方法：只读遍历源码；界面用 JDK 11 真机离屏渲染成 PNG 核对（`ui-main.png` / `ui-form.png` / `ui-conn.png`）。
> 判定标准只有一条：**`merged\build.ps1:122-156` 那 33 个回归测试会不会红**，以及插件入口契约会不会断。

---

## 0. 一句话结论

- 这个插件**没有皮肤文件**：界面全是 Java Swing 代码在构造函数里搭出来的，改风格 = 改 Java 源码。
- **能放心改的**：约 **67 处**内联样式（颜色、字号、圆角、内边距、边框粗细）、按钮外观、图标外观、文案。
  这些在 `bioenv\ui` 下全是散着写的，全工程 `UIManager.put` **0 处**、`LookAndFeel` **0 处**、
  唯一命名的颜色常量只有 `HintText.HINT_COLOR`（`HintText.java:27`）。
- **改不了的**：10 类东西被测试用**反射**锁住了字段名、组件类型、分隔条数量与位置、控件映射、
  甚至图标的像素颜色。这些一旦动，`build.ps1` 直接红。

---

## 1. 先认清入口链：这三个名字不能动

```
TBtools 读 <插件目录>\project.json
    panelPackage         = "bioenv.ui"      ← 包名固定
    panelClassSimpleName = "MainPanel"      ← 类名固定
    pluginName           = "BioEnv Caller"
    menuPath             = "AI+"
  └─ 反射调用 Plugin.PluginInject
        getPluginName() → "BioEnv Caller"
        generatePanel() → I18n.loadOnce(); return new MainPanel();
  └─ 把这个 JPanel 嵌进 TBtools 主窗口（所以顶层是 JPanel，不是 JFrame）
```

证据：`merged\aidev-project\project.json:3-6`、`MenuConfig.ini`、
`Plugin\PluginInject.java:26,39-55`、`Plugin\PluginInterface.java:18-25`。
回归测试 `UiSmokeTest.java:161-170` 也反射调用这两个方法。

---

## 2. UI 是怎么定义的（四层）

### 2.1 组件树

代码在两个包里。注意 **`merged` 不复制 vmadapt 的源码** —— `merged\build.ps1:31` 指向上层
`虚拟机适配\src`，编译后把 class 打进同一个 JAR。所以改 `ConnectionPanel` 只需改一份。

| 包 | 文件 | 规模 | 职责 |
| --- | --- | --- | --- |
| `bioenv.ui` | `MainPanel.java` | 2868 行 | 主面板（被 TBtools 嵌入的那个） |
| | `SchemaFormPanel.java` | 2951 行 | 参数表单 + 命令预览 + 附加参数 |
| | `ParameterEditorPanel.java` | 1228 行 | 「编辑参数…」独立窗口 |
| | `HintText` / `FileDialogs` / `LinkState` | 小 | 占位文字 / 文件对话框 / 状态落盘 |
| | `CollapsiblePanel.java` | 203 行 | **死代码**：唯一实例化点在 `SchemaFormPanel:674`，而那个方法没有调用方 |
| `vmadapt.ui` | `ConnectionPanel.java` | 1922 行 | 连接配置窗口（3 个选项卡） |
| | `MainFrame` / `Text` / `UiText` / `LangProvider` | 小 | 独立验证窗口 + 自带中英文案表 |

```
MainPanel                       JPanel, BorderLayout(6,6), 外留白 6      :460-481
├─ NORTH  工具条                GridBagLayout                        :1234-1284
├─ CENTER outerSplit            JSplitPane 横向  resizeWeight .24  divider 8   :1478
│    ├─ LEFT  工具树            JScrollPane + TitledBorder「命令（按环境分组）」 :1317
│    └─ RIGHT top
│         ├─ logSplit           JSplitPane 横向  resizeWeight 1.0  divider 6   :1431
│         │    ├─ LEFT  中区    SchemaFormPanel（上）+ 命令预览（下）            :1412
│         │    └─ RIGHT 输出    logPanel + TitledBorder「输出」, 宽 340         :1393
│         └─ SOUTH 按钮行       运行/停止/清空日志/查看帮助原文/编辑参数…        :1461
└─ SOUTH  状态栏                JLabel                                   :1522
```

主面板另外**直接 new 两个窗口**（不在上面这棵树里）：

| 窗口 | 尺寸 | 内容 | 位置 |
| --- | --- | --- | --- |
| 连接配置 `JDialog` | 760×560，最小 520×380 | `vmadapt.ui.ConnectionPanel` | `MainPanel.java:1028-1058` |
| 参数编辑 `JFrame` | 900×660，最小 520×360 | `ParameterEditorPanel` | `MainPanel.java:2674-2681` |
| 选项弹出 `JFrame`（表单内） | 1100×800，最小 480×320 | **复用同一个** `optionScroll` 对象 | `SchemaFormPanel.java:395-405` |

### 2.2 布局

`BorderLayout` 做骨架；`GridBagLayout` 用在工具条和参数行（3 列：标签 / 控件 / 类型徽章）；
`FlowLayout` 用在按钮行；`BoxLayout.Y_AXIS` 用在参数编辑器列表；
**`JSplitPane` 是界面可拖动性的全部来源，共 3 个（2 横向 + 1 纵向）**。
`CardLayout` 被 import 但从未使用（`SchemaFormPanel.java:4`）。

### 2.3 外观：67 处内联，没有主题层

| 文件 | `setBackground` | `setForeground` | `setFont` | `setBorder` | `new Color` | `new Insets` |
| --- | --- | --- | --- | --- | --- | --- |
| `MainPanel.java` | 1 | 1 | 3 | 7 | 2 | 1 |
| `SchemaFormPanel.java` | 2 | 7 | 5 | 10 | 8 | 10 |
| `ParameterEditorPanel.java` | 0 | 5 | 1 | 4 | 6 | 8 |
| `CollapsiblePanel.java`（死代码） | 0 | 0 | 3 | 3 | 0 | 0 |
| `HintText.java` | 0 | 5 | 0 | 0 | 4 | 0 |

典型：`MainPanel.java:1339` 命令预览写死 `setBackground(Color.WHITE)`；
`MainPanel.java:1387` 提示文字写死 `new Color(0x66,0x66,0x66)`；
`SchemaFormPanel.java:1468` 分组标题写死蓝色 `new Color(0x1A,0x5F,0xB4)`；
`HintText.java:53/113/127` 写死 `Color.BLACK`。

**自绘只有 3 个类**，都在 `SchemaFormPanel.java:2843-2950`：
`DrawnIcon`（基类）、`WindowsMark`（蓝色四格标）、`PenguinMark`（Tux 企鹅）。
它们是"工作目录"两个按钮的图标，**像素颜色与像素数被测试断言**（见 §4-D）。

`vmadapt.ui.ConnectionPanel` 半集中：7 个颜色常量在 `:124-178`，但另有 2 处内联灰
（`:479`、`MainFrame:158/179`），状态色的 `setForeground` 在 9 个调用点重复。

### 2.4 文案：两套互不相通的 i18n，都是手写的

| | bioenv 侧 | vmadapt 侧 |
| --- | --- | --- |
| 取词 | `I18n.t(Strings.KEY)` | `Text.get(KEY)` |
| 词表 | `Strings.java:885-1503` 成对 `put(ZH,EN,KEY,"中文","English")` | `UiText.java:398-1017` 成对 `put(KEY,"English","中文")` |
| 默认语言 | 中文（`I18n.java:76`） | 中文（`Text.java:42-52`） |
| 落盘 | `%USERPROFILE%\.TBtools\BioEnvCaller\language.txt` | 无（跟随宿主） |
| 桥 | `MainPanel.java:1108-1130` `PluginLangProvider`，`t()` 故意返回 `null`，让 vmadapt 用自己的词表 | |

改文案必须**同步**改面板里的刷新回调（`MainPanel.java:521-567`、
`SchemaFormPanel.java:935-965`、`ParameterEditorPanel.java:202-216`），否则切语言时那处不变。
中英一致性只有 `Strings.missingKeys()` 一个检查器，而它**没有被任何 build 脚本调用**。

---

## 3. 能改的（构建不会红）

| # | 能改什么 | 依据 |
| --- | --- | --- |
| 1 | **所有颜色、字号、圆角、内边距、边框粗细** | 33 个测试里没有任何一条断言颜色/字体/大部分边框。可改点：`MainPanel:1316,1338-1339,1367,1387,462,1524`；`SchemaFormPanel:260,330-332,486-487,537,874,1407,1467-1468,1968,2479,2788`；`ParameterEditorPanel:294,438,457,549,701`；`HintText:44,53,113,127,133` |
| 2 | **按钮外观**：扁平化 + 主次分级；连弹窗里的按钮也能统一 | 无测试断言按钮 UI。做法是注册到 `UIManager` 的 `ButtonUI` |
| 3 | **图标外观**：环境/工具的彩色圆点、Tux / Windows 标 | 只有像素颜色（±60）和非透明像素数（>40）两条限制，见 §4-D |
| 4 | **顶栏重排**成「品牌区 + 分段控件 + 动作 + 语言开关」 | 只要 §4-A 的分隔条约束仍满足 |
| 5 | **命令预览改成深色终端**、输出区字体/底色、状态栏配色、分区标题样式 | 纯外观；`MainPanel:1339` 一处 `setBackground` |
| 6 | **连接配置窗口（`vmadapt.ui.ConnectionPanel`）的整个外观** | 12 个 vmadapt 测试全部 headless、不碰 Swing；只有两个引用了它的静态/包内方法（`CommandTimeoutTest`、`KeygenCommandTest`），**没有任何断言组件树 / 尺寸 / 颜色 / 文案布局**。唯一不能动的是按钮文案，见 §4-E |
| 7 | **i18n 文案** | 在 `Strings.java` / `UiText.java` 里成对新增 key + 接上刷新回调即可。英文/中文必须成对（`put()` 一次写两张表） |
| 8 | `CollapsiblePanel.java` 整个文件 | 死代码：无调用方；还有两个测试专门断言预览区**不是**它 |

---

## 4. 改不了的（改了会红）

| # | 锁住什么 | 具体约束 | 谁在锁 |
| --- | --- | --- | --- |
| **A** | **分隔条** | 数量必须**恰好 2 个横向 + 1 个纵向**（是 `==` 不是 `>=`）；粗细 ≥ 4px；每个分隔条两侧都要能拖；树侧初始位置落在 **120–400px**；右侧宽度 **≥ 300px**（760px 窄窗也要满足） | `LayoutTest.java:86,90,109-110`；`DividerRangeTest.java:70-71,86-87` |
| **B** | **字段名与类型** | `modeBox` 必须是 `JComboBox`（下标 0=WSL、1=Linux VM）、`logArea` 必须是 `JTextArea`、`statusLabel` 必须是 `JLabel`、`applyStatus`/`applyLog` 必须是 `Runnable` | `HotPathClassLoadTest.java:78-79,150-159`；`ConnectionModeRememberedTest.java:97,114`；`AutoLoadTest.java:83-87,116` |
| **C** | **选项行的控件类型映射** | TEXT→`JTextField`、NUMBER→`JSpinner`、SWITCH→`JCheckBox`、路径→输入框+按钮；**参数区里不能出现 `JComboBox`**；协议字符串 `CheckBox`/`number`/`InFilePath`/`OutFilePath`/`Text` 要能往返 | `KindChooserTest.java:69-93,150-167` |
| **D** | **工作目录图标的像素** | 必须含 `0xF5A623` / `0x000000` / `0x00A4EF`（容差 ±60）；非透明像素 **> 40**；两个图标不能是同一实例；图标按钮文字必须为空但 tooltip / 无障碍名非空 | `WorkdirUiTest.java:66-88` |
| **E** | **连接窗口的按钮文案** | 「测试连接」/「Test connection」逐字断言 | `merged\build\CrossModuleCheck.java:60-78` |
| **F** | **方法名** | `setStatus(String)`、`appendLog(String)`、`appendLogRaw(String)` | `HotPathClassLoadTest.java:58-60` |
| **G** | **`setStatus` 热路径** | 暖机后**不得再触发任何类加载** → 这 5000 次调用路径上不能加 lambda / 匿名类 / 新类 | `HotPathClassLoadTest.java:102-103` |
| **H** | **分区标题** | 三个 `TitledBorder` 的标题必须能从 i18n key 取到；语言切换是**改标题**（复用同一个 border 对象）而不是换新对象 | `MainPanel.java:2513-2517`；`MainLayoutTest:79,87,114`；`OptionAccessTest:247` |
| **I** | **`setName` 契约** | `bioenv.makeWorkdir`、`bioenv.chooseWorkdir`、`bioenv.subcommand`、`bioenv.windowPreview`、`bioenv.windowRun`、`bioenv.connectionDialog`、`bioenv.parameterEditor`、`bioenv.editor.*` 等 —— 测试唯一的抓手 | 各 UI 测试 |
| **J** | **入口与构建** | `project.json` 固定 `bioenv.ui.MainPanel`；`PluginInject` 的两个方法签名；JDK 11（class 主版本 **55**）；33 个测试 + JAR 暂存门禁 + `MainPanel$N` 匿名类完整性门禁全绿 | `merged\build.ps1:99-109,236-251,267-280,289-294`；`UiSmokeTest.java:161-170` |

另外还有 8 条**中文字符串被逐字断言**（状态栏 / 日志）：
`AutoLoadTest.java:120-131`、`SshNoProfileMessageTest.java:115-118`，
以及六个工作目录文案不能含子串 `WSL`（`WorkdirUiTest.java:91-100`）。
动文案前先搜这几条。

> **一个容易踩的坑**：`bioenv\test\` 下有 30 多个测试类，但 build 只跑 33 个。
> `MainLayoutTest`、`OptionsWindowTest`、`SubcommandLayoutTest`、`ParameterEditorTest`、
> `ParameterDragTest`、`EditorEndToEndTest`、`OptionAccessTest` 等**都不在构建列表里** ——
> 因为它们断言英文字符串，而默认语言是中文，只有手工塞英文语言文件才会过。
> 它们仍描述"设计上冻结的形态"，但**不能当自动门禁**，只能当参考。

---

## 5. 高风险区：能做，但动之前先想清楚

| 区域 | 为什么危险 |
| --- | --- |
| `MainPanel.buildCenter()`（`:1309-1490`） | 分隔条数量 / 方向 / `setPreferredSize` `setMinimumSize` 全在这里，直接对 §4-A |
| `MainPanel.doLayout()`（`:1504-1520`）+ `logSplit` 的 `componentResized`（`:1437-1457`） | 分隔条位置是**按 `getMinimumSize()` 实时算**的；改边框/内边距会让某个分隔条卡死或越界 |
| 三个 `TitledBorder` 换成自绘标题 | 测试靠它定位分区，且语言切换复用同一对象 |
| `SchemaFormPanel.optionHost`（`:1258-1263`）的父子关系 | 选项弹出窗口借用/归还**同一个** `optionScroll` 对象 |
| 参数行内边距 / 行高 | `ROW_HEIGHT=33`（`:807`）与滚轮步进绑定；`:1255/:1587` 用 `getPreferredSize()` 实时算宽度 |
| `ParameterEditorPanel` 的 `JComponentHeader`（`:1203-1207`） | 拖拽排序靠 `instanceof` 判断行容器；`BoxLayout` + strut 也不能随便换 |
| `HintText` 的占位机制 | 它**只画不写** `setText`（`:43-47,100-105`），就是为了不让占位文字进命令行 |
| 把 `JPanel` 包一层再塞进窗口 | `MainPanel:1054` / `:2677` 用的是 `setContentPane(...)`，测试会顺着 parent 链找组件 |

---

## 6. 建议的动手顺序

**第 1 步：先造主题层，视觉零变化。**
新建 `bioenv\ui\UiTheme.java`，把 §3-1 那 67 处内联样式**原值搬迁**进去。
改完界面应当一个像素都不变，但此后"调风格"就是改这一个文件。
（这一步同时给 `vmadapt.ui.ConnectionPanel` 的 7 个颜色常量留下统一出口。）

**第 2 步：分四块换外观，每块单独构建 + 跑完 33 个测试。**
① 顶栏 → ② 参数表单 → ③ 命令预览与按钮行 → ④ 连接配置窗口。

**第 3 步：视觉回归。**
用本目录的 `Shot2.java`（离屏渲染 harness）重新出图，与 `ui-*.png` 逐张对比，
确认没有布局位移。

---

## 7. 本目录的产物

| 文件 | 说明 |
| --- | --- |
| `BioEnvCaller-UI-可改动边界图.png` | **主图**：三个界面截图 + 绿框（能改）/ 红框红线（改不了）标注 + 两份清单 |
| `ui-main.png` / `ui-form.png` / `ui-conn.png` | 基线真实渲染：主界面 / 参数表单 / 连接配置窗口 |
| `Shot2.java` | 离屏截图 harness，可重跑 |
| `compose_bounds.py` | 拼图脚本；改标注只需改这个文件里的 `ROWS` |
| 本文件 | 逐条依据与风险分级 |

渲染方法（可复现）：

```powershell
# 编译 harness
& "D:\TBtools\jre\bin\javac.exe" -encoding UTF-8 `
  -cp "..\虚拟机适配\merged\build\classes;..\虚拟机适配\merged\build\vmadapt-classes" `
  -d out Shot2.java
# 出图
& "D:\TBtools\jre\bin\java.exe" `
  -cp "out;..\虚拟机适配\merged\build\classes;..\虚拟机适配\merged\build\vmadapt-classes" `
  Shot2 ui system all
```

---

## 8. 已实施的改动：左侧工具树（2026-10-04）

按参考图只改了左侧这棵树，面板其余部分一行未动。

| 文件 | 改动 |
| --- | --- |
| `merged\aidev-project\src\bioenv\ui\ToolTreeStyle.java` | **新增**。树的圆点、引导线、字重分层、行高、底色、选中态、字体回退 |
| `merged\aidev-project\src\bioenv\ui\MainPanel.java` | 在第 1315–1319 行附近：删掉 `toolTree.setFont(MONOSPACED)`，把 `new JScrollPane(toolTree)` 提前一行，加一句 `ToolTreeStyle.apply(toolTree, treeScroll)` |

**做法要点**

- 节点的 `userObject` 字符串格式**没有改**（仍是 `"EMBOSS_env  (261)"`、工具名）。
  选中逻辑靠 `nodeToTool` 查表，改格式会打断查表。渲染器只按 **层级 + 是不是叶子**
  决定画成哪一种，并把 `"名称  (计数)"` 拆成两段分别上色。
- 引导线画在 `StyledTreeUI` 里，不画在渲染器里 —— 单元格渲染器的绘制区域从节点自己的
  缩进位置开始，缩进空白不在它的范围内。
- 展开用的 `±` 方框按参考图去掉了。展开/折叠仍是 Swing 原生行为：**双击行**，或选中后
  用左右方向键。这是本次唯一的交互变化。
- 配色不是估的：圆点色值、引导线 `#B8CFE5`、底色 `#F7F7F9`、文字 `#111114` / `#474751`
  都是从参考图上量出来的像素。

**验证**

| 项目 | 结果 |
| --- | --- |
| 编译 | JDK 11 / class 主版本 55 ✅ |
| 回归测试 | **33 / 33 通过**（含 `LayoutTest`、`DividerRangeTest`、`UiSmokeTest`、`WorkdirUiTest`、`HotPathClassLoadTest`） |
| 打包 | 185 个产品类全部入 JAR；`MainPanel` 的 36 个匿名类完整；`BioEnvCaller.plugin` 已重建（915,518 字节） |
| 影响范围 | 同一状态下前后各渲染两次做像素比对：**树面板之外 0 处差异**（工具条 / 参数表单 / 按钮行 / 命令预览 / 输出区 / 状态栏全部逐像素相同） |

对照图见 `工具树-改造对照.png`；参考图原件见 `参考-工具树样式.png`。

