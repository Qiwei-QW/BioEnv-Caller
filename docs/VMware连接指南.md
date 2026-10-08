# VMware 虚拟机 SSH 连接指南

本文档面向**不熟悉网络的用户**，从零开始把 Windows 上的 TBtools 连到 VMware 里的
Linux 虚拟机上，一步一步照着做即可。

> **实测状态声明**
>
> 本机**未安装 VMware Workstation**（执行 `Get-Service VM*` 只看到 Hyper-V 的
> `vmcompute`、`vmic*` 服务，没有 VMware 服务与 VMware 虚拟网卡）。
> 因此：
>
> - 第 3、4、5 节中所有 **VMware 图形界面操作步骤**均为按 VMware 官方通用流程
>   撰写，**未在本机实测**；
> - 第 6、7 节中**客户机内部的 Linux 命令**与**Windows 端 OpenSSH 命令**是标准
>   用法，但也**未在真实虚拟机上端到端验证**。
>
> 本机**已实测**的是：Windows 自带 OpenSSH 存在且版本为
> `OpenSSH_for_Windows_9.5p2, LibreSSL 3.8.2`，`ssh.exe` / `scp.exe` /
> `ssh-keygen.exe` 均在 `C:\WINDOWS\System32\OpenSSH\` 下。

---

## 目录

1. [先理解三件事](#1-先理解三件事)
2. [整体流程](#2-整体流程)
3. [VMware 三种网络模式怎么选](#3-vmware-三种网络模式怎么选)
4. [NAT 模式：添加 2222 → 22 端口转发（详细步骤）](#4-nat-模式添加-2222--22-端口转发详细步骤)
5. [仅主机模式：Windows 防火墙挡 22 端口的排查](#5-仅主机模式windows-防火墙挡-22-端口的排查)
6. [在客户机里确认 sshd 在跑、找到 IP](#6-在客户机里确认-sshd-在跑找到-ip)
7. [配置密钥登录（完整流程）](#7-配置密钥登录完整流程)
8. [在 TBtools 连接面板里怎么填](#8-在-tbtools-连接面板里怎么填)
9. [常见报错对照表](#9-常见报错对照表)
10. [在客户机里安装 Miniconda](#10-在客户机里安装-miniconda)
11. [本方案不支持的登录方式](#11-本方案不支持的登录方式)

---

## 1. 先理解三件事

理解这三件事，后面的步骤就都不会迷惑。

### 1.1 什么是「主机」和「客户机」

| 叫法 | 指什么 | 在本文里 |
| --- | --- | --- |
| 宿主机 / 主机 | 你正在用的这台 Windows 电脑 | 「Windows 端」 |
| 客户机 / 虚拟机 | VMware 里跑的那台 Linux | 「客户机」 |

### 1.2 什么是「端口」和「端口转发」

- 一台机器上可以同时跑很多网络服务，靠**端口号**区分。SSH 服务的默认端口是
  **22**。
- 问题在于：客户机是一台「借住在」你电脑里的独立电脑，它有自己的内网 IP
  （比如 `192.168.xx.xx`）。在 **NAT 模式**下，**Windows 端不能直接访问客户机的
  IP**，必须让 VMware 在中间做一次「转接」。
- 这个转接就叫**端口转发**：把 Windows 上某个端口（比如 `2222`）收到的连接，
  转给客户机的 `22` 端口。

所以：

- **如果做了端口转发 2222 → 22**：Windows 端连 `127.0.0.1:2222`，就等于连上了客户机的 22。
- **如果没做端口转发**：Windows 端直接连客户机 IP 的 22 端口（仅桥接 / 仅主机模式可行）。

### 1.3 填「主机 + 端口」的两条铁律

| 场景 | 主机填什么 | 端口填什么 |
| --- | --- | --- |
| NAT + 端口转发 2222 → 22 | **`127.0.0.1`** 或 `localhost` | **`2222`**（转发出来的那个端口） |
| 桥接模式 | **客户机的 IP**（如 `192.168.1.50`） | **`22`** |
| 仅主机模式 | **客户机的 IP**（通常是 `192.168.xx.xx`） | **`22`**（先过第 5 节的防火墙排查） |

> ⚠️ **最常见的错误**：在 NAT 模式下把客户机的内网 IP（如 `192.168.190.128`）
> 填进「主机」栏。这个 IP 在 Windows 端**根本不通**，一定会超时。
> NAT 模式下**必须**填 `127.0.0.1` + 转发端口。

---

## 2. 整体流程

```
① 客户机装好 openssh-server 并启动        （第 6 节）
② 确认网络模式，确认 Windows 端能连通      （第 3、4、5 节）
③ Windows 端生成密钥并装进客户机           （第 7 节）
④ 用命令行验证能不输密码登录              （第 7.5 节）
⑤ 在 TBtools 连接面板里填主机/端口/用户/私钥（第 8 节）
⑥ 客户机里装 Miniconda，装好分析工具       （第 10 节）
```

第 ④ 步不要跳过。命令行通了，插件一定通；命令行不通，插件不可能通。

---

## 3. VMware 三种网络模式怎么选

在 VMware 里选中虚拟机 → 右键「设置」→「网络适配器」→ 右侧「网络连接」区域。

| 模式 | 通俗解释 | Windows 能否直连客户机 IP | 推荐度 | 主机/端口怎么填 |
| --- | --- | --- | --- | --- |
| **NAT** | 客户机躲在 VMware 后面共享 Windows 的网络，客户机有独立内网 IP | ❌ 不能，必须做端口转发 | ⭐ 推荐 | 主机 `127.0.0.1`，端口 `2222`（转发端口） |
| **桥接（Bridged）** | 客户机像另一台真电脑一样接入你家/公司局域网，有局域网 IP | ✅ 能 | 可用 | 主机 = 客户机 IP，端口 `22` |
| **仅主机（Host-only）** | 客户机只和 Windows 之间有一条私有通道，不连外网 | ✅ 能（但要过防火墙） | 可用 | 主机 = 客户机 IP（通常 `192.168.x.x`），端口 `22` |

### 3.1 该选哪个

- **不确定就选 NAT**：客户机能上网（装 Miniconda 需要），配置靠端口转发，最稳。
- **想少配一步、局域网 IP 固定**：选桥接。缺点是客户机 IP 依赖路由器分配，
  可能变化（可在客户机里设静态 IP，**未实测**）。
- **完全不想让虚拟机碰外网**：选仅主机。缺点是客户机装不了软件，且要处理
  Windows 防火墙（第 5 节）。

### 3.2 改完模式要重启网络

改网络模式后，建议在客户机里重启网络，或直接重启客户机：

```bash
sudo systemctl restart NetworkManager
```

重启客户机（在客户机里执行）：

```bash
sudo reboot
```

> 若客户机用的是老式 `network` 服务而非 NetworkManager：
> `sudo systemctl restart networking`（Debian/Ubuntu 旧版本常见）。

---

## 4. NAT 模式：添加 2222 → 22 端口转发（详细步骤）

> **本节全部为 VMware 图形界面操作，本机未安装 VMware，未实测。**
> 步骤按 VMware Workstation 17 的中文界面通用流程撰写。不同版本菜单文字可能
> 略有差异（例如「编辑」可能是「Edit」），按位置找即可。

### 4.0 前置条件

1. VMware Workstation 已安装并已打开。
2. 虚拟机**已关机**（改虚拟网络设置通常要求关机；开着也可能允许改，但关机最稳）。
3. 虚拟机网络适配器已设为 **NAT**。

### 4.1 打开虚拟网络编辑器

- 在 VMware 主界面顶部菜单栏点 **「编辑」**；
- 在下拉菜单里点 **「虚拟网络编辑器」**。

> 若菜单里是英文：**Edit → Virtual Network Editor**。
> 该菜单项需要**管理员权限**才能修改配置，如果下面的「更改设置」按钮是灰的，
> 请关掉 VMware，右键 VMware 图标 →「以管理员身份运行」再重开。

### 4.2 取得管理员修改权限

在弹出的「虚拟网络编辑器」窗口**右下角**，点 **「更改设置」** 按钮。

- 会弹出 UAC 授权框，点「是」。
- 授权后，窗口上半部分的网络列表变成**可编辑**状态。

> 如果没看到「更改设置」，说明你已经以管理员运行，或者窗口太小被遮挡——
> 把窗口拉大一点。

### 4.3 选中 VMnet8

在上半部分的列表里，单击选中 **`VMnet8`**。

判断哪个是 NAT 网卡的方法：

| 列 | 该行应显示的内容 |
| --- | --- |
| 名称 | `VMnet8` |
| 类型 | **NAT** |
| 外部连接 | `NAT` 或「已连接」 |
| 子网 IP | 形如 `192.168.xxx.0` |

> 如果列表里没有 VMnet8，点「添加网络」按钮，选 VMnet8，类型选 NAT。
> 如果 VMnet8 显示为「仅主机」，把类型改回 NAT。

### 4.4 打开 NAT 设置

保持 VMnet8 选中，点窗口**下方的「NAT 设置」**按钮。
（在部分版本中，这个按钮位于「VMnet 信息」区域的右侧。）

### 4.5 添加端口转发规则

在弹出的「NAT 设置」窗口里：

1. 切换到 **「端口转发」** 选项卡（英文 `Port Forwarding`）；
2. 点 **「添加」** 按钮（英文 `Add`）；
3. 在随后的小窗口里按**下表**逐格填写；
4. 填完点「确定」；
5. 回到「NAT 设置」窗口再点「确定」；
6. 回到「虚拟网络编辑器」窗口再点「确定」/「应用」。

**填写内容：**

| 字段（中文界面） | 字段（英文界面） | 填什么 | 说明 |
| --- | --- | --- | --- |
| 主机端口 | Host port | `2222` | Windows 端用来访问的那个端口 |
| 类型 | Type | `TCP` | SSH 走 TCP |
| 虚拟机 IP 地址 | Virtual machine IP address | 客户机的 IP，如 `192.168.190.128` | 见下方 4.6 怎么查 |
| 虚拟机端口 | Virtual machine port | `22` | SSH 默认端口 |
| 描述 | Description | 例如 `ssh-ubuntu` | 可选，方便以后认出来 |

点「确定」后，列表里应出现一行类似：

```
2222  TCP  192.168.190.128  22  ssh-ubuntu
```

### 4.6 客户机 IP 地址怎么查

启动客户机，登录后在客户机里执行：

```bash
ip a
```

在一堆输出里找**形如 `192.168.` 开头**的那一行，例如：

```
2: ens33: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc fq_codel state UP group default qlen 1000
    link/ether 00:0c:29:xx:xx:xx brd ff:ff:ff:ff:ff:ff
    inet 192.168.190.128/24 brd 192.168.190.255 scope global dynamic ens33
         valid_lft 1712sec preferred_lft 1712sec
