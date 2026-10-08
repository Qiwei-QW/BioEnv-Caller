# 移植到 BioEnvCaller

本文档面向**开发者**，说明如何把「虚拟机适配」模块的 SSH 传输能力，接到既有的
`BioEnvCaller` 插件上，让 BioEnvCaller 除了 WSL 之外，也能把工具送到 VMware 虚拟机
里执行。

> **本文档只做说明，不改任何代码。**
>
> 撰写本文档时**只读取**了 `BioEnvCaller` 的源码作为参考（路径：
> `BioEnvCaller\aidev-project\src\bioenv\...`），**没有修改 BioEnvCaller 的任何
> 文件**。下文中出现的行号是撰写时的位置，仅供参考，实际改动请以当前源码为准。
>
> **未实测**：本文描述的改造方案**未在 BioEnvCaller 上实际实施过**，因此所有
> 「改动后」的代码片段都是**示意性写法**，未编译、未运行、未经真实虚拟机验证。
> 回归测试清单中的用例也**未实际执行**。

---

## 目录

1. [总览：为什么要抽 Transport 接口](#1-总览为什么要抽-transport-接口)
2. [两个接缝：WslBridge 与 SshTransport 同构](#2-两个接缝wslbridge-与-sshtransport-同构)
3. [改动点清单](#3-改动点清单)
4. [坑一：translatePaths 在 SSH 下会静默改错路径](#4-坑一translatepaths-在-ssh-下会静默改错路径)
5. [坑二：Job.cancel() 杀不掉远端进程](#5-坑二jobcancel-杀不掉远端进程)
6. [坑三：isInfrastructureDistro 是 WSL 专属概念](#6-坑三isinfrastructuredistro-是-wsl-专属概念)
7. [坑四：输入文件必须上传](#7-坑四输入文件必须上传)
8. [坑五：ssh 与 scp 的端口参数大小写相反](#8-坑五ssh-与-scp-的端口参数大小写相反)
9. [改动前 / 改动后对照表](#9-改动前--改动后对照表)
10. [回归测试清单](#10-回归测试清单)
11. [实施建议顺序](#11-实施建议顺序)

---

## 1. 总览：为什么要抽 Transport 接口

### 1.1 现状：执行通道被写死在 WslBridge 上

`BioEnvCaller` 目前的执行链路是这样的：

```
MainPanel  →  WslBridge  →  ProcessBuilder("wsl.exe", "-d", distro, "--", "bash", "-s")
   ↑              ↑
 界面只认 WSL    直接 new ProcessBuilder
```

`WslBridge` 同时承担了三件事：

| 职责 | 具体内容 |
| --- | --- |
| **通道** | 启动 `wsl.exe` 子进程、写 stdin、读 stdout/stderr |
| **发行版管理** | `listDistros()`、`isInfrastructureDistro()`、`preferredDistro()` |
| **路径翻译** | `toWslPath()`：把 `D:\data\x.fa` 翻成 `/mnt/d/data/x.fa` |

而在 SSH 场景下：

| 职责 | WSL 的做法 | SSH 该怎么做 |
| --- | --- | --- |
| 通道 | `wsl.exe -d X -- bash -s` | `ssh.exe -p P U@H bash -s` |
| 发行版管理 | 列出并选择发行版 | **不存在这个概念的对应物**，应替换为「连接配置」 |
| 路径翻译 | `D:\x` → `/mnt/d/x` | **完全错误**，远端没有 `/mnt/d` |

三者里只有**通道**是同构的，另外两个都必须换掉。如果直接把 `WslBridge` 改成
「既能 WSL 又能 SSH」的巨型类，它会变成一个满是 `if (isSsh)` 的类，而且路径翻译
那个错误的默认行为会一直留在里面，成为持续的错误来源。

### 1.2 目标：把「通道」抽出来，其余留给各实现

```java
/** 执行通道：一个把 bash 脚本送出去并拿回结果的东西。 */
public interface Transport {
    /** 同步跑一段脚本并收集输出（短命令、探测用）。 */
    Result runBashCapture(String script);

    /** 跑一段脚本，并把输出逐行推给 sink（长任务用）。 */
    Result runBashStreaming(String script, LineSink sink);

    /** 起一段脚本但不等它结束（用于在远端发 kill）。 */
    Process runDetached(String script) throws IOException;

    /** 这个通道显示给用户的名字，例如 "WSL: Ubuntu" 或 "SSH: bio@127.0.0.1:2222"。 */
    String describe();
}
```

于是有两个实现：

```
              ┌──────────────┐
              │  Transport   │  ← 只描述"怎么把脚本送出去"
              └──────┬───────┘
                     │
        ┌────────────┴────────────┐
        │                         │
  ┌─────▼──────┐          ┌───────▼────────┐
  │ WslBridge  │          │  SshTransport  │
  │ (既有)     │          │  (本模块)      │
  └────────────┘          └────────────────┘
   wsl.exe -d X            ssh.exe -p P U@H
   toWslPath() 保留        RemoteFiles 处理远端路径
```

**关键收益：`Runner`、`EnvScanner`、`EnvRunner`、`ToolRegistry` 这些类只需要把
参数类型从 `WslBridge` 换成 `Transport`，逻辑一行都不用改。**

### 1.3 为什么两个类是「同构」的

对照两边的方法签名，可以看到它们是**同一个形状**：

| WSL 侧（BioEnvCaller） | SSH 侧（虚拟机适配） | 是否同构 |
| --- | --- | --- |
| `WslBridge.runBashCapture(String)` → `Result` | `SshTransport.runBashCapture(String)` → `Result` | ✅ 完全一致 |
| `WslBridge.runBashStreaming(String, LineSink)` → `Result` | `SshTransport.runBashStreaming(String, LineSink)` → `Result` | ✅ 完全一致 |
| `WslBridge.runDetached(String, String distro)` → `Process` | `SshTransport.runDetached(String)` → `Process` | ✅ （distro 参数由 SshTarget 承担） |
| `WslBridge.LineSink.line(String, boolean)` | `SshTransport.LineSink.line(String, boolean)` | ✅ 完全一致 |
| `WslBridge.Result { exitCode, stdout, stderr, timedOut }` | `SshTransport.Result { exitCode, stdout, stderr, timedOut, ... }` | ✅ 兼容 |
| `WslBridge.isCondaNoise(String)` | 需要搬成静态工具方法 | ⚠️ 需处理 |
| `WslBridge.listDistros()` | **无对应物** | ❌ 不该进接口 |
| `WslBridge.toWslPath(String)` | **无对应物，且语义相反** | ❌ 不该进接口 |

**结论**：

- `runBashCapture` / `runBashStreaming` / `runDetached` / `LineSink` / `Result`
  这五样进接口；
- `listDistros` / `isInfrastructureDistro` / `preferredDistro` 属于 WSL 特有，**留在
  `WslBridge` 里**，不进接口；
- `toWslPath` **绝对不能进接口**（见第 4 节）。

> 补充：`虚拟机适配` 的 `SshTransport.Result` 还额外带了 `commandLine`、
> `isTransportFailure()`、`isRemoteFailure()`、`diagnose(Result)`。若接口只声明
> `exitCode/stdout/stderr/timedOut` 四个字段，则两边都能满足；额外的便捷方法
> 各自保留在自己的实现类里，不影响接口兼容。

---

## 2. 两个接缝：WslBridge 与 SshTransport 同构

### 2.1 接缝一：WslBridge.java —— 唯一碰 ProcessBuilder 的类

先确认一个事实：**`WslBridge` 是 BioEnvCaller 里唯一直接碰 `ProcessBuilder` 的
类**（用 ripgrep 搜过 `new ProcessBuilder`，命中都在 `WslBridge.java` 里）。
这让它成为**天然的、也是唯一的接缝**：

- 它内部有 `startBash(String)` 私有方法，负责拼 `wsl.exe` 命令行、写 stdin；
- 它内部有 `runDetached(String, String)`，负责起一个不等结果的进程；
- 它对外暴露的就是那几个 `runBash*` 方法 + 内部类 `Result` / `LineSink`。

所以在 `WslBridge` 上加 `implements Transport` 是**成本最低、侵入最小**的改法：
不用动它的实现，只需要让它声明实现了接口（并在个别签名上补齐）。

需要处理的签名差异：

| 方法 | 现状 | 改为 |
| --- | --- | --- |
| `runBashCapture(String)` | 已经是 `public Result runBashCapture(String)` | 直接满足接口 |
| `runBashStreaming(String, LineSink)` | 已经是 `public Result runBashStreaming(String, LineSink)` | 直接满足接口 |
| `runDetached(String, String)` | 多一个 `distro` 参数 | 接口声明 `runDetached(String)`；`WslBridge` 里加一个单参重载委托给双参版本（`this` 的 distro） |
| `describe()` | 不存在 | 新增，返回 `"WSL: " + distro` |
| `Result` | 内部类 `WslBridge.Result` | 若接口把 `Result` 定义在 `Transport` 里，需要让 `WslBridge.Result` 实现/改名为 `Transport.Result`；**更省事的做法是让接口直接引用 `WslBridge.Result`**（见 2.3 的取舍） |
| `LineSink` | 内部接口 `WslBridge.LineSink` | 同上 |

### 2.2 接缝二：MainPanel.java —— 唯一决定「用哪个桥」的地方

`MainPanel` 里跟「选环境」有关的字段和方法（撰写本文档时的位置）：

| 位置 | 内容 | 改造要点 |
| --- | --- | --- |
| `MainPanel.java:114` | `private WslBridge wsl;` | 改为 `private Transport wsl;`（或改名 `transport`） |
| `MainPanel.java:126` | `private WslBridge pendingBridge;` | 改为 `private Transport pendingBridge;` |
| `MainPanel.java:63` | `private final JComboBox<String> distroBox` | 这个下拉框在 SSH 场景下语义变成「连接配置选择」 |
| `MainPanel.java:275-360` | `loadDistroAsync(String)`：调 `WslBridge.listDistros()` → `new WslBridge(distro)` → 扫描环境 | 需要按「当前是 WSL 还是 SSH」走不同分支 |
| `MainPanel.java:287` | `WslBridge bridge = new WslBridge(pendingDistro);` | 改为从工厂方法取 `Transport` |
| `MainPanel.java:913` | `WslBridge bridge = new WslBridge(distro);` | 同上 |
| `MainPanel.java:1346-1354` | `new WslBridge.LineSink() {...}` | 改为 `Transport.LineSink` |
| `MainPanel.java:812` | `job.cancel();` | 无需改这里，但 `Job.cancel()` 内部要改（见第 5 节） |

**接入方式**：在面板顶部加一个「连接方式」的选择（WSL / SSH），选 SSH 时展示
连接面板（主机 / 端口 / 用户名 / 私钥 / 标签 + 「测试连接」按钮）。

`虚拟机适配` 里已有可直接复用的 UI 与连接逻辑：

| 复用对象 | 作用 |
| --- | --- |
| `vmadapt.core.SshTarget` | 连接参数的数据载体（`host` / `port` / `user` / `keyPath` / `label` / `timeoutSeconds`），自带 `validate()` |
| `vmadapt.core.SshTransport` | SSH 通道实现，方法签名与 `WslBridge` 同构 |
| `vmadapt.core.VmConnector` | 「测试连接」：返回 `VmConnector.ConnectionReport`（`uname` / `whoami` / bash 路径 / conda 路径 / 建议） |
| `vmadapt.core.ConnectionStore` | 连接配置的保存与读取（去敏） |
| `vmadapt.ui.ConnectionPanel` | 可直接嵌入的 Swing 连接面板 |
| `vmadapt.core.RemoteFiles` | 远端工作目录创建、路径校验、scp 目标串拼装 |

### 2.3 接口放在哪：一个必须做的取舍

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **A. 接口定义在 BioEnvCaller 侧** | 在 `bioenv.core` 里新增 `Transport.java`，`WslBridge` 和 `SshTransport` 都实现它 | 依赖方向正确：BioEnvCaller 不依赖 `vmadapt` 的内部结构 | `vmadapt` 若要实现 BioEnvCaller 的接口，就产生了**反向依赖** |
| **B. 接口定义在独立的第三方位置** | 新增一个极小的 `vmadapt.core` 接口包，两边都依赖它 | 无循环依赖 | 需要保证两边打包时接口类不冲突（**同名类在两个 JAR 里会打架**） |
| **C. 适配器（推荐）** | 不改 `vmadapt` 一行；在 BioEnvCaller 里写一个 `SshBridgeAdapter implements Transport`，内部持有 `SshTransport` 并转发 | **零耦合，两边各自独立演进**，也不需要共享接口类 | 多一个薄适配类（约 30 行） |

**推荐方案 C。** 理由：

1. 本模块的定位是**独立模块**，不应为了被集成而反向依赖 BioEnvCaller；
2. 方案 C 让「移植」变成**单向的、发生在 BioEnvCaller 一侧的**工作，
   `虚拟机适配` 目录一个字节都不用改；
3. 避免了方案 B 里同名类在两个 JAR 中重复的打包陷阱。

**适配器示意（未实测，示意性写法）：**

```java
package bioenv.core;

import vmadapt.core.SshTarget;
import vmadapt.core.SshTransport;

/** 把 SSH 通道伪装成 BioEnvCaller 认识的 Transport。 */
final class SshBridgeAdapter implements Transport {

    private final SshTransport ssh;
    private final String description;

    SshBridgeAdapter(SshTarget target) {
        this.ssh = new SshTransport(target);
        this.description = "SSH: " + target.toDisplay();
    }

    @Override
    public Result runBashCapture(String script) {
        return convert(ssh.runBashCapture(script));
    }

    @Override
    public Result runBashStreaming(String script, LineSink sink) {
        return convert(ssh.runBashStreaming(script,
                (text, isError) -> sink.line(text, isError)));
    }

    @Override
    public Process runDetached(String script) throws java.io.IOException {
        return ssh.runDetached(script);
    }

    @Override
    public String describe() {
        return description;
    }

    /** 把 SshTransport.Result 转成 Transport.Result。 */
    private static Result convert(SshTransport.Result r) {
        Result out = new Result();
        out.exitCode = r.exitCode;
        out.timedOut = r.timedOut;
        out.stdout.addAll(r.stdout);
        out.stderr.addAll(r.stderr);
        return out;
    }
}
```

> 注意 `Process` 的导入：`java.lang.Process` 无需 import。

> **命名提醒**：`ConnectionReport` 是 **`VmConnector` 的静态嵌套类**，它的全名是
> `vmadapt.core.VmConnector.ConnectionReport`。引用时必须写成
> `import vmadapt.core.VmConnector.ConnectionReport;`
> 或直接使用 `VmConnector.ConnectionReport`；写成 `vmadapt.core.ConnectionReport`
> 会编译失败。

---

## 3. 改动点清单

按顺序列出全部改动。**共涉及 2 个既有文件 + 若干新增文件。**

### 3.1 新增文件（BioEnvCaller 侧）

| 文件 | 作用 | 备注 |
| --- | --- | --- |
| `bioenv\core\Transport.java` | 通道接口 | 只声明 4 个方法 + `Result` / `LineSink` |
| `bioenv\core\SshBridgeAdapter.java` | 把 `SshTransport` 适配成 `Transport` | 见 2.3 方案 C |
| `bioenv\core\ConnectionKind.java` | 枚举 `WSL` / `SSH` | 或直接用 boolean |

> 若采用方案 A（接口放 BioEnvCaller 侧、让 `SshTransport` 直接实现），则
> **需要修改 `虚拟机适配` 的源码**，与「独立模块、零耦合」的定位冲突，
> **不推荐**。

### 3.2 `WslBridge.java` 改动点

| # | 改动 | 说明 |
| --- | --- | --- |
| W1 | 类声明加 `implements Transport` | `public class WslBridge implements Transport` |
| W2 | 新增 `describe()` | 返回 `"WSL: " + distro`（处理 `distro` 为 null 的情况） |
| W3 | 新增单参 `runDetached(String script)` 重载 | 委托给既有的 `runDetached(script, distro)`，不动原方法（原方法仍被 `Job.cancel()` 用） |
| W4 | 让 `Result` / `LineSink` 与接口类型一致 | 若接口沿用 `WslBridge.Result` / `WslBridge.LineSink`，此项无需改动——**推荐这样，改动最小** |
| W5 | **不要**把 `toWslPath` 加进接口 | 见第 4 节 |
| W6 | **不要**把 `listDistros` / `isInfrastructureDistro` 加进接口 | 见第 6 节 |

### 3.3 `MainPanel.java` 改动点

| # | 改动 | 说明 |
| --- | --- | --- |
| M1 | 字段 `wsl` / `pendingBridge` 类型由 `WslBridge` 改为 `Transport` | 编译期即可发现所有需要改的调用点 |
| M2 | 顶部加「连接方式」选择（WSL / SSH），对应 `ConnectionKind` | 切换时刷新下方内容 |
| M3 | 选 SSH 时，嵌入 `vmadapt.ui.ConnectionPanel` | 或按同样布局自绘主机/端口/用户/私钥/标签 + 测试按钮 |
| M4 | `loadDistroAsync` 拆出 SSH 分支 | WSL 分支保持原样（仍用 `listDistros()`）；SSH 分支用 `VmConnector.connect()` 探测 |
| M5 | 新增 `makeTransport()` 工厂 | 返回 `Transport`：WSL 返回 `new WslBridge(distro)`，SSH 返回 `new SshBridgeAdapter(target)` |
| M6 | 所有 `WslBridge.LineSink` 处改为 `Transport.LineSink` | `MainPanel.java:1346`、`:1354` 附近 |
| M7 | 状态栏文案区分连接方式 | 例如「Checking environments in bio@127.0.0.1:2222...」 |
| M8 | 工具/环境缓存需按连接身份隔离 | 否则切到另一台机器会读到上一台的缓存（见回归测试 R6） |
| M9 | 输入文件路径处理接上上传逻辑 | 见第 7 节 |

### 3.4 `Runner.java` 改动点

| # | 改动 | 说明 |
| --- | --- | --- |
| R1 | 字段与构造参数 `WslBridge` → `Transport` | `private final Transport wsl;` |
| R2 | `run(...)` / `runTyped(...)` 的 `WslBridge.LineSink` 参数 → `Transport.LineSink` | 纯类型替换 |
| R3 | `execute(...)` 里 `job.useBridge(wsl, wsl.getDistro())` | `getDistro()` 在 `Transport` 上不存在 → 改为 `job.useTransport(wsl, ...)`，或让适配器暴露标识串 |
| R4 | `Job.cancel()` 逻辑重写 | **见第 5 节，这是最关键的一处** |
| R5 | `WslBridge.isCondaNoise(text)` 调用 | 这个静态方法若留在 `WslBridge` 上，`Transport` 化之后仍可直接用（静态方法不受接口影响）；若想彻底解耦，可搬到 `AnsiText` 之类的工具类 |

### 3.5 `EnvScanner` / `EnvRunner` / `ToolRegistry` 改动点

| 类 | 改动 |
| --- | --- |
| `EnvScanner` | 构造参数 `WslBridge` → `Transport` |
| `EnvRunner` | 构造参数 `WslBridge` → `Transport` |
| `ToolRegistry` | 若内部持有 bridge，同样替换类型 |

这几个类的内部逻辑**完全不用改**——它们只调 `runBashCapture` / `runBashStreaming`，
这正是抽接口的收益所在。

### 3.6 `CommandBuilder.java` 改动点

| # | 改动 | 说明 |
| --- | --- | --- |
| C1 | `WslBridge.toWslPath(...)` 的 3 处调用改为走 `Transport` 感知的路径解析 | **见第 4 节** |
| C2 | `WslBridge.quote(...)` / `quoteIfNeeded(...)` | 这两个是纯字符串工具，**可保留不动**（SSH 与 WSL 的 bash 引用规则相同） |
| C3 | `killScript(String marker)` | 逻辑可保留，但**调用方式要改**（见第 5 节） |

`CommandBuilder` 里 `WslBridge.toWslPath` 的具体位置（撰写本文档时）：

| 行号 | 用途 |
| --- | --- |
| `CommandBuilder.java:86` | 拼接 `cd <workdir>` 时翻译工作目录 |
| `CommandBuilder.java:411` | `valueArg(...)` 里把 Windows 路径翻成 WSL 路径 |
| `CommandBuilder.java:427` | `translatePaths(String)` 内部 |
| `CommandBuilder.java:434` | `translatePaths(String)` 循环内部 |
| `CommandBuilder.java:534` | 创建输出目录时翻译 |
| `CommandBuilder.java:580` | 工作目录参数翻译 |

---

## 4. 坑一：translatePaths 在 SSH 下会静默改错路径

### 4.1 问题

`CommandBuilder.translatePaths` 与 `looksLikeWindowsPath` 的设计目标是 WSL：

```java
// CommandBuilder.java:488 附近
public static boolean looksLikeWindowsPath(String text) {
    // C:\... 或 C:/... 或 \\wsl$\...
}

// CommandBuilder.java:425 附近
public static String translatePaths(String text) {
    // 对逗号分隔的每一段调用 WslBridge.toWslPath(piece)
}
```

`valueArg(...)`（`CommandBuilder.java:400` 附近）里的判断逻辑：

```java
boolean declared = declaresPath(o.kind);
boolean windows = looksLikeWindowsPath(raw);
String text = raw;
if (declared) {
    text = translatePaths(raw);       // 只要选项声明为路径，就翻译
} else if (windows) {
    text = WslBridge.toWslPath(raw);  // 看起来像 Windows 路径，也翻译
}
```

于是用户在表单里填 `D:\data\x.fa` 时：

| 场景 | 结果 |
| --- | --- |
| WSL | `D:\data\x.fa` → `/mnt/d/data/x.fa` ✅ **正确** |
| **SSH** | `D:\data\x.fa` → `/mnt/d/data/x.fa` ❌ **远端根本没有 `/mnt/d`** |

### 4.2 为什么这个 bug 特别危险：它是**静默**的

注意两个细节：

1. `translatePaths` **不报错**，它只是产生一个字符串；
2. 生成出来的 `/mnt/d/data/x.fa` 是一个**语法完全合法**的 POSIX 路径。

所以后果是：

- 命令**能成功构造**，日志里也能看到一行漂亮的命令；
- ssh **能成功连接**，远端 bash **能成功启动**；
- 工具启动后才发现文件打不开，报一个类似
  `No such file or directory: /mnt/d/data/x.fa` 的错误；
- 用户看着这个路径会非常困惑——他明明填的是 `D:\data\x.fa`，而且文件就在那儿。

**这个错误只会在运行时、在远端、以工具自己的错误信息形式暴露出来**，而不会在
参数校验阶段被拦住。这是它比「参数拼错」更难排查的原因。

### 4.3 解决：收敛到 resolveInputPath()

不要在 `translatePaths` 内部加分支（那会让它同时承担两种语义），而是**在调用它
之前，先把「路径的来源」解析清楚**，收敛到一个入口：

```
                     ┌──────────────────────┐
   用户填的原始值 ──→ │  resolveInputPath()  │ ──→ 可以直接送出去的路径
                     └──────────────────────┘
                              │
                  ┌───────────┴───────────┐
                  │                       │
            Transport 是 WSL         Transport 是 SSH
                  │                       │
          toWslPath(raw)          上传后返回远端绝对路径
                                       （或原样保留 POSIX 路径）
```

**`resolveInputPath` 的职责**：

| 输入形态 | WSL 下 | SSH 下 |
| --- | --- | --- |
| `D:\data\x.fa`（Windows 路径） | 翻成 `/mnt/d/data/x.fa` | **上传**到 `~/vmadapt_work/<label>_<时间戳>/x.fa`，返回该远端绝对路径 |
| `/home/bio/x.fa`（POSIX 路径） | 原样保留 | 原样保留（用户自己保证远端存在） |
| `x.fa`（相对路径） | 原样保留 | 原样保留（由工作目录决定） |
| 逗号分隔的多路径 | 逐段翻译 | 逐段判定：Windows 段上传，其余保留 |
| 输出路径（OUTPUT_PATH） | 翻成 `/mnt/d/...` | **翻译自 windows 路径 → 本地，结果回传**（见 7.3） |

**改造要点**：

1. `translatePaths` 保留现状，但**只有 WSL 分支才调它**；
2. 新增 `resolveInputPath(Schema.Option o, String raw, Transport t)`，
   在两个分支里分别实现上表；
3. `valueArg(...)` 里那两处 `translatePaths` / `toWslPath` 调用，改为调用
   `resolveInputPath`；
4. `CommandBuilder.java:86`、`:534`、`:580` 三处工作目录/输出目录的翻译，同样要
   按 `Transport` 分流。

**判断「这是 Windows 路径还是 POSIX 路径」**：直接复用
`looksLikeWindowsPath`（纯形状判断，与传输方式无关），以及 `虚拟机适配` 里
`RemoteFiles.looksLikeWindowsPath` / `RemoteFiles.looksLikePosixPath`
（语义相同，可任选其一）。

> ⚠️ **同时要注意反向的风险**：在 SSH 场景下，用户完全可能**故意**填一个远端
> 已经存在的 POSIX 路径（比如 `/data/genome.fa`，数据本来就在服务器上，不需要
> 上传）。所以 `resolveInputPath` 的逻辑必须是
> **「长得像 Windows 路径才处理，否则原样保留」**，
> 绝不能是「一律当成 Windows 路径去翻译」。

---

## 5. 坑二：Job.cancel() 杀不掉远端进程

### 5.1 问题：SSH 的进程树和 WSL 不一样

`Runner.Job.cancel()` 现在的实现（`Runner.java:59` 附近）：

```java
public void cancel() {
    cancelled = true;
    WslBridge b = bridge;
    if (b != null) {
        try {
            b.runDetached(CommandBuilder.killScript(marker), distro);
        } catch (Exception ignored) { }
    }
    Process p = process;
    if (p != null) {
        p.destroyForcibly();   // ← 杀掉本地 ssh.exe
    }
}
```

在 **WSL** 下这段逻辑是对的，因为：

- `runDetached(...)` 里的 `wsl.exe` 能**穿过边界**进去执行 `killScript`，
  脚本在 WSL 内部用 `pgrep` + `/proc/$p/environ` 找到带 marker 的进程并 kill；
- WSL 是一个**本机**子系统，`wsl.exe` 起的进程和 WSL 里的进程可以沟通。

在 **SSH** 下，同样这段逻辑会出问题：

| 步骤 | SSH 下的实际情况 |
| --- | --- |
| `runDetached(script)` | 起了一个 `ssh.exe -p P U@H bash -s`，脚本经 stdin 发过去在远端跑 |
| `p.destroyForcibly()` | 杀掉的是**本地那个 `ssh.exe` 客户端进程** |
| 远端那个真正在跑的工具 | **完全不受影响，继续跑** |

**根因**：SSH 的客户端与远端进程**不是同一个进程树**。杀掉客户端只是断开了连接，
远端进程会继续运行（甚至因为它失去了 stdout 的读取者而阻塞，最终变成一个
"僵尸"式挂着的进程，占着 CPU 或内存，用户却以为已经停了）。

**这个 bug 的表现**：用户点了「停止」，界面确实恢复了（因为本地 `ssh.exe` 被杀，
读取 stdout 的线程返回了），但**去虚拟机里 `top` 一看，工具还在跑**。如果用户
接着再点一次「运行」，就会有两个同样的任务在跑，争抢同一个输出文件。

### 5.2 解决：另开一条 SSH 连接执行 pkill

**必须用一条新的 SSH 连接，在远端执行 kill。**不能用刚才那条（它已经被杀或正在
阻塞）。

`虚拟机适配` 的 `SshTransport` 已经提供了这个能力：

```java
// SshTransport.java:304 附近
public boolean killRemote(String pattern) { ... }
```

它内部新起一条独立的 ssh 连接执行 kill 命令（而不是复用正在跑任务的那条），
这正是 SSH 场景需要的语义。

**改造后的 `Job.cancel()` 示意（未实测）：**

```java
public void cancel() {
    cancelled = true;

    // 1) 先在远端把真正干活的进程杀掉。
    //    必须另开一条连接：跑任务的那条连接正忙（或即将被销毁）。
    //    用独立的 pattern（marker）匹配，只杀本次运行启动的进程。
    Transport t = transport;
    if (t != null) {
        try {
            if (t instanceof vmadapt.core.SshTransport) {
                // SSH：远端 pkill -f <marker>
                ((vmadapt.core.SshTransport) t).killRemote(marker);
            } else {
                // WSL：保持原有做法，进 WSL 内部按环境变量匹配
                t.runDetached(CommandBuilder.killScript(marker));
            }
        } catch (Exception ignored) {
            // 远端杀不掉时，退化为杀掉本地客户端：至少让等待返回。
        }
    }

    // 2) 再杀掉本地客户端，让阻塞在 read 上的线程返回。
    Process p = process;
    if (p != null) {
        p.destroyForcibly();
    }
}
```

### 5.3 marker 的作用与两种匹配方式的差异

`Job` 里的 marker（`Runner.java:22` 附近）形如：

```java
private final String marker = "bioenv-"
        + Long.toHexString(System.nanoTime()) + "-"
        + Integer.toHexString((int) (Math.random() * 0xFFFF));
```

| 匹配方式 | WSL 的 `killScript` | SSH 的 `pkill -f <marker>` |
| --- | --- | --- |
| 匹配对象 | 进程的**环境变量** `BIOENV_JOB=<marker>` | 进程的**命令行文本** |
| 实现 | 遍历 `pgrep -f .`，读 `/proc/$p/environ`，`grep -qx "BIOENV_JOB=<marker>"` | `pkill -f <marker>` |
| 可靠性 | **更可靠**：环境变量是本次运行自己的记录 | 稍弱：`-f` 匹配整条命令行，理论上可能与用户自己敲的命令撞上 |
| 前提 | 启动脚本时把 marker 注入环境 | 命令行里必须**真的出现** marker 字符串 |

**注意 `marker` 要真的出现在命令行里**，`pkill -f` 才能匹配到。`CommandBuilder.buildScript(...)`
在拼接脚本时会带上 marker（`Runner.java:119` 传了 `marker` 参数），改造时要确认
marker 确实落在**被启动进程的命令行**上（而不是只写在环境变量里）——这是 SSH
方案能否生效的关键。

**更稳妥的做法（推荐，但需要改脚本生成）**：让脚本里启动的进程命令行**显式带上
marker**，例如以 `env BIOENV_JOB=<marker> <tool> ...` 或注释形式出现，这样
`pkill -f` 一定命中，而不会误伤。

**双保险**：`killRemote` 之后，可再执行一次 `pgrep -f <marker>` 确认已经杀干净，
把结果回报给 UI（让用户知道"已停止"是真的停了，而不是"断了连接"）。

---

## 6. 坑三：isInfrastructureDistro 是 WSL 专属概念

### 6.1 它解决的是什么问题

`WslBridge.isInfrastructureDistro(String)`（`WslBridge.java:160` 附近）用来过滤
那些**不是给人用的 WSL 发行版**：

```java
return n.equals("docker-desktop")
        || n.startsWith("docker-desktop-")
        || n.startsWith("rancher-desktop")
        || n.startsWith("podman-machine");
```

它存在的原因（源码注释里说得很清楚）：

1. Docker Desktop 会注册 `docker-desktop` 和 `docker-desktop-data` 两个发行版，
   它们是 Alpine 基础、只有 `ash` 没有 `bash`、永远不会有 conda；
2. Rancher Desktop、Podman Desktop 同理；
3. **Windows 会把默认发行版排在列表第一位**，而这些基础设施发行版经常就是默认的；
4. 于是探测脚本被送到一个没有 `bash` 的系统里，报出「WSL 不可达」——而用户的
   WSL 其实完全正常。

配套的还有 `listDistros()`（过滤后）与 `listDistrosUnfiltered()`（不过滤），
以及 `preferredDistro()`（跳过基础设施发行版选第一个真发行版）。

### 6.2 SSH 下为什么完全不适用

| 概念 | WSL | SSH |
| --- | --- | --- |
| 有没有「发行版列表」 | 有（`wsl -l -q` 返回多个） | **没有**——一次连接就是一台机器 |
| 有没有「默认发行版排第一」 | 有 | **没有** |
| 有没有 docker-desktop 这种东西 | 有（容器工具注册的） | **没有**——容器工具不会在 SSH 主机上注册发行版 |
| 该不该过滤 | 该 | **无从过滤，也不需要** |

**结论**：

1. `isInfrastructureDistro` 及 `listDistros` / `preferredDistro` /
   `listDistrosUnfiltered` **全部属于 WSL 专属**，**不要**放进 `Transport` 接口；
2. SSH 场景下替代它的是**连接配置的校验**（`SshTarget.validate()`：主机非空、
   端口 1..65535、私钥文件存在）；
3. 不要在 SSH 分支里复用 `isInfrastructureDistro` 去做「这台机器能不能用」的
   判断——**主机名恰好叫 `docker-desktop` 是完全合法的**，用 WSL 的规则去拒绝它
   是错的。

### 6.3 UI 上的一处连带改动

`MainPanel` 里那个下拉框（`MainPanel.java:63` 的 `distroBox`）在 WSL 下是
「发行版选择」，在 SSH 下应该变成**「已保存的连接选择」**。这两者都是「让用户在
多个候选里挑一个」，所以控件可以复用，但：

| 方面 | WSL | SSH |
| --- | --- | --- |
| 数据来源 | `WslBridge.listDistros()` | `ConnectionStore` 里保存的连接列表 |
| 为空时的提示 | 「没有可用的 WSL 发行版」 | 「还没有保存的连接，请先添加一个」 |
| 提供「新增」入口 | 不需要（从系统读） | **需要**（用户手工添加） |

`虚拟机适配` 的 `ConnectionStore` 已经处理了连接的保存与读取（且**不保存口令**），
可直接复用。

---

## 7. 坑四：输入文件必须上传

### 7.1 根本差异：远端没有 /mnt/c

WSL 和 Windows 跑在**同一台机器**上，所以 WSL 能通过 `/mnt/c`、`/mnt/d` 直接访问
Windows 的全部磁盘。**这是 WSL 独有的能力。**

SSH 的远端是**一台独立的机器**：

| 路径 | WSL | SSH 远端 |
| --- | --- | --- |
| `/mnt/c/Users/...` | ✅ 存在，指向 Windows 的 C 盘 | ❌ **不存在** |
| `/mnt/d/data/x.fa` | ✅ 存在 | ❌ **不存在** |
| `D:\data\x.fa` | 经 `toWslPath` 翻译可用 | ❌ 完全不认识 |

### 7.2 解决：scp 上传到远端工作目录

**规则：SSH 场景下，凡是用户从 Windows 选的文件，都必须先上传。**

上传目标（`RemoteFiles` 已实现）：

```
~/vmadapt_work/<标签>_<yyyyMMdd_HHmmss>/
```

`RemoteFiles.createWorkDir(String label)` 会：

1. 在远端 `mkdir -p ~/vmadapt_work/<label>_<时间戳>`；
2. `cd` 进去并 `pwd -P` 回显**绝对路径**（`pwd -P` 解析软链接，得到稳定路径）；
3. 把绝对路径返回给调用方。

**为什么必须返回绝对路径**：后续的 `scp`、后台任务、结果回传都是**各自独立的
进程**，工作目录（cwd）不会继承。只有绝对路径才在所有环节都可靠。

**为什么不用相对的 `~`**：`~` 只有登录 shell 会展开；虽然 scp 的远端参数会过一遍
shell，但用 `$HOME` 展开后的绝对结果更稳妥。

### 7.3 各方向的传输处理

| 方向 | 类型 | 处理 |
| --- | --- | --- |
| 输入 | `INPUT_PATH` / `PASTE_IN_PATH` | **上传**到工作目录，命令里用远端路径 |
| 输入 | `PATH_LIST` / `PATH_LIST_FILE` | 逐项判定：Windows 路径上传，POSIX 路径保留 |
| 输出 | `OUTPUT_PATH` / `TEXT_OUT_PATH` / `DIRECTORY_PATH` | **翻译成远端路径**（在工作目录下），运行结束后**下载回 Windows** |
| 工作目录 | `cd <workdir>` | 若用户填了 Windows 路径，需先 `mkdir -p` 上传侧对应目录 |

### 7.4 可复用的组件

| 组件 | 作用 |
| --- | --- |
| `RemoteFiles.createWorkDir(label)` | 创建远端工作目录并返回绝对路径 |
| `RemoteFiles.sftpTarget(remotePath)` | 拼 `user@host:path` 形式的 scp 目标串（处理含空格/中文的路径） |
| `RemoteFiles.validateRemotePath(p)` | 校验远端路径合法性（拒绝 Windows 路径误填） |
| `RemoteFiles.needsUpload(local, remote)` | 按**文件名 + 大小**判断是否可以跳过上传（避免重复传输大文件） |
| `RemoteFiles.remoteChild(dir, fileName)` | 拼出远端子路径 |
| `ScpTransfer.upload(paths, remoteDir, sink)` | 执行上传，带进度回调 |
| `ScpTransfer.download(remotePath, localDir, sink)` | 执行下载 |
| `ScpTransfer.parsePercent(line)` / `parseProgressFile(line)` | 解析 scp 进度输出，用于进度条 |

> **注意 bioenv 的 `AutoCopyInputsTest`**：BioEnvCaller 里似乎已有类似「自动拷贝
> 输入」的机制（WSL 侧用 `cp_one`，见 `WslBridge.java:402` 附近）。SSH 场景下这个
> 环节必须换成 `scp` 上传，**不能**复用 WSL 的 `cp`（远端没有源文件可拷）。

---

## 8. 坑五：ssh 与 scp 的端口参数大小写相反

这是一个**极易踩中且报错很迷惑**的陷阱：

| 工具 | 端口参数 | 大小写 |
| --- | --- | --- |
| `ssh` | `-p 2222` | **小写** |
| `scp` | `-P 2222` | **大写** |

写成 `scp -p 2222 src dst` 会怎样？`scp` 的 `-p` 是**"preserve 时间戳和权限"**
的意思，不接参数。于是 `2222` 被当成**一个源文件**，报错大致是：

```
scp: 2222: No such file or directory
```

**看起来像"文件找不到"，实际上是端口参数写错了**——这个报错方向完全误导人。

`虚拟机适配` 的 `SshCommand` 里专门处理了这一点（见 `SshCommand.java:63` 的注释）：

```java
// scp 的端口选项是大写 -P，ssh 是小写 -p；写反了 scp 会把端口当成源文件。
cmd.add(forScp ? "-P" : "-p");
```

**接缝上必须保证的选项**（`SshCommand.build(...)` 已实现，改造时不要绕过它自己拼）：

| 选项 | 值 | 为什么必须有 |
| --- | --- | --- |
| `-o BatchMode=yes` | — | 禁止一切交互提问。**没有它 ssh 会在无 TTY 时永久挂起**，界面表现为「点了没反应」 |
| `-o StrictHostKeyChecking=accept-new` | — | 首次连自动接受主机密钥，之后仍严格校验。虚拟机重建/IP 复用是常态，纯 `yes` 会让用户每次都手工删 known_hosts，`no` 又等于关掉中间人防护 |
| `-o ConnectTimeout=N` | 默认 15 秒 | 把「网络不通」的等待压到可接受范围，否则 Windows 默认要等几十秒 |
| `-i <私钥>` | 有密钥时 | 指定私钥（本方案不支持密码登录） |
| `-T`（仅 ssh） | — | 禁用伪终端分配，配合 `BatchMode` 保证不挂起 |

**脚本一律通过 stdin 传给 `bash -s`**，绝不拼进命令行。原因：Windows 的命令行
引用规则和 bash 完全不同，脚本里的 `"`、`$`、中文路径都可能被 cmd 或
`CreateProcess` 改掉；用 `ProcessBuilder` 的**列表形式**传短参数、脚本走 stdin，
才能彻底绕开 Windows 的引号地狱。

---

## 9. 改动前 / 改动后对照表

| 方面 | 改动前 | 改动后 |
| --- | --- | --- |
| **执行通道** | 只有 `WslBridge`，到处直接依赖具体类 | `Transport` 接口 + 两个实现，业务类只依赖接口 |
| **通道选择** | 写死 `wsl.exe` | `makeTransport()` 按连接方式返回；SSH 走适配器 |
| **`Runner` 字段** | `private final WslBridge wsl;` | `private final Transport wsl;` |
| **`Runner.execute` 里的 job 绑定** | `job.useBridge(wsl, wsl.getDistro());` | `job.useTransport(wsl, ...)`（`Transport` 上没有 `getDistro`） |
| **`Job.cancel()` 远端终止** | `b.runDetached(killScript(marker), distro)` | SSH：**另开一条连接** `killRemote(marker)`；WSL：保持原做法 |
| **`Job.cancel()` 本地清理** | `p.destroyForcibly()` 杀掉 `wsl.exe` | 保留，但**必须放在远端 kill 之后**（顺序反了就先断了通道） |
| **路径翻译入口** | `translatePaths(raw)` 无条件调用 | `resolveInputPath(o, raw, t)` 按 `Transport` 分流 |
| **Windows 路径（输入）** | `D:\x` → `/mnt/d/x` | WSL 同左；**SSH：上传后返回远端绝对路径** |
| **Windows 路径（输出）** | `D:\out` → `/mnt/d/out` | WSL 同左；**SSH：写成远端工作目录，跑完下载回 `D:\out`** |
| **发行版概念** | `listDistros()` / `isInfrastructureDistro()` / `preferredDistro()` | WSL 分支保留；SSH 分支**不用**，改为连接配置校验 |
| **UI 环境选择** | `JComboBox<String> distroBox` 只列发行版 | WSL：发行版列表；SSH：已保存的连接列表 |
| **UI 连接参数** | 无 | 主机 / 端口 / 用户名 / 私钥 / 标签 + 「测试连接」 |
| **输入文件传递** | WSL 内 `cp` 到工作目录（`/mnt/c` 可直读） | **`scp` 上传**到 `~/vmadapt_work/<label>_<时间戳>/` |
| **端口参数** | 无（WSL 无端口） | `ssh -p` **小写**、`scp -P` **大写** |
| **认证** | 无需（同机） | **密钥登录**；不支持密码（`BatchMode=yes` 无 TTY 可输入） |
| **缓存隔离** | 按发行版名缓存 | 需**按连接身份**（host+port+user）隔离，否则切机器会读到旧缓存 |
| **错误提示** | WSL 相关文案 | 区分「本地连接失败」（exit=255）与「远端命令失败」（exit>0 且 ≠255） |
| **对 BioEnvCaller 的侵入** | — | 改 2 个既有文件（`WslBridge`、`MainPanel`）+ 少量连带类型替换；`虚拟机适配` 目录**零改动** |

---

## 10. 回归测试清单

> **全部未实测**：以下用例是**待执行的清单**，本文档撰写时未在改造后的
> BioEnvCaller 上运行过。

### 10.1 改造后必须依然通过的既有测试

改造是**类型替换为主**，所以既有测试应当全部保持通过。重点跑这些（名称取自
`BioEnvCaller\aidev-project\src\bioenv\test\`）：

| 测试类 | 关注点 |
| --- | --- |
| `DistroFilterTest` | `isInfrastructureDistro` 行为不变（**改造不应影响它**） |
| `WslWorkdirTest` | WSL 工作目录逻辑不变 |
| `BuilderFixesTest` | 路径识别与命令组装不变 |
| `TypedCommandTest` / `TypedValueReachesCommandTest` | 用户手敲命令的路径处理 |
| `AutoCopyInputsTest` | 输入自动拷贝逻辑（SSH 分支要新增对应用例） |
| `StopRunTest` | **取消逻辑**——最关键的回归点，见 10.2 |
| `EmptyStateTest` | 无 WSL 时的空状态提示 |
| `UiSmokeTest` / `MainLayoutTest` | 界面骨架仍能构建 |
| `SelfTest` / `CommandLineTest` | 端到端命令组装 |
| 其余全部 `bioenv.test.*` | **全量跑一遍**，确认零回归 |

### 10.2 必须新增的用例

| # | 用例 | 验证内容 | 类型 |
| --- | --- | --- | --- |
| R1 | **SSH 端口参数大小写** | `ssh` 用 `-p`、`scp` 用 `-P`；断言生成的 argv 里正确的位置 | 纯逻辑，可离线 |
| R2 | **BatchMode 与 accept-new 必须在** | 断言 argv 同时含 `-o BatchMode=yes` 和 `-o StrictHostKeyChecking=accept-new` | 纯逻辑，可离线 |
| R3 | **脚本走 stdin** | 断言 argv 里**不含**脚本内容，且末尾是 `bash -s` | 纯逻辑，可离线 |
| R4 | **Windows 路径在 SSH 下不被翻译成 /mnt/** | 输入 `D:\data\x.fa`，断言结果**不以 `/mnt/` 开头** | 纯逻辑，可离线 |
| R5 | **POSIX 路径在 SSH 下原样保留** | 输入 `/data/x.fa`，断言输出完全相同 | 纯逻辑，可离线 |
| R6 | **缓存按连接隔离** | 连接 A 扫描后切到连接 B，断言不会复用 A 的工具列表 | 纯逻辑（用假 transport） |
| R7 | **marker 真的出现在命令行** | 断言生成的脚本里，被启动进程的命令行含 marker 字符串（`pkill -f` 能命中） | 纯逻辑，可离线 |
| R8 | **`Job.cancel()` 在 SSH 下走 killRemote** | 用假 Transport 记录调用，断言走了「另开连接 kill」而不是只 `destroyForcibly` | 纯逻辑，可离线 |
| R9 | **`killScript` 对空/null marker 返回空** | 既有行为不能退化 | 纯逻辑，可离线 |
| R10 | **连接参数校验** | 端口 0 / 65536 / 非数字、私钥文件不存在、主机为空，都要给出明确错误 | 纯逻辑，可离线 |
| R11 | **传输失败与远端失败可区分** | exit=255（连接失败）与 exit=1（远端命令失败）给出不同提示 | 纯逻辑，可离线 |
| R12 | **真实虚拟机端到端** | 连上真机 → 上传输入 → 跑一个工具 → 回传输出 → 点停止确认远端进程真的没了 | ⚠️ **需要真实虚拟机，当前不具备** |

> R1–R11 全部可以在**没有虚拟机的情况下**离线验证——这是把逻辑与进程启动分离的
> 好处，也正是本模块现有测试的做法。只有 R12 需要真机。

### 10.3 手工验证清单（有真机时）

| # | 步骤 | 期望 |
| --- | --- | --- |
| H1 | 面板选 SSH，填错的主机名 | 明确的「无法解析主机」，**不是卡死** |
| H2 | 填错端口（如 `2222` 但没做转发） | 明确的 `Connection refused`，响应很快 |
| H3 | 不对的私钥 | 明确的 `Permission denied (publickey)`，**不是卡死** |
| H4 | 正确的配置点「测试连接」 | 显示远端 `uname` / `whoami`，响应迅速 |
| H5 | 选一个输入文件跑工具 | 文件出现在 `~/vmadapt_work/<label>_<时间戳>/` |
| H6 | 跑一个长任务，中途点「停止」 | 界面恢复；**在虚拟机里 `top` 确认工具进程真的没了** |
| H7 | 含空格和中文的文件名 | 上传和运行都正常（引号处理正确） |
| H8 | 输出文件 | 跑完能在 Windows 侧看到结果 |
| H9 | 切回 WSL 模式 | 一切与改造前**完全一致**（零回归） |

H6 是**最关键的一条**——它是第 5 节那个 bug 的唯一判据。

---

## 11. 实施建议顺序

按依赖关系排列，每一步都可独立验证：

| 步骤 | 做什么 | 完成标志 |
| --- | --- | --- |
| 1 | 在 BioEnvCaller 侧新增 `Transport` 接口，**只声明方法，不改任何实现** | 能编译 |
| 2 | `WslBridge implements Transport`，补 `describe()` 和单参 `runDetached` | 既有测试**全部通过**（此时 WSL 行为零变化） |
| 3 | 把 `Runner` / `EnvScanner` / `EnvRunner` / `ToolRegistry` 的 `WslBridge` 参数替换为 `Transport` | 既有测试**全部通过** |
| 4 | 写 `SshBridgeAdapter`，先只让 `describe()` / `runBashCapture()` 可用 | 能编译；写 R1–R3 的离线测试 |
| 5 | `MainPanel` 加连接方式选择 + 连接面板 + `makeTransport()` 工厂 | 手工验证 H5（WSL 模式零回归） |
| 6 | 改 `resolveInputPath`，把路径翻译收敛到一个入口 | R4 / R5 通过；WSL 模式下 `BuilderFixesTest` 等仍通过 |
| 7 | 改 `Job.cancel()`，SSH 分支走 `killRemote` | R7 / R8 通过 |
| 8 | 接上 scp 上传/下载 | H4 / H5 / H7 / H8 |
| 9 | 缓存按连接隔离 | R6 |
| 10 | 有真机时跑 R12 / H1–H9 | 全绿 |

**关键原则**：每一步结束时，**WSL 模式必须与改造前完全一致**（步骤 2、3、5 后
各跑一次全量测试）。这样一旦出问题，就能立刻定位到是哪一步引入的——
这也是先抽接口、再接 SSH 的顺序，比一上来就双线改要安全得多。

---

## 附录：涉及的文件清单

| 路径 | 角色 | 本次改造是否修改 |
| --- | --- | --- |
| `BioEnvCaller\aidev-project\src\bioenv\core\WslBridge.java` | 接缝一（唯一碰 ProcessBuilder） | ✅ 改（加 `implements Transport` + 两个小方法） |
| `BioEnvCaller\aidev-project\src\bioenv\ui\MainPanel.java` | 接缝二（决定用哪个桥 + 连接面板） | ✅ 改 |
| `BioEnvCaller\aidev-project\src\bioenv\core\Runner.java` | 类型替换 + `Job.cancel()` 重写 | ✅ 改 |
| `BioEnvCaller\aidev-project\src\bioenv\core\CommandBuilder.java` | 路径解析收敛到 `resolveInputPath` | ✅ 改 |
| `BioEnvCaller\aidev-project\src\bioenv\core\EnvScanner.java` | 类型替换 | ✅ 改（仅类型） |
| `BioEnvCaller\aidev-project\src\bioenv\core\EnvRunner.java` | 类型替换 | ✅ 改（仅类型） |
| `BioEnvCaller\aidev-project\src\bioenv\core\ToolRegistry.java` | 类型替换（若持有 bridge） | ✅ 改（仅类型） |
| `bioenv\core\Transport.java` | 新增接口 | ➕ 新增 |
| `bioenv\core\SshBridgeAdapter.java` | 新增适配器 | ➕ 新增 |
| `虚拟机适配\src\vmadapt\**` | **被复用，不被修改** | ❌ **零改动** |

> **再次强调**：本文档只做说明。撰写时**没有修改 `BioEnvCaller` 目录下的任何
> 文件**，也没有修改 `虚拟机适配` 的既有文件。
