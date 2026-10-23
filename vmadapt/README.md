# vmadapt —— SSH 传输模块

**中文** ｜ [English](README.en.md)

把 Windows 上的 TBtools 与一台 **Linux 主机（虚拟机 / 云服务器 / 工作站）**接起来的最小传输层。
它只做一件事：**在这台机器上执行命令、拿回输出**。工具发现、参数解析、界面都不在这里。

```
vmadapt\src\vmadapt\
├─ core\                  传输层（无 UI 依赖）
│   ├─ SshTarget          一台主机的描述：别名 / 主机 / 端口 / 用户
│   ├─ SshCommand         ssh 命令行组装（引号、BatchMode、超时）
│   ├─ SshTransport       执行 + 取回 stdout/stderr/退出码
│   ├─ SshAskpass         密码方式的 askpass 辅助
│   ├─ KeyInstaller       生成密钥 / 把公钥追加到 authorized_keys
│   ├─ RemoteFiles        远端目录列举与文件读写
│   ├─ RemoteSetup        首次接入时的远端探测
│   ├─ Reachability       连不通时区分「拒绝 / 超时 / 解析失败」
│   └─ ConnectionStore    已保存配置（JSON）
├─ ui\                    Swing 连接面板与连接向导
└─ test\                  自带测试（纯 main()，不依赖 JUnit）
```

## 和宿主插件的关系

`BioEnv Caller` 把本模块编进**同一个 JAR**，通过 `bioenv.core.SshTransportAdapter` 接入：

- 插件侧只依赖一个很窄的接口（执行命令、列目录、传文件）
- 本模块不知道「工具」「参数」「命令预览」的存在
- 因此它也可以单独被别的插件复用

## 编译

单独编译（在仓库根目录）：

```powershell
# build.ps1 会顺带编译本模块并把产物并入主 JAR
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

产物在 `vmadapt\build\classes`，测试由 `build.ps1` 统一跑（15 条，含实时 SSH 用例的自动跳过逻辑）。

## 设计上的三条边界

1. **只走 `ssh`**：不实现自己的协议，用户能在终端里 `ssh` 通，这里就能通。
2. **不做 PTY**：交互式程序交给「在终端中运行」，本模块只跑非交互命令（`BatchMode=yes`，禁掉一切提问）。
3. **失败要说清是哪一类**：拒绝连接 / 超时 / 解析不了，三种给三种提示 —— 用户按提示就能自己修。