```

这里客户机 IP 就是 **`192.168.190.128`**（`inet` 后面、斜杠前面那一段）。

也可以只看四行、更清爽：

```bash
ip -4 addr show
```

或使用 NetworkManager 的命令行界面：

```bash
nmcli device show
nmcli -g IP4.ADDRESS device show
```

> `nmcli` 只有在客户机装了 NetworkManager 时才可用（Ubuntu 桌面版、CentOS/Rocky
> 默认有）。命令不存在就退回用 `ip a`。

### 4.7 注意：客户机 IP 会变

NAT 模式下客户机的 IP 由 VMware 的 DHCP 分配，**重启后可能变化**。
一旦变化，4.5 里填的「虚拟机 IP 地址」就失效了，端口转发会指向一台不存在的机器。

两个应对办法：

| 办法 | 做法 | 说明 |
| --- | --- | --- |
| 固定转发规则用的 IP | 在客户机里给网卡配静态 IP | 需要改客户机的 netplan / NetworkManager 配置（**未实测**） |
| 用 DHCP 保留 | 在「虚拟网络编辑器 → DHCP 设置」里为 MAC 地址保留 IP | VMware 界面操作（**未实测**） |

**排查提示**：如果昨天还能连、今天突然 `Connection refused` 或超时，
**第一件事就是回客户机执行 `ip a` 看 IP 是否变了**。

### 4.8 验证转发是否生效

在 **Windows PowerShell** 里执行（本机已实测 `ssh.exe` 存在；转发规则本身未实测）：

```powershell
& "C:\WINDOWS\System32\OpenSSH\ssh.exe" -p 2222 -o ConnectTimeout=5 bio@127.0.0.1 exit
```

- 出现 `Permission denied (publickey)` → **转发已通**，只是还没配密钥（去第 7 节）。
- 出现 `Connection refused` → 转发没生效，或客户机 `sshd` 没跑（去第 6 节）。
- 出现 `Connection timed out` → 转发规则填错（尤其是虚拟机 IP 填错），回 4.5 检查。

> 建议把这条命令的**回显当成体检表**来用：不同回显对应不同问题，比在界面里瞎点快得多。

---

## 5. 仅主机模式：Windows 防火墙挡 22 端口的排查

仅主机模式下，Windows 端和客户机之间是一条私有通道，客户机 IP 通常是
`192.168.x.x`。**理论上 Windows 能直连客户机的 22 端口**，但经常被 Windows
防火墙拦掉。

> **本节未实测**：本机既未装 VMware，也没有仅主机虚拟网卡，因此下面是在
> 「仅主机环境下遇到连接不上」时的标准排查流程。

### 5.1 先确认到底是哪一层不通

在 **Windows PowerShell** 里，先看这台机器上跟 VMware 有关的网卡：

```powershell
Get-NetAdapter | Where-Object { $_.InterfaceDescription -like "*VMware*" } |
    Select-Object Name, InterfaceDescription, Status
