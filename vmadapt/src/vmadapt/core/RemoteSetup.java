package vmadapt.core;

/**
 * 远端环境准备与检查：这台虚拟机上「能不能跑分析」。
 *
 * <p>为什么只检查、不自动安装：conda 的安装要下载几百 MB，还要用户决定装在
 * 家目录还是 /opt、要不要初始化 shell、用不用国内镜像源。替用户做这些决定，
 * 结果通常是一个他找不到、也不敢删的目录。所以本类只负责<b>如实报告</b>
 * 「有什么、缺什么」，缺 conda 时输出一份可以照着敲的中文分步指引，
 * 把最后一步留给用户。
 *
 * <p>注意这里没有 WSL 那套「基础设施发行版」的概念：虚拟机的用户系统就是
 * 用户自己的系统，不存在 Docker Desktop 那种只为跑引擎而存在的发行版。
 * 相关判断刻意不搬过来。
 */
public final class RemoteSetup {

    private final SshTransport transport;

    /** 上次查询得到的 bash 路径；null 表示还没查过。 */
    private String bashPath;

    /** 上次查询得到的 conda 路径。 */
    private String condaPath;

    /** 上次查询得到的 python 路径。 */
    private String pythonPath;

    /**
     * @param transport 已配置好的传输层
     */
    public RemoteSetup(SshTransport transport) {
        this.transport = transport;
    }

    /**
     * 远端是否有 bash。
     *
     * <p>为什么这条最重要：所有远端脚本都是通过 {@code bash -s} 执行的。
     * 没有 bash 的系统（Alpine、部分容器镜像）连第一条命令都跑不起来，
     * 而且是硬失败——不是「功能少一点」，是「完全不能用」，所以必须能单独问。
     *
     * @return 存在返回 true
     */
    public boolean hasBash() {
        if (bashPath != null) {
            return !bashPath.isEmpty();
        }
        SshTransport.Result r = transport.runBashCapture("command -v bash 2>/dev/null || true\n");
        if (!r.ok()) {
            // 连不上时不能谎报「有 bash」。把路径记为已知的「没有」，
            // 让调用方拿到 false 而不是再次发起失败的连接。
            bashPath = "";
            return false;
        }
        bashPath = r.stdoutTrimmed();
        if (bashPath.isEmpty() && r.exitCode == 0) {
            // 有些极简系统没有 command -v 的返回值但环境里确实有 bash（少见），
            // 再用 which 兜一次；失败也当没有。
            SshTransport.Result again = transport.runBashCapture("which bash 2>/dev/null || true\n");
            if (again.ok()) {
                bashPath = again.stdoutTrimmed();
            }
        }
        return !bashPath.isEmpty();
    }

    /**
     * 远端是否有 conda。
     *
     * <p>不只用 {@code command -v conda}：conda 常见安装方式是「不初始化 shell」，
     * 此时 PATH 里没有 conda，但家目录下确实装了。所以路径探测要覆盖
     * ~/miniconda3、~/anaconda3、/opt/conda 这些标准位置，否则会误报
     * 「没装 conda」，让用户白白重装一遍。
     *
     * @return 存在返回 true
     */
    public boolean hasConda() {
        if (condaPath != null) {
            return !condaPath.isEmpty();
        }
        StringBuilder script = new StringBuilder();
        script.append("p=$(command -v conda 2>/dev/null || true)\n");
        script.append("if [ -z \"$p\" ]; then\n");
        script.append("  for c in \"$HOME/miniconda3/bin/conda\" \"$HOME/anaconda3/bin/conda\" ");
        script.append("\"$HOME/miniforge3/bin/conda\" \"/opt/conda/bin/conda\" \"/opt/miniconda3/bin/conda\"; do\n");
        script.append("    if [ -x \"$c\" ]; then p=\"$c\"; break; fi\n");
        script.append("  done\n");
        script.append("fi\n");
        script.append("printf '%s' \"$p\"\n");
        SshTransport.Result r = transport.runBashCapture(script.toString());
        if (!r.ok()) {
            condaPath = "";
            return false;
        }
        condaPath = r.stdoutTrimmed();
        return !condaPath.isEmpty();
    }

