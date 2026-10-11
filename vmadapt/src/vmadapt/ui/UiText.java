package vmadapt.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * vmadapt 界面的双语文案表。
 *
 * <p>这里存放所有面向用户的文字，中英各一份，用键名索引。
 * 单独成类的原因：文案集中在一处才看得见全貌，也才有可能交给别人校对；
 * 散落在几十个 {@code new JButton("...")} 里时，漏译和口径不一致是必然的。
 *
 * <h2>回退顺序</h2>
 *
 * <p>{@link #get(String, String)} 的回退顺序是
 * 「按语言取 → 英文 → 键名本身」。最后回退到键名是为了让漏译一眼可见
 * （界面上出现 {@code btn.test} 这种字样，而不是一片空白或 "null"）。
 *
 * <h2>技术标识符不译</h2>
 *
 * <p>{@code ssh.exe}、{@code ~/.ssh}、{@code authorized_keys}、
 * {@code 127.0.0.1}、{@code VMnet1}、{@code 2222}、{@code chmod 700} 等
 * 命令、路径、端口、权限数字在两种语言里<b>原样保留</b>，这不是偷懒：
 * 用户要照着这些字面量敲进终端，翻译它们等于制造错误。
 */
final class UiText {

    /** 语言代码：中文。 */
    static final String ZH = "zh";

    /** 语言代码：英文。 */
    static final String EN = "en";

    /** 英文文案。 */
    private static final Map<String, String> EN_MAP = new HashMap<String, String>();

    /** 中文文案。 */
    private static final Map<String, String> ZH_MAP = new HashMap<String, String>();

    private UiText() {
        // 纯静态工具类。
    }

    /**
     * 取文案。
     *
     * @param key    键名
     * @param code   语言代码，{@code "zh"} 表示中文，其余（含 null）按英文处理
     * @return 文案；永不返回 null
     */
    static String get(String key, String code) {
        if (key == null) {
            return "";
        }
        if (ZH.equals(code)) {
            String zh = ZH_MAP.get(key);
            if (zh != null) {
                return zh;
            }
        }
        String en = EN_MAP.get(key);
        if (en != null) {
            return en;
        }
        String zh = ZH_MAP.get(key);
        if (zh != null) {
            return zh;
        }
        // 两边都没有：回退到键名。难看，但可搜索、能一眼认出是漏译。
        return key;
    }

    /** 该键是否同时有中英两版（自检用）。 */
    static boolean hasBoth(String key) {
        return EN_MAP.containsKey(key) && ZH_MAP.containsKey(key);
    }

    /** 全部键名（自检用）。 */
    static java.util.Set<String> keys() {
        java.util.Set<String> all = new java.util.TreeSet<String>();
        all.addAll(EN_MAP.keySet());
        all.addAll(ZH_MAP.keySet());
        return all;
    }

    private static void en(String key, String text) {
        EN_MAP.put(key, text);
    }

    private static void zh(String key, String text) {
        ZH_MAP.put(key, text);
    }

    /** 中英一起登记，保证两边键名不会写歪。 */
    private static void put(String key, String english, String chinese) {
        EN_MAP.put(key, english);
        ZH_MAP.put(key, chinese);
    }

    // ==================================================================
    // 标签页
    // ==================================================================

    // 只有两页。「连接结果」那一页已删除：结果改由弹窗呈现，见
    // ConnectionPanel 构造函数里加标签页处的说明。
    static final String TAB_CONNECTION = "tab.connection";
    static final String TAB_NETWORK = "tab.network";

    static final String TAB_CONNECTION_TIP = "tab.connection.tip";
    static final String TAB_NETWORK_TIP = "tab.network.tip";

    // ==================================================================
    // 表单标签
    // ==================================================================

    static final String LBL_HOST = "lbl.host";
    static final String LBL_PORT = "lbl.port";
    static final String LBL_USER = "lbl.user";
    static final String LBL_NAME = "lbl.name";

    // ==================================================================
    // 字段悬停提示
    // ==================================================================


    // ==================================================================
    // 输入框占位提示
    // ==================================================================


    // ==================================================================
    // 按钮
    // ==================================================================

    static final String BTN_SAVE = "btn.save";


    // ==================================================================
    // 区块标题与提示
    // ==================================================================


    /**
     * 第一页的三个分组标题。
     *
     * <p>分组是为了回答"我现在在做哪一步"：一张表上八行输入加七个按钮，用户得自己
     * 判断先填什么、能点哪个。分开之后每组只讲一件事——这台机器是什么、怎么登录、
     * 这条配置叫什么。
     */

    /** 按钮：测试连接进行中的文案。 */
    static final String BTN_TESTING = "btn.testing";

    /** 临时：评审用布局变体的文案。选定后保留需要的那些。 */

    /** 按钮：设置密钥（生成 + 安装到远端，一步完成）。 */

    /** 错误：设置密钥时表单还没填全。 */
    static final String MSG_SETUP_NEED_FORM = "msg.setupNeedForm";

    /** 主面板：唯一入口按钮「连接虚拟机…」。 */
    static final String BTN_CONNECT = "btn.connect";
    static final String BTN_CONNECT_TIP = "btn.connect.tip";

    /** 主面板：还没有配置任何机器。 */
    static final String PANEL_NO_TARGET = "panel.noTarget";
    static final String PANEL_NO_TARGET_HINT = "panel.noTargetHint";
    static final String PANEL_READY_HINT = "panel.readyHint";

    // 向导各步的标题、提示与按钮。
    static final String DIALOG_CONNECTION_TITLE_WIZARD = "dialog.wizardTitle";
    static final String WIZ_HOST_HINT = "wiz.host.hint";
    static final String WIZ_PORT_HINT = "wiz.port.hint";
    static final String WIZ_USER_HINT = "wiz.user.hint";
    static final String WIZ_PWD_HINT = "wiz.pwd.hint";
    static final String WIZ_WORKING = "wiz.working";
    static final String WIZ_NAME_NOTE = "wiz.name.note";
    static final String WIZ_FAIL_HINT = "wiz.fail.hint";
    static final String WIZ_OLD_SSH_TITLE = "wiz.oldSsh.title";
    static final String WIZ_OLD_SSH_REASON = "wiz.oldSsh.reason";
    static final String WIZ_NEED_HOST = "wiz.needHost";
    static final String WIZ_NEED_USER = "wiz.needUser";
    static final String WIZ_NEED_PASSWORD = "wiz.needPassword";
    static final String BTN_NEXT = "btn.next";
    static final String BTN_BACK = "btn.back";
    static final String BTN_START = "btn.start";
    static final String BTN_CANCEL = "btn.cancel";
    static final String BTN_CLOSE = "btn.close";
    static final String BTN_SKIP = "btn.skip";

    /** 密码那一行的标签。 */
    static final String LBL_PASSWORD = "lbl.password";

    /**
     * 密钥须知：密钥那一步的唯一内容。
     *
     * <p>这一步不填任何东西，只说明接下来会发生什么，所以文案要完整：会生成什么、
     * 私钥留在哪、公钥去哪、以及点哪个按钮继续。
     */
    static final String WIZ_KEY_NOTICE = "wiz.keyNotice";

    /** 选择私钥文件的对话框。 */

    // ==================================================================
    // 状态区
    // ==================================================================

    static final String STATUS_NOT_TESTED = "status.nottested";
    static final String STATUS_INTRO = "status.intro";

    // ==================================================================
    // 连接过程
    // ==================================================================

    static final String MSG_NULL_REPORT = "msg.nullreport";
    static final String MSG_NULL_REPORT_REASON = "msg.nullreport.reason";
    static final String MSG_CONNECTED = "msg.connected";
    static final String MSG_CONNECTED_SHORT = "msg.connected.short";
    static final String MSG_CONNECT_FAILED = "msg.connectfailed";
    static final String MSG_NO_REASON = "msg.noreason";

    static final String RPT_OS = "rpt.os";
    static final String RPT_KERNEL = "rpt.kernel";
    static final String RPT_HOME = "rpt.home";
    static final String RPT_UNRECOGNIZED = "rpt.unrecognized";
    static final String RPT_NOTES = "rpt.notes";
    static final String RPT_SUGGESTIONS = "rpt.suggestions";
    static final String RPT_NEXT = "rpt.next";
    static final String RPT_REASON = "rpt.reason";
    static final String RPT_UNKNOWN = "rpt.unknown";
    static final String RPT_SEE_NETWORK = "rpt.seenetwork";
    static final String RPT_RAW = "rpt.raw";

    static final String TIP_FALLBACK_1 = "tipfallback.1";
    static final String TIP_FALLBACK_2 = "tipfallback.2";
    static final String TIP_FALLBACK_3 = "tipfallback.3";
    static final String TIP_FALLBACK_4 = "tipfallback.4";
    static final String TIP_FALLBACK_5 = "tipfallback.5";

    static final String ERR_PORT_NUMBER = "err.portnumber";
    static final String ERR_TIMEOUT_NUMBER = "err.timeoutnumber";

    // ------------------------------------------------------------------
    // 连接参数校验（ConnectionPanel.validateLocalized）
    //
    // vmadapt.core.SshTarget.validate() 返回的是固定英文（core 是纯逻辑层，
    // 不认识界面语言），这里按同样的条件给出本地化文案。
    // ------------------------------------------------------------------

    static final String VAL_HOST_EMPTY = "val.hostempty";
    static final String VAL_HOST_SPACE = "val.hostspace";
    static final String VAL_HOST_PREFIX = "val.hostprefix";
    static final String VAL_HOST_AT = "val.hostat";
    static final String VAL_USER_EMPTY = "val.userempty";
    static final String VAL_PORT_RANGE = "val.portrange";
    static final String VAL_TIMEOUT_RANGE = "val.timeoutrange";
    static final String VAL_KEY_MISSING = "val.keymissing";

    // ==================================================================
    // 保存 / 删除 / 载入配置
    // ==================================================================

    static final String MSG_SAVE_FAILED = "msg.savefailed";
    static final String MSG_SAVE_REJECTED = "msg.saverejected";
    static final String MSG_SAVE_REJECTED_REASON = "msg.saverejected.reason";
    static final String MSG_SAVED_TITLE = "msg.saved.title";
    static final String MSG_SAVED_BODY = "msg.saved.body";


    // ==================================================================
    // 密钥生成
    // ==================================================================

    static final String MSG_NO_KEYGEN = "msg.nokeygen";
    static final String MSG_NO_KEYGEN_REASON = "msg.nokeygen.reason";
    static final String MSG_NO_HOME = "msg.nohome";
    static final String MSG_NO_HOME_REASON = "msg.nohome.reason";
    static final String MSG_KEY_EXISTS = "msg.keyexists";
    static final String MSG_KEY_EXISTS_TITLE = "msg.keyexists.title";
    static final String MSG_KEY_DELETE_FAILED = "msg.keydeletefailed";
    static final String MSG_GEN_RUNNING = "msg.genrunning";
    static final String MSG_GEN_RUNNING_BODY = "msg.genrunning.body";
    static final String MSG_GEN_OUTFILES = "msg.gen.outfiles";
    static final String MSG_MKDIR_FAILED = "msg.mkdirfailed";
    static final String MSG_GEN_FAILED = "msg.genfailed";
    static final String MSG_GEN_FAILED_CODE = "msg.genfailed.code";
    static final String MSG_GEN_OK_TITLE = "msg.genok.title";
    static final String MSG_GEN_OK_BODY = "msg.genok.body";
    static final String MSG_GEN_PRIVKEY = "msg.gen.privkey";
    static final String MSG_GEN_PUBKEY = "msg.gen.pubkey";
    static final String MSG_GEN_PASSPHRASE = "msg.gen.passphrase";
    static final String MSG_GEN_TAIL = "msg.gen.tail";
    static final String MSG_GEN_OUTPUT = "msg.gen.output";

    static final String TIP_KEYGEN_1 = "tipkeygen.1";
    static final String TIP_KEYGEN_2 = "tipkeygen.2";
    static final String TIP_KEYGEN_3 = "tipkeygen.3";
    static final String TIP_KEYGEN_4 = "tipkeygen.4";

    // ==================================================================
    // 复制公钥
    // ==================================================================

    static final String MSG_INCOMPLETE_COPY = "msg.incompletecopy";
    static final String MSG_NO_PRIVKEY = "msg.noprivkey";
    static final String MSG_NO_PRIVKEY_REASON = "msg.noprivkey.reason";
    static final String MSG_NO_PUBFILE = "msg.nopubfile";
    static final String MSG_NO_PUBFILE_REASON = "msg.nopubfile.reason";
    static final String MSG_NO_SSH = "msg.nossh";
    static final String MSG_NO_SSH_REASON = "msg.nossh.reason";
    static final String MSG_COPYING = "msg.copying";
    static final String MSG_COPYING_BODY = "msg.copying.body";
    static final String MSG_READ_PUB_FAILED = "msg.readpubfailed";
    static final String MSG_PUB_EMPTY = "msg.pubempty";
    static final String MSG_COPY_FAILED = "msg.copyfailed";
    static final String MSG_COPY_FAILED_CODE = "msg.copyfailed.code";
    static final String MSG_COPY_OK_TITLE = "msg.copyok.title";
    static final String MSG_COPY_OK_BODY = "msg.copyok.body";
    static final String MSG_CMD_OUTPUT = "msg.cmdoutput";

    static final String TIP_COPY_1 = "tipcopy.1";
    static final String TIP_COPY_2 = "tipcopy.2";
    static final String TIP_COPY_3 = "tipcopy.3";
    static final String TIP_COPY_4 = "tipcopy.4";
    static final String TIP_COPY_5 = "tipcopy.5";

    // ==================================================================
    // 执行失败
    // ==================================================================


    /** 只当前缀用的短句，拼在异常或命令前面。 */
    static final String PFX_EXEC_FAILED = "pfx.execfailed";
    static final String PFX_EXEC_CMD = "pfx.execcmd";

    // ==================================================================
    // 独立验证窗口（MainFrame）
    // ==================================================================

    static final String FRAME_TITLE = "frame.title";
    static final String FRAME_HEAD_TITLE = "frame.head.title";
    static final String FRAME_HEAD_SUB = "frame.head.sub";
    static final String FRAME_GUIDE_BTN = "frame.guide.btn";
    static final String FRAME_GUIDE_TITLE = "frame.guide.title";
    static final String FRAME_GUIDE_FAILED = "frame.guide.failed";
    static final String FRAME_GUIDE_EMPTY = "frame.guide.empty";
    static final String FRAME_ENV = "frame.env";
    static final String FRAME_ENV_NO_SSH = "frame.env.nossh";

    // ==================================================================
    // 命令行用法与无头提示（MainFrame）
    // ==================================================================

    static final String USAGE_TITLE = "usage.title";
    static final String USAGE_LINE1 = "usage.line1";
    static final String USAGE_OPTS = "usage.opts";
    static final String USAGE_HOST = "usage.host";
    static final String USAGE_PORT = "usage.port";
    static final String USAGE_USER = "usage.user";
    static final String USAGE_KEY = "usage.key";
    static final String USAGE_NAME = "usage.name";
    static final String USAGE_HELP = "usage.help";
    static final String USAGE_EXAMPLE = "usage.example";

    static final String HL_BANNER = "hl.banner";
    static final String HL_TITLE = "hl.title";
    static final String HL_BODY = "hl.body";
    static final String HL_NOT_ERROR = "hl.noterror";
    static final String HL_CAUSES = "hl.causes";
    static final String HL_CAUSE_1 = "hl.cause1";
    static final String HL_CAUSE_2 = "hl.cause2";
    static final String HL_CAUSE_3 = "hl.cause3";
    static final String HL_TESTS = "hl.tests";
    static final String HL_TESTS_EXAMPLE = "hl.tests.example";
    static final String HL_RETRY = "hl.retry";

    // ==================================================================
    // Network modes 整段
    // ==================================================================

    static final String NET_HELP = "net.help";

    static {
        // 注意：Network modes 整段（NET_HELP_EN / NET_HELP_ZH）的登记**不在这里**。
        // 那两个字段声明在文件末尾（static 块之后），而在初始化器里引用声明在后面
        // 的字段属于「非法前向引用」，javac 会直接报错：它们不是编译期常量。
        // 它们的 put(...) 放在字段声明之后（见文件末尾）。
        put(TAB_CONNECTION, "Connection", "连接配置");
        put(TAB_NETWORK, "Network modes", "网络模式速查");

        put(TAB_CONNECTION_TIP,
                "Connection settings: host, port, user, key, profile name.",
                "连接参数：主机、端口、用户名、密钥路径、配置名称。");
        put(TAB_NETWORK_TIP,
                "How VMware NAT, bridged and host-only modes affect SSH.",
                "VMware 的 NAT、桥接、仅主机三种模式对 SSH 连接的影响。");

        put(LBL_HOST, "Host / IP:", "主机 / IP：");
        put(LBL_PORT, "Port:", "端口：");
        put(LBL_USER, "User:", "用户名：");
        put(LBL_NAME, "Profile name:", "配置名称：");


        put(BTN_SAVE, "Save", "保存配置");


        put(BTN_TESTING, "Connecting...", "正在连接…");


        put(MSG_SETUP_NEED_FORM, "Set up key needs these first:",
                "要设置密钥，请先填好：");

        put(BTN_CONNECT, "SSH connection...", "SSH 连接…");
        put(BTN_CONNECT_TIP,
                "Opens a step-by-step wizard: address, port, user, key, "
                        + "then connect and save.",
                "打开一步步的向导：地址、端口、用户名、密钥，然后连接并保存。");
        put(PANEL_NO_TARGET, "No SSH connection configured yet",
                "还没有配置 SSH 连接");
        put(PANEL_NO_TARGET_HINT,
                "Click 'SSH connection...' to set one up. It takes five short steps.",
                "点「SSH 连接…」开始配置，一共五步。");
        put(PANEL_READY_HINT,
                "Click 'SSH connection...' to test the connection or switch machine.",
                "点「SSH 连接…」可以测试连接，或换一台机器。");

        put(DIALOG_CONNECTION_TITLE_WIZARD, "SSH connection",
                "SSH 连接配置");
        put(WIZ_HOST_HINT,
                "All three network modes work the same way here: enter the target's own IP "
                        + "with port 22. Run ip addr inside the VM to get it.\n"
                        + "  Bridged: the VM has its own address on your LAN.\n"
                        + "  Host-only: the VM is on the VMnet1 subnet (and has no "
                        + "internet in this mode).\n"
                        + "  NAT: the VM is on the VMnet8 subnet. The host can reach it "
                        + "directly as long as it has the VMnet8 adapter, which it has "
                        + "by default.\n"
                        + "Port forwarding is only a fallback for when the host cannot "
                        + "reach that subnet: forward the VM's port 22 to a host port, "
                        + "then enter 127.0.0.1 and that port here instead.",
                "三种网络模式在这里填法是一样的：填目标机器自己的 IP，端口 22。"
                        + "在虚拟机里执行 ip addr 就能查到。\n"
                        + "  桥接：虚拟机在你所在局域网里有独立 IP。\n"
                        + "  仅主机：虚拟机在 VMnet1 网段（这种模式下虚拟机没有外网）。\n"
                        + "  NAT：虚拟机在 VMnet8 网段。只要宿主机有 VMnet8 适配器"
                        + "（默认就有）就能直接连它。\n"
                        + "端口转发只是备选，用在宿主机到不了那个网段的时候："
                        + "把虚拟机的 22 端口转发到宿主机某个端口，这里改填 127.0.0.1 和那个端口。");
        put(WIZ_PORT_HINT,
                "SSH is 22 unless you changed it in the target's sshd config.",
                "SSH 默认是 22。只有在目标机器上改过 sshd 配置时才需要改这里。");
        put(WIZ_USER_HINT,
                "The login name on the target - the one you set when installing it, "
                        + "often ubuntu.",
                "目标机器里你的登录名。装系统时设的那个，常见是 ubuntu。");
        put(WIZ_PWD_HINT,
                "Enter the login password so the public key can be sent to the target. "
                        + "The password is not saved.",
                "请输入登陆密码，以便发送公钥到目标机器。密码不会被保存。");
        put(WIZ_WORKING, "Connecting, please wait...", "正在连接，请稍候…");
        put(WIZ_NAME_NOTE,
                "Leave blank to use \"user@host:port\". You can rename it later.",
                "留空则用「用户名@主机:端口」。以后可以随时改。");
        put(WIZ_FAIL_HINT,
                "Nothing was changed on the target. Check the reason below and try again.",
                "目标机器上没有做任何改动。请对照下面的原因重试。");
        put(WIZ_OLD_SSH_TITLE, "OpenSSH is too old for automatic setup",
                "OpenSSH 版本太旧，无法自动安装公钥");
        put(WIZ_OLD_SSH_REASON,
                "The plugin needs OpenSSH 8.4 or newer to supply a password "
                        + "without putting it on the command line. This computer has: {0}",
                "插件需要 OpenSSH 8.4 或更新版本，才能在不把密码写进命令行的前提下"
                        + "使用密码。这台电脑上是：{0}");
        put(WIZ_NEED_HOST, "Enter the target's IP address first.",
                "请先填目标机器的 IP 地址。");
        put(WIZ_NEED_USER, "Enter the login user name first.",
                "请先填登录用户名。");
        put(WIZ_NEED_PASSWORD, "Enter the password, or click Back.",
                "请输入密码，或点「上一步」返回。");
        put(BTN_NEXT, "Next", "下一步");
        put(BTN_BACK, "Back", "上一步");
        put(BTN_START, "Start connecting", "开始连接");
        put(BTN_CANCEL, "Cancel", "取消");
        put(BTN_CLOSE, "Close", "关闭");
        put(BTN_SKIP, "Skip", "跳过");
        put(LBL_PASSWORD, "Password:", "密码：");
        put(WIZ_KEY_NOTICE,
                "This will create an SSH key pair on this computer. The private key "
                        + "stays here and is used for authentication; the public key is "
                        + "appended to the target automatically.\n\n"
                        + "Click Next to continue.",
                "此操作将在本机生成 SSH 密钥对，私钥用于身份验证，"
                        + "公钥将自动追加到目标机器上。\n\n"
                        + "继续请点击「下一步」。");


        put(STATUS_NOT_TESTED, "Not tested yet.", "尚未测试连接。");
        put(STATUS_INTRO,
                "Click \"Test connection\" and this area shows the "
                        + "operating system, kernel version and home directory detected by "
                        + "vmadapt.core, along with troubleshooting advice when it fails.",
                "点「测试连接」后，这里会显示 vmadapt.core 探测到的操作系统、"
                        + "内核版本、家目录，以及失败时的排查建议。");

        put(MSG_NULL_REPORT, "The probe returned no result", "探测没有返回结果");
        put(MSG_NULL_REPORT_REASON, "vmadapt.core returned a null report.",
                "vmadapt.core 返回了 null 报告。");
        put(MSG_CONNECTED, "Connected: {0}", "连接成功：{0}");
        put(MSG_CONNECTED_SHORT, "Connected - {0}", "连接成功 — {0}");
        put(MSG_CONNECT_FAILED, "Connection failed - {0}", "连接失败 — {0}");
        put(MSG_NO_REASON, "No specific reason was given.", "没有给出具体原因。");

        put(RPT_OS, "Operating system : {0}", "操作系统   : {0}");
        put(RPT_KERNEL, "Kernel           : {0}", "内核版本   : {0}");
        put(RPT_HOME, "Remote home dir  : {0}", "远端家目录 : {0}");
        put(RPT_UNRECOGNIZED, "(unrecognized)", "(未识别)");
        put(RPT_NOTES, "Notes", "提示");
        put(RPT_SUGGESTIONS, "Suggestions", "建议");
        put(RPT_NEXT,
                "Next: click \"Copy public key to VM\" for passwordless login, "
                        + "or use the \"Network modes\" tab to confirm the transport path.",
                "下一步：可以点「复制公钥到远端」免密登录，"
                        + "或用「网络模式速查」确认传输路径。");
        put(RPT_REASON, "Reason: {0}", "原因：{0}");
        put(RPT_UNKNOWN, "unknown", "未知");
        put(RPT_SEE_NETWORK,
                "See the \"Network modes\" tab for detailed troubleshooting.",
                "详细排查请看「网络模式速查」页。");
        put(RPT_RAW,
                "Raw output from the remote command:",
                "远端命令的原始输出：");

        put(TIP_FALLBACK_1,
                "Check that the target's IP, port and user name are correct and that "
                        + "it is powered on.",
                "确认目标机器的 IP、端口、用户名填对了，目标机器处于开机状态。");
        put(TIP_FALLBACK_2,
                "In NAT mode, enter the VM's own address from ip addr with port 22 - "
                        + "the host reaches it through its VMnet8 adapter. Check that "
                        + "adapter exists with ipconfig; if it is missing, enable "
                        + "\"Connect a host virtual adapter to this network\" for VMnet8 "
                        + "in the Virtual Network Editor. Only if the host cannot reach "
                        + "that subnet at all, fall back to port forwarding: forward the "
                        + "VM's port 22 to a host port, then enter 127.0.0.1 and that port.",
                "NAT 模式下填 ip addr 查到的虚拟机地址 + 端口 22 就行——宿主机通过自己的 "
                        + "VMnet8 适配器就能连到它。用 ipconfig 确认那块适配器在；"
                        + "如果没有，去「虚拟网络编辑器」里给 VMnet8 勾上"
                        + "「将主机虚拟适配器连接到此网络」。"
                        + "只有宿主机完全到不了那个网段时，才退而用端口转发："
                        + "把虚拟机的 22 端口转发到宿主机某个端口，然后填 127.0.0.1 和那个端口。");
        put(TIP_FALLBACK_3,
                "In host-only mode, check that the host has a \"VMware Network Adapter "
                        + "VMnet1\" with an address (ipconfig), and that the VM's own "
                        + "firewall allows port 22 (on Ubuntu: sudo ufw allow 22/tcp).",
                "仅主机模式下，确认宿主机上有「VMware Network Adapter VMnet1」且有地址"
                        + "（用 ipconfig 看），并确认虚拟机自己的防火墙放行了 22 端口"
                        + "（Ubuntu 上：sudo ufw allow 22/tcp）。");
        put(TIP_FALLBACK_4,
                "Run systemctl status ssh on the target to confirm sshd is running.",
                "在目标机器里执行 systemctl status ssh，确认 sshd 已启动。");
        put(TIP_FALLBACK_5,
                "If public key authentication fails, check that the private key "
                        + "path is correct and that authorized_keys already contains the "
                        + "matching public key.",
                "公钥认证失败时，检查私钥路径是否正确、"
                        + "以及 authorized_keys 是否已包含对应公钥。");

        put(ERR_PORT_NUMBER,
                "The port must be a number; the current value is \"{0}\".",
                "端口必须是数字，当前填的是「{0}」。");
        put(ERR_TIMEOUT_NUMBER,
                "The timeout must be a number of seconds; the current value is \"{0}\".",
                "超时必须是数字（秒），当前填的是「{0}」。");

        // 与 vmadapt.core.SshTarget.validate() 的各分支一一对应。
        put(VAL_HOST_EMPTY,
                "Enter the target's host name or IP address.",
                "请填写目标机器的主机名或 IP 地址。");
        put(VAL_HOST_SPACE,
                "The host name must not contain spaces: {0}",
                "主机名中不能包含空格：{0}");
        put(VAL_HOST_PREFIX,
                "Do not include the ssh:// prefix in the host name - enter just the IP "
                        + "or domain, for example 192.168.1.100.",
                "主机名里不要带 ssh:// 前缀，请只填 IP 或域名，例如 192.168.1.100。");
        put(VAL_HOST_AT,
                "Do not include the user name in the host name - split {0} into the "
                        + "user name and host fields.",
                "主机名里不要带用户名，请把 {0} 拆成用户名和主机两栏。");
        put(VAL_USER_EMPTY,
                "Enter the login user name, for example ubuntu or root.",
                "请填写登录用户名，例如 ubuntu 或 root。");
        put(VAL_PORT_RANGE,
                "The port must be between 1 and 65535; the current value is {0}.",
                "端口号必须在 1 到 65535 之间，当前是 {0}。");
        put(VAL_TIMEOUT_RANGE,
                "The connection timeout must be between 1 and 600 seconds; the current "
                        + "value is {0}.",
                "连接超时必须在 1 到 600 秒之间，当前是 {0}。");
        put(VAL_KEY_MISSING,
                "Private key file not found: {0}. Check that the path is correct (use "
                        + "backslashes or forward slashes for Windows paths).",
                "找不到私钥文件：{0}。请确认路径正确（Windows 路径请用反斜杠或正斜杠）。");

        put(MSG_SAVE_FAILED, "Saving the profile failed", "保存配置失败");
        put(MSG_SAVE_REJECTED, "The profile was rejected", "保存配置被拒绝");
        put(MSG_SAVE_REJECTED_REASON,
                "The store did not accept the profile \"{0}\"; "
                        + "the name may be empty or contain invalid "
                        + "characters. Try a different name.",
                "存储层没有接受配置「{0}」，可能是名称为空或含有"
                        + "非法字符。请换一个名称再试。");
        put(MSG_SAVED_TITLE, "Saved profile \"{0}\"", "已保存配置「{0}」");
        put(MSG_SAVED_BODY, "Profile \"{0}\" was saved locally.",
                "配置「{0}」已保存到本地。");


        put(MSG_NO_KEYGEN, "ssh-keygen.exe was not found", "找不到 ssh-keygen.exe");
        put(MSG_NO_KEYGEN_REASON,
                "ssh-keygen.exe was not found in C:\\WINDOWS\\System32\\OpenSSH\\ "
                        + "or anywhere on PATH.",
                "在 C:\\WINDOWS\\System32\\OpenSSH\\ 和 PATH 里都没有找到 ssh-keygen.exe。");
        put(MSG_NO_HOME, "Cannot determine the user home directory", "无法确定用户目录");
        put(MSG_NO_HOME_REASON,
                "The system property user.home is empty, so there is nowhere to "
                        + "put the new key.",
                "系统属性 user.home 为空，没地方放新生成的密钥。");
        put(MSG_KEY_EXISTS,
                "The key file already exists:\n{0}\n\n"
                        + "Overwriting generates a brand new key pair, and the "
                        + "old key stops matching the authorized_keys entry on the "
                        + "remote side. Overwrite it?",
                "密钥文件已存在：\n{0}\n\n"
                        + "覆盖会生成一对新密钥，旧密钥对应远端的 authorized_keys 将失效。"
                        + "确定覆盖吗？");
        put(MSG_KEY_EXISTS_TITLE, "Key already exists", "密钥已存在");
        put(MSG_KEY_DELETE_FAILED,
                "Could not delete the old key file:\n{0}\n\n"
                        + "Generation stopped on purpose: ssh-keygen would ask "
                        + "\"Overwrite?\" and wait for an answer that a windowed "
                        + "program can never give, so it would hang. Close anything "
                        + "holding the file (or delete it by hand) and try again.",
                "无法删除旧的密钥文件：\n{0}\n\n"
                        + "这里刻意停下：ssh-keygen 会接着问「是否覆盖」并等一个"
                        + "窗口程序永远给不出的回答，结果就是一直卡住。"
                        + "请关掉占用该文件的程序（或手工删掉它）后重试。");
        put(MSG_GEN_RUNNING,
                "Generating the key, please wait... (RSA generation can take a few seconds)",
                "正在生成密钥，请稍候…（RSA 生成可能需要几秒）");
        put(MSG_GEN_RUNNING_BODY, "Running:", "正在调用：");
        put(MSG_GEN_OUTFILES, "Output files:", "输出文件：");
        put(MSG_MKDIR_FAILED, "Could not create the directory: {0}",
                "无法创建目录：{0}");
        put(MSG_GEN_FAILED, "Generating the key failed", "生成密钥失败");
        put(MSG_GEN_FAILED_CODE, "Generating the key failed (exit code {0})",
                "生成密钥失败（退出码 {0}）");
        put(MSG_GEN_OK_TITLE, "Key generated", "密钥已生成");
        put(MSG_GEN_OK_BODY, "Key generated:", "密钥已生成：");
        put(MSG_GEN_PRIVKEY, "Private key: {0}", "私钥：{0}");
        put(MSG_GEN_PUBKEY, "Public key : {0}", "公钥：{0}");
        put(MSG_GEN_PASSPHRASE,
                "Passphrase : none (empty passphrase, so login is automatic)",
                "口令：无（空口令，便于自动登录）");
        put(MSG_GEN_TAIL,
                "The \"Private key\" field in the form has been filled in; "
                        + "you can now click \"Copy public key to VM\" to install the "
                        + "public key on the VM.",
                "表单里的「密钥路径」已自动填好，现在可以点"
                        + "「复制公钥到远端」把公钥装到目标机器上。");
        put(MSG_GEN_OUTPUT, "ssh-keygen output:", "ssh-keygen 输出：");

        put(TIP_KEYGEN_1,
                "Windows 10/11 ships with an OpenSSH client; check under Settings -> "
                        + "Apps -> Optional features that \"OpenSSH Client\" is installed.",
                "Windows 10/11 一般自带 OpenSSH 客户端，可在「设置 → 应用 → "
                        + "可选功能」里确认「OpenSSH 客户端」已安装。");
        put(TIP_KEYGEN_2,
                "Make sure the file exists: C:\\WINDOWS\\System32\\OpenSSH\\ssh-keygen.exe.",
                "确认文件存在：C:\\WINDOWS\\System32\\OpenSSH\\ssh-keygen.exe。");
        put(TIP_KEYGEN_3,
                "If security software has locked the .ssh directory, run ssh-keygen "
                        + "manually on the command line once.",
                "如果 .ssh 目录被安全软件锁定，请手动在命令行执行一次 ssh-keygen。");
        put(TIP_KEYGEN_4,
                "You can also generate it by hand: ssh-keygen -t rsa -b 4096 -N \"\" "
                        + "-f %USERPROFILE%\\.ssh\\id_rsa_tbvm",
                "也可以手工生成：ssh-keygen -t rsa -b 4096 -N \"\" "
                        + "-f %USERPROFILE%\\.ssh\\id_rsa_tbvm");

        put(MSG_INCOMPLETE_COPY,
                "Incomplete settings, cannot copy the public key",
                "参数不完整，无法复制公钥");
        put(MSG_NO_PRIVKEY, "No private key specified", "没有指定私钥");
        put(MSG_NO_PRIVKEY_REASON,
                "\"Copy public key to VM\" needs a private key path first, so that "
                        + "the matching .pub file can be found.",
                "「复制公钥到远端」需要先指定私钥路径，以便找到同名的 .pub 公钥文件。");
        put(MSG_NO_PUBFILE, "The public key file was not found", "找不到公钥文件");
        put(MSG_NO_PUBFILE_REASON,
                "The expected public key file does not exist: {0}\n"
                        + "(The private key is there but the .pub file is missing; "
                        + "click \"Generate key\" to create a new key pair.)",
                "期望的公钥文件不存在：{0}\n"
                        + "（私钥存在但 .pub 不见了，可以点「生成密钥」重新生成一对。）");
        put(MSG_NO_SSH, "ssh.exe was not found", "找不到 ssh.exe");
        put(MSG_NO_SSH_REASON,
                "ssh.exe was not found in C:\\WINDOWS\\System32\\OpenSSH\\ or anywhere "
                        + "on PATH.",
                "在 C:\\WINDOWS\\System32\\OpenSSH\\ 和 PATH 里都没有找到 ssh.exe。");
        put(MSG_COPYING, "Copying the public key to {0} ...",
                "正在把公钥复制到 {0} …");
        put(MSG_COPYING_BODY,
                "Installing the public key in the background, waiting up to {0} "
                        + "seconds.\n"
                        + "This step still needs a connection that can log in (with a "
                        + "password or an existing key).\n"
                        + "If the remote side has never had a public key installed, "
                        + "ssh.exe cannot enter a password non-interactively; log in once "
                        + "on the target with a password, or use the machine's own console.",
                "正在后台执行公钥安装，最长等待 {0} 秒。\n"
                        + "这一步仍然需要一次能够登录的连接（用密码或已有密钥）。\n"
                        + "如果远端从未装过任何公钥，ssh.exe 无法非交互式输入密码，"
                        + "请先在目标机器上用密码登录一次，或用该机器自己的控制台操作。");
        put(MSG_READ_PUB_FAILED, "Could not read the public key file: {0}",
                "读取公钥文件失败：{0}");
        put(MSG_PUB_EMPTY, "The public key file is empty: {0}",
                "公钥文件是空的：{0}");
        put(MSG_COPY_FAILED, "Copying the public key failed", "复制公钥失败");
        put(MSG_COPY_FAILED_CODE, "Copying the public key failed (exit code {0})",
                "复制公钥失败（退出码 {0}）");
        put(MSG_COPY_OK_TITLE, "Public key installed on {0}",
                "公钥已安装到 {0}");
        put(MSG_COPY_OK_BODY,
                "The public key was written to the remote ~/.ssh/authorized_keys.\n"
                        + "You can now click \"Test connection\" to check that "
                        + "passwordless login works.",
                "公钥已写入远端 ~/.ssh/authorized_keys。\n"
                        + "现在可以点「测试连接」验证免密登录是否生效。");
        put(MSG_CMD_OUTPUT, "Command output:", "命令输出：");

        put(TIP_COPY_1,
                "The first connection asks you to confirm the host fingerprint; here "
                        + "accept-new accepts it automatically. If it still fails, run ssh "
                        + "manually once so the fingerprint is stored in known_hosts.",
                "第一次连接会提示确认主机指纹；这里用 accept-new 自动接受。"
                        + "如仍失败，先在命令行手动 ssh 一次把指纹存入 known_hosts。");
        put(TIP_COPY_2,
                "When the remote side has no usable public key yet, ssh.exe cannot "
                        + "type a password non-interactively. Log in on the target's own "
                        + "console first, or complete one command-line login with a password.",
                "远端还没有任何可用公钥时，ssh.exe 不能非交互式输密码。"
                        + "请先在目标机器自己的控制台里登录，或用密码完成一次命令行登录。");
        put(TIP_COPY_3,
                "Make sure the remote sshd allows public key authentication: "
                        + "PubkeyAuthentication yes in /etc/ssh/sshd_config, then restart sshd.",
                "确认远端的 sshd 允许公钥认证：/etc/ssh/sshd_config 里 "
                        + "PubkeyAuthentication yes，改完记得重启 sshd。");
        put(TIP_COPY_4,
                "Make sure the remote ~/.ssh is 700 and authorized_keys is 600, "
                        + "otherwise sshd refuses to read the public key.",
                "确认远端 ~/.ssh 权限是 700、authorized_keys 是 600，"
                        + "否则 sshd 会拒绝读取公钥。");
        put(TIP_COPY_5,
                "If you see Permission denied (publickey), the private key and the "
                        + "installed public key do not match; generate a new pair and copy it again.",
                "若提示 Permission denied (publickey)，说明私钥和远端已装公钥不配对，"
                        + "可重新生成一对并再次复制。");

        put(PFX_EXEC_FAILED, "Execution failed: ", "执行失败：");
        put(PFX_EXEC_CMD, "Command: ", "命令：");

        put(FRAME_TITLE, "VM Adapter - SSH Connection Console",
                "虚拟机适配 — SSH 连接控制台");
        put(FRAME_HEAD_TITLE,
                "SSH Connection Console (standalone verification window)",
                "虚拟机 SSH 连接控制台（独立验证窗口）");
        put(FRAME_HEAD_SUB,
                "This window does not depend on TBtools and "
                        + "exists to verify the connection panel on its own. Inside the TBtools "
                        + "plugin the same panel is embedded directly in the host UI.",
                "这个窗口不依赖 TBtools，用于单独验证连接面板。"
                        + "在 TBtools 插件里，同样的面板会被直接嵌进宿主界面。");
        put(FRAME_GUIDE_BTN, "Remote conda install guide", "查看远端 conda 安装指引");
        put(FRAME_GUIDE_TITLE, "Remote conda install guide", "远端 conda 安装指引");
        put(FRAME_GUIDE_FAILED,
                "The conda install guide is not available right now: {0}\n\n"
                        + "(The call to vmadapt.core.RemoteSetup.condaInstallGuide() "
                        + "failed; check that the core package is compiled and passing tests.)",
                "暂时拿不到 conda 安装指引：{0}\n\n"
                        + "（vmadapt.core.RemoteSetup.condaInstallGuide() 调用失败，"
                        + "请确认核心包已编译并通过测试。）");
        put(FRAME_GUIDE_EMPTY, "The core package returned an empty install guide.",
                "核心包返回了空的安装指引。");
        put(FRAME_ENV, "Java {0}  |  ssh.exe: {1}", "Java {0}　|　ssh.exe: {1}");
        put(FRAME_ENV_NO_SSH, "not found (install the OpenSSH client)",
                "未找到（请安装 OpenSSH 客户端）");

        put(USAGE_TITLE, "VM Adapter - SSH Connection Console: usage",
                "虚拟机适配 — SSH 连接控制台 用法");
        put(USAGE_LINE1, "  java vmadapt.ui.MainFrame [options]",
                "  java vmadapt.ui.MainFrame [选项]");
        put(USAGE_OPTS,
                "Options (all optional; when omitted the form is empty and the port defaults to 22):",
                "选项（都可省略，省略时表单为空，端口默认 22）：");
        put(USAGE_HOST, "  --host=192.168.1.100   Target IP address or host name",
                "  --host=192.168.1.100   目标机器 IP 或主机名");
        put(USAGE_PORT,
                "  --port=22              SSH port (NAT port forwarding commonly uses 2222)",
                "  --port=22              SSH 端口（NAT 端口转发常见 2222）");
        put(USAGE_USER, "  --user=ubuntu          Login user name",
                "  --user=ubuntu          登录用户名");
        put(USAGE_KEY,
                "  --key=C:\\Users\\me\\.ssh\\id_rsa_tbvm   Private key path",
                "  --key=C:\\Users\\me\\.ssh\\id_rsa_tbvm   私钥路径");
        put(USAGE_NAME, "  --name=seq-box         Profile name",
                "  --name=seq-box         配置名称");
        put(USAGE_HELP, "  -h, --help             Show this help",
                "  -h, --help             显示本帮助");
        put(USAGE_EXAMPLE, "Example:", "示例：");

        put(HL_BANNER, "======================================================",
                "======================================================");
        put(HL_TITLE, " VM Adapter - cannot start the graphical interface",
                " 虚拟机适配 — 图形界面无法启动");
        put(HL_BODY,
                "This environment is headless, so a Swing window cannot be created.",
                "检测到当前是「无图形界面（headless）」环境，Swing 窗口无法创建。");
        put(HL_NOT_ERROR,
                "This is not a program error: by design the tool exits normally "
                        + "here with status code 0.",
                "这不是程序错误，本工具按设计在此环境下正常退出（退出码 0）。");
        put(HL_CAUSES, "Common causes:", "常见原因：");
        put(HL_CAUSE_1,
                "  1. Running over ssh or a remote session without X11 forwarding.",
                "  1. 通过 ssh / 远程会话运行，且未开启 X11 转发；");
        put(HL_CAUSE_2,
                "  2. The JVM was started with -Djava.awt.headless=true.",
                "  2. JVM 启动参数里带了 -Djava.awt.headless=true；");
        put(HL_CAUSE_3,
                "  3. Running on a server or CI container with no desktop environment.",
                "  3. 运行在无桌面环境的服务器或持续集成容器里。");
        put(HL_TESTS,
                "To verify the core logic only, run the unit test classes instead; "
                        + "they do not need a graphical interface. For example:",
                "如果只想验证核心逻辑，请直接跑单元测试类，它们不依赖图形界面；例如：");
        put(HL_TESTS_EXAMPLE,
                "  java -cp build\\classes vmadapt.test.SshTargetTest",
                "  java -cp build\\classes vmadapt.test.SshTargetTest");
        put(HL_RETRY,
                "If this machine does have a desktop, remove "
                        + "-Djava.awt.headless=true and try again.",
                "如果本机确实有桌面，请去掉 -Djava.awt.headless=true 后重试。");
    }

    // ------------------------------------------------------------------
    // Network modes 整段（中英各一版）
    // ------------------------------------------------------------------

    private static final String NET_HELP_EN =
            "When you cannot reach a VM, nine times out of ten the cause is the "
                    + "VMware network mode. First check which mode the VM's network "
                    + "adapter uses, then follow the matching steps below.\n"
                    + "\n"
                    + "[The three modes share one thing]\n"
                    + "  In all three you fill this panel in the SAME way: the VM's own\n"
                    + "  IP address, port 22. Get it by running ip addr inside the VM.\n"
                    + "  What differs is who can reach the VM, and whether the VM has\n"
                    + "  internet of its own:\n"
                    + "    Bridged   - the host and other LAN machines can reach it; the\n"
                    + "                VM has internet.\n"
                    + "    Host-only - only the host can reach it; the VM has NO internet.\n"
                    + "    NAT       - the host can reach it through its VMnet8 adapter;\n"
                    + "                the VM has internet. Other machines cannot reach it\n"
                    + "                unless you add port forwarding.\n"
                    + "\n"
                    + "[NAT mode]\n"
                    + "  Setup: VM -> Settings -> Network Adapter -> choose \"NAT\". Run\n"
                    + "         ip addr inside the VM and take the address on the VMnet8\n"
                    + "         subnet.\n"
                    + "  Fill in this panel: that IP address, port 22.\n"
                    + "  This works because the host has its own adapter on that same\n"
                    + "  subnet. Check with ipconfig on the host: there should be a\n"
                    + "  \"VMware Network Adapter VMnet8\" with an address in the same\n"
                    + "  subnet as the VM.\n"
                    + "  If that adapter is missing, enable \"Connect a host virtual\n"
                    + "  adapter to this network\" for VMnet8 in the Virtual Network\n"
                    + "  Editor (needs administrator).\n"
                    + "  Port forwarding is NOT required for the host to connect. It is\n"
                    + "  for letting other machines in, or for the rare case where the\n"
                    + "  host cannot reach that subnet at all (for example when the\n"
                    + "  VMnet8 subnet collides with your LAN). If you do use it: forward\n"
                    + "  host port 2222 -> the VM's IP port 22, then enter host 127.0.0.1\n"
                    + "  and port 2222 here.\n"
                    + "\n"
                    + "[Bridged mode]\n"
                    + "  The VM and the host share the same physical LAN, each with its\n"
                    + "  own IP address.\n"
                    + "  Setup: make sure the VM's adapter has an IP assigned by the\n"
                    + "         router (check with ip addr), then ping that IP from the host.\n"
                    + "  Fill in this panel: the VM's real IP, port 22.\n"
                    + "  Note: the IP changes when you switch networks or routers, so\n"
                    + "        confirm it again with ip addr instead of reusing the old one.\n"
                    + "\n"
                    + "[Host-only mode]\n"
                    + "  The VM and the host are connected through the VMnet1 virtual\n"
                    + "  adapter and are isolated from everything else.\n"
                    + "  Setup: on the host, run ipconfig and find the address of\n"
                    + "         \"VMware Network Adapter VMnet1\" (commonly 192.168.x.1);\n"
                    + "         the VM's IP is usually on the same subnet.\n"
                    + "  Fill in this panel: the VM's IP inside the VMnet1 subnet, port 22.\n"
                    + "  Note: the VM has no internet in this mode, so anything that\n"
                    + "        downloads (installing packages, conda create) will fail.\n"
                    + "        Use bridged or NAT for that, or give the VM a second adapter.\n"
                    + "  If the host cannot reach the VM, check that the VMnet1 adapter\n"
                    + "  exists and has an address, and that the VM's own firewall allows\n"
                    + "  port 22 (on Ubuntu: sudo ufw allow 22/tcp). The Windows firewall\n"
                    + "  does not block this direction - the connection starts on the host.\n"
                    + "\n"
                    + "[General troubleshooting order]\n"
                    + "  1. On the VM, run systemctl status ssh to confirm sshd is running.\n"
                    + "  2. On the host, run ssh -p PORT -i KEY USER@HOST -v to see where\n"
                    + "     it stops.\n"
                    + "  3. From the host, ping the VM: if that fails it is a network\n"
                    + "     problem; if it succeeds, it is a port or authentication problem.\n"
                    + "  4. \"Permission denied\" points at the key; a timeout points at the\n"
                    + "     network mode.\n"
                    + "  5. Key permissions: on Linux ~/.ssh must be 700 and\n"
                    + "     authorized_keys must be 600.\n";

    private static final String NET_HELP_ZH =
            "连不上虚拟机时，九成问题出在 VMware 的网络模式上。先确认虚拟机的"
                    + "网卡是哪种模式，再对照下面的办法。\n"
                    + "\n"
                    + "【三种模式有一点是共通的】\n"
                    + "  三种模式在本面板里的填法是一样的：填虚拟机自己的 IP，端口 22。\n"
                    + "  地址在虚拟机里用 ip addr 查。\n"
                    + "  区别在于谁能连到虚拟机、以及虚拟机自己有没有外网：\n"
                    + "    桥接   —— 宿主机和局域网里其它机器都能连；虚拟机有外网。\n"
                    + "    仅主机 —— 只有宿主机能连；虚拟机没有外网。\n"
                    + "    NAT    —— 宿主机通过自己的 VMnet8 适配器能连；虚拟机有外网。\n"
                    + "              别的机器要连它，才需要配端口转发。\n"
                    + "\n"
                    + "【NAT 模式】\n"
                    + "  做法：虚拟机 → 设置 → 网络适配器 → 选「NAT」。\n"
                    + "        在虚拟机里用 ip addr 查它 VMnet8 网段上的地址。\n"
                    + "  本面板填：那个 IP，端口 22。\n"
                    + "  之所以能直连：宿主机自己在同一个网段上有一块适配器。用 ipconfig\n"
                    + "  确认——应该能看到「VMware Network Adapter VMnet8」，地址和虚拟机同网段。\n"
                    + "  如果没有这块适配器：去「编辑 → 虚拟网络编辑器」给 VMnet8 勾上\n"
                    + "  「将主机虚拟适配器连接到此网络」（需要管理员）。\n"
                    + "  端口转发不是宿主机连接所必需的——它是给别的机器进来用的，或者用在\n"
                    + "  宿主机确实到不了那个网段的时候（例如 VMnet8 网段和你的局域网撞了）。\n"
                    + "  真要用它：主机端口 2222 → 虚拟机 IP 的 22，然后本面板填 127.0.0.1 和 2222。\n"
                    + "\n"
                    + "【桥接模式】\n"
                    + "  虚拟机和宿主机在同一个物理局域网里，各自有独立 IP。\n"
                    + "  做法：确认虚拟机网卡拿到的是路由器分配的 IP（ip addr 查看），\n"
                    + "        宿主机直接 ping 一下这个 IP 通不通。\n"
                    + "  本面板填：虚拟机的真实 IP，端口 22。\n"
                    + "  注意：换网络、换路由器后 IP 会变，要用 ip addr 重新确认，\n"
                    + "        不要照抄上次的地址。\n"
                    + "\n"
                    + "【仅主机模式（Host-Only）】\n"
                    + "  虚拟机和宿主机通过 VMnet1 这块虚拟网卡互联，与外界隔离。\n"
                    + "  做法：在宿主机用 ipconfig 找到「VMware Network Adapter VMnet1」\n"
                    + "        的地址（常见 192.168.x.1），虚拟机 IP 通常同网段。\n"
                    + "  本面板填：VMnet1 网段内虚拟机的 IP，端口 22。\n"
                    + "  注意：这一模式下虚拟机没有外网，凡是需要下载的都会失败\n"
                    + "        （装包、conda create）。需要外网就用桥接或 NAT，\n"
                    + "        或者给虚拟机加第二块网卡。\n"
                    + "  连不上时查：VMnet1 适配器在不在、有没有地址；以及虚拟机自己的\n"
                    + "  防火墙是否放行 22（Ubuntu 上 sudo ufw allow 22/tcp）。\n"
                    + "  Windows 防火墙不拦这个方向——连接是从宿主机发起的。\n"
                    + "\n"
                    + "【通用排查顺序】\n"
                    + "  1. 在虚拟机里跑 systemctl status ssh，确认 sshd 正在运行。\n"
                    + "  2. 在宿主机跑 ssh -p 端口 -i 私钥 用户@主机 -v，看卡在哪一步。\n"
                    + "  3. 宿主机 ping 虚拟机：不通是网络问题，通了是端口或认证问题。\n"
                    + "  4. 报「Permission denied」看密钥对不对；报超时看网络模式。\n"
                    + "  5. 密钥权限：Linux 上 ~/.ssh 要 700，authorized_keys 要 600。\n";

    // ------------------------------------------------------------------
    // 登记放在两个字段都声明完之后。
    //
    // 这两句**不能**放进上面的 static 块：NET_HELP_EN / NET_HELP_ZH 是用 + 拼接
    // 出来的 static 字段，不是编译期常量，在声明之前引用属于非法前向引用，
    // javac 会报「非法前向引用」。放在这里既合法，也不用把几百行字面量内联进
    // put(...) 调用里。
    // ------------------------------------------------------------------

    static {
        put(NET_HELP, NET_HELP_EN, NET_HELP_ZH);
    }
}