```

- **有输出且 Status 是 `Up`** → 虚拟网卡正常；
- **完全没有输出** → 说明 VMware 虚拟网卡没装好（重装 VMware 或修复安装）。

再看 IPv4 地址和路由：

```powershell
Get-NetIPAddress -AddressFamily IPv4 |
    Select-Object InterfaceAlias, IPAddress, PrefixLength
```

仅主机模式下，你会看到一张 VMware 网卡，它的 IP 和客户机 IP **在同一网段**
（例如 Windows 是 `192.168.190.1`，客户机是 `192.168.190.128`）。

### 5.2 用 Test-NetConnection 定位

在 **Windows PowerShell** 里（把 IP 换成你客户机的真实 IP）：

```powershell
Test-NetConnection -ComputerName 192.168.190.128 -Port 22
```

看输出里的两行关键信息：

| 输出 | 含义 | 下一步 |
| --- | --- | --- |
| `PingSucceeded : True` | 网络层通 | 看下面的 `TcpTestSucceeded` |
| `PingSucceeded : False` | 网络层就不通 | 回 5.1 检查网段；确认客户机在运行 |
| `TcpTestSucceeded : True` | **22 端口通了** | 问题在密钥或 sshd 配置，去第 7 节 |
| `TcpTestSucceeded : False` | 22 端口被拦或 sshd 没跑 | 先回第 6 节确认 sshd 在跑，再按 5.3 放行防火墙 |

> `PingSucceeded : True` 但 `TcpTestSucceeded : False`，是最典型的
> **防火墙拦端口**的特征——网络能通，端口不让过。

### 5.3 放行 Windows 防火墙（仅主机模式）

仅在确认「客户机 `sshd` 确实在跑」之后才做这一步。

**先看现有规则**（Windows PowerShell，需要管理员）：

```powershell
Get-NetFirewallRule -DisplayName "*VMware*" |
    Select-Object DisplayName, Direction, Action, Enabled
```

**放行 VMware 虚拟网卡上到 22 的出站连接**（Windows PowerShell，**以管理员身份**运行）：

```powershell
New-NetFirewallRule -DisplayName "VMware Host-only SSH out (TCP 22)" `
    -Direction Outbound -Protocol TCP -RemotePort 22 -Action Allow
```

> 说明：Windows 防火墙默认**出站放行**，所以多数情况下问题不在出站。
> 如果你或安全软件改过默认策略，这条规则才有意义。

**更常见的拦截来自第三方安全软件**（360、火绒、企业终端安全等）：
它们会静默拦截到 `192.168.x.x` 的连接。排查办法：

1. **临时**退出安全软件，重跑 5.2 的 `Test-NetConnection`；
2. 若此时 `TcpTestSucceeded` 变成 `True`，就确认是它拦的；
3. 在该软件里为该虚拟网段加白名单（具体位置因软件而异，**未实测**）。

### 5.4 从客户机反向验证

在**客户机**里执行，看 sshd 到底有没有在监听：

```bash
ss -tlnp | grep 22
```

若能看到 `0.0.0.0:22` 或 `[::]:22` 且进程是 `sshd`，说明客户机这边没问题，
问题一定在 Windows 侧（防火墙 / 安全软件 / 网段不对）。

### 5.5 仅主机模式的一个先天性限制

仅主机模式下，**客户机没有默认外网路由**，因此：

- 客户机**不能** `apt install`、**不能**下载 Miniconda；
- 解决办法：改用 NAT 模式装软件，装完再切回仅主机；或给客户机加一块 NAT 网卡
  （双网卡，**未实测**）。

所以如果你还需要装 conda 环境，**建议直接用 NAT 模式**。

---

## 6. 在客户机里确认 sshd 在跑、找到 IP

在客户机的终端里执行（或通过 VMware 的虚拟机窗口操作）。

> **本节未实测**：本节命令是标准 Linux 用法，但未在真实虚拟机上执行验证。

### 6.1 确认 sshd 服务状态

```bash
systemctl status sshd
```

**在 Ubuntu / Debian 上，服务名通常是 `ssh` 而不是 `sshd`**，所以要两条都试：

```bash
systemctl status ssh
```

判断方法看输出里的 `Active:` 那一行：

| 输出 | 含义 | 处理 |
| --- | --- | --- |
| `Active: active (running)` | 正在运行 | ✅ 正常，继续 6.2 |
| `Active: inactive (dead)` | 已安装但没启动 | 执行下面的启动命令 |
| `Unit sshd.service could not be found.` | **没装 SSH 服务** | 执行下面的安装命令 |

**启动并设置开机自启**（Ubuntu/Debian，服务名 `ssh`）：

```bash
sudo systemctl start ssh
sudo systemctl enable ssh
```

CentOS / Rocky / AlmaLinux（服务名 `sshd`）：

```bash
sudo systemctl start sshd
sudo systemctl enable sshd
```

**如果没装 SSH 服务**（Ubuntu/Debian）：

```bash
sudo apt update
sudo apt install -y openssh-server
sudo systemctl enable --now ssh
```

CentOS / Rocky / AlmaLinux：

```bash
sudo dnf install -y openssh-server
sudo systemctl enable --now sshd
```

> 只有 NAT 或桥接模式下客户机才能 `apt update` 成功。仅主机模式下会卡在下载，
> 见 5.5。

### 6.2 确认 sshd 真的在监听端口

```bash
ss -tlnp | grep 22
```