    /**
     * 远端是否有 Python，以及它的版本。
     *
     * <p>报告版本而不是只报「有没有」：很多分析工具对 Python 版本敏感，
     * 用户需要看到实际版本才能判断该不该建新环境。
     *
     * @return 例如 {@code /usr/bin/python3 (3.10.12)}；没有则返回空串
     */
    public String pythonInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("p=$(command -v python3 2>/dev/null || command -v python 2>/dev/null || true)\n");
        sb.append("if [ -n \"$p\" ]; then\n");
        sb.append("  v=$(\"$p\" --version 2>&1 | head -n 1)\n");
        sb.append("  printf '%s (%s)' \"$p\" \"$v\"\n");
        sb.append("fi\n");
        SshTransport.Result r = transport.runBashCapture(sb.toString());
        if (!r.ok()) {
            return "";
        }
        pythonPath = r.stdoutTrimmed();
        return pythonPath;
    }

    /** 上次查询到的 bash 路径；空串表示「确认没有」，null 表示还没查。 */
    public String bashPath() {
        return bashPath;
    }

    /** 上次查询到的 conda 路径；空串表示「确认没有」。 */
    public String condaPath() {
        return condaPath;
    }

    /**
     * 一次问完所有环境问题，返回一份可直接显示的中文报告。
     *
     * <p>为什么合并成一次：三段探测各自一次 SSH 往返，在虚拟机上要好秒级。
     * 一次性跑完，用户点「检查环境」时几乎没有等待感。
     *
     * @return 多行中文文本
     */
    public String checkAll() {
        StringBuilder script = new StringBuilder();
        script.append("p=$(command -v bash 2>/dev/null || true); printf 'BASH=%s\\n' \"$p\"\n");
        script.append("p=$(command -v python3 2>/dev/null || command -v python 2>/dev/null || true)\n");
        script.append("if [ -n \"$p\" ]; then printf 'PYTHON=%s (%s)\\n' \"$p\" \"$(\"$p\" --version 2>&1 | head -n 1)\"; ");
        script.append("else printf 'PYTHON=\\n'; fi\n");
        script.append("p=$(command -v conda 2>/dev/null || true)\n");
        script.append("if [ -z \"$p\" ]; then\n");
        script.append("  for c in \"$HOME/miniconda3/bin/conda\" \"$HOME/anaconda3/bin/conda\" ");
        script.append("\"$HOME/miniforge3/bin/conda\" \"/opt/conda/bin/conda\"; do\n");
        script.append("    if [ -x \"$c\" ]; then p=\"$c\"; break; fi\n");
        script.append("  done\n");
        script.append("fi\n");
        script.append("printf 'CONDA=%s\\n' \"$p\"\n");
        script.append("printf 'ARCH=%s\\n' \"$(uname -m)\"\n");
        script.append("printf 'DISK=%s\\n' \"$(df -h \"$HOME\" 2>/dev/null | tail -n 1 | awk '{print $4}')\"\n");

        SshTransport.Result r = transport.runBashCapture(script.toString());
        if (!r.ok()) {
            String why = transport.diagnose(r);
            if (why == null || why.isEmpty()) {
                why = "Could not connect to the remote host, so the environment check was not run.";
            }
            return "Environment check failed: " + why;
        }

        String bash = "";
        String python = "";
        String conda = "";
        String arch = "";
        String disk = "";
        String[] lines = r.stdoutText().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (k.equals("BASH")) {
                bash = v;
            } else if (k.equals("PYTHON")) {
                python = v;
            } else if (k.equals("CONDA")) {
                conda = v;
            } else if (k.equals("ARCH")) {
                arch = v;
            } else if (k.equals("DISK")) {
                disk = v;
            }
        }
        bashPath = bash;
        pythonPath = python;
        condaPath = conda;

        StringBuilder out = new StringBuilder();
        out.append("Remote environment check\n");
        out.append("  Architecture : ").append(arch.isEmpty() ? "unknown" : arch).append('\n');
        out.append("  Free space   : ").append(disk.isEmpty() ? "unknown" : disk).append(" (home directory)\n");
        out.append("  bash         : ").append(bash.isEmpty() ? "not found (required!)" : bash).append('\n');
        out.append("  Python       : ").append(python.isEmpty() ? "not found" : python).append('\n');
        out.append("  conda        : ").append(conda.isEmpty() ? "not found" : conda).append('\n');
        out.append('\n');
        if (bash.isEmpty()) {
            out.append("Result: unusable. The remote host has no bash, so this plugin's scripts cannot run.\n");
            out.append("      Ubuntu/Debian: sudo apt install bash\n");
            out.append("      Alpine:        apk add bash\n");
        } else if (conda.isEmpty()) {
            out.append("Result: usable, but conda is not installed yet, so the analysis environment is not ready.\n\n");
            out.append(condaInstallGuide());
        } else {
            out.append("Result: environment ready - you can start the analysis.\n");
        }
        return out.toString();
    }

    /**
     * 在虚拟机内安装 Miniconda 的分步指引。
     *
     * <p>只输出文本，不自动执行。前面说过理由：装在哪里、要不要初始化 shell、
     * 用不用国内镜像，都是用户该自己决定的事。指引里给出的默认选择都是
     * 教科书式的保守选项（装在家目录、不自动改 shell 配置）。
     *
     * <p>静态方法：界面在还没建立连接时就要能显示这段文字（用户可能想先看
     * 说明书再动手）。
     *
     * @return 多行英文指引
     */
    public static String condaInstallGuide() {
        StringBuilder sb = new StringBuilder();
        sb.append("How to install Miniconda inside the VM (copy these into the VM's terminal):\n");
        sb.append('\n');
        sb.append("  1) Check that the VM has internet access (NAT or bridged mode recommended):\n");
        sb.append("       ping -c 2 mirrors.tuna.tsinghua.edu.cn\n");
        sb.append('\n');
        sb.append("  2) Download the installer (Tsinghua mirror - much faster inside China):\n");
        sb.append("       cd ~\n");
        sb.append("       wget https://mirrors.tuna.tsinghua.edu.cn/anaconda/miniconda/Miniconda3-latest-Linux-x86_64.sh\n");
        sb.append("     If wget is missing, install it first: sudo apt update && sudo apt install -y wget\n");
        sb.append('\n');
        sb.append("  3) Run the installer (-b for non-interactive, -p to install into miniconda3 under your home directory):\n");
        sb.append("       bash Miniconda3-latest-Linux-x86_64.sh -b -p $HOME/miniconda3\n");
        sb.append('\n');
        sb.append("  4) Initialise conda so that new shells can use it directly:\n");
        sb.append("       $HOME/miniconda3/bin/conda init bash\n");
        sb.append("       source ~/.bashrc\n");
        sb.append('\n');
        sb.append("  5) Switch to the domestic mirrors (otherwise installing bioinformatics software is very slow):\n");
        sb.append("       conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/main\n");
        sb.append("       conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/r\n");
        sb.append("       conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/cloud/bioconda\n");
        sb.append("       conda config --set show_channel_urls yes\n");
        sb.append('\n');
        sb.append("  6) Verify:\n");
        sb.append("       conda --version\n");
        sb.append('\n');
        sb.append("  7) Come back to this plugin and click \"Check environment\" to confirm that conda is detected.\n");
        sb.append('\n');
        sb.append("Note: step 3 installs under your home directory and needs no sudo; if disk space is tight, check it first with df -h ~.\n");
        sb.append("      Miniconda plus a typical bioinformatics environment usually needs 10 GB or more.\n");
        return sb.toString();
    }

    /**
     * 远端没有 conda 时能给出的一句最短建议。
     *
     * <p>给状态栏这类空间有限的地方用；完整指引见 {@link #condaInstallGuide()}。
     */
    public static String condaMissingHint() {
        return "conda was not detected on the remote host - install Miniconda inside the VM first (see the installation guide).";
    }
}
