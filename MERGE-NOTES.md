# 合并说明：BioEnv Caller + SSH 虚拟机适配

本目录是 **BioEnv Caller 的可运行副本**，已把 SSH 远程 Linux 虚拟机通道合并进去。

> **原始 `BioEnvCaller\` 目录一个字都没有改动。** 本次全部改动都发生在本目录内。
> 验证：以本目录创建时刻为基线扫描全工作区，被修改的既有文件数 = **0**；原始树
> 667 个文件最新 mtime 仍是 `09/30 21:11:40`；原始树单独编译仍 `exit 0`（既未被改，
> 也未被耦合进新接口）。

---

## 1. 为什么是"复制一份再合并"

原始插件的 `WslBridge` 是**全项目唯一**碰 `ProcessBuilder` 的类，这是天然的接缝。
但它把"传输"写死成了 WSL：`EnvScanner`、`EnvRunner`、`Runner`、`EnvFingerprint`、
`ToolRegistry` 五个类都直接依赖 `WslBridge` 类型。

要同时支持 WSL 与 SSH，必须在这五个类和传输实现之间插一层接口。既然必然要改这些
文件，就先把整棵源码树复制过来再改——原始版本随时可回到已知good状态。

---

## 2. 核心设计：Transport 接缝

新增 `bioenv.core.Transport` 接口，只声明**真正属于"传输"语义**的 4 个方法：

```java
public interface Transport {
    interface LineSink { void line(String text, boolean isError); }
    interface Result {
        int exitCode();  boolean timedOut();  boolean ok();
        String stdoutText();  String stderrText();
        java.util.List<String> stdout();  java.util.List<String> stderr();
    }
    Result runBashCapture(String script);
    Result runBashStreaming(String script, LineSink sink);
    String getDistro();
    Process runDetached(String script, String distroName) throws IOException;
}
```

两个实现：

| 实现 | 位置 | 说明 |
|---|---|---|
| `WslBridge implements Transport` | 本目录 `bioenv/core/` | 原类加 `implements`，行为零改变 |
| `SshTransportAdapter implements Transport` | 本目录 `bioenv/core/` | 包装 `vmadapt.core.SshTransport` |

### 几个关键设计决策

**① `Result` 必须是接口，不能是 `WslBridge.Result`。**
`WslBridge.Result` 是 `final class`，`SshTransport` 的 `Result` 也是另一个 `final class`；
Java 没有结构化类型，两边无法自动互转。用接口才能让同一段业务代码吃下两种结果。

**② `timedOut()` 而不是 `isTimedOut()`。**
两侧真实字段都叫 `timedOut`，消费者原代码写的是 `r.timedOut`。同名方法让
"字段→访问器"的迁移成为纯机械替换，少一层命名翻译就少一处写错的机会。

**③ `WslBridge` 的返回类型用协变，不改成 `Transport.Result`。**

```java
public Result runBashStreaming(String script, Transport.LineSink sink)
```

返回 `WslBridge.Result`（它是 `Transport.Result` 的子类型）是合法协变返回，好处是
**既有调用点一行都不用改**——例如 `AutoCopyInputsTest:178` 的
`WslBridge.Result r = bridge.runBashCapture(...)` 照常编译。

**④ `WslBridge.LineSink` 保持独立，不能 `extends Transport.LineSink`。**
这是个反直觉的点：`runBashStreaming` 的形参是**逆变位置**，接口要求它能接收
**任意** `Transport.LineSink`。一旦声明继承关系，Java 就要求
`runBashStreaming(String, WslBridge.LineSink)` 去覆盖
`runBashStreaming(String, Transport.LineSink)`，而子接口不能替代父接口**接收**调用，
于是报"未覆盖抽象方法"。正确做法是把三个转发点（`runBashStreaming`、
`pumpStreaming`、`readLinesStreaming`）的形参统一**放宽**为 `Transport.LineSink`。
（此结论经实际构造反例验证过。）

**⑤ 只把"跑命令"放进接口。**
`createWorkDir` / `copyInto` 留在 `WslBridge` 自己身上——它们是通道专有能力，
不是执行语义。SSH 侧对应能力由 `vmadapt.core.RemoteFiles` / `ScpTransfer` 提供，
在 `MainPanel` 里按通道分支接上，**不做降级**。

---

## 3. 改了哪些文件

### 新增（3 个）
| 文件 | 作用 |
|---|---|
| `bioenv/core/Transport.java` | 抽象接缝 |
| `bioenv/core/WslResultAdapter.java` | 把 `WslBridge.Result` 包成 `Transport.Result` |
| `bioenv/core/SshTransportAdapter.java` | 把 vmadapt 的 `SshTransport` 接到接缝上 |

### 改类型（6 个，行为零改变）
| 文件 | 改动 |
|---|---|
| `bioenv/core/WslBridge.java` | 加 `implements Transport`；`Result implements Transport.Result`；补 4 个访问器；3 处形参放宽为 `Transport.LineSink` |
| `bioenv/core/EnvScanner.java` | 字段/构造/`getWsl()` 及 6 处 `Result` → `Transport`；字段式访问改方法式 |
| `bioenv/core/EnvRunner.java` | 同上，10 处 `runBashCapture` |
| `bioenv/core/EnvFingerprint.java` | `compute(Transport, ...)` |
| `bioenv/core/Runner.java` | 字段/构造/`Job.bridge`/`useBridge`/`LineSink` 全部 → `Transport` |
| `bioenv/core/ToolRegistry.java` | 字段 → `Transport` |

### 新增功能（1 个）
| 文件 | 改动 |
|---|---|
| `bioenv/ui/MainPanel.java` | 加「Connection」下拉（WSL / Linux VM）、Linux VM 配置列表；发现流程拆成 `discoverOverWsl` / `discoverOverSsh`；SSH 文案分流；workdir/copier 按通道分支 |

**WSL 路径行为未改变的证据**（不是声称，是可复核的）：
- 13 条 WSL 专属英文提示逐条 grep 命中，逐字保留
- 4 类 WSL 调用点（`listDistros` / `listDistrosUnfiltered` / `preferredDistro` / `new WslBridge`）
  全部收敛在带 SSH 早退闸门的 `loadDistroAsync` 与仅 WSL 走的 `discoverOverWsl` 内
- `modeBox` 默认 index 0 = WSL，构造函数流程与改动前逐行一致
- `WslBridge` 去掉注释空行后与原树逐行 diff，差异**只有**类型关系与 4 个访问器
- 截图确认：默认 WSL 视图仍是 `Connection | WSL | WSL distro | Detect conda environments | Filter`

---

## 3b. 界面英文化与交互调整（后续需求）

用户提出三条界面要求，均已落地并**截图验证**：

**① 页面全英文。**
- `MainPanel`（合并侧）与 `vmadapt.ui.ConnectionPanel` / `MainFrame` 的中文字符串字面量均清零。
  实测：`MainPanel` = 0 个、`src\vmadapt\ui\` = 0 个（校验方式：正则
  `"[^"]*[\u4e00-\u9fff][^"]*"`）。剩余中文只在代码注释/Javadoc 里。
- 技术标识符一律保留原样：`ssh.exe`、`scp.exe`、`ssh-keygen`、`~/.ssh`、
  `authorized_keys`、`systemctl status ssh`、`ss -lntp | grep sshd`、`VMnet1`/`VMnet8`、
  `127.0.0.1`、`2222`、`22`、`chmod 700/600` 等。
- 英文按原生习惯写，不直译：「尚未测试连接。」→ `Not tested yet.`；
  「复制公钥到远端」→ `Copy public key to VM`；「网络模式速查」→ `Network modes`。

**② 命名从 "SSH 虚拟机" 改为 "Linux VM"。**
模式下拉现在是 `WSL` / `Linux VM`；对话框标题 `Linux VM Connection Profile`；
源下拉标签在 Linux VM 模式下为 `Saved profile`（WSL 模式下仍是 `WSL distro`）。

**③ 「配置连接」独立按钮移除，选择 Linux VM 时自动弹出。**
- 发现栏不再有 configure 按钮（现已实测残留引用 = 0）。
- 切到 `Linux VM` → `refreshSshList()` 在后台读取已保存配置 → 若**一个都没有**，
  其 `done()` 直接调用 `openConnectionDialog()`，因为此时"请先选一个"是死路。
- 已有配置时**不**弹窗，只把列表填好并提示，避免每次切模式都被窗口打断。
- 防重入：新增 `adjustingMode` 标志。切换时会清空并重填源下拉框，而填下拉框会触发它
  自己的 ActionListener；没有这个标志，程序化的"选择变化"会被误当成用户选了配置而再次弹窗。
  同时 `distroBox` 的监听器现在同时检查 `fillingDistros || adjustingMode`。

**④ 顺带修掉的健壮性问题（我引入的代码）。**
自动弹窗若在无显示环境（headless JVM）被触发，`new JDialog(...)` 会抛
`HeadlessException`，而它是在 `ActionListener` / `SwingWorker.done()` 里抛出的——
会直接打断事件线程。`openConnectionDialog()` 现在先查
`GraphicsEnvironment.isHeadless()`，无显示时只写一行状态说明并返回。

**验证方式**（可复跑）：
- 行为测试（无头，10/10 PASS）：模式项 = `[WSL, Linux VM]`、无 configure 按钮、
  中文字符串清零、标签随模式切换、无头下自动弹窗优雅降级不崩溃。
- 行为测试（有显示，4/4 PASS）：等待 `SwingWorker` 完成后断言对话框**确实自动打开**、
  标题为 `Linux VM Connection Profile`、`isShowing()` 为真、内容面板是英文 `ConnectionPanel`。
- 截图：`dialog.png`（英文配置窗口）、`main_lvm.png`、`main_wsl.png`。
- 构建回归：23/23 全绿，`exit 0`。

> 注：断言"有显示时自动弹窗"必须**等待后台 worker 完成**——弹窗发生在
> `SwingWorker.done()` 里，切换后立刻断言会失败。这一点我第一版测试没做对。

---

## 4. 两个真实修掉的问题

**① `Job.cancel()` 在 SSH 下会留下孤儿进程。**
本地杀掉 `ssh.exe` 不影响远端进程树。现在 `Runner.Job` 只调
`Transport.runDetached(killScript, distro)`，两条通道各自实现：WSL 走 `wsl.exe`，
SSH 开一条独立 ssh 连接执行 `pkill -f <marker>`。`Job` 内部**零 SSH 特判**。

**② 状态栏文案的连线竞态**（本次合并中由我修掉）。
`applyDiscoveryReport` 原先用实时的 `isSshMode()` 判文案。但它在 worker 的 `done()`
里执行，用户可能在扫描途中切换了连接方式——于是"刚跑完的 SSH 失败"会被写成 WSL 文案
（或反之）。现在通道由调用方显式传入（`dr.sshTarget != null`），描述的是**这次运行
真正用的通道**。

---

## 5. 如何构建与验证

```powershell
cd 'C:\Users\14231\Desktop\TBtools插件\虚拟机适配\merged'
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

构建脚本会自动：
1. 先用 `D:\TBtools\jre` 的 javac 11.0.6 编译 `虚拟机适配\src`（vmadapt 模块）；
2. 再编译本目录源码（classpath 带上 vmadapt 与 TBtools 的真实 JAR）；
3. 校验所有 class 都是 `major 55`（Java 11）——TBtools 自带 JRE 拒绝更高版本；
4. 跑 **23 个回归测试**（原插件自带的那套，任何一条挂掉都说明合并改变了既有行为）；
5. 打包成 `BioEnvCaller.plugin`，并把 vmadapt 的 class 一并打进 JAR，
   使插件**自包含**、不依赖外部 vmadapt 目录。

当前实测结果：

```
Compiling 11 vmadapt source file(s)...
Compiling 99 source file(s) against D:\TBtools\TBtools_JRE1.6.jar + vmadapt
PASS  all class files are Java 11 (major 55)
Regression: 23 passed, 0 failed
 Built: BioEnvCaller.plugin
```

产物校验：JAR 内 213 条目 / 204 个 class，全部 `major 55`；
`vmadapt` 42 个 class 已打入；**测试类泄漏 = 0**；`Plugin/PluginInterface.class`
已正确剔除（TBtools 运行时自己提供）。

### 单独看界面

```powershell
& 'D:\TBtools\jre\bin\java.exe' -cp '.\build\classes;..\build\classes' vmadapt.ui.MainFrame
```

---

## 6. 还没验证的部分

- **真实 SSH 握手、scp 字节流、远端 `pkill` 是否真能杀掉进程** —— 本机无虚拟机、
  22 端口无 sshd，全部未实测。vmadapt 侧文档已标注 27 处「未实测」。
- **界面真实渲染**：合并后的 `MainPanel` 只能在 TBtools 里跑；本机验证到
  「headless 下可构造 + 模式切换不阻塞 EDT」为止。
- **WSL 真机路径**：只验证到 `EmptyStateTest` 的「WSL 不可达」分支，
  真实 WSL 环境下的完整跑通未测。

---

## 7. 想装进 TBtools

把 `BioEnvCaller.plugin` 解压后的 `BioEnv Caller` 文件夹放到
`D:\TBtoolsDir\.Plugin\` 下，重启 TBtools，从 `AI+` 菜单打开。

打开后顶部多了一个「连接方式」下拉：

- **WSL**（默认）——行为与原来完全一致
- **SSH 虚拟机** —— 点「配置连接…」填主机/端口/用户名/私钥，保存后从下拉里选，
  再点 Detect

SSH 走**密钥认证**（`BatchMode=yes` 禁止交互提问，没有可输密码的地方）；
NAT 模式记得先在 VMware 虚拟网络编辑器里加 2222→22 端口转发。详见
`..\docs\VMware连接指南.md`。