参数含义：`-t` 只看 TCP，`-l` 只看监听中，`-n` 不做域名解析（显示纯数字），
`-p` 显示进程名。

**期望输出**（类似这样）：

```
LISTEN 0  128  0.0.0.0:22  0.0.0.0:*  users:(("sshd",pid=812,fd=3))
LISTEN 0  128     [::]:22     [::]:*  users:(("sshd",pid=812,fd=4))
```

**关键看地址部分**：

| 看到什么 | 含义 | 处理 |
| --- | --- | --- |
| `0.0.0.0:22` | 监听所有 IPv4 地址 | ✅ 正常 |
| `[::]:22` | 监听所有 IPv6 地址 | ✅ 正常 |
| `127.0.0.1:22` | **只监听本机回环** | ❌ 外部连不上，见 6.4 |
| 什么都没输出 | 没在监听 | ❌ 回 6.1 |

> 若 `ss` 报 `command not found`，说明客户机是精简系统，装 `iproute2`：
> `sudo apt install -y iproute2`。临时替代命令是 `netstat -tlnp | grep 22`
> （需要 `net-tools` 包）。

### 6.3 找到客户机的 IP

```bash
ip a
```

在输出里找 `inet ` 开头的行（IPv4）。完整示例与判读见本文 [4.6](#46-客户机-ip-地址怎么查)。

只想要纯 IP 列表、更清爽：

```bash
ip -4 -o addr show
```

`-o` 表示单行输出，便于阅读。或用 NetworkManager：

```bash
nmcli -g IP4.ADDRESS device show
```

### 6.4 如果只监听了 127.0.0.1

编辑 sshd 配置：

```bash
sudo nano /etc/ssh/sshd_config
```

找到 `ListenAddress` 这一行（可能有，也可能整行被 `#` 注释掉）：

| 当前内容 | 改成 |
| --- | --- |
| `ListenAddress 127.0.0.1` | 在该行最前面加 `#` 注释掉 |
| `#ListenAddress 0.0.0.0` | 把开头的 `#` 去掉 |
| 完全没有这一行 | 不用改，默认就监听所有地址 |

顺便确认认证相关配置（下一步配密钥要用）：

| 配置项 | 应该是什么 | 说明 |
| --- | --- | --- |
| `PubkeyAuthentication` | `yes`（默认就是） | 允许密钥登录 |
| `PasswordAuthentication` | 密钥配好后可设为 `no` | 本方案不用密码登录 |
| `PermitRootLogin` | 建议 `no` | 用普通用户登录更安全 |

保存退出（`nano` 里是 `Ctrl+O` 回车保存、`Ctrl+X` 退出），然后重启 sshd：

```bash
sudo systemctl restart ssh
```

配置语法有问题会导致重启失败，先校验一下更稳：

```bash
sudo sshd -t
```

没有输出 = 语法正确。

> 服务名按 6.1 的判断用 `ssh` 或 `sshd`。

---

## 7. 配置密钥登录（完整流程）

> **本节未实测**：命令均为 Windows OpenSSH 与 Linux OpenSSH 的标准用法，
> 但未在真实虚拟机上端到端验证。
>
> **为什么必须配密钥**：本方案的每次连接都带 `-o BatchMode=yes`，该选项禁止一切
> 交互式提问（原因见 README 第 3.4 节）。因此**不支持密码登录**——不是实现不
> 到，是刻意的选择：TBtools 是 GUI 进程，它启动的 `ssh.exe` 没有可输入密码的
> 控制台，加 `BatchMode=yes` 会让它在需要密码时**直接失败**，而不是**卡死**。

### 7.1 原理：一把锁和一把钥匙

1. 在 **Windows 端**生成一对密钥：**私钥**（自己留着，绝不外传）+ **公钥**。
2. 把**公钥**的内容，追加到**客户机**的 `~/.ssh/authorized_keys` 文件里。
3. 以后 Windows 端用**私钥**登录，客户机用 `authorized_keys` 里的**公钥**验证，
   匹配就放行，**不需要输密码**。

### 7.2 Windows 端：生成密钥对

在 **Windows PowerShell** 里执行：

```powershell
& "C:\WINDOWS\System32\OpenSSH\ssh-keygen.exe" -t ed25519 -C "tbtools-vm"
```

参数说明：

| 参数 | 含义 |
| --- | --- |
| `-t ed25519` | 密钥类型，比 RSA 更短更安全，Windows OpenSSH 完全支持 |
| `-C "tbtools-vm"` | 注释，只是给人看的备注，方便以后认出这把钥匙是干嘛的 |

执行后会**依次问三个问题**：

```
Generating public/private ed25519 key pair.
Enter file in which to save the key (C:\Users\14231/.ssh/id_ed25519):
Enter passphrase (empty for passphrase to the best of your ability):
Enter same passphrase again:
```

| 问题 | 怎么答 |
| --- | --- |
| 保存路径 | **直接按回车**，用默认的 `C:\Users\<用户名>\.ssh\id_ed25519` |
| 口令（passphrase） | **直接按回车**（留空） |
| 再输一次 | **直接按回车**（留空） |

> ⚠️ **口令必须留空**。原因同上：连接时用 `BatchMode=yes`，ssh 无法弹出输入框
> 问你口令，设了口令就会变成 `Permission denied`。若你确实需要口令保护，则要用
> `ssh-agent` 预先加载（见 7.6）。

生成后目录里会有两个文件：

| 文件 | 是什么 | 能给别人吗 |
| --- | --- | --- |
| `id_ed25519` | **私钥** | ❌ **绝对不能**，泄露等于把虚拟机交出去 |
| `id_ed25519.pub` | **公钥** | ✅ 就是要拷到客户机去的那个 |

确认文件已生成：

```powershell
Get-ChildItem "$env:USERPROFILE\.ssh"
```

### 7.3 把公钥装进客户机

**Windows 上没有 `ssh-copy-id`**（那是 Linux 上的脚本，OpenSSH for Windows 不带它）。
用下面这条等价做法。

在 **Windows PowerShell** 里执行（把 `bio` / `192.168.190.128` / `2222` 换成你的
实际值）：

```powershell
type "$env:USERPROFILE\.ssh\id_ed25519.pub" | & "C:\WINDOWS\System32\OpenSSH\ssh.exe" -p 2222 bio@192.168.190.128 "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

拆开看这段命令做了四件事：

| 片段 | 作用 |
| --- | --- |
| `type "...id_ed25519.pub"` | 在 Windows 端把公钥内容打印到标准输出 |
| `\|` | 管道，把它喂给下一条命令的标准输入 |
| `ssh.exe ... "..."` | 登录客户机并执行引号里的命令 |
| `mkdir -p ~/.ssh` | 确保 `.ssh` 目录存在（`-p` 表示已存在也不报错） |
| `chmod 700 ~/.ssh` | 目录权限设为「只有自己能进」 |
| `cat >> ~/.ssh/authorized_keys` | **追加**标准输入（即公钥）到授权文件 |
| `chmod 600 ~/.ssh/authorized_keys` | 文件权限设为「只有自己能读写」 |

> ⚠️ **这一步会要密码**（此时还没配好密钥，第一次必须用密码登录一次）。
> 所以这一步**必须在客户机的终端里能正常用密码登录**的前提下做。
> 如果客户机禁用了密码登录（`PasswordAuthentication no`），先临时改回 `yes`、
> `sudo systemctl restart ssh`，装完公钥再改回 `no`。

> 关于 `>>` 和 `>` 的区别：`>>` 是**追加**，不会覆盖已有内容。**务必用 `>>`**，
> 用 `>` 会把别人已有的公钥全部删掉。

**替代方案（更省事）**：如果客户机桌面能操作，直接在客户机里执行：

```bash
mkdir -p ~/.ssh && chmod 700 ~/.ssh
nano ~/.ssh/authorized_keys
```

把 Windows 端公钥文件的内容（用记事本打开 `id_ed25519.pub` 全选复制）粘贴进去，
保存退出，然后：

```bash
chmod 600 ~/.ssh/authorized_keys
```

### 7.4 修正权限（关键，最容易出错的一步）

OpenSSH 对权限极其严格，**权限不对会静默拒绝密钥登录**，只报
`Permission denied (publickey)`，不告诉你原因。

在**客户机**里执行：

```bash
chmod 700 ~/.ssh
chmod 600 ~/.ssh/authorized_keys
ls -ld ~/.ssh ~/.ssh/authorized_keys
```

**期望输出**：

```
drwx------ 2 bio bio 4096 Jan  1 12:00 /home/bio/.ssh
-rw------- 1 bio bio  412 Jan  1 12:00 /home/bio/.ssh/authorized_keys
```

权限判读表：

| 路径 | 正确权限 | 数字含义 |
| --- | --- | --- |
| `~/.ssh`（目录） | `drwx------` | `700` = 属主读+写+进入，同组和其他人无权限 |
| `~/.ssh/authorized_keys` | `-rw-------` | `600` = 属主读+写，同组和其他人无权限 |
| 家目录 `~` 本身 | `drwxr-xr-x` 或更严 | 若家目录是 `drwxrwxrwx`，sshd 也会拒绝 |

**家目录权限也要检查**：

```bash
ls -ld ~
```

如果显示 `drwxrwxrwx`（777），改成：

```bash
chmod 755 ~
```

> 另外确认文件属主是你自己：`ls -l ~/.ssh/authorized_keys` 第一列后面应显示
> `bio bio`（和你登录的用户名一致）。若用了 `sudo` 创建导致属主变成 `root`：
> `sudo chown -R $(whoami):$(whoami) ~/.ssh`。

### 7.5 验证：不输密码就能登录

在 **Windows PowerShell** 里执行（用你实际的私钥路径、端口、用户名、IP）：

```powershell
& "C:\WINDOWS\System32\OpenSSH\ssh.exe" -i "$env:USERPROFILE\.ssh\id_ed25519" -p 2222 -o BatchMode=yes -o ConnectTimeout=10 bio@192.168.190.128 "whoami && uname -a"
```

**成功的样子**（直接打印结果，**不出现**任何密码提示）：

```
bio
Linux ubuntu 6.8.0-45-generic #45-Ubuntu SMP ... x86_64 GNU/Linux
```

**把这条命令当作「插件能不能用」的唯一判据**：

| 结果 | 结论 |
| --- | --- |
| 直接打印 `bio` 和 `uname` 输出 | ✅ 全部配好了，可以去第 8 节填插件了 |
| `Permission denied (publickey)` | ❌ 密钥配置有问题，跳第 9.3 条 |
| `Connection refused` | ❌ 网络/端口问题，跳第 9.1 条 |
| `Connection timed out` | ❌ 地址/转发问题，跳第 9.2 条 |

> 命令里 `-o BatchMode=yes` 是**故意加上的**：它和插件用的参数完全一致，
> 所以这条命令通过 = 插件必定通过。如果去掉 BatchMode 能登录、加上就失败，
> 说明你的私钥设了口令，见 7.6。

### 7.6 如果私钥必须设口令：使用 ssh-agent

只有当你有安全要求、必须给私钥设口令时才需要这一步。**未实测**。

Windows 端以**管理员身份**打开 PowerShell，启动 ssh-agent 服务：

```powershell
Set-Service -Name ssh-agent -StartupType Automatic
Start-Service ssh-agent
Get-Service ssh-agent
```

把私钥加进 agent（会提示输入一次口令）：

```powershell
& "C:\WINDOWS\System32\OpenSSH\ssh-add.exe" "$env:USERPROFILE\.ssh\id_ed25519"
```

列出已加载的密钥确认：

```powershell
& "C:\WINDOWS\System32\OpenSSH\ssh-add.exe" -l
```

之后 ssh 连接时会自动向 agent 索取密钥，不再需要交互输密码。

> 注意：ssh-agent 是**按用户会话**运行的。TBtools 若以管理员身份启动，而密钥加载
> 在普通用户会话里，可能读不到——这种情况请统一在同一权限级别下操作。

### 7.7 关于 known_hosts

插件每次连接都带 `-o StrictHostKeyChecking=accept-new`，含义是：

| 情况 | 行为 |
| --- | --- |
| **第一次**连这台机器 | 自动接受并把主机密钥写入 `known_hosts`，不提问 |
| **以后**再连 | 严格比对，**不匹配就拒绝连接** |

这样设计的原因：虚拟机重建、IP 复用是常态，如果设成 `yes` 用户每次都要手工删
`known_hosts`，很烦；设成 `no` 又等于完全关掉中间人防护。`accept-new` 是二者
之间正确的折中。

**如果虚拟机重装过**，主机密钥变了，会报
`Host key verification failed`。解决办法（在 Windows PowerShell 里）：

```powershell
& "C:\WINDOWS\System32\OpenSSH\ssh-keygen.exe" -R "192.168.190.128"
```

`-R` 表示从 `known_hosts` 里删除该主机的记录。之后重新连接即可。

若端口是 2222 转发出来的，也可以用：

```powershell
& "C:\WINDOWS\System32\OpenSSH\ssh-keygen.exe" -R "[127.0.0.1]:2222"
```

---

## 8. 在 TBtools 连接面板里怎么填

| 输入项 | 填写内容 | 示例 |
| --- | --- | --- |
| 主机 | NAT 模式填 `127.0.0.1`；桥接/仅主机填客户机 IP | `127.0.0.1` |
| 端口 | NAT 模式填转发端口；其他填 `22` | `2222` |
| 用户名 | 客户机里的 Linux 用户名（**不是** root，除非你特意配了） | `bio` |
| 私钥 | Windows 上私钥文件的**完整路径** | `C:\Users\14231\.ssh\id_ed25519` |
| 标签 | 给这台机器起个名字，多台机器时用来区分 | `ubuntu-vm` |

> 注意私钥栏填的是 `id_ed25519`（**没有** `.pub` 后缀），公钥是 `.pub` 结尾的那个。

填完点「测试连接」，成功会返回远端的 `uname -a` 与 `whoami`。

> **未实测**：面板的真实渲染与「测试连接」在真实虚拟机上的回显未验证。

---

## 9. 常见报错对照表

### 9.1 Connection refused（连接被拒绝）

```
ssh: connect to host 127.0.0.1 port 2222: Connection refused
```

| 项目 | 内容 |
| --- | --- |
| **现象** | 立刻失败（不是等很久），提示 `Connection refused` |
| **含义** | 「网络通了，但那个端口上没有服务在听」——这是一个**明确、快速**的失败 |
| **原因 1** | 客户机里 `sshd` 没在运行 |
| **原因 2** | 客户机里 sshd 只监听 `127.0.0.1`，没监听对外地址 |
| **原因 3** | NAT 端口转发规则没生效 / 填的端口和实际转发端口不一致 |
| **原因 4** | NAT 转发规则里「虚拟机 IP」填的客户机 IP 已经变了（见 4.7） |
| **解决 1** | 客户机执行 `systemctl status ssh`（Debian 系）或 `systemctl status sshd`；没跑就 `sudo systemctl start ssh` / `sudo systemctl enable --now ssh` |
| **解决 2** | 客户机执行 `ss -tlnp \| grep 22`；若显示 `127.0.0.1:22`，按第 6.4 节改 `/etc/ssh/sshd_config` 并 `sudo systemctl restart ssh` |
| **解决 3** | 回第 4 节重新检查端口转发规则的「主机端口」是否为 `2222`、「虚拟机端口」是否为 `22` |
| **解决 4** | 客户机执行 `ip a` 看当前 IP，与转发规则里的 IP 比对，不一致就改过来 |
| **快速自检** | Windows 端执行 `Test-NetConnection -ComputerName 127.0.0.1 -Port 2222`，看 `TcpTestSucceeded` |

### 9.2 Connection timed out（连接超时）

```
ssh: connect to host 192.168.190.128 port 22: Connection timed out
```

| 项目 | 内容 |
| --- | --- |
| **现象** | 卡住很久（默认可能几十秒）才失败，提示 `Connection timed out` |
| **含义** | 「数据包发出去没有任何回应」——比 `refused` 严重，通常意味着**地址不可达或被丢弃** |
| **原因 1** | **NAT 模式下填了客户机内网 IP**（最常见的错误，见第 1.3 节） |
| **原因 2** | 客户机没开机，或 VMware 里的虚拟机没启动 |
| **原因 3** | VMware 网络适配器被设为「未连接」/ 桥接到了错误的物理网卡 |
| **原因 4** | 防火墙（Windows 防火墙或第三方安全软件）静默丢包 |
| **原因 5** | 客户机 IP 变了，填的是旧 IP |
| **解决 1** | NAT 模式改成填主机 `127.0.0.1` + 转发端口 `2222` |
| **解决 2** | 启动客户机，等待其完全开机（看 VMware 窗口里的登录界面） |
| **解决 3** | VMware → 虚拟机设置 → 网络适配器 → 勾选「已连接」和「启动时连接」 |
| **解决 4** | 按第 5 节排查；先临时退出安全软件验证 |
| **解决 5** | 客户机执行 `ip a` 确认当前 IP |
| **快速自检** | Windows 端 `Test-NetConnection -ComputerName <主机> -Port <端口>`：`PingSucceeded` / `TcpTestSucceeded` 两行的真假组合直接指向原因 |

**`PingSucceeded` 与 `TcpTestSucceeded` 判读表：**

| PingSucceeded | TcpTestSucceeded | 结论 |
| --- | --- | --- |
| True | True | 网络和端口都通，问题在认证 → 看 9.3 |
| True | False | 网络通、端口被拦 → 防火墙或 sshd 没跑 |
| False | False | 地址根本不通 → IP 填错 / 客户机没开 / 网段不对 |

### 9.3 Permission denied (publickey)（密钥被拒绝）

```
bio@192.168.190.128: Permission denied (publickey).
```

| 项目 | 内容 |
| --- | --- |
| **现象** | 网络和端口都通，但被拒绝登录，明确写着 `publickey` |
| **含义** | 「我不接受密码认证，而你的公钥没通过验证」——注意这里**不会**提示输密码，因为连接带了 `BatchMode=yes` |
| **原因 1** | 公钥没装进客户机的 `~/.ssh/authorized_keys`，或装错了用户（装到 root 的家目录去了） |
| **原因 2** | **权限不对**（最常见）：`~/.ssh` 不是 `700`、`authorized_keys` 不是 `600`，或家目录是 777 |
| **原因 3** | 连接时没指定私钥，或用错了私钥文件 |
| **原因 4** | 私钥设了口令（passphrase），而 `BatchMode=yes` 下无法输入 |
| **原因 5** | 客户机 sshd 配置里 `PubkeyAuthentication no` |
| **原因 6** | 追加公钥时误用了 `>`（覆盖）而不是 `>>`（追加） |
| **解决 1** | 客户机执行 `cat ~/.ssh/authorized_keys` 确认里面有内容，且内容以 `ssh-ed25519 ` 开头、结尾是生成时的注释 |
| **解决 2** | 客户机执行 `chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys && chmod 755 ~`，再 `ls -ld ~/.ssh ~/.ssh/authorized_keys` 核对 |
| **解决 3** | 连接命令加 `-i "C:\Users\14231\.ssh\id_ed25519"`；注意是**私钥**（无 `.pub` 后缀） |
| **解决 4** | 重新生成一把**不带口令**的密钥（7.2 节），或按 7.6 节用 ssh-agent 预加载 |
| **解决 5** | 客户机 `/etc/ssh/sshd_config` 里确认 `PubkeyAuthentication yes`，改完 `sudo systemctl restart ssh` |
| **解决 6** | 重新用 `>>` 追加一次公钥 |
| **权威排查** | 让**客户机**端的 sshd 说出真正原因（需要 sudo）：`sudo journalctl -u ssh -n 50 --no-pager`（Debian 系）或 `sudo journalctl -u sshd -n 50 --no-pager`，里面会有 `Authentication refused: bad ownership or modes for file ...` 之类的明确提示 |

### 9.4 Host key verification failed（主机密钥校验失败）

```
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @
@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
Host key verification failed.
```

| 项目 | 内容 |
| --- | --- |
| **现象** | 提示远程主机标识已改变，或直接 `Host key verification failed` |
| **含义** | 「这台主机的指纹和我上次记住的不一样」——可能是虚拟机重装了，也可能是真的中间人攻击 |
| **原因 1** | 虚拟机重装过 / 换了 IP，而该 IP 在 `known_hosts` 里已有旧记录（**最常见**） |
| **原因 2** | 用了端口转发，`[127.0.0.1]:2222` 这台"主机"之前指向的是另一个虚拟机 |
| **原因 3** | 客户机重新生成了主机密钥（`/etc/ssh/ssh_host_*` 被删或重装 sshd 时） |
| **解决** | 确认这台机器**确实是你自己的虚拟机**之后，删掉旧记录再连（命令见 7.7 节） |

**删记录的两种方式**（Windows PowerShell）：

```powershell
# 按主机名/IP 删除
& "C:\WINDOWS\System32\OpenSSH\ssh-keygen.exe" -R "192.168.190.128"

# 端口转发场景按 [主机]:端口 删除
& "C:\WINDOWS\System32\OpenSSH\ssh-keygen.exe" -R "[127.0.0.1]:2222"
```

也可以直接删整个文件（会丢失所有机器记录，下次全部重新接受）：

```powershell
Remove-Item "$env:USERPROFILE\.ssh\known_hosts"
```

> ⚠️ **安全提示**：只有当你能确定那台机器的变化是自己造成的（重装、换 IP），
> 才删记录。若你从未改动过它却出现这个警告，请先查清楚，不要盲目删。

### 9.5 Could not resolve hostname（主机名无法解析）

```
ssh: Could not resolve hostname ubuntu-vm: Name or service not known
```

| 项目 | 内容 |
| --- | --- |
| **现象** | 报 `Could not resolve hostname` / `Name or service not known` |
| **含义** | 「你说的这个名字，我查不到对应的 IP」——连 IP 都没拿到，网络层面还没开始 |
| **原因 1** | 主机栏里填了一个**不存在的主机名**（比如随手打的名字、或者从别处抄来的） |
| **原因 2** | 主机栏里填空了，或者带上了多余的空格、中文全角字符 |
| **原因 3** | 主机栏里误填了完整 URL，如 `http://192.168.1.5` 或 `ssh://...` |
| **原因 4** | 主机名拼写错误（`localhsot`、`127.0.0.1.` 之类） |
| **解决 1** | **优先直接用 IP**：NAT 模式用 `127.0.0.1`，其他模式用 `ip a` 查到的 IP |
| **解决 2** | 清空重填，注意不要有前后空格；输入法切回英文再输 |
| **解决 3** | 去掉 `http://`、`ssh://` 等前缀，只留主机名或 IP |
| **解决 4** | 逐字符核对拼写 |
| **快速自检** | Windows 端 `Resolve-DnsName <主机名>`；若是 IP 则 `Test-Connection <IP>` 看能否 ping 通 |

### 9.6 其他常见情况

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| 点了「测试连接」界面**一直转圈不返回** | 通常不会发生（`ConnectTimeout` 会兜底），但若发生，多为网络设备静默丢包 | 等待 `ConnectTimeout` 秒数到期；检查 IP 是否可达 |
| `ssh: connect to host ... port 22: Network is unreachable` | Windows 端没有到该网段的路由 | 检查 VMware 虚拟网卡是否正常（第 5.1 节） |
| `ssh.exe 不是内部或外部命令` | `ssh.exe` 不在 PATH 里 | 用绝对路径 `C:\WINDOWS\System32\OpenSSH\ssh.exe`，或安装 OpenSSH 客户端可选功能 |
| 客户机执行 `sudo` 时提示需要密码 | 正常行为 | 这是客户机内的操作，与插件无关，按提示输密码 |
| `scp` 报 `not a regular file` | 上传的路径里有目录 | 目录需加 `-r`；插件内部已处理，手工测试时注意 |
| `scp: dest open ... Failure` | 远端目录不存在或无写权限 | 确认远端工作目录已创建且有写权限 |

---

## 10. 在客户机里安装 Miniconda

> **本节未实测**：命令为 Miniconda 官方安装方式，未在真实虚拟机上执行验证。
> 请**手动逐步执行**，插件不会替你自动安装。
>
> **前置条件**：客户机必须能访问外网（**NAT 或桥接模式**）。仅主机模式下会下载
> 失败，见 5.5 节。

### 10.1 下载安装脚本

在**客户机**里执行：

```bash
mkdir -p ~/miniconda-dl && cd ~/miniconda-dl
wget https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh
```

若客户机没装 `wget`：

```bash
sudo apt install -y wget        # Debian / Ubuntu
# 或
sudo dnf install -y wget        # CentOS / Rocky / AlmaLinux
```

也可以用 `curl` 代替：

```bash
curl -O https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh
```

> **架构注意**：`x86_64` 适用于 Intel/AMD 虚拟机。若是 ARM 虚拟机（Apple 芯片
> 宿主上的 VMware Fusion 等），需换成 `Miniconda3-latest-Linux-aarch64.sh`。
> 用 `uname -m` 确认：输出 `x86_64` 用前者，输出 `aarch64` 用后者。

可选：校验下载完整性（**未实测**，若官方页面提供了校验值再比对）：

```bash
sha256sum Miniconda3-latest-Linux-x86_64.sh
```

### 10.2 执行安装

```bash
bash Miniconda3-latest-Linux-x86_64.sh
```

安装向导的交互过程：

| 提示 | 建议操作 |
| --- | --- |
| `Please, press ENTER to continue` | 按回车 |
| 显示许可协议（`--More--`） | 连按空格翻页到末尾 |
| `Do you accept the license terms? [yes\|no]` | 输入 `yes` 回车 |
| `Miniconda3 will now be installed into this location: /home/bio/miniconda3` | 按回车用默认路径 |
| `Do you wish to update your shell profile to automatically initialize conda? [yes\|no]` | 建议 `yes`（省去后面手工初始化） |

静默安装（无人值守，`-b` 表示 batch 模式，自动同意协议）：

```bash
bash Miniconda3-latest-Linux-x86_64.sh -b -p "$HOME/miniconda3"
```

### 10.3 初始化并验证

让 conda 在**新开的** shell 里生效：

```bash
source ~/miniconda3/etc/profile.d/conda.sh
```

或者重新登录一次（退出 SSH 再连），然后验证：

```bash
conda --version
which conda
```

**期望输出**：

```
conda 24.x.x
/home/bio/miniconda3/bin/conda
```

> 若 `conda: command not found`：手动加到 `~/.bashrc`：
>
> ```bash
> echo '. "$HOME/miniconda3/etc/profile.d/conda.sh"' >> ~/.bashrc
> source ~/.bashrc
> ```

### 10.4 配置 conda 镜像（国内网络加速，可选）

清华 TUNA 镜像（**未实测**，仅供参考，请以镜像站最新说明为准）：

```bash
conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/main
conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/free
conda config --set show_channel_urls yes
```

查看当前配置：

```bash
conda config --show channels
```

### 10.5 创建一个分析环境

以创建一个叫 `bioinfo` 的环境为例：

```bash
conda create -n bioinfo -y
conda activate bioinfo
```

在环境里装工具（按需，示例）：

```bash
conda install -y -c bioconda mafft
mafft --version
```

退出环境：

```bash
conda deactivate
```

### 10.6 关于无外网的仅主机模式

若客户机不能上外网，可考虑（均**未实测**）：

1. 先在 NAT/桥接模式下装好 Miniconda 与环境，再切回仅主机模式；
2. 把已装好的 `miniconda3` 目录用 `scp -r` 从另一台机器拷过来（注意路径变化要
   重装或修正 conda 的 base 路径）；
3. 配置内网 conda 镜像。

---

## 11. 本方案不支持的登录方式

| 登录方式 | 是否支持 | 原因 |
| --- | --- | --- |
| **密钥登录（无口令）** | ✅ **支持，且推荐** | `BatchMode=yes` 下无需任何交互 |
| **密钥登录（私钥带口令）** | ⚠️ 需配合 ssh-agent | 前者本身不弹窗；用 ssh-agent 预加载后可用（7.6 节，**未实测**） |
| **密码登录** | ❌ **不支持** | `BatchMode=yes` 禁止一切交互式提问，而没有 TTY 时 ssh 无法输入密码 |
| **键盘交互（keyboard-interactive）** | ❌ 不支持 | 同上，属于交互式认证 |
| **SSH 跳板 / ProxyJump** | ❌ 未实现 | 未纳入本模块范围 |
| **非标准端口** | ✅ 支持 | 端口可自由填写，注意 `ssh` 用小写 `-p`、`scp` 用大写 `-P` |

### 为什么不支持密码登录（完整解释）

1. TBtools 是 GUI 进程。它通过 `ProcessBuilder` 启动 `ssh.exe` 时，子进程**没有
   可用的控制台**，也就没有地方显示密码提示、没有地方接收键盘输入。
2. 如果没有 `BatchMode=yes`，ssh 在需要密码时会尝试打开 `/dev/tty` 或调用
   Windows 的控制台 API 提问。在无控制台的进程里，这一步不是报错，而是**挂起**。
3. 表现出来就是：用户点了「测试连接」，界面**一直没有反应**，既没有成功也没有
   失败。这种"静默卡死"比一个明确的错误信息糟糕得多，用户完全无从下手。
4. 所以本方案选择加上 `BatchMode=yes`：**宁可明确地失败，也不要静默地挂起**。
   失败信息是 `Permission denied (publickey)`，配合本文第 9.3 节的排查表，用户
   能准确知道该去配密钥。

**结论**：首次配置时用密码登录一次（把公钥装进去），此后全部走密钥。

---

## 附：命令速查表

| 目的 | 在哪执行 | 命令 |
| --- | --- | --- |
| 看 ssh 版本 | Windows | `& "C:\WINDOWS\System32\OpenSSH\ssh.exe" -V` |
| 生成密钥 | Windows | `& "C:\WINDOWS\System32\OpenSSH\ssh-keygen.exe" -t ed25519 -C "tbtools-vm"` |
| 装公钥进客户机 | Windows | `type "$env:USERPROFILE\.ssh\id_ed25519.pub" \| & "C:\WINDOWS\System32\OpenSSH\ssh.exe" -p 2222 bio@127.0.0.1 "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"` |
| 验证免密登录 | Windows | `& "C:\WINDOWS\System32\OpenSSH\ssh.exe" -i "$env:USERPROFILE\.ssh\id_ed25519" -p 2222 -o BatchMode=yes bio@127.0.0.1 "whoami && uname -a"` |
| 测端口通不通 | Windows | `Test-NetConnection -ComputerName 127.0.0.1 -Port 2222` |
| 删主机密钥记录 | Windows | `& "C:\WINDOWS\System32\OpenSSH\ssh-keygen.exe" -R "[127.0.0.1]:2222"` |
| 看 sshd 状态 | 客户机 | `systemctl status ssh`（Debian 系）/ `systemctl status sshd` |
| 看 22 端口监听 | 客户机 | `ss -tlnp \| grep 22` |
| 看本机 IP | 客户机 | `ip a` 或 `ip -4 -o addr show` |
| 看网卡信息 | 客户机 | `nmcli device show` |
| 校验 sshd 配置 | 客户机 | `sudo sshd -t` |
| 看认证失败日志 | 客户机 | `sudo journalctl -u ssh -n 50 --no-pager` |
| 修权限 | 客户机 | `chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys` |
| 装 Miniconda | 客户机 | `bash Miniconda3-latest-Linux-x86_64.sh` |
